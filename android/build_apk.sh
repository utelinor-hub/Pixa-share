#!/usr/bin/env bash
# ============================================================
# PIXA SHARE — APK build (no Gradle, no Android Studio)
# aapt2 (resources) -> ECJ (java -> class) -> d8 (dex)
# -> zip -> zipalign -> apksigner (debug keystore)
# Output: ../app/pixa-share.apk  (stable name for the in-app link)
# ============================================================
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
TOOLS="/home/z/my-project/tools/android"
BT="$TOOLS/android-14"                       # build-tools 34
AJ="$TOOLS/android-34/android.jar"           # platform android.jar
ECJ="$TOOLS/ecj.jar"
OUT="$ROOT/out"

rm -rf "$OUT"
mkdir -p "$OUT/classes" "$OUT/dex"

echo "== 1/5 resources (aapt2) =="
"$BT/aapt2" compile --dir "$ROOT/res" -o "$OUT/res.zip"
"$BT/aapt2" link -o "$OUT/base.apk" -I "$AJ" \
  --manifest "$ROOT/AndroidManifest.xml" -R "$OUT/res.zip" --auto-add-overlay

echo "== 2/5 java -> class (ECJ) =="
# -source/-target 8: module-free compile so java.* resolves cleanly
# against the JDK while android.* comes from android.jar
java -jar "$ECJ" -nowarn -source 8 -target 8 -classpath "$AJ" -d "$OUT/classes" \
  "$ROOT/src/com/shivam/pixashare/MainActivity.java"

echo "== 3/5 class -> dex (d8) =="
"$BT/d8" --release --min-api 24 --lib "$AJ" --output "$OUT/dex" \
  $(find "$OUT/classes" -name '*.class')
(cd "$OUT/dex" && zip -qj "$OUT/base.apk" classes.dex)

echo "== 4/5 align (zipalign) =="
"$BT/zipalign" -f 4 "$OUT/base.apk" "$OUT/aligned.apk"

echo "== 5/5 sign (apksigner, debug keystore) =="
if [ ! -f "$ROOT/debug.keystore" ]; then
  keytool -genkeypair -keystore "$ROOT/debug.keystore" \
    -storepass android -keypass android -alias androiddebugkey \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=PIXA SHARE Debug,O=SHIVAM PIXA,C=IN" >/dev/null 2>&1
fi
mkdir -p "$ROOT/../app"
"$BT/apksigner" sign --ks "$ROOT/debug.keystore" \
  --ks-pass pass:android --ks-key-alias androiddebugkey \
  --out "$ROOT/../app/pixa-share.apk" "$OUT/aligned.apk"

"$BT/apksigner" verify --print-certs "$ROOT/../app/pixa-share.apk" | head -4
echo "== APK built: $ROOT/../app/pixa-share.apk ($(du -h "$ROOT/../app/pixa-share.apk" | cut -f1)) =="
