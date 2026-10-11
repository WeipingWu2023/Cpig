param([string[]]$Tasks = @(':app:assembleDebug', ':app:testDebugUnitTest', ':data:testDebugUnitTest', ':domain:test', ':app:lintDebug', ':data:lintDebug'))
$ErrorActionPreference = 'Stop'
$copyPreviousEnvironment = @{}
foreach ($copyEnvironmentKey in @('JAVA_HOME', 'ANDROID_HOME', 'GRADLE_USER_HOME', 'ANDROID_USER_HOME')) {
    $copyPreviousEnvironment[$copyEnvironmentKey] = [Environment]::GetEnvironmentVariable($copyEnvironmentKey, 'Process')
}
Push-Location $PSScriptRoot
try {
    $copyJdk = Get-ChildItem '.tools/jdk' -Directory | Select-Object -First 1
    if (!$copyJdk -or !(Test-Path '.tools/gradle/gradle-8.11.1/bin/gradle.bat') -or !(Test-Path '.tools/sdk/platforms/android-35/android.jar')) {
        throw 'Local toolchain is missing. Open the project in Android Studio or populate .tools first; see README.md.'
    }
    $env:JAVA_HOME = $copyJdk.FullName
    $env:ANDROID_HOME = Join-Path $PSScriptRoot '.tools/sdk'
    $env:GRADLE_USER_HOME = Join-Path $PSScriptRoot '.tools/gradle-home'
    $env:ANDROID_USER_HOME = Join-Path $PSScriptRoot '.tools/android-user'
    & '.tools/gradle/gradle-8.11.1/bin/gradle.bat' --no-daemon --console=plain @Tasks
    if ($LASTEXITCODE -ne 0) { throw 'Android build failed' }
} finally {
    foreach ($copyEnvironmentKey in $copyPreviousEnvironment.Keys) {
        [Environment]::SetEnvironmentVariable($copyEnvironmentKey, $copyPreviousEnvironment[$copyEnvironmentKey], 'Process')
    }
    Pop-Location
}
