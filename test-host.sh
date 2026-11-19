#!/usr/bin/env bash
set -Eeuo pipefail
ROOT=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
SRC="$ROOT/src/main/java/org/matonos/store/fdroid"
TEST="$ROOT/src/testHost/java/org/matonos/store/fdroid"
TMP=$(mktemp -d "${MATON_TEST_TMPDIR:-${TMPDIR:-/tmp}}/fdroid-host.XXXXXX")
trap 'rm -rf "$TMP"' EXIT
mkdir -p "$TMP/classes" "$TMP/payload"
cat > "$TMP/index-v2.json" <<'EOF'
{"packages":{}}
EOF
INDEX_HASH=$(sha256sum "$TMP/index-v2.json" | cut -d' ' -f1)
printf '{"index":{"sha256":"%s"}}' "$INDEX_HASH" > "$TMP/payload/entry.json"
keytool -genkeypair -alias fixture -keyalg RSA -keysize 2048 -validity 2 \
  -dname 'CN=F-Droid host test' -storetype PKCS12 -keystore "$TMP/test.p12" \
  -storepass changeit -keypass changeit -noprompt >/dev/null 2>&1
jar --create --file "$TMP/entry.jar" -C "$TMP/payload" .
jarsigner -keystore "$TMP/test.p12" -storepass changeit -keypass changeit "$TMP/entry.jar" fixture >/dev/null
# F-Droid's entry.jar stores entry.json before META-INF/; keep that order so
# verification is tested against the real layout (JarInputStream fails on it).
python3 -c 'import sys,zipfile
src=zipfile.ZipFile(sys.argv[1]); names=sorted(src.namelist(), key=lambda n: n.startswith("META-INF/"))
with zipfile.ZipFile(sys.argv[2],"w",zipfile.ZIP_DEFLATED) as out:
  [out.writestr(src.getinfo(n), src.read(n)) for n in names]' "$TMP/entry.jar" "$TMP/entry-fdroid.jar"
mv "$TMP/entry-fdroid.jar" "$TMP/entry.jar"
[[ $(unzip -Z1 "$TMP/entry.jar" | sed -n 1p) == entry.json ]] || { echo "fixture JAR not in F-Droid order" >&2; exit 1; }
keytool -exportcert -alias fixture -keystore "$TMP/test.p12" -storepass changeit -file "$TMP/cert.der" >/dev/null 2>&1
SDK=${ANDROID_HOME:-$HOME/Android/Sdk}
AAPT2="$SDK/build-tools/36.0.0/aapt2"
for kind in base split; do
  split_attr=; [[ $kind != split ]] || split_attr='split="config.en"'
  printf '<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="org.example.demo" %s android:versionCode="1"><uses-sdk android:minSdkVersion="37"/><application/></manifest>' "$split_attr" > "$TMP/AndroidManifest.xml"
  "$AAPT2" link --manifest "$TMP/AndroidManifest.xml" -I "$SDK/platforms/android-37.0/android.jar" -o "$TMP/$kind.apk"
done
javac -d "$TMP/classes" "$SRC/Json.java" "$SRC/JsonStream.java" "$SRC/FdroidCatalog.java" "$SRC/FdroidRepository.java" "$SRC/FdroidIndexClient.java" "$SRC/OperationTable.java" "$SRC/FdroidApkInstaller.java" "$SRC/FdroidSafety.java" "$SRC/FdroidMarkdown.java" "$SRC/ApkManifest.java" "$TEST/FdroidIndexClientTest.java"
mkdir -p "$TMP/payload2"
printf '{"packages":{},"changed":true}\n' > "$TMP/index-v2-changed.json"
CHANGED_HASH=$(sha256sum "$TMP/index-v2-changed.json" | cut -d' ' -f1)
printf '{"index":{"sha256":"%s"}}' "$CHANGED_HASH" > "$TMP/payload2/entry.json"
jar --create --file "$TMP/entry-changed.jar" -C "$TMP/payload2" .
jarsigner -keystore "$TMP/test.p12" -storepass changeit -keypass changeit "$TMP/entry-changed.jar" fixture >/dev/null
java -cp "$TMP/classes" org.matonos.store.fdroid.FdroidIndexClientTest "$TMP/entry.jar" "$TMP/cert.der" "$TMP/index-v2.json" "$TMP/entry-changed.jar" "$TMP/index-v2-changed.json"
python3 - "$TMP/large-index.json" <<'PY'
import json, sys
path=sys.argv[1]
prefix='{"packages":{"org.example.demo":{"metadata":{"name":{"en-US":"Stream Demo"},"summary":{"en-US":"Large fixture"}},"versions":{"v1":{"file":{"name":"demo.apk","sha256":"abc"},"manifest":{"versionCode":1,"versionName":"1","signer":{"sha256":["aa"]}}}},"unused":"'
suffix='"}}}'
target=100*1024*1024
with open(path,'w') as f:
    f.write(prefix)
    remaining=target-len(prefix)-len(suffix)
    chunk='x'*min(1024*1024,remaining)
    while remaining:
        n=min(len(chunk),remaining); f.write(chunk[:n]); remaining-=n
    f.write(suffix)
PY
/usr/bin/time -f 'large-index peak RSS: %M KiB' java -Xmx160m -cp "$TMP/classes" org.matonos.store.fdroid.FdroidIndexClientTest "$TMP/large-index.json"
