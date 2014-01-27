package jmxgraphite;

import java.io.ObjectInputStream.ValidationList;
import groovy.json.JsonSlurper;
import groovy.io.FileType;
import java.io.FilenameFilter;
import sun.misc.Signal;
import sun.misc.SignalHandler;

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
			
		}
		
	}
	
	def static loadconfigs() {
		def inputFile;
		def config;
		
		try {
			println "Loading main config ${conf}";
			inputFile = new File(conf);
			config = new JsonSlurper().parseText(inputFile.text);
		}
		catch (Exception ex) {
			println "Config File ${conf} not found or invalid JSON."
			System.exit(1);
		}
		
		if (config.interval != null) interval = config.interval * 1000;
		if (config.graphite_host != null) graphite_host = config.graphite_host;
		if (config.graphite_port != null) graphite_port = config.graphite_port;
		
		config.virtualmachines.each { v ->
			JVMs.add(new JMXConnection(v.graphite_url, v.service_url, v.username, v.password, v.ssl, v.provider, v.mbeans));
		}
		
		def inc = config.includedir;
		
		if (inc != null) {
			def dir = new File(inc);
			
			if (dir.exists()) {
				def files = [];
				files = dir.list( [accept:{d, f-> f ==~ /.*\.json$/ }] as FilenameFilter);

				files.each{fname -> 
					def f = new File("${inc}/${fname}");
					
					println "Including config ${inc}/${fname}";
					
					if (f.exists()) {
						try {
							def c = new JsonSlurper().parseText(f.text);
						
							JVMs.add(new JMXConnection(c.graphite_url, c.service_url, c.username, c.password, c.ssl, c.provider, c.mbeans));
						}
						catch (Exception ex) {
							println "Invalid JSON in ${fname}";
							println ex;
						}
					}
					else {
						println "Include file ${fname} doesn't exist"; 
					}
				}
			}
			else {
				println "Include folder ${inc} doesn't exist!";
			}
		}
	}
	
	def static main(args) {
		parse(args);		
		loadconfigs();

		def handler;
		Signal.handle( new Signal("HUP"), [ handle:{ sig ->
			println "Caught SIGHUP, Reloading configs..."
			JVMs.each {jvm -> jvm.Disconnect()}
			JVMs = [];
			loadconfigs();
			
			if (handler) handler.handle(sig)
		} ] as SignalHandler );
		
		println "Interval: ${interval}"
		while (true) {
			def start = System.currentTimeMillis();
			def Outputs = [];
			
			JVMs.each {jvm ->
				def out = jvm.Discover();
				if (out != null) Outputs.addAll(out);
			}
			
			if (Outputs.size() > 0) {
				def requestSocket = new Socket(graphite_host, graphite_port);
				def writer = new BufferedWriter(new OutputStreamWriter(requestSocket.getOutputStream()));
			
				Outputs.each {o ->
					println o;
					writer.writeLine(o);
					writer.flush();
				};
			
				writer.flush()
				writer.close()
			}
			else println "Nothing to write, skipping...";
			
			def end = System.currentTimeMillis();
			def dur = end - start;
			println "${Outputs.size()} metrics in ${dur} ms";
			println("sleeping for ${interval - dur}");
			sleep(interval - dur)
		}
	}
}
