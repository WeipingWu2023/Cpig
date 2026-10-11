param([switch]$FrameworkDependent, [switch]$PackageOnly)
$ErrorActionPreference = 'Stop'
$workspace = Split-Path -Parent $PSScriptRoot
$localDotnet = Join-Path $workspace '.tools\dotnet\dotnet.exe'
$dotnetCommand = if (Test-Path -LiteralPath $localDotnet) { $localDotnet } else { 'dotnet' }
$project = Join-Path $PSScriptRoot 'Copig.Receiver\Copig.Receiver.csproj'
$output = Join-Path $PSScriptRoot 'publish'
$selfContained = if ($FrameworkDependent) { 'false' } else { 'true' }
if (-not $PackageOnly) {
    & $dotnetCommand publish $project -c Release -r win-x64 --self-contained $selfContained -o $output '-p:PublishSingleFile=true' '-p:IncludeNativeLibrariesForSelfExtract=true' '-p:DebugType=None' '-p:DebugSymbols=false'
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}
if (-not (Test-Path -LiteralPath (Join-Path $output 'Copig Receiver.exe'))) { throw 'Publish the receiver before packaging it.' }
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'README.md') -Destination $output -Force
$runtimeConfig = Join-Path $PSScriptRoot 'Copig.Receiver\bin\Release\net10.0-windows\win-x64\Copig Receiver.runtimeconfig.json'
$frameworks = (Get-Content -LiteralPath $runtimeConfig -Raw | ConvertFrom-Json).runtimeOptions.includedFrameworks
$cacheLine = & $dotnetCommand nuget locals global-packages --list
if ($LASTEXITCODE -ne 0) { throw 'Could not locate the NuGet cache for runtime notices.' }
$packageCache = ($cacheLine -replace '^[^:]+:\s*', '').Trim()
$sdkRoot = if (Test-Path -LiteralPath $localDotnet) {
    Split-Path -Parent $localDotnet
} else {
    Split-Path -Parent (Get-Command $dotnetCommand).Source
}
foreach ($framework in $frameworks) {
    $packageName = $framework.name.ToLowerInvariant() + '.runtime.win-x64'
    $packageFolder = Join-Path $packageCache (Join-Path $packageName $framework.version)
    $licenseFile = Get-ChildItem -LiteralPath $packageFolder -File -ErrorAction SilentlyContinue | Where-Object Name -like 'LICENSE*' | Select-Object -First 1
    $noticeFile = Get-ChildItem -LiteralPath $packageFolder -File -ErrorAction SilentlyContinue | Where-Object Name -like '*THIRD-PARTY*' | Select-Object -First 1
    $licenseSource = if ($licenseFile) { $licenseFile.FullName } else { Join-Path $sdkRoot 'LICENSE.txt' }
    $noticeSource = if ($noticeFile) { $noticeFile.FullName } else { Join-Path $sdkRoot 'ThirdPartyNotices.txt' }
    Copy-Item -LiteralPath $licenseSource -Destination (Join-Path $output ($framework.name + '-LICENSE.txt')) -Force
    Copy-Item -LiteralPath $noticeSource -Destination (Join-Path $output ($framework.name + '-THIRD-PARTY-NOTICES.txt')) -Force
}
Compress-Archive -Path (Join-Path $output '*') -DestinationPath (Join-Path $workspace 'Copig-Receiver-Windows-x64.zip') -Force
Write-Host 'Built Copig-Receiver-Windows-x64.zip. Extract it, then run Copig Receiver.exe.'
