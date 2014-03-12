package jmxgraphite

import groovy.json.JsonSlurper;
import groovy.json.JsonBuilder;
import java.util.regex.Matcher;
import java.lang.Thread;
import javax.management.MBeanServerConnection;
import javax.management.ObjectName;
import javax.management.Query;
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

class JMXConnection extends Thread {
	def static Logger _LOG = LoggerFactory.getLogger(JMXGraphite.class);
	private _templateDir = "";
	private _interval = 60*1000;
	
    private JMXConnector _Connector = null;
	private MBeanServerConnection _Connection;
	private JMXServiceURL _JMXUrl;
	private Map<String, Object> _ConnectionProperties;
	private _MBeans, _Prefix;
	private _allMBeans = null;
	
	def private LoadConfig(fname) {
		def f = new File(fname);
		
		try {
			def c = new JsonSlurper().parseText(f.text);
			
			_interval = c.interval * 1000;
			
			if (c.pass_encrypted != true) {
				if (c.password != null) {
					c.password = Encryption.Encrypt(c.password);
					c.pass_encrypted = true;
					
					String newJson = new JsonBuilder(c).toPrettyString()
					f.withWriter( 'UTF-8' ) { it << newJson }
					
					_LOG.info("Rewriting ${fname} with encrypted password.");
				}
				else {
					_LOG.warn("No password for JVM ${fname} -- skipping");
					return;
				}
			}
			
			if (c.templates instanceof ArrayList) {
						
				c.templates.each{ t ->
					def tf = new File("${_templateDir}/${t}.json");
							  
					if (tf.exists()) {
						_LOG.info("Including template ${t}");
								
						try {
							def d = new JsonSlurper().parseText(tf.text);
									
							d.each{k,v ->
								_LOG.debug("Adding ${k}");
								c.mbeans[k] = v
							};
						}
						catch (Exception ex2) {
							_LOG.error("Invalid JSON in template ${t}.");
							_LOG.trace("Exception parsing template ${t}", ex2);
						}
					}
					else {
						_LOG.warn("Template ${t} Not Found");
					}
				}
			}
			else _LOG.info("No templates")
					
			_MBeans = c.mbeans;
			_Prefix = c.graphite_prefix;
		
			_JMXUrl = new JMXServiceURL(c.service_url);
			_ConnectionProperties = new HashMap<String,Object>();

			if(c.username != null)
			{
				_ConnectionProperties.put(JMXConnector.CREDENTIALS, [c.username, Encryption.Decrypt(c.password)] as String[]);
			}

			// For SSL connections
			if (c.ssl != null)
			{
				SslRMIClientSocketFactory csf = new SslRMIClientSocketFactory();
				SslRMIServerSocketFactory ssf = new SslRMIServerSocketFactory();
				_ConnectionProperties.put(RMIConnectorServer.RMI_CLIENT_SOCKET_FACTORY_ATTRIBUTE, csf);
				_ConnectionProperties.put(RMIConnectorServer.RMI_SERVER_SOCKET_FACTORY_ATTRIBUTE, ssf);
				_ConnectionProperties.put("com.sun.jndi.rmi.factory.socket", csf);
			}

			// This is for using External Jars for connecting to WebLogic, etc.
			if (c.provider != null)
			{
				_ConnectionProperties.put(JMXConnectorFactory.PROTOCOL_PROVIDER_PACKAGES, c.provider);
			}
		}
		catch (Exception ex) {
			_LOG.error("Invalid JSON in ${fname}");
			_LOG.trace("Exception parsing include ${fname}", ex);
		}
	}
	
	def JMXConnection(name, tmplDir, f) {
		this.setName(name);
		_templateDir = tmplDir;
		
		_LOG.info("Loading config for ${name}");
		LoadConfig(f);
	}

	def void run() {
		_LOG.info("Check Interval: ${_interval}");
		
		while (true) {
			def start = System.currentTimeMillis();
			def out = Discover();
			
			if (out instanceof ArrayList) JMXGraphite.sendUpdate(out);
			else out = [];
			
			def end = System.currentTimeMillis();
			def dur = end - start;
			
			_LOG.info("Retrieved ${out.size()} metrics in ${dur} ms");
			
			sleep(_interval - dur);
		}
	}
	
	def private Connect() {
		def connected = false;
		
		if (_Connector == null) {
			try {
				_LOG.info("Connecting to ${_JMXUrl}");
				_Connector = JMXConnectorFactory.connect(_JMXUrl, _ConnectionProperties);
				_Connection = _Connector.getMBeanServerConnection();
				_LOG.info("Connected Successfully.");
			}
			catch (Exception ex) {
				_LOG.debug("Can't get initial connection to ${_JMXUrl}");
				_LOG.trace("Exception calling getMBeanServerConnection():", ex);
				return false;
			}
		}
		
		try {
			_Connection.getMBeanCount();
			connected = true;
		}
		catch(Exception ex) {
			_LOG.info("Got disconnected from ${_JMXUrl}");
			_LOG.trace("Exception calling getMBeanCount()", ex);
			
			for (int c = 1; c < 4; c++)
			{
				_LOG.info("Reconnecting to ${_JMXUrl}, try #${c}");
				try {
					_Connector.connect(ConnectionProperties);
					connected = true;
					_LOG.info("Reconnected Successfully.");
					break;
				}
				catch (Exception ex2) {
					_LOG.warn("Unable to Connect to ${_JMXUrl}");
					_LOG.trace("Exception while connecting:", ex2);
					if (c < 3) {
						def backoff = Math.pow(10, c);
						_LOG.info("Backing off ${(int)backoff} ms");
						sleep((long)backoff);
					}
				}
			}
		}
		
		return connected;
	}
	
	def private Disconnect() {
		if(_Connector != null)
		{
			_LOG.info("Disconnecting from ${_JMXUrl}");
			try {
				_Connector.close();
			}
			catch (Exception ex) {
				_LOG.trace("Exception while disconnecting:", ex);
			}
			finally {
				_Connector = null;
			}
		}
	}
	
	def private Discover() {
		def Output = [];
		
		if (!Connect()) {
			_LOG.warn("Can't Connect to ${_JMXUrl}");
			Disconnect();
			return;
		}
		
		// Pre-load just in case queryNames doesn't support "*" 
		if (_allMBeans == null) _allMBeans = _Connection.queryNames(new ObjectName(""), null);
		
		_MBeans.each {MBName, MBAttrs ->
			def obj = new ObjectName(MBName);
			def mBeans
			
			try {
				mBeans = _Connection.queryNames(obj, null);
			}
			catch (Exception ex) {
				// WebLogic domainruntime service doesn't support queryNames with "*"
				// we need to use "" and then find the names ourselves :(
				_LOG.trace("Exception on queryNames():", ex)
				def q = MBName.replaceAll(/\*/,'.*');
				mBeans = _allMBeans.grep(~/${q}/);
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
					if (i % 2 == 1) objName += "." + t.replace('.', '_').replace(' ', '_').replace('[', '').replace(']', '').replace('"', '');
				}
								
				if (MBAttrs instanceof HashMap) {
					MBAttrs.each {CDSName, CDSAttrs ->
						if (CDSName == "attributes") {
							try {
								def values = _Connection.getAttributes(mb, CDSAttrs as String[]);
								def time = (int)(System.currentTimeMillis() / 1000);

								values.each {javax.management.Attribute v ->
									Output.add("${_Prefix}.${objName}.${v.getName()} ${v.getValue()} ${time}");
								}
							}
							catch (Exception ex) {
								_LOG.warn("Type not compatible with configuration: ${mb.toString()} - ${CDSAttrs}");
								_LOG.trace("Printing StackTrace:", ex);
							}
						}
						else {
							try {
								def CompositeDataSupport cds = _Connection.getAttribute(mb, CDSName);
							
								try {
									def time = (int)(System.currentTimeMillis() / 1000);
									def values = cds.getAll(CDSAttrs as String[])
								
									values.eachWithIndex {v,i ->
										Output.add("${_Prefix}.${objName}.${CDSName}.${CDSAttrs[i].toString()} ${v} ${time}");
									}
								}
								catch (Exception ex1) {
									_LOG.warn("All properties not found getting individually: ${mb.toString()} - ${CDSName} - ${CDSAttrs}");
									_LOG.trace("Printing StackTrace:", ex1);
									
									def time = (int)(System.currentTimeMillis() / 1000);
									
									CDSAttrs.each { a ->
										try {
											def v = cds.get(a);
											Output.add("${_Prefix}.${objName}.${CDSName}.${a.toString()} ${v} ${time}");
										}
										catch (Exception ex2) {
											_LOG.warn("Type not found or not compatible with configuration: ${mb.toString()} - ${CDSName} - ${a}");
											_LOG.trace("Printing StackTrace:", ex2);
										}
									}
								}
							}
							catch (Exception ex) {
								_LOG.warn("Type not found or not compatible with configuration: ${mb.toString()} - ${CDSName}");
								_LOG.trace("Printing StackTrace:", ex);
							}
						}
					}
				}
			}
		}
		
		return Output;
	}
}

	
