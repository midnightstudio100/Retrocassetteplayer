#!/bin/bash
set -e
if [ -d "$HOME/workspace/android-builds/jdk-17.0.21+9" ]; then
  export JAVA_HOME=$HOME/workspace/android-builds/jdk-17.0.21+9
else
  export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
fi
export PATH=$JAVA_HOME/bin:$PATH
SDK=$HOME/workspace/android-builds/sdk
PLATFORM=$SDK/platforms/android-37.0/android.jar
BT=$SDK/build-tools/37.0.0
PROJ=$HOME/workspace/android-builds/retro-cassette-radio-player
APP=$PROJ/app/src/main
BUILD=$PROJ/build
rm -rf $BUILD/gen $BUILD/classes $BUILD/dex $BUILD/*.apk $BUILD/*.zip
mkdir -p $BUILD/gen $BUILD/classes $BUILD/dex
echo "== aapt2 compile"
$BT/aapt2 compile --dir $APP/res -o $BUILD/compiled.zip
echo "== aapt2 link"
$BT/aapt2 link -o $BUILD/app.unaligned.apk \
  --manifest $APP/AndroidManifest.xml \
  -I $PLATFORM \
  --java $BUILD/gen \
  -R $BUILD/compiled.zip \
  --auto-add-overlay \
  --min-sdk-version 24 --target-sdk-version 37 \
  --version-code 29 --version-name 3.7-skipbuffer
find $BUILD/gen -name "*.java"
echo "== javac"
find $APP/java -name "*.java" > $BUILD/sources.txt
javac -encoding UTF-8 -source 8 -target 8 -nowarn -cp $PLATFORM -d $BUILD/classes \
  $(find $BUILD/gen -name "*.java") $(cat $BUILD/sources.txt)
echo "== d8"
$BT/d8 --min-api 24 --output $BUILD/dex $(find $BUILD/classes -name "*.class")
ls -lh $BUILD/dex/
echo "== add dex to apk"
python3 - << 'PY'
import zipfile, os
apk = os.path.expanduser("~/workspace/android-builds/retro-cassette-radio-player/build/app.unaligned.apk")
dexdir = os.path.expanduser("~/workspace/android-builds/retro-cassette-radio-player/build/dex")
with zipfile.ZipFile(apk, 'a', zipfile.ZIP_DEFLATED) as z:
    for root, dirs, files in os.walk(dexdir):
        for f in files:
            full = os.path.join(root, f)
            arc = os.path.relpath(full, dexdir)
            z.write(full, arc)
            print("added", arc)
PY
echo "== zipalign"
$BT/zipalign -f 4 $BUILD/app.unaligned.apk $BUILD/app.aligned.apk
echo "== keystore"
if [ ! -f $PROJ/debug.keystore ]; then
  keytool -genkeypair -keystore $PROJ/debug.keystore -storepass android -keypass android \
    -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=Android Debug, OU=Android, O=Android, L=Mountain View, ST=California, C=US" -noprompt
fi
echo "== apksigner"
$BT/apksigner sign --ks $PROJ/debug.keystore --ks-pass pass:android --key-pass pass:android \
  --out $BUILD/app-debug.apk $BUILD/app.aligned.apk
echo "== verify"
$BT/apksigner verify --verbose $BUILD/app-debug.apk || true
$BT/aapt dump badging $BUILD/app-debug.apk | head -20
unzip -l $BUILD/app-debug.apk | head -40
ls -lh $BUILD/app-debug.apk
echo "BUILD OK"
