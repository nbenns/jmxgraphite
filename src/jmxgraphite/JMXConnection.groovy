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

class JMXConnection {
    private JMXConnector Connector = null;
	private MBeanServerConnection Connection;
	private JMXServiceURL JMXUrl;
	private Map<String, Object> ConnectionProperties;
	private MBeans, Prefix;
	
	def JMXConnection(prefix, url, user, pass, mbeans) {
		MBeans = mbeans;
		Prefix = prefix;
		
		JMXUrl = new JMXServiceURL(url);
		ConnectionProperties = new HashMap<String,Object>();

		if(user != null)
		{
			ConnectionProperties.put(JMXConnector.CREDENTIALS, [user, pass] as String[]);
		}

		// For SSL connections
		if (!System.getProperty("javax.net.ssl.trustStore", "NULL").equals("NULL"))
		{
			SslRMIClientSocketFactory csf = new SslRMIClientSocketFactory();
			SslRMIServerSocketFactory ssf = new SslRMIServerSocketFactory();
			ConnectionProperties.put(RMIConnectorServer.RMI_CLIENT_SOCKET_FACTORY_ATTRIBUTE, csf);
			ConnectionProperties.put(RMIConnectorServer.RMI_SERVER_SOCKET_FACTORY_ATTRIBUTE, ssf);
			ConnectionProperties.put("com.sun.jndi.rmi.factory.socket", csf);
		}

		// This is for using External Jars for connecting to WebLogic, etc.
		if (!System.getProperty("jmx.remote.protocol.provider.pkgs", "NULL").equals("NULL"))
		{
			ConnectionProperties.put(JMXConnectorFactory.PROTOCOL_PROVIDER_PACKAGES, System.getProperty("jmx.remote.protocol.provider.pkgs"));
		}
	}
	
	def Connect() {
		def connected = false;
		
		try {
		  Connector.getConnectionId();
		  connected = true;
		}
		catch(Exception ex) {
			for (int c = 0; c < 3; c++)
			{
				try {
					Connector = JMXConnectorFactory.connect(JMXUrl, ConnectionProperties);
					Connection = Connector.getMBeanServerConnection();

					connected = true;
					break;
				}
				catch (Exception ex2) {
					if (c < 2) continue;
				}
			}
		}
		
		return connected;
	}
	
	private def Disconnect() {
		if(Connector != null)
		{
			Connector.close();
			//Connector = null;
		}
	}
	
	def Discover() {
		def Output = [];
		if (!Connect()) {
			println ("Can't Connect to ${JMXUrl}")
			Disconnect();
			return;
		}
		
		MBeans.each {MBName, MBAttrs ->
			def obj = new ObjectName(MBName);
			
			def mBeans = Connection.queryNames(obj, null);
			
			mBeans.each { mb ->
				def String[] tmpName1 = mb.toString().split(",");
				def tmpName2 = [];
				
				tmpName1.each { s ->
					def a = s.split('=');
					tmpName2.addAll(a);
				};
							
				def objName = tmpName2[0].split(":")[0];
				tmpName2.eachWithIndex {t, i ->
					if (i % 2 == 1) objName += "." + t.replace('.', '_').replace(' ', '_').replace('[', '.').replace(']', '');
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
								println "Type not compatible with configuration: ${mb.toString()} - ${CDSAttrs}"
							}
						}
						else {
							try {
								def CompositeDataSupport cds = Connection.getAttribute(mb, CDSName);
								def time = (int)(System.currentTimeMillis() / 1000);
								def values = cds.getAll(CDSAttrs as String[])
								
								values.eachWithIndex {v,i ->
									Output.add("${Prefix}.${objName}.${CDSName}.${CDSAttrs[i].toString()} ${v} ${time}");
								}
							}
							catch (Exception ex) {
								println "Type not found or not compatible with configuration: ${mb.toString()} - ${CDSName} - ${CDSAttrs}"
								println ex;
							}
						}
					}
				}
			}
		}
		
		return Output;
	}
}

	
