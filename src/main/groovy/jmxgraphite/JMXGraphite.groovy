package jmxgraphite

import groovy.json.JsonSlurper
import sun.misc.Signal
import sun.misc.SignalHandler
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * @author nbenns
 *
 */
class JMXGraphite {
    static _globalConf = 'conf/global.json'
	static _sendInterval = 1000
	static _graphiteHost = 'localhost'
	static _graphitePort = 2003
	static _templateDir = 'templates'
	static _jvmDir = 'jvms'
	
	static _output = []
	static _lock = new Object()
	
	static _JVMs = []
	private static final Logger _LOG = LoggerFactory.getLogger(JMXGraphite)
	
	static parse(args) {
		try {
			for (int i = 0; i < args.length; i++) {
				String option = args[i]

				if (option.equals('-c'))
				{
					_globalConf = args[++i]
				}
			}
		}
		catch (Exception ex) {
			_LOG.trace('Exception parsing Startup Arguments', ex)
		}
		
	}
	
	static loadMainConfig() {
		def config
		def inputFile = new File(_globalConf)
		
		if (inputFile.exists()) {
			try {
				_LOG.info("Loading main config ${_globalConf}")
				config = new JsonSlurper().parseText(inputFile.text)
			}
			catch (Exception ex) {
				_LOG.error("Config File ${_globalConf} invalid JSON.")
				_LOG.trace('Exception parsing Global Config', ex)
				System.exit(2)
			}
		}
		else {
			_LOG.error("Config File ${_globalConf} not found.")
			System.exit(1)
		}
		
		if (config.send_interval != null) _sendInterval = config.send_interval * 1000
		if (config.graphite_host != null) _graphiteHost = config.graphite_host
		if (config.graphite_port != null) _graphitePort = config.graphite_port
		if (config.templatedir != null) _templateDir = config.templatedir
		if (config.jvmdir != null) _jvmDir = config.jvmdir
	}
	
	static loadJVMs() {
		def jdir = new File(_jvmDir)
			
		if (jdir.exists()) {
			def files = []
			files = jdir.list( [accept: { d, f -> f ==~ /.*\.json$/ } ] as FilenameFilter)

			files.each { fname ->
				def name = fname.tokenize('.')[0]
				def jvm = new JMXConnection(name, _templateDir, "${_jvmDir}/${fname}")
				
				jvm.start()
				
				_JVMs.add(jvm)
			}
		}
		else {
			_LOG.warn("Include folder ${_jvmDir} doesn't exist!")
		}
	}
	
	static sendUpdate(output) {
		synchronized(_lock) {
			_LOG.debug('Updating output')
			_output.addAll(output)
		}
	}
	
	static main(args) {
		parse(args)
		loadMainConfig()
		loadJVMs()
		
		def graphiteAddr = new InetSocketAddress(_graphiteHost, _graphitePort)
		def graphiteLine = new Socket()
		def shutdown = false
		def handler

		Signal.handle( new Signal('HUP'), [ handle: { sig ->
			_LOG.info('Caught SIGHUP, Reloading configs...')
			
			_JVMs.each { jvm -> jvm.stop() }
			_JVMs = []

			loadMainConfig()
			loadJVMs()

			if (handler) handler.handle(sig)
		} ] as SignalHandler )
	
		//Runtime.getRuntime().addShutdownHook((Thread)ProxyGenerator.instantiateAggregate([run: {
		Runtime.runtime.addShutdownHook((Thread)ProxyGenerator.instantiateAggregate([run: {
			shutdown = true
			_LOG.info('Shutdown initiated')
			_JVMs.each { jvm -> jvm.stop() }
		}
		], [Runnable], Thread))
		
		_LOG.debug("Graphite Send Interval: ${_sendInterval}")
		
		while (!shutdown) {
			def start = System.currentTimeMillis()
			def sz = _output.size()
			def wrote = 0
			def dropped = 0
			
			if (sz > 0) {
				if (! graphiteLine.connected) {
					try {
						graphiteLine = new Socket()
						graphiteLine.connect(graphiteAddr)
						
						_LOG.info("Connected to Graphite @ ${_graphiteHost}:${_graphitePort}")
					}
					catch (Exception ex) {
						graphiteLine.close()
						
						_LOG.warn('Can\'t connect to Graphite')
						_LOG.trace('Graphite Exception', ex)
					}
				}
				
				synchronized(_lock) {
					Iterator i = _output.iterator()
				
					while (i.hasNext()) {
						def o = i.next()
				   
						_LOG.debug(o)
						try {
							if (graphiteLine.connected) {
								//def graphite = new BufferedWriter(new OutputStreamWriter(graphiteLine.getOutputStream()))
								def graphite = new BufferedWriter(new OutputStreamWriter(graphiteLine.outputStream))
								
								graphite.writeLine(o)
								graphite.flush()
								
								wrote++
							}
							else dropped++
						}
						catch (Exception ex) {
							graphiteLine.close()
							graphiteLine.connected = false
							
							_LOG.warn('Can\'t write to Graphite')
							_LOG.trace('Graphite Exception', ex)
							dropped++
						}
				   
						i.remove()
					}
				}
			
				def end = System.currentTimeMillis()
				def dur = end - start
				_LOG.info("metrics:${sz} - wrote:${wrote}, dropped:${dropped} in ${dur} ms")
			}
			sleep(_sendInterval)
		}
		
		_LOG.info('Shudown completed')
	}
}
