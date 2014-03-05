package jmxgraphite;

import java.io.ObjectInputStream.ValidationList;
import groovy.json.JsonSlurper;
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
    def static conf = "";
	def static JVMs = [];
	def static interval = 60 * 1000;
	def static graphite_host = "localhost";
	def static graphite_port = 2003;
	def static Logger LOG = LoggerFactory.getLogger(JMXGraphite.class);
	
	def static parse(args) {
		try {
			for(int i = 0; i < args.length; i++) {
				String option = args[i];

				if(option.equals("-c"))
				{
					conf = args[++i];
				}
			}
		}
		catch (Exception ex) {
			LOG.trace("Exception parsing Startup Arguments", ex);
		}
		
	}
	
	def static loadconfigs() {
		def inputFile;
		def config;
		
		try {
			LOG.info("Loading main config ${conf}");
			inputFile = new File(conf);
			config = new JsonSlurper().parseText(inputFile.text);
		}
		catch (Exception ex) {
			LOG.error("Config File ${conf} not found or invalid JSON.");
			LOG.trace("Exception parsing Global Config", ex);
			System.exit(1);
		}
		
		if (config.interval != null) interval = config.interval * 1000;
		if (config.graphite_host != null) graphite_host = config.graphite_host;
		if (config.graphite_port != null) graphite_port = config.graphite_port;
		
		def tmpl = config.templatedir;
		
		def inc = config.jvmdir;
		
		if (inc != null) {
			def idir = new File(inc);
			
			if (idir.exists()) {
				def files = [];
				files = idir.list( [accept:{d, f-> f ==~ /.*\.json$/ }] as FilenameFilter);

				files.each{ fname -> 
					def f = new File("${inc}/${fname}");
					
					LOG.info("Loading config ${inc}/${fname}");
					
					if (f.exists()) {
						try {
							def c = new JsonSlurper().parseText(f.text);
						    if (c.templates instanceof ArrayList) {
								
								c.templates.each{ t -> 
									def tf = new File("${tmpl}/${t}.json");
									  
									if (tf.exists()) {
										LOG.info("Including template ${t}");
										
										try {
											def d = new JsonSlurper().parseText(tf.text);
											
											d.each{k,v ->
												LOG.debug("Adding ${k}");
												c.mbeans[k] = v
											};
										}
										catch (Exception ex2) {
											LOG.error("Invalid JSON in template ${t}.");
											LOG.trace("Exception parsing template ${t}", ex2);
										}
									}
									else {
										LOG.warn("Template ${t} Not Found");
									}
								}	
						    }
							else LOG.info("No templates")
							
							JVMs.add(new JMXConnection(c.graphite_url, c.service_url, c.username, c.password, c.ssl, c.provider, c.mbeans));
						}
						catch (Exception ex) {
							LOG.error("Invalid JSON in ${fname}");
							LOG.trace("Exception parsing include ${fname}", ex);
						}
					}
					else {
						LOG.error("Include file ${fname} doesn't exist"); 
					}
				}
			}
			else {
				LOG.warn("Include folder ${inc} doesn't exist!");
			}
		}
	}
	
	def static main(args) {
		def shutdown = false;
		
		parse(args);		
		loadconfigs();

		def handler;
		Signal.handle( new Signal("HUP"), [ handle:{ sig ->
			LOG.info("Caught SIGHUP, Reloading configs...");
			JVMs.each {jvm -> jvm.Disconnect()}
			JVMs = [];

			loadconfigs();

			if (handler) handler.handle(sig)
		} ] as SignalHandler );
	
		Runtime.getRuntime().addShutdownHook((Thread)ProxyGenerator.instantiateAggregate([run: {
			shutdown = true;
			LOG.info("Shutdown initiated");
			JVMs.each {jvm -> jvm.Disconnect() }
		}
		], [Runnable], Thread.class))
		
		LOG.debug("Interval: ${interval}");
		
		while (!shutdown) {
			def start = System.currentTimeMillis();
			def Outputs = [];
			
			JVMs.each {jvm ->
				def out;
				
				try {
					out = jvm.Discover();
				}
				catch (Exception ex) {
					LOG.error("Error getting data from JVM.");
					LOG.trace("Exception calling jvm.Discover()", ex);
				}
				
				if (out != null) Outputs.addAll(out);
			}
			
			if (Outputs.size() > 0) {
				def requestSocket = new Socket(graphite_host, graphite_port);
				def writer = new BufferedWriter(new OutputStreamWriter(requestSocket.getOutputStream()));
			
				Outputs.each {o ->
					LOG.debug(o);
					writer.writeLine(o);
					writer.flush();
				};
			
				writer.flush()
				writer.close()
			}
			else LOG.debug("Nothing to write, skipping...");
			
			def end = System.currentTimeMillis();
			def dur = end - start;
			LOG.debug("${Outputs.size()} metrics in ${dur} ms");
			
			
			def sleepTime = (interval - dur);
			if (sleepTime >  0) {
				LOG.debug("Sleeping for ${sleepTime} ms");
			}
			else {
				LOG.warn("Operation time exceeded or equal to check interval - defaulting to 100ms sleep to prevent cpu saturation")
				sleepTime = 100;
			}
			
			sleep(sleepTime)
		}
		
		LOG.info("Shudown completed");
	}
}
