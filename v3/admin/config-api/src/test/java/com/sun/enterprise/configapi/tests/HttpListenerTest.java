/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright 1997-2007 Sun Microsystems, Inc. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License. You can obtain
 * a copy of the License at https://glassfish.dev.java.net/public/CDDL+GPL.html
 * or glassfish/bootstrap/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at glassfish/bootstrap/legal/LICENSE.txt.
 * Sun designates this particular file as subject to the "Classpath" exception
 * as provided by Sun in the GPL Version 2 section of the License file that
 * accompanied this code.  If applicable, add the following below the License
 * Header, with the fields enclosed by brackets [] replaced by your own
 * identifying information: "Portions Copyrighted [year]
 * [name of copyright owner]"
 *
 * Contributor(s):
 *
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

package com.sun.enterprise.configapi.tests;

import com.sun.enterprise.config.serverbeans.ConfigBeansUtilities;
import com.sun.enterprise.config.serverbeans.HttpListener;
import com.sun.enterprise.config.serverbeans.HttpService;
import org.glassfish.api.admin.SingleConfigCode;
import static org.junit.Assert.assertTrue;
import org.junit.Before;
import org.junit.Test;

import java.beans.PropertyVetoException;

/**
 * HttpListener related tests
 *
 * @author Jerome Dochez
 */
public class HttpListenerTest extends ConfigApiTest {


    public String getFileName() {
        return "DomainTest";
    }
    
    HttpListener listener = null;

    @Before
    public void setup() {
        HttpService service = getHabitat().getComponent(HttpService.class);
        assertTrue(service!=null);
        for (HttpListener list : service.getHttpListener()) {
            if (list.getId().equals("http-listener-1")) {
                listener=list;
                break;
            }
        }

        logger.fine("listener = " + listener);
        assertTrue(listener!=null);

    }
    
    @Test
    public void portTest() {
        logger.fine("port = " + listener.getPort());
        assertTrue(listener.getPort().equals("8080"));
    }

    @Test
    public void validTransaction() throws PropertyVetoException {
        
        ConfigBeansUtilities.apply((new SingleConfigCode<HttpListener>() {
            public boolean run(HttpListener okToChange) throws PropertyVetoException {
                okToChange.setAcceptorThreads("2");
                logger.fine("ID inside the transaction is " + okToChange.getId());
                return true;
            }
        }), listener);
        logger.fine("ID outside the transaction is " + listener.getId());
        assertTrue(listener.getAcceptorThreads().equals("2"));
    }    
}
