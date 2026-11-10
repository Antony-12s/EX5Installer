#!/bin/sh
# JVM tests: Adb + Installer are pure Java. LiveTest needs a device: EX5_HOST=ip EX5_PORT=5566 sh test/run.sh
set -eu
cd "$(dirname "$0")/.."
JAVA_HOME=${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}
rm -rf out/test && mkdir -p out/test
"$JAVA_HOME/bin/javac" -nowarn -d out/test src/com/wasak/ex5installer/Adb.java src/com/wasak/ex5installer/Installer.java src/com/wasak/ex5installer/Scrcpy.java test/*.java
"$JAVA_HOME/bin/java" -cp out/test ScriptTest
if [ -n "${EX5_HOST:-}" ]; then "$JAVA_HOME/bin/java" -cp out/test LiveTest "$EX5_HOST" "${EX5_PORT:-5566}" ${EX5_APK:-}; fi
