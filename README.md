JMXGraphite

This project is still in its infancy, so beware.
Once it is deployable, I will update this to be more concise.
Right now its just random info I put in a place I won't forget.

Connecting to standard JMX:

{
  "graphite_url": "apps.jvm.blah",
  "service_url": "service:jmx:rmi:///jndi/rmi://<hostname>:<port>/jmxrmi",
  "username": "monitor",
  "password": "monitor",

  "mbeans": {

    "java.lang:type=Memory": {
      "HeapMemoryUsage": [ "committed", "init", "max", "used" ],
      "NonHeapMemoryUsage": [ "committed", "init", "max", "used" ],

      "attributes": [
        "ObjectPendingFinalizationCount"
      ]
    }
  }
}

example with match:

    "java.lang:type=MemoryPool,name=*": {
      "PeakUsage": [ "committed", "init", "max", "used" ],
      "Usage": [ "committed", "init", "max", "used" ],

      "attributes": [
        "UsageThreshold",
        "UsageThresholdCount"
      ]
    },

WebLogic doesn't support queryNames on the domainruntime service, but does on the runtime service
However all the good stuff is in domainruntime :(

service:jmx:t3s://<host>:<port>/jndi/weblogic.management.mbeanservers.domainruntime
service:jmx:t3s://<host>:<port>/jndi/weblogic.management.mbeanservers.runtime

Here is how to get jvisualvm to connect to WebLogic:

jvisualvm -J-Djmx.remote.protocol.provider.pkgs=weblogic.management.remote -J-Dweblogic.security.TrustKeyStore=CustomTrust -J-Dweblogic.security.CustomTrustKeyStoreFileName=trust.jks -cp wlcipher.jar:wlfullclient.jar

To connect JMXGraphite with WebLogic you need to have a WebLogic full client jar, and the wlcypher jar as well.
Then add some params at startup.

-classpath wlcipher.jar:wlfullclient.jar

in you're virtual server setting:

"provider": "weblogic.management.remote",

if you are using an SSL enabled connection:

-Dweblogic.security.TrustKeyStore=CustomTrust
-Dweblogic.security.CustomTrustKeyStoreFileName=<truststore>

in you're virtual server:

"ssl": true,

example mbean:

"mbeans": {
    "com.bea:Name=server.jms,ServerRuntime=server,Location=server,Type=JMSRuntime": {
      "attributes": [
        "ConnectionsCurrentCount"
      ]
    }
}