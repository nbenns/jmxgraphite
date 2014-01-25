package jmxgraphite;

import java.io.ObjectInputStream.ValidationList;
import groovy.json.JsonSlurper;

/**
 * @author nbenns
 *
 */
class JMXGraphite {
    
	def static main(args) {
		def inputFile = new File("/home/nbenns/jmxgraphite/conf.json")
		def config = new JsonSlurper().parseText(inputFile.text)
		def JVMs = [];
		
		def interval = (config.interval != null) ? (config.interval * 1000) : 60 * 1000;
		def graphite_host = (config.graphite_host != null) ? config.graphite_host : "localhost";
		def graphite_port = (config.graphite_port != null) ? config.graphite_port : 2003;
		
		config.virtualmachines.each { v ->
			JVMs.add(new JMXConnection(v.graphite_url, v.service_url, v.username, v.password, v.mbeans));
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
			println();
			def end = System.currentTimeMillis();
			def dur = end - start;
			
			sleep(interval - dur)
		}
	}

}
