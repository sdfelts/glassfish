/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2006-2010 Oracle and/or its affiliates. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License.  You can
 * obtain a copy of the License at
 * https://glassfish.dev.java.net/public/CDDL+GPL_1_1.html
 * or packager/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at packager/legal/LICENSE.txt.
 *
 * GPL Classpath Exception:
 * Oracle designates this particular file as subject to the "Classpath"
 * exception as provided by Oracle in the GPL Version 2 section of the License
 * file that accompanied this code.
 *
 * Modifications:
 * If applicable, add the following below the License Header, with the fields
 * enclosed by brackets [] replaced by your own identifying information:
 * "Portions Copyright [year] [name of copyright owner]"
 *
 * Contributor(s):
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 */

package org.glassfish.ejb.startup;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;
import java.util.List;
import java.util.logging.Logger;
import java.util.logging.Level;

import com.sun.ejb.Container;
import com.sun.ejb.ContainerFactory;
import com.sun.ejb.containers.AbstractSingletonContainer;
import com.sun.enterprise.deployment.Application;
import com.sun.enterprise.deployment.EjbDescriptor;
import com.sun.enterprise.deployment.EjbBundleDescriptor;
import org.glassfish.ejb.security.application.EJBSecurityManager;
import org.glassfish.ejb.security.factory.EJBSecurityManagerFactory;

import org.glassfish.api.deployment.ApplicationContainer;
import org.glassfish.api.deployment.ApplicationContext;
import org.glassfish.api.deployment.DeploymentContext;
import org.glassfish.api.deployment.DeployCommandParameters;
import org.glassfish.api.deployment.OpsParams;

import org.jvnet.hk2.annotations.Scoped;
import org.jvnet.hk2.annotations.Service;
import org.jvnet.hk2.component.Habitat;
import org.jvnet.hk2.component.PerLookup;

import com.sun.enterprise.security.PolicyLoader;

/**
 * This class represents a logical collection of EJB components contained in one ejb-jar
 * or one .war.
 *
 * @author Mahesh Kannan
 */
@Service(name = "ejb")
@Scoped(PerLookup.class)
public class EjbApplication
        implements ApplicationContainer<Collection<EjbDescriptor>> {

    private EjbBundleDescriptor ejbBundle;
    private Collection<EjbDescriptor> ejbs;
    private Collection<Container> containers = new ArrayList();
    private ClassLoader ejbAppClassLoader;
    private DeploymentContext dc;
    
    private Habitat habitat;

    private EJBSecurityManagerFactory ejbSMF;
     
    private ContainerFactory ejbContainerFactory;

    private SingletonLifeCycleManager singletonLCM;

    boolean usesEJBTimerService = false;
    
    private PolicyLoader policyLoader;

    private boolean initializeInOrder;

    private volatile boolean started;

    private static final String CONTAINER_LIST_KEY = "org.glassfish.ejb.startup.EjbContainerList";

    private static final String EJB_APP_MARKED_AS_STARTED_STATUS = "org.glassfish.ejb.startup.EjbApplicationMarkedAsStarted";

    public EjbApplication(
            EjbBundleDescriptor bundle, DeploymentContext dc,
            ClassLoader cl, Habitat habitat, 
            EJBSecurityManagerFactory ejbSecMgrFactory) {
        this.ejbBundle = bundle;
        this.ejbs = bundle.getEjbs();
        this.ejbAppClassLoader = cl;
        this.dc = dc;
        this.habitat = habitat;
        this.ejbContainerFactory = habitat.getByContract(ContainerFactory.class);
        this.ejbSMF = ejbSecMgrFactory;
        this.policyLoader = habitat.getComponent(PolicyLoader.class);

        Application app = ejbBundle.getApplication();
        initializeInOrder = (app != null) && (app.isInitializeInOrder());
    }
    
    public Collection<EjbDescriptor> getDescriptor() {
        return ejbs;
    }

    public EjbBundleDescriptor getEjbBundleDescriptor() {
        return ejbBundle;
    }

    public boolean isStarted() {
        return started;
    }                                                                                     // TODO handle singleton startup dependencies that refer to singletons in a different
            // module within the application

    void markAllContainersAsStarted() {
        for (Container container : containers) {
                container.setStartedState();
        }
    }

    public boolean start(ApplicationContext startupContext) throws Exception {
        started = true;

        if (! initializeInOrder) {
            Boolean alreadyMarked = dc.getTransientAppMetaData(EJB_APP_MARKED_AS_STARTED_STATUS, Boolean.class);
            if (! alreadyMarked.booleanValue()) {
                List<EjbApplication> ejbAppList = dc.getTransientAppMetaData(CONTAINER_LIST_KEY, List.class);
                for (EjbApplication app : ejbAppList) {
                    app.markAllContainersAsStarted();
                }
                dc.addTransientAppMetaData(EJB_APP_MARKED_AS_STARTED_STATUS, new Boolean(true));
            }
        }
        
        try {
            DeployCommandParameters params = ((DeploymentContext)startupContext).
                    getCommandParameters(DeployCommandParameters.class);

            for (Container container : containers) {
                container.startApplication(params.origin.isDeploy());
            }

            singletonLCM.doStartup(this);

        } catch(Exception e) {
            abortInitializationAfterException();
            throw e;
        }

        return true;
    }

    /**
     * Initial phase of continer initialization.  This creates the concrete container
     * instance for each EJB component, registers JNDI entries, etc.  However, no
     * EJB bean instances or invocations occur during this phase.  Those must be
     * delayed until start() is called.
     * @param startupContext
     * @return
     */
    boolean loadContainers(ApplicationContext startupContext) {

        DeploymentContext dc = (DeploymentContext) startupContext;

        DeployCommandParameters params = dc.getCommandParameters(DeployCommandParameters.class);

        // If true the application is being deployed.  If false, it's
        // an initialization after the app was already deployed. 
        boolean deploy = params.origin.isDeploy();

        String dcMapToken = "org.glassfish.ejb.startup.SingletonLCM";
        singletonLCM = dc.getTransientAppMetaData(dcMapToken, SingletonLifeCycleManager.class);
        if (singletonLCM == null) {
            singletonLCM = new SingletonLifeCycleManager(initializeInOrder);
            dc.addTransientAppMetaData(dcMapToken, singletonLCM);
        }

        if (! initializeInOrder) {
            dc.addTransientAppMetaData(EJB_APP_MARKED_AS_STARTED_STATUS, new Boolean(false));
            List<EjbApplication> ejbAppList = dc.getTransientAppMetaData(CONTAINER_LIST_KEY, List.class);
            if (ejbAppList == null) {
                ejbAppList = new ArrayList<EjbApplication>();
                dc.addTransientAppMetaData(CONTAINER_LIST_KEY, ejbAppList);
            }
            ejbAppList.add(this);
        }

        try {
            policyLoader.loadPolicy();
            String moduleName = null;
        
            for (EjbDescriptor desc : ejbs) {
                EJBSecurityManager ejbSM = null;

                // Initialize each ejb container (setup component environment, register JNDI objects, etc.)
                // Any instance instantiation , timer creation/restoration, message inflow is delayed until
                // start phase.
                // create and register the security manager with the factory
                ejbSM = ejbSMF.createManager(desc, true);
                Container container = ejbContainerFactory.createContainer(desc, ejbAppClassLoader,
                        ejbSM, dc);
                containers.add(container);

                if (container instanceof AbstractSingletonContainer) {
                    singletonLCM.addSingletonContainer(this, (AbstractSingletonContainer) container);
                }

            }

        } catch(Throwable t) {
            abortInitializationAfterException();
            throw new RuntimeException("EJB Container initialization error", t);
        }
        
        return true;
    }

    public boolean stop(ApplicationContext stopContext) {

        OpsParams params = ((DeploymentContext)stopContext).
                getCommandParameters(OpsParams.class);

        // If true we're shutting down b/c of an undeploy or a fatal error during
        // deployment.  If false, it's a shutdown where the application will remain
        // deployed.
        boolean undeploy = (params.origin.isUndeploy() || params.origin.isDeploy());

        // First, shutdown any singletons that were initialized based
        // on a particular ordering dependency.
        // TODO Make sure this covers both eagerly and lazily initialized
        // Singletons.
        singletonLCM.doShutdown();

        for (Container container : containers) {
            if( undeploy ) {
                container.undeploy();      
            } else {
                container.onShutdown();
            }
            if(container.getSecurityManager() != null) {
                container.getSecurityManager().destroy();
            }
        }
        
        containers.clear();
        
        return true;
    }

    /**
     * Suspends this application container.
     *
     * @return true if suspending was successful, false otherwise.
     */
    public boolean suspend() {
        // Not (yet) supported
        return false;
    }

    /**
     * Resumes this application container.
     *
     * @return true if resumption was successful, false otherwise.
     */
    public boolean resume() {
        // Not (yet) supported
        return false;
    }

    /**
     * Returns the class loader associated with this application
     *
     * @return ClassLoader for this app
     */
    public ClassLoader getClassLoader() {
        return ejbAppClassLoader;
    }

    /**
     * Returns true if at least one of the containers represents a timed object
     */
    boolean containsTimedObject() {
        for (Container container : containers) {
            if (container.isTimedObject()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Called when an exception is thrown from either the load phase or the start phase.
     * In this case we can't guarantee that the deployment framework will give us an
     * opportunity to clean up, especially if the EjbApplication object itself is never
     * registered due to the exception.  The most important thing is to make sure
     * global resources like JNDI names are cleaned up. Otherwise, subsequent deployment
     * attempts might fail without a server restart.   The container instances
     * themselves must be prepared to gracefully handle any case where undeploy/shutdown
     * is called multiple times.
     */
    private void abortInitializationAfterException() {

        for (Container container : containers) {
            container.undeploy();
        }

    }
}
