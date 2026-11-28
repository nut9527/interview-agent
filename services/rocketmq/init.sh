#!/bin/sh
set -eu
classes=$(mktemp -d)
trap 'rm -rf "$classes"' EXIT
# 使用当前 Broker 镜像自带的管理库编译，确保初始化程序与服务端版本一致。
javac -proc:none -cp "$ROCKETMQ_HOME/lib/*" -d "$classes" /dispatch/InitResources.java
java -Xmx128m -cp "$classes:$ROCKETMQ_HOME/lib/*" InitResources
