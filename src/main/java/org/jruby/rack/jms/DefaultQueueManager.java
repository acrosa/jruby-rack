/*
 * Copyright (c) 2010-2011 Engine Yard, Inc.
 * Copyright (c) 2007-2009 Sun Microsystems, Inc.
 * This source code is available under the MIT license.
 * See the file LICENSE.txt for details.
 */

package org.jruby.rack.jms;

import java.io.ByteArrayInputStream;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Properties;
import javax.jms.Message;
import javax.jms.MessageListener;
import javax.jms.Connection;
import javax.jms.ConnectionFactory;
import javax.jms.Destination;
import javax.jms.MessageConsumer;
import javax.jms.Session;
import javax.naming.Context;
import javax.naming.InitialContext;
import org.jruby.Ruby;
import org.jruby.RubyModule;
import org.jruby.RubyObjectAdapter;
import org.jruby.RubyRuntimeAdapter;
import org.jruby.javasupport.JavaEmbedUtils;
import org.jruby.rack.RackApplication;
import org.jruby.rack.RackApplicationFactory;
import org.jruby.rack.RackContext;
import org.jruby.runtime.builtin.IRubyObject;

/**
 *
 * @author nicksieger
 */
public class DefaultQueueManager implements QueueManager {
    private ConnectionFactory connectionFactory = null;
    private RackContext context;
    private Context jndiContext;
    private Map<String,Connection> queues = new HashMap<String,Connection>();
    private RubyRuntimeAdapter rubyRuntimeAdapter = JavaEmbedUtils.newRuntimeAdapter();
    private RubyObjectAdapter rubyObjectAdapter = JavaEmbedUtils.newObjectAdapter();

    public DefaultQueueManager() {
    }

    public DefaultQueueManager(ConnectionFactory qcf, Context ctx) {
        this.connectionFactory = qcf;
        this.jndiContext = ctx;
    }
    
    public void init(RackContext context) throws Exception {
        this.context = context;
        String jndiName = context.getInitParameter("jms.connection.factory");
        if (jndiName != null && connectionFactory == null) {
            Properties properties = new Properties();
            String jndiProperties = context.getInitParameter("jms.jndi.properties");
            if (jndiProperties != null) {
                properties.load(new ByteArrayInputStream(jndiProperties.getBytes("UTF-8")));
            }
            jndiContext = new InitialContext(properties);
            connectionFactory = (ConnectionFactory) jndiContext.lookup(jndiName);
        }
    }

    public synchronized void listen(String queueName) {
        Connection conn = queues.get(queueName);
        if (conn == null) {
            try {
                conn = connectionFactory.createConnection();
                Session session = conn.createSession(false, Session.AUTO_ACKNOWLEDGE);
                Destination dest = (Destination) lookup(queueName);
                MessageConsumer consumer = session.createConsumer(dest);
                consumer.setMessageListener(new RubyObjectMessageListener(queueName));
                queues.put(queueName, conn);
                conn.start();
            } catch (Exception e) {
                context.log("Unable to listen to '"+queueName+"': " + e.getMessage(), e);
            }
        // } else { ... already listening on that queue
        }
    }

    public synchronized void close(String queueName) {
        Connection conn = queues.remove(queueName);
        if (conn != null) {
            closeConnection(conn);
        }
    }

    public ConnectionFactory getConnectionFactory() {
        return connectionFactory;
    }
    
    public Object lookup(String name) throws javax.naming.NamingException {
        return jndiContext.lookup(name);
    }

    @SuppressWarnings("unchecked")
    public void destroy() {
        for (Iterator it = queues.entrySet().iterator(); it.hasNext();) {
            Map.Entry<String,Connection> entry = (Map.Entry<String, Connection>) it.next();
            closeConnection(entry.getValue());
        }
        queues.clear();
        connectionFactory = null;
    }

    private void closeConnection(Connection conn) {
        try {
            conn.close();
        } catch (Exception e) {
            context.log("exception while closing connection: " + e.getMessage(), e);
        }
    }

    private class RubyObjectMessageListener implements MessageListener {
        private String queueName;
        private RackApplicationFactory rackFactory;
        public RubyObjectMessageListener(String name) {
            this.queueName = name;
            this.rackFactory = context.getRackFactory();
        }

        public void onMessage(Message message) {
            RackApplication app = null;
            try {
                app = rackFactory.getApplication();
                Ruby runtime = app.getRuntime();
                RubyModule mod = runtime.getClassFromPath("JRuby::Rack::Queues");
                IRubyObject obj = mod.getConstant("Registry");
                rubyObjectAdapter.callMethod(obj, "receive_message", new IRubyObject[] {
                    JavaEmbedUtils.javaToRuby(runtime, queueName),
                    JavaEmbedUtils.javaToRuby(runtime, message)});
            } catch (Exception e) {
                context.log("exception during message reception: " + e.getMessage(), e);
            } finally {
                if (app != null) {
                    rackFactory.finishedWithApplication(app);
                }
            }
        }
    }
}