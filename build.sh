#!/bin/sh
# Plain SDK build (no Gradle): aapt2 + javac + d8, signed with this PC's debug key.
set -eu
cd "$(dirname "$0")"
SDK=${ANDROID_HOME:-$HOME/Android/Sdk}
BT=$SDK/build-tools/36.0.0
JAR=$SDK/platforms/android-36/android.jar
JAVA_HOME=${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}
rm -rf out && mkdir -p out/res out/classes out/dex
sh test/run.sh
"$BT/aapt2" compile --dir res -o out/res.zip
"$BT/aapt2" link -I "$JAR" --manifest AndroidManifest.xml -A assets -o out/unsigned.apk out/res.zip
"$JAVA_HOME/bin/javac" -nowarn --release 11 -cp "$JAR" -d out/classes $(find src -name '*.java')
"$BT/d8" --release --min-api 26 --lib "$JAR" --output out/dex $(find out/classes -name '*.class')
(cd out/dex && zip -q ../unsigned.apk classes.dex)
"$BT/zipalign" -f -p 4 out/unsigned.apk out/aligned.apk
"$BT/apksigner" sign --ks "$HOME/.android/debug.keystore" --ks-pass pass:android --key-pass pass:android \
    --out out/ex5-installer.apk out/aligned.apk
"$BT/apksigner" verify out/ex5-installer.apk 2>/dev/null
ls -l out/ex5-installer.apk
