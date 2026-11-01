param(
    [string]$AndroidSdk = $env:ANDROID_HOME,
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$DeviceSerial = ''
)
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path $PSScriptRoot -Parent
$originalDirectory = Get-Location
try {
    Set-Location -LiteralPath $repoRoot
    if (-not $AndroidSdk) { $AndroidSdk = Join-Path $env:LOCALAPPDATA 'Android/Sdk' }
    if (-not $JavaHome) { $JavaHome = Join-Path $env:ProgramFiles 'Android/Android Studio/jbr' }
    if (-not (Test-Path -LiteralPath (Join-Path $JavaHome 'bin/java.exe'))) { throw 'Pass -JavaHome pointing to a JDK.' }
    if (-not (Test-Path -LiteralPath (Join-Path $AndroidSdk 'platforms/android-36/android.jar'))) { throw 'Pass -AndroidSdk with platform android-36 installed.' }
    $env:JAVA_HOME = $JavaHome
    $escapedSdk = $AndroidSdk.Replace('\','/').Replace(':','\:')
    [System.IO.File]::WriteAllText((Join-Path $repoRoot 'local.properties'), "sdk.dir=$escapedSdk")
    $outputDirectory = Join-Path $repoRoot 'outputs'
    $verificationDirectory = Join-Path $outputDirectory 'verification'
    [System.IO.Directory]::CreateDirectory($verificationDirectory) | Out-Null
    & ./gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest --console=plain 2>&1 |
        Tee-Object -FilePath (Join-Path $verificationDirectory 'build.txt')
    if ($LASTEXITCODE -ne 0) { throw "Gradle verification failed: $LASTEXITCODE" }
    $apkPath = Join-Path $repoRoot 'app/build/outputs/apk/debug/app-debug.apk'
    $apksigner = Join-Path $AndroidSdk 'build-tools/36.0.0/apksigner.bat'
    & $apksigner verify --verbose --print-certs $apkPath |
        Tee-Object -FilePath (Join-Path $verificationDirectory 'signature.txt')
    if ($LASTEXITCODE -ne 0) { throw 'APK signature verification failed.' }
    if ($DeviceSerial) {
        $adbPath = Join-Path $AndroidSdk 'platform-tools/adb.exe'
        & $adbPath -s $DeviceSerial install -r $apkPath
        if ($LASTEXITCODE -ne 0) { throw 'Application installation failed.' }
        & $adbPath -s $DeviceSerial install -r (Join-Path $repoRoot 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk')
        if ($LASTEXITCODE -ne 0) { throw 'Test APK installation failed.' }
        $instrumentationOutput = & $adbPath -s $DeviceSerial shell am instrument -w com.rokid.stabledemo.test/androidx.test.runner.AndroidJUnitRunner
        $instrumentationOutput | Tee-Object -FilePath (Join-Path $verificationDirectory 'instrumentation.txt')
        if ($LASTEXITCODE -ne 0 -or ($instrumentationOutput -join "`n") -notmatch 'OK \(\d+ tests?\)') {
            throw 'Instrumentation did not report a passing test run.'
        }
    }
    $deliveredApk = Join-Path $outputDirectory 'rokid-3dof-demo-debug.apk'
    Copy-Item -LiteralPath $apkPath -Destination $deliveredApk -Force
    $digest = (Get-FileHash -LiteralPath $deliveredApk -Algorithm SHA256).Hash.ToLowerInvariant()
    [System.IO.File]::WriteAllText((Join-Path $outputDirectory 'SHA256SUMS.txt'), "$digest  rokid-3dof-demo-debug.apk`n")
    Write-Output "APK: $deliveredApk"
    Write-Output "SHA-256: $digest"
} finally { Set-Location -LiteralPath $originalDirectory }
