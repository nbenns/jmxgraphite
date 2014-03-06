package jmxgraphite

import javax.management.MBeanServerConnection;
import javax.management.ObjectName;
import javax.management.OperationsException;
import javax.management.openmbean.CompositeData;
import javax.management.openmbean.CompositeDataSupport;
import javax.management.openmbean.CompositeType;
import javax.management.remote.JMXConnector;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;
import javax.rmi.ssl.SslRMIClientSocketFactory;
import javax.rmi.ssl.SslRMIServerSocketFactory;
import javax.management.remote.rmi.RMIConnectorServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class JMXConnection {
	def static Logger LOG = LoggerFactory.getLogger(JMXGraphite.class);
	
    private JMXConnector Connector = null;
	private MBeanServerConnection Connection;
	private JMXServiceURL JMXUrl;
	private Map<String, Object> ConnectionProperties;
	private MBeans, Prefix;
	
	def JMXConnection(prefix, url, user, pass, ssl, provider, mbeans) {
		MBeans = mbeans;
		Prefix = prefix;
		
		JMXUrl = new JMXServiceURL(url);
		ConnectionProperties = new HashMap<String,Object>();

		if(user != null)
		{
			ConnectionProperties.put(JMXConnector.CREDENTIALS, [user, Encryption.Decrypt(pass)] as String[]);
		}

		// For SSL connections
		if (ssl != null)
		{
			SslRMIClientSocketFactory csf = new SslRMIClientSocketFactory();
			SslRMIServerSocketFactory ssf = new SslRMIServerSocketFactory();
			ConnectionProperties.put(RMIConnectorServer.RMI_CLIENT_SOCKET_FACTORY_ATTRIBUTE, csf);
			ConnectionProperties.put(RMIConnectorServer.RMI_SERVER_SOCKET_FACTORY_ATTRIBUTE, ssf);
			ConnectionProperties.put("com.sun.jndi.rmi.factory.socket", csf);
		}

		// This is for using External Jars for connecting to WebLogic, etc.
		if (provider != null)
		{
			ConnectionProperties.put(JMXConnectorFactory.PROTOCOL_PROVIDER_PACKAGES, provider);
		}
	}
	
	def Connect() {
		def connected = false;
		
		if (Connector == null) {
			try {
				LOG.info("Connecting to ${JMXUrl}");
				Connector = JMXConnectorFactory.connect(JMXUrl, ConnectionProperties);
				Connection = Connector.getMBeanServerConnection();
				LOG.info("Connected Successfully.");
			}
			catch (Exception ex) {
				LOG.debug("Can't get initial connection to ${JMXUrl}");
				LOG.trace("Exception calling getMBeanServerConnection():", ex);
				return false;
			}
		}
		
		try {
			Connection.getMBeanCount();
			connected = true;
		}
		catch(Exception ex) {
			LOG.info("Got disconnected from ${JMXUrl}");
			LOG.trace("Exception calling getMBeanCount()", ex);
			
			for (int c = 1; c < 4; c++)
			{
				LOG.info("Reconnecting to ${JMXUrl}, try #${c}");
				try {
					Connector.connect(ConnectionProperties);
					connected = true;
					LOG.info("Reconnected Successfully.");
					break;
				}
				catch (Exception ex2) {
					LOG.warn("Unable to Connect to ${JMXUrl}");
					LOG.trace("Exception while connecting:", ex2);
					if (c < 3) {
						def backoff = Math.pow(10, c);
						LOG.info("Backing off ${(int)backoff} ms");
						sleep((long)backoff);
					}
				}
			}
		}
		
		return connected;
	}
	
	def Disconnect() {
		if(Connector != null)
		{
			LOG.info("Disconnecting from ${JMXUrl}");
			try {
				Connector.close();
			}
			catch (Exception ex) {
				LOG.trace("Exception while disconnecting:", ex);
			}
			finally {
				Connector = null;
			}
		}
	}
	
	def Discover() {
		def Output = [];
		
		if (!Connect()) {
			LOG.warn("Can't Connect to ${JMXUrl}");
			Disconnect();
			return;
		}
		
		MBeans.each {MBName, MBAttrs ->
			def obj = new ObjectName(MBName);
			def mBeans
			
			try {
				mBeans = Connection.queryNames(obj, null);
			}
			catch (Exception ex) {
				// WebLogic domainruntime service doesn't support queryNames
				LOG.trace("Exception on queryNames():", ex)
				mBeans = [new ObjectName(MBName)]
			}
			
			mBeans.each { mb ->
				def String[] tmpName1 = mb.toString().split(",");
				def tmpName2 = [];
				
				tmpName1.each { s ->
					def a = s.split('=');
					tmpName2.addAll(a);
				};
							
				def objName = tmpName2[0].split(":")[0];
				
				tmpName2.eachWithIndex {t, i ->
					if (i % 2 == 1) objName += "." + t.replace('.', '_').replace(' ', '_').replace('[', '.').replace(']', '').replace('"', '');
				}
								
				if (MBAttrs instanceof HashMap) {
					MBAttrs.each {CDSName, CDSAttrs ->
						if (CDSName == "attributes") {
							try {
								def values = Connection.getAttributes(mb, CDSAttrs as String[]);
								def time = (int)(System.currentTimeMillis() / 1000);

								values.each {javax.management.Attribute v ->
									Output.add("${Prefix}.${objName}.${v.getName()} ${v.getValue()} ${time}");
								}
							}
							catch (Exception ex) {
								LOG.error("Type not compatible with configuration: ${mb.toString()} - ${CDSAttrs}");
								LOG.trace("Printing StackTrace:", ex);
							}
						}
						else {
							try {
								def CompositeDataSupport cds = Connection.getAttribute(mb, CDSName);
								if (cds != null) {
									def time = (int)(System.currentTimeMillis() / 1000);
									def values = cds.getAll(CDSAttrs as String[])
								
									values.eachWithIndex {v,i ->
										Output.add("${Prefix}.${objName}.${CDSName}.${CDSAttrs[i].toString()} ${v} ${time}");
									}
								}
								else LOG.debug("CompositeDataSupport ${mb.toString()} - ${CDSName} is NULL")
							}
							catch (Exception ex) {
								LOG.error("Type not found or not compatible with configuration: ${mb.toString()} - ${CDSName} - ${CDSAttrs}");
								LOG.trace("Printing StackTrace:", ex);
							}
						}
					}
				}
			}
		}
		
		return Output;
	}
}

	
