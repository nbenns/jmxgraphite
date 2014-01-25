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
    private JMXConnector connector;
	private MBeanServerConnection connection;
	private JMXServiceURL jmxUrl;
	private Map<String, Object> m;
	private MBeans, Prefix;
	
	def JMXConnection(prefix, url, user, pass, mbeans) {
		MBeans = mbeans;
		Prefix = prefix;
		
		jmxUrl = new JMXServiceURL(url);
		m = new HashMap<String,Object>();

		if(user != null)
		{
			m.put(JMXConnector.CREDENTIALS, [user, pass] as String[]);
		}

		// For SSL connections
		if (!System.getProperty("javax.net.ssl.trustStore", "NULL").equals("NULL"))
		{
			SslRMIClientSocketFactory csf = new SslRMIClientSocketFactory();
			SslRMIServerSocketFactory ssf = new SslRMIServerSocketFactory();
			m.put(RMIConnectorServer.RMI_CLIENT_SOCKET_FACTORY_ATTRIBUTE, csf);
			m.put(RMIConnectorServer.RMI_SERVER_SOCKET_FACTORY_ATTRIBUTE, ssf);
			m.put("com.sun.jndi.rmi.factory.socket", csf);
		}

		// This is for using External Jars for connecting to WebLogic, etc.
		if (!System.getProperty("jmx.remote.protocol.provider.pkgs", "NULL").equals("NULL"))
		{
			m.put(JMXConnectorFactory.PROTOCOL_PROVIDER_PACKAGES, System.getProperty("jmx.remote.protocol.provider.pkgs"));
		}
	}
	
	private def Connect() {
		for (int c = 0; c < 3; c++)
		{
			try {
				connector = JMXConnectorFactory.connect(jmxUrl,m);
				break;
			}
			catch (IOException ex) {
				if (c < 2) continue;
				else throw ex;
			}
		}

		connection = connector.getMBeanServerConnection();
	}
	
	private def Disconnect() {
		if(connector != null)
		{
			connector.close();
			connector = null;
		}
	}
	
	def Discover() {
		def Output = [];
		
		Connect();
		
		MBeans.each {oName, oAttrs ->
			def obj = new ObjectName(oName);
			def String[] tmpName1 = oName.toString().split(",");
			def tmpName2 = [];
			
			tmpName1.each { s ->
				def a = s.split('=');
				tmpName2.add(a[0]);
				tmpName2.add(a[1]);
			};
			
			//def objName = tmpName2[0].split(":")[0] + "." + tmpName2[1].replace('.', '_').replace(' ', '_');
			//if (tmpName2.size() > 2) objName += ".${tmpName2[3].replace('.', '_').replace(' ', '_')}";
		
			def objName = tmpName2[0].split(":")[0];
			tmpName2.eachWithIndex {t, i -> 
				if (i % 2 == 1) objName += "." + t.replace('.', '_').replace(' ', '_');
			}
			
			if (oAttrs instanceof HashMap) {
				oAttrs.each {Attr, Comp ->
					if (Attr == "attributes") {
						Comp.each {attr ->
							def value = connection.getAttribute(obj, attr);
							def time = (int)(System.currentTimeMillis() / 1000);

							Output.add("${Prefix}.${objName}.${attr} ${value} ${time}");
						}  
					}
					else {
						if (Comp instanceof HashMap) {
							Comp.each {attr, val ->
								if (val != null) {
									println val;
									
									def comp = connection.getAttribute(obj, Attr);
									def cds = comp[attr.toInteger()];
									
									val.each {v ->
										def time = (int)(System.currentTimeMillis() / 1000);
										def value = cds.get(v);
											
										Output.add("${Prefix}.${objName}.${Attr}.${attr}.${v} ${value} ${time}");
									}
									
								}
							}
						}
						else {
							Comp.each {attr ->
								def CompositeDataSupport cds = connection.getAttribute(obj, Attr);
								def time = (int)(System.currentTimeMillis() / 1000);
								def value = cds.get(attr);
						  
								Output.add("${Prefix}.${objName}.${Attr}.${attr} ${value} ${time}");
							}
						}
					}
				}
			}
		}
		
		Disconnect();
		
		return Output;
	}
}
