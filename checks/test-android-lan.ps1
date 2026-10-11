param(
    [int]$Port = 53419,
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$AndroidSdk = $env:ANDROID_HOME,
    [string]$GradleUserHome = $env:GRADLE_USER_HOME
)
$ErrorActionPreference = 'Stop'
$copigWorkspace = Split-Path -Parent $PSScriptRoot
$copigLocalJdk = Join-Path $copigWorkspace '.tools\jdk'
if (!$JavaHome -and (Test-Path -LiteralPath $copigLocalJdk)) {
    $JavaHome = Get-ChildItem -LiteralPath $copigLocalJdk -Directory | Select-Object -First 1 -ExpandProperty FullName
}
if (!$JavaHome) { throw 'Set JAVA_HOME to JDK 17 or pass -JavaHome. See GETTING-STARTED.md.' }
$copigJdk = Join-Path $JavaHome 'bin'
if (!$AndroidSdk) {
    $copigLocalSdk = Join-Path $copigWorkspace '.tools\sdk'
    $AndroidSdk = if (Test-Path -LiteralPath $copigLocalSdk) { $copigLocalSdk } else { $env:ANDROID_SDK_ROOT }
}
if (!$AndroidSdk) { throw 'Set ANDROID_HOME or pass -AndroidSdk with your Android SDK location.' }
if (!$GradleUserHome) {
    $copigLocalGradle = Join-Path $copigWorkspace '.tools\gradle-home'
    $GradleUserHome = if (Test-Path -LiteralPath $copigLocalGradle) { $copigLocalGradle } else { Join-Path $env:USERPROFILE '.gradle' }
}
$copigDotnet = Join-Path $copigWorkspace '.tools\dotnet\dotnet.exe'
$copigReceiver = Join-Path $copigWorkspace 'windows\Copig.Receiver\bin\Release\net10.0-windows\Copig Receiver.dll'
$copigPublishedReceiver = Join-Path $copigWorkspace 'windows\publish\Copig Receiver.exe'
$copigCheckOutput = Join-Path $PSScriptRoot ('build\interop-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $copigCheckOutput -Force | Out-Null
$copigKotlinVersion = [regex]::Match((Get-Content -LiteralPath (Join-Path $copigWorkspace 'gradle\libs.versions.toml') -Raw), '(?m)^kotlin\s*=\s*"([^"]+)"').Groups[1].Value
$copigStdlib = Get-ChildItem -LiteralPath (Join-Path $GradleUserHome "caches\modules-2\files-2.1\org.jetbrains.kotlin\kotlin-stdlib\$copigKotlinVersion") -Filter '*.jar' -Recurse | Select-Object -First 1 -ExpandProperty FullName
$copigClasspath = @(
    (Join-Path $copigWorkspace 'data\build\intermediates\runtime_library_classes_jar\debug\bundleLibRuntimeToJarDebug\classes.jar'),
    (Join-Path $copigWorkspace 'domain\build\libs\domain.jar'),
    $copigStdlib,
    (Join-Path $AndroidSdk 'platforms\android-35\android.jar')
) -join ';'
if (!$copigStdlib) { throw 'Build Android with Gradle first so its compiled classes and Kotlin runtime are available.' }
& (Join-Path $copigJdk 'javac.exe') -cp $copigClasspath -d $copigCheckOutput (Join-Path $PSScriptRoot 'AndroidSenderInterop.java')
if ($LASTEXITCODE -ne 0) { throw 'Interop check did not compile.' }
$copigOutput = Join-Path $copigCheckOutput 'received'
$copigState = Join-Path $copigCheckOutput 'state'
$copigLog = Join-Path $copigCheckOutput 'receiver.log'
$copigErrorLog = Join-Path $copigCheckOutput 'receiver-error.log'
$copigArguments = @('--headless', '--port', $Port, '--output', ('"' + $copigOutput + '"'), '--state', ('"' + $copigState + '"'), '--pin', '194827')
if (Test-Path -LiteralPath $copigPublishedReceiver) { $copigCommand = $copigPublishedReceiver }
else { $copigCommand = $copigDotnet; $copigArguments = @(('"' + $copigReceiver + '"')) + $copigArguments }
$copigProcess = Start-Process -FilePath $copigCommand -ArgumentList $copigArguments -PassThru -WindowStyle Hidden -RedirectStandardOutput $copigLog -RedirectStandardError $copigErrorLog
try {
    $copigReady = $false
    for ($copigAttempt = 0; $copigAttempt -lt 40; $copigAttempt++) {
        if ($copigProcess.HasExited) { throw ('Receiver exited: ' + (Get-Content -LiteralPath $copigErrorLog -Raw)) }
        if ((Get-Content -LiteralPath $copigLog -Raw -ErrorAction SilentlyContinue) -match 'READY ') { $copigReady = $true; break }
        Start-Sleep -Milliseconds 250
    }
    if (!$copigReady) { throw 'Receiver startup timed out.' }
    & (Join-Path $copigJdk 'java.exe') -cp ($copigCheckOutput + ';' + $copigClasspath) AndroidSenderInterop $Port $copigOutput '194827'
    if ($LASTEXITCODE -ne 0) { throw 'Android/Windows interoperability check failed.' }
} finally {
    if (!$copigProcess.HasExited) { Stop-Process -Id $copigProcess.Id -Force }
}
