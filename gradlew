#!/bin/sh
PRG="$0"
while [ -h "$PRG" ]; do
  ls=$(ls -ld "$PRG")
  link=$(expr "$ls" : '.*-> \(.*\)$')
  if expr "$link" : '/.*' > /dev/null; then PRG="$link"
  else PRG="$(dirname "$PRG")/$link"; fi
done
APP_HOME=$(cd "$(dirname "$PRG")" > /dev/null && pwd -P) || exit
if [ -n "$JAVA_HOME" ]; then JAVA="$JAVA_HOME/bin/java"
else JAVA=$(which java 2>/dev/null) || { echo "ERROR: java not found"; exit 1; }; fi
exec "$JAVA" -Xmx64m -Xms64m \
  -classpath "$APP_HOME/gradle/wrapper/gradle-wrapper.jar" \
  org.gradle.wrapper.GradleWrapperMain "$@"
