#!/usr/bin/env bash
# 一键打包 APK（改完 ../fitness/index.html 后跑这个）
#
# 依赖：JDK 8+、Android SDK 的 build-tools（aapt2 / zipalign / apksigner）与 platform 的 android.jar。
# 不需要 Gradle，也不需要 Android Studio。
#
# 目录约定：android-build/sdk/bt/android-14/ 与 android-build/sdk/pf/android-34/android.jar
# 可用环境变量覆盖：ANDROID_HOME / BUILD_TOOLS / PLATFORM / KEYSTORE_PASS / VERSION_NAME
set -e
cd "$(dirname "$0")"

SDK_DIR="${ANDROID_HOME:-sdk}"
BT="$SDK_DIR/bt/${BUILD_TOOLS:-android-14}"
AJ="$SDK_DIR/pf/${PLATFORM:-android-34}/android.jar"
KS="build/fitlog.jks"
KS_PASS="${KEYSTORE_PASS:-fitlog2026}"
VER="${VERSION_NAME:-1.0.1}"
OUT="../TrainingLog-v${VER}.apk"

if [ ! -f "$AJ" ]; then
  echo "找不到 android.jar：$AJ"
  echo "请把 Android SDK 放到 ./sdk（或用 ANDROID_HOME 指定），目录结构需为："
  echo "  sdk/bt/android-14/          (build-tools)"
  echo "  sdk/pf/android-34/android.jar"
  exit 1
fi

# 0. 首次运行自动生成签名密钥。每个开发者应使用自己的密钥，不要把 .jks 提交到仓库。
if [ ! -f "$KS" ]; then
  echo "==> 生成签名密钥 $KS"
  mkdir -p build
  keytool -genkeypair -v -keystore "$KS" -alias fitlog \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass "$KS_PASS" -keypass "$KS_PASS" \
    -dname "CN=Training Log, O=Personal, C=CN"
fi

# 1. 同步网页资源到 assets
mkdir -p app/assets
cp ../fitness/index.html ../fitness/icon-192.png ../fitness/icon-512.png \
   ../fitness/manifest.webmanifest ../fitness/sw.js app/assets/

# 2. 资源编译 + 链接（生成 R.java 和未签名包）
rm -rf build/res.zip build/gen build/classes build/dex build/app-unsigned.apk build/app-aligned.apk
mkdir -p build/gen build/classes build/dex
"$BT/aapt2.exe" compile --dir app/res -o build/res.zip
"$BT/aapt2.exe" link -o build/app-unsigned.apk -I "$AJ" \
  --manifest app/AndroidManifest.xml -R build/res.zip --java build/gen \
  --auto-add-overlay -A app/assets --min-sdk-version 24 --target-sdk-version 34

# 3. javac（必须带 -parameters，否则 d8 解析 MethodParameters 会空指针）
javac -nowarn -parameters -source 1.8 -target 1.8 -classpath "$AJ" \
  -d build/classes build/gen/com/fitlog/training/R.java \
  app/java/com/fitlog/training/MainActivity.java

# 4. dex
java -cp "$BT/lib/d8.jar" com.android.tools.r8.D8 --lib "$AJ" --min-api 24 \
  --output build/dex $(find build/classes -name "*.class" | tr '\n' ' ')

# 5. 塞进 apk 并对齐
( cd build/dex && jar uf ../app-unsigned.apk classes.dex )
"$BT/zipalign.exe" -f -p 4 build/app-unsigned.apk build/app-aligned.apk

# 6. 签名（密钥固定，升级安装才能覆盖旧版）
"$BT/apksigner.bat" sign --ks "$KS" --ks-pass "pass:$KS_PASS" \
  --key-pass "pass:$KS_PASS" --v1-signing-enabled true --v2-signing-enabled true \
  --out "$OUT" build/app-aligned.apk

"$BT/apksigner.bat" verify --print-certs "$OUT" | head -4
ls -la "$OUT"
