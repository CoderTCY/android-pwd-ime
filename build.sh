#!/usr/bin/env bash
set -euo pipefail

resolve_sdk() {
    for candidate in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" "$HOME/Android/Sdk" "$HOME/android-sdk" "/usr/local/lib/android/sdk" "/opt/android-sdk"; do
        if [[ -n "$candidate" && -d "$candidate" ]]; then
            echo "$candidate"
            return 0
        fi
    done
    echo "Error: Android SDK not found (set ANDROID_HOME or ANDROID_SDK_ROOT)." >&2
    return 1
}

SDK="$(resolve_sdk)"

if [[ -d "$SDK/build-tools/36.0.0" ]]; then
    BT="$SDK/build-tools/36.0.0"
else
    BT="$(ls -d "$SDK/build-tools"/* 2>/dev/null | sort -V | tail -n 1 || true)"
fi
if [[ -z "${BT:-}" || ! -x "$BT/aapt2" ]]; then
    echo "Error: Android build-tools (aapt2/d8/zipalign/apksigner) not found under $SDK/build-tools." >&2
    exit 1
fi

if [[ -f "$SDK/platforms/android-36/android.jar" ]]; then
    AJ="$SDK/platforms/android-36/android.jar"
else
    AJ="$(ls -d "$SDK/platforms"/android-*/android.jar 2>/dev/null | sort -V | tail -n 1 || true)"
fi
if [[ -z "${AJ:-}" || ! -f "$AJ" ]]; then
    echo "Error: android.jar not found under $SDK/platforms." >&2
    exit 1
fi

API_JAR="libs/api-102.0.0.jar"

rm -rf build/obj build/dex build/res build/apk
mkdir -p build/obj build/dex build/res build/apk

CLEANUP_KS=""
cleanup() {
    if [[ -n "$CLEANUP_KS" && -f "$CLEANUP_KS" ]]; then
        rm -f "$CLEANUP_KS"
    fi
}
trap cleanup EXIT

echo "[1/6] Compiling Android resources..."
"$BT/aapt2" compile --dir res -o build/res/resources.zip

echo "[2/6] Compiling Java sources (release 17)..."
javac --release 17 -encoding UTF-8 -nowarn -classpath "$AJ:$API_JAR" -d build/obj \
    src/com/pwdime/hook/PwdImeSwitcher.java \
    src/com/pwdime/app/ConfigActivity.java

echo "[3/6] Dexing classes with d8..."
mapfile -t CLASSES < <(find build/obj -name '*.class')
"$BT/d8" --release --lib "$AJ" --classpath "$API_JAR" --min-api 28 --output build/dex "${CLASSES[@]}"

echo "[4/6] Linking resources and AndroidManifest..."
"$BT/aapt2" link -o build/apk/unsigned.apk -I "$AJ" --manifest AndroidManifest.xml build/res/resources.zip

echo "[5/6] Packaging classes.dex and META-INF/xposed/*..."
cp build/apk/unsigned.apk build/apk/unsigned_dex.apk
jar uMf build/apk/unsigned_dex.apk \
    -C build/dex classes.dex \
    META-INF/xposed/module.prop \
    META-INF/xposed/java_init.list \
    META-INF/xposed/scope.list

echo "[6/6] Aligning and signing APK..."
"$BT/zipalign" -f 4 build/apk/unsigned_dex.apk build/apk/aligned.apk

KS_PASS="${KEYSTORE_PASS:-android}"
if [[ -n "${KEYSTORE_B64:-}" ]]; then
    KS_FILE="build/signing.ks"
    CLEANUP_KS="$KS_FILE"
    printf '%s' "$KEYSTORE_B64" | (base64 -d 2>/dev/null || base64 --decode) > "$KS_FILE"
elif [[ -n "${KEYSTORE_PATH:-}" && -f "$KEYSTORE_PATH" ]]; then
    KS_FILE="$KEYSTORE_PATH"
elif [[ -f "keystore.pfx" ]]; then
    KS_FILE="keystore.pfx"
else
    echo "No keystore provided; generating ephemeral debug keystore..."
    KS_FILE="build/debug.pfx"
    rm -f "$KS_FILE"
    keytool -genkeypair -keystore "$KS_FILE" -storetype PKCS12 \
        -storepass android -keypass android -alias pwdime \
        -keyalg RSA -keysize 2048 -validity 10000 \
        -dname "CN=PwdImeSwitcher Debug, O=PwdImeSwitcher, C=US" >/dev/null 2>&1
    KS_PASS="android"
fi

SIGN_ARGS=(--ks "$KS_FILE" --ks-pass "pass:$KS_PASS")
if [[ -n "${KEYSTORE_TYPE:-}" ]]; then
    SIGN_ARGS+=(--ks-type "$KEYSTORE_TYPE")
fi
if [[ -n "${KEY_ALIAS:-}" ]]; then
    SIGN_ARGS+=(--ks-key-alias "$KEY_ALIAS")
fi
if [[ -n "${KEY_PASS:-}" ]]; then
    SIGN_ARGS+=(--key-pass "pass:$KEY_PASS")
fi

"$BT/apksigner" sign "${SIGN_ARGS[@]}" --out build/apk/PwdImeSwitcher.apk build/apk/aligned.apk
"$BT/apksigner" verify --verbose build/apk/PwdImeSwitcher.apk

APK_ENTRIES="$(jar tf build/apk/PwdImeSwitcher.apk)"
for required in classes.dex META-INF/xposed/module.prop META-INF/xposed/java_init.list META-INF/xposed/scope.list; do
    if ! grep -Fxq "$required" <<< "$APK_ENTRIES"; then
        echo "Error: missing expected APK entry: $required" >&2
        exit 1
    fi
done

echo "Build succeeded: build/apk/PwdImeSwitcher.apk"
