package jmxgraphite;

import java.io.ObjectInputStream.ValidationList;
import groovy.json.JsonSlurper;
import groovy.json.JsonBuilder;
import groovy.io.FileType;
import java.io.FilenameFilter;
import sun.misc.Signal;
import sun.misc.SignalHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author nbenns
 *
 */
class JMXGraphite {
    def static _globalConf = "conf/global.json";
	def static _interval = 60 * 1000;
	def static _graphiteHost = "localhost";
	def static _graphitePort = 2003;
	def static _templateDir = "templates";
	def static _jvmDir = "jvms";
	
	def static _JVMs = [];
	def static Logger _LOG = LoggerFactory.getLogger(JMXGraphite.class);
	
	def static parse(args) {
		try {
			for(int i = 0; i < args.length; i++) {
				String option = args[i];

				if(option.equals("-c"))
				{
					_globalConf = args[++i];
				}
			}
		}
		catch (Exception ex) {
			_LOG.trace("Exception parsing Startup Arguments", ex);
		}
		
	}
	
	def static loadMainConfig() {
		def config;
		def inputFile = new File(_globalConf);
		
		if(inputFile.exists()) {
			try {
				_LOG.info("Loading main config ${_globalConf}");
				config = new JsonSlurper().parseText(inputFile.text);
			}
			catch (Exception ex) {
				_LOG.error("Config File ${_globalConf} invalid JSON.");
				_LOG.trace("Exception parsing Global Config", ex);
				System.exit(2);
			}
		}
		else {
			_LOG.error("Config File ${_globalConf} not found.");
			System.exit(1);
		}
		
		if (config.interval != null) _interval = config.interval * 1000;
		if (config.graphite_host != null) _graphiteHost = config.graphite_host;
		if (config.graphite_port != null) _graphitePort = config.graphite_port;
		if (config.templatedir != null) _templateDir = config.templatedir;
		if (config.jvmdir != null) _jvmDir = config.jvmdir;
	}
	
	def static loadJVMs() {
		def jdir = new File(_jvmDir);
			
		if (jdir.exists()) {
			def files = [];
			files = jdir.list( [accept:{d, f-> f ==~ /.*\.json$/ }] as FilenameFilter);

			files.each{ fname -> 
				def f = new File("${_jvmDir}/${fname}");
					
				_LOG.info("Loading config ${_jvmDir}/${fname}");
					
				try {
					def c = new JsonSlurper().parseText(f.text);
					
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
							
					_JVMs.add(new JMXConnection(c.graphite_prefix, c.service_url, c.username, c.password, c.ssl, c.provider, c.mbeans));
				}
				catch (Exception ex) {
					_LOG.error("Invalid JSON in ${fname}");
					_LOG.trace("Exception parsing include ${fname}", ex);
				}
			}
		}
		else {
			_LOG.warn("Include folder ${_jvmDir} doesn't exist!");
		}
	}
	
	def static main(args) {
		def shutdown = false;
		
		parse(args);
		loadMainConfig();		
		loadJVMs();

		def handler;
		Signal.handle( new Signal("HUP"), [ handle:{ sig ->
			_LOG.info("Caught SIGHUP, Reloading configs...");
			
			_JVMs.each {jvm -> jvm.Disconnect()}
			_JVMs = [];

			loadMainConfig();
			loadJVMs();

			if (handler) handler.handle(sig)
		} ] as SignalHandler );
	
		Runtime.getRuntime().addShutdownHook((Thread)ProxyGenerator.instantiateAggregate([run: {
			shutdown = true;
			_LOG.info("Shutdown initiated");
			_JVMs.each {jvm -> jvm.Disconnect() }
		}
		], [Runnable], Thread.class))
		
		_LOG.debug("Interval: ${_interval}");
		
		while (!shutdown) {
			def start = System.currentTimeMillis();
			def Outputs = [];
			
			_JVMs.each {jvm ->
				def out;
				
				try {
					out = jvm.Discover();
				}
				catch (Exception ex) {
					_LOG.error("Error getting data from JVM.");
					_LOG.trace("Exception calling jvm.Discover()", ex);
				}
				
				if (out != null) Outputs.addAll(out);
			}
			
			if (Outputs.size() > 0) {
				def requestSocket = new Socket(_graphiteHost, _graphitePort);
				def writer = new BufferedWriter(new OutputStreamWriter(requestSocket.getOutputStream()));
			
				Outputs.each {o ->
					_LOG.debug(o);
					writer.writeLine(o);
					writer.flush();
				};
			
				writer.flush()
				writer.close()
			}
			else _LOG.debug("Nothing to write, skipping...");
			
			def end = System.currentTimeMillis();
			def dur = end - start;
			_LOG.debug("${Outputs.size()} metrics in ${dur} ms");
			
			
			def sleepTime = (_interval - dur);
			if (sleepTime >  0) {
				_LOG.debug("Sleeping for ${sleepTime} ms");
			}
			else {
				_LOG.warn("Operation time exceeded or equal to check interval - defaulting to 100ms sleep to prevent cpu saturation")
				sleepTime = 100;
			}
			
			sleep(sleepTime)
		}
		
		_LOG.info("Shudown completed");
	}
}
