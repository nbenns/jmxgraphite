%define debug_package %{nil}
%define __find_requires %{nil}
Name:		jmxgraphite
Version:	1.0.0
Release:	1
Summary:	JMXGraphite-%{version}

Group:		Administration/Monitoring
License:	LGPL
URL:		https://github.com/polariss0i/jmxgraphite
Source0:	jmxgraphite-%{version}.tgz

BuildRequires:	maven
BuildArch:	noarch

%description
Send JMX metrics to a Graphite host for monitoring

%prep
%setup -q -n jmxgraphite

%build
mvn package

%install
./install.sh %{buildroot}/opt
mkdir -p %{buildroot}/etc/init.d
cp %{buildroot}/opt/jmxgraphite/bin/jmxgraphite %{buildroot}/etc/init.d
chmod +x %{buildroot}/etc/init.d/jmxgraphite

%files
%defattr(664, jmxgraphite, jmxgraphite, 775)

%dir /opt/jmxgraphite
%dir /opt/jmxgraphite/bin
%dir /opt/jmxgraphite/lib
%dir /opt/jmxgraphite/conf
%dir /opt/jmxgraphite/templates
%dir /opt/jmxgraphite/jvms
%dir /opt/jmxgraphite/logs

%verify(not owner group) /opt/jmxgraphite/bin/jmxgraphite
%verify(not owner group) /opt/jmxgraphite/lib/jmxgraphite-1.0.0-jar-with-dependencies.jar

%attr(755, root, root) /etc/init.d/jmxgraphite

%config %verify(not owner group size md5 mtime) /opt/jmxgraphite/java.conf
%config %verify(not owner group size md5 mtime) /opt/jmxgraphite/conf/global.json
%config %verify(not owner group size md5 mtime) /opt/jmxgraphite/conf/logging.xml
%config %verify(not owner group size md5 mtime) /opt/jmxgraphite/templates/java.json
%config %verify(not owner group size md5 mtime) /opt/jmxgraphite/templates/tomcat.json
%config %verify(not owner group size md5 mtime) /opt/jmxgraphite/jvms/example.json

%pre

if ! getent passwd jmxgraphite
then
  useradd jmxgraphite -r -d /opt/jmxgraphite -s /bin/bash
fi

%changelog
