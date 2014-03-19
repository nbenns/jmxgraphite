#!/bin/bash

prefix=$1

if [ "${prefix}" == "" ]
then
  prefix="/opt"
fi

base="${prefix}/jmxgraphite"

#mvn package

mkdir -p ${prefix}/jmxgraphite/{bin,conf,lib,logs}
cp -rf src/main/config/{jvms,templates} ${base}

find src/main/config -maxdepth 1 -type f |xargs -I{} -n1 cp {} ${base}/conf

find src/main/scripts -maxdepth 1 -type f |xargs -I{} -n1 cp {} ${base}/bin

cp target/jmxgraphite-*-jar-with-dependencies.jar ${base}/lib

echo "export JAVA_OPTS" > ${base}/java.conf
