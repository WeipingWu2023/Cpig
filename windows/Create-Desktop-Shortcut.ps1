$ErrorActionPreference = 'Stop'
$receiverPath = Join-Path $PSScriptRoot 'Copig Receiver.exe'
if (-not (Test-Path -LiteralPath $receiverPath -PathType Leaf)) {
    throw 'Extract the Windows ZIP first, then run this script beside Copig Receiver.exe.'
}
$desktopPath = [Environment]::GetFolderPath('DesktopDirectory')
$shell = New-Object -ComObject WScript.Shell
$shortcut = $shell.CreateShortcut((Join-Path $desktopPath 'Copig Receiver.lnk'))
$shortcut.TargetPath = $receiverPath
$shortcut.WorkingDirectory = $PSScriptRoot
$shortcut.IconLocation = "$receiverPath,0"
$shortcut.Description = 'Copig: receive clips from your phone over Wi-Fi'
$shortcut.Save()
Write-Host 'Created Copig Receiver on your desktop.'
