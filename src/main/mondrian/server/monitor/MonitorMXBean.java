/*
// This software is subject to the terms of the Eclipse Public License v1.0
// Agreement, available at the following URL:
// http://www.eclipse.org/legal/epl-v10.html.
// You must accept the terms of that agreement to use this software.
//
// Copyright (C) 2005-2013 Pentaho and others
// All Rights Reserved.
*/
package mondrian.server.monitor;

import com.sun.org.glassfish.gmbal.ManagedAttribute;

import java.util.List;
import javax.management.MXBean;

/**
 * Defines the MXBean interface required to register
 * MonitorImpl with a JMX agent.  This simply lists
 * the attributes we want exposed to a JMX client.
 */
@MXBean
public interface MonitorMXBean {
    @ManagedAttribute
    mondrian.server.monitor.ServerInfo getServer();

    @ManagedAttribute
    List<ConnectionInfo> getConnections();

    @ManagedAttribute
    List<StatementInfo> getStatements();

    @ManagedAttribute
    List<SqlStatementInfo> getSqlStatements();
}

// End MonitorMXBean.java