$ErrorActionPreference = "Stop"

$SDK = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } elseif ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } else { "$env:LOCALAPPDATA\Android\Sdk" }
$BT = "$SDK\build-tools\36.0.0"
$AJ = "$SDK\platforms\android-36\android.jar"
$API_JAR = "libs\api-102.0.0.jar"

foreach ($dir in @("build\obj", "build\dex", "build\res", "build\apk")) {
    if (Test-Path $dir) {
        Remove-Item -Recurse -Force $dir
    }
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
}

Write-Host "[1/6] Compiling Android resources..."
& "$BT\aapt2.exe" compile --dir res -o build\res\resources.zip
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

Write-Host "[2/6] Compiling Java sources (release 17)..."
javac --release 17 -encoding UTF-8 -nowarn -classpath "$AJ;$API_JAR" -d build\obj `
    src\com\pwdime\hook\PwdImeSwitcher.java `
    src\com\pwdime\app\ConfigActivity.java
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

Write-Host "[3/6] Dexing classes with d8..."
$classes = Get-ChildItem -Recurse build\obj\*.class | ForEach-Object FullName
& "$BT\d8.bat" --release --lib $AJ --classpath $API_JAR --min-api 28 --output build\dex $classes
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

Write-Host "[4/6] Linking resources and AndroidManifest..."
& "$BT\aapt2.exe" link -o build\apk\unsigned.apk -I $AJ --manifest AndroidManifest.xml build\res\resources.zip
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

Write-Host "[5/6] Packaging classes.dex and META-INF/xposed/*..."
Copy-Item build\apk\unsigned.apk build\apk\unsigned_dex.apk -Force
jar uMf build\apk\unsigned_dex.apk `
    -C build\dex classes.dex `
    META-INF/xposed/module.prop `
    META-INF/xposed/java_init.list `
    META-INF/xposed/scope.list
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

Write-Host "[6/6] Aligning and signing APK..."
& "$BT\zipalign.exe" -f 4 build\apk\unsigned_dex.apk build\apk\aligned.apk
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

$ksPass = if ($env:KEYSTORE_PASS) { $env:KEYSTORE_PASS } else { "android" }
if ($env:KEYSTORE_PATH -and (Test-Path $env:KEYSTORE_PATH)) {
    $ksFile = $env:KEYSTORE_PATH
} elseif (Test-Path "keystore.pfx") {
    $ksFile = "keystore.pfx"
} else {
    Write-Host "No keystore provided; generating ephemeral debug keystore..."
    $ksFile = "build\debug.pfx"
    if (Test-Path $ksFile) { Remove-Item -Force $ksFile }
    $prevEap = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    & keytool -genkeypair -keystore $ksFile -storetype PKCS12 `
        -storepass android -keypass android -alias pwdime `
        -keyalg RSA -keysize 2048 -validity 10000 `
        -dname "CN=PwdImeSwitcher Debug, O=PwdImeSwitcher, C=US" *> $null
    $ErrorActionPreference = $prevEap
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    $ksPass = "android"
}

$signArgs = @("sign", "--ks", $ksFile, "--ks-pass", "pass:$ksPass")
if ($env:KEYSTORE_TYPE) { $signArgs += @("--ks-type", $env:KEYSTORE_TYPE) }
if ($env:KEY_ALIAS) { $signArgs += @("--ks-key-alias", $env:KEY_ALIAS) }
if ($env:KEY_PASS) { $signArgs += @("--key-pass", "pass:$env:KEY_PASS") }
$signArgs += @("--out", "build\apk\PwdImeSwitcher.apk", "build\apk\aligned.apk")

& "$BT\apksigner.bat" @signArgs
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

& "$BT\apksigner.bat" verify --verbose build\apk\PwdImeSwitcher.apk
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

Write-Host "Build succeeded: build\apk\PwdImeSwitcher.apk"
