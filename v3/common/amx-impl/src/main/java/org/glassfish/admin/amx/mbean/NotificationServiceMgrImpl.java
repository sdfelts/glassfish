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
package org.glassfish.admin.amx.mbean;

import java.util.Map;
import java.util.HashMap;
import java.util.Collections;

import javax.management.MBeanServer;
import javax.management.ObjectName;
import javax.management.InstanceNotFoundException;
import javax.management.MBeanRegistrationException;
import javax.management.Notification;
import javax.management.NotificationListener;
import javax.management.MBeanServerNotification;

import com.sun.appserv.management.base.NotificationServiceMgr;
import com.sun.appserv.management.base.NotificationService;
import com.sun.appserv.management.base.XTypes;
import com.sun.appserv.management.base.AMX;
import com.sun.appserv.management.util.jmx.JMXUtil;
import com.sun.appserv.management.base.Util;

import org.glassfish.admin.amx.util.UniqueIDGenerator;

/**
 */
public class NotificationServiceMgrImpl extends AMXNonConfigImplBase
	implements NotificationListener
{
	private final Map<ObjectName,NotificationServiceImpl> mServices;
	private final UniqueIDGenerator	mUniqueIDs;
	
		public
	NotificationServiceMgrImpl(final ObjectName parentObjectName)
	{
        super( NotificationServiceMgr.J2EE_TYPE, NotificationServiceMgr.J2EE_TYPE, parentObjectName, NotificationServiceMgr.class, null );
        
		mServices	= Collections.synchronizedMap( new HashMap<ObjectName,NotificationServiceImpl>() );
		
		mUniqueIDs	= new UniqueIDGenerator( "notif-service-" );
	}
	
		public void
	handleNotification(
		final Notification	notifIn, 
		final Object		handback) 
	{
		final String	type	= notifIn.getType();
		
		// ensure that if a NotificationService is unregistered that we remove
		// it from our list
		if ( type.equals( MBeanServerNotification.UNREGISTRATION_NOTIFICATION)  )
		{
			final MBeanServerNotification	notif	= (MBeanServerNotification)notifIn;
			final ObjectName	objectName	= notif.getMBeanName();
			
			if ( Util.getJ2EEType( objectName ).
					equals( XTypes.NOTIFICATION_SERVICE ) )
			{
				mServices.remove( objectName );
			}
		}
	}

		public String
	getGroup()
	{
		return( AMX.GROUP_UTILITY );
	}
	
	/*
	
		public Map
	getNotificationServiceObjectNameMap()
	{
		return( getContaineeObjectNameMap( XTypes.NOTIFICATION_SERVICE ) );
	}
	*/
	
		public void
	preRegisterDone()
		throws Exception
	{
		JMXUtil.listenToMBeanServerDelegate( getMBeanServer(), this, null, null );
	}
	
	
		public ObjectName
	createNotificationService(
		final Object	userData,
		final int		bufferSize )
	{
		final NotificationServiceImpl	service	=
			new NotificationServiceImpl( getObjectName(), userData, bufferSize );
		
		final ObjectName	self	= getObjectName();
		
		final String	domain		= self.getDomain();
		final String	childName	= mUniqueIDs.createID().toString();
		final String	requiredProps	=
			Util.makeRequiredProps( XTypes.NOTIFICATION_SERVICE, childName );
		
		final ObjectName	tempName	= JMXUtil.newObjectName( domain, requiredProps );
		
		ObjectName	objectName	= null;
		try
		{
			objectName	= registerMBean( service, tempName );
			mServices.put( objectName, service );
		}
		catch( Exception e )
		{
			throw new RuntimeException( e );
		}
		
		return( objectName );
	}
	
		public ObjectName
	getNotificationServiceObjectName( final String name  )
	{
		return( getContainerSupport().getContaineeObjectName( XTypes.NOTIFICATION_SERVICE, name ) );
	}
	
		public synchronized void
	removeNotificationService( final String name )
		throws InstanceNotFoundException
	{
		final ObjectName	objectName	= getNotificationServiceObjectName( name );
		if ( objectName == null )
		{
			throw new IllegalArgumentException( name );
		}
		
		if ( ! mServices.containsKey( objectName ) )
		{
			throw new InstanceNotFoundException( objectName.toString() );
		}
		
		try
		{
			getMBeanServer().unregisterMBean( objectName );
		}
		catch( MBeanRegistrationException e )
		{
			throw new RuntimeException( e );
		}
		
		// remove it after unregistering it, in case an exception is thrown
		mServices.remove( objectName );
	}
	
}











