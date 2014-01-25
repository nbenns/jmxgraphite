package jmxgraphite;

import java.io.ObjectInputStream.ValidationList;
import groovy.json.JsonSlurper;

/**
 * @author nbenns
 *
 */
class JMXGraphite {
    
	def static main(args) {
		def conf = "/home/nbenns/jmxgraphite/conf.json";
		def inputFile;
		def config;
		try {
			inputFile = new File(conf);
			config = new JsonSlurper().parseText(inputFile.text);
		}
		catch (Exception ex) {
			println "Config File ${conf} not found or invalid JSON."
			System.exit(1);
		}
		def JVMs = [];
		
		def interval = (config.interval != null) ? (config.interval * 1000) : 60 * 1000;
		def graphite_host = (config.graphite_host != null) ? config.graphite_host : "localhost";
		def graphite_port = (config.graphite_port != null) ? config.graphite_port : 2003;
		
		config.virtualmachines.each { v ->
			def j = new JMXConnection(v.graphite_url, v.service_url, v.username, v.password, v.mbeans);
			JVMs.add(j);
		}

		while (true) {
			def start = System.currentTimeMillis();
			def requestSocket = new Socket(graphite_host, graphite_port);
			def writer = new BufferedWriter(new OutputStreamWriter(requestSocket.getOutputStream()));
			def Outputs = [];
			
			JVMs.each {jvm ->
				jvm.Discover().each {o -> Outputs.add(o)};
			}
			
			Outputs.each {o ->
				println o;
				writer.writeLine(o);
				writer.flush();
			};
			
			writer.flush()
			writer.close()
			
			def end = System.currentTimeMillis();
			def dur = end - start;
			println dur;
			println();
			sleep(interval - dur)
		}
	}

}
