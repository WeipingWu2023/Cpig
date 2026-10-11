param([string]$ReceiverPath)
$ErrorActionPreference = 'Stop'
$workspace = Split-Path -Parent $PSScriptRoot
$testRoot = Join-Path $PSScriptRoot 'test-output'
$runRoot = Join-Path $testRoot ([Guid]::NewGuid().ToString('N'))
$files = Join-Path $runRoot 'files'
$state = Join-Path $runRoot 'state'
New-Item -ItemType Directory -Path $files,$state -Force | Out-Null
if (-not $ReceiverPath) { $ReceiverPath = Join-Path $PSScriptRoot 'publish\Copig Receiver.exe' }
if (-not (Test-Path -LiteralPath $ReceiverPath)) { throw 'Build the Windows receiver first with windows\build-windows.ps1.' }
$port = 53517
$pin = '194827'
$base = "https://127.0.0.1:$port/api/localsend/v2"
$utf8 = New-Object System.Text.UTF8Encoding($false)
$count = 0
function Check([bool]$condition, [string]$name) {
    if (-not $condition) { throw "FAIL: $name" }
    $script:count++
    Write-Host "PASS: $name"
}
function Request([string]$route, [string]$method = 'POST', [object]$body = $null, [string]$binary = '') {
    $response = Join-Path $runRoot 'response.json'
    $arguments = @('--insecure','--silent','--show-error','--max-time','20','--request',$method,'--write-out','%{http_code}','--output',$response)
    if ($binary) { $arguments += @('--data-binary', "@$binary") }
    elseif ($null -ne $body) {
        $jsonPath = Join-Path $runRoot 'request.json'
        [System.IO.File]::WriteAllText($jsonPath, ($body | ConvertTo-Json -Depth 12 -Compress), $utf8)
        $arguments += @('--header','Content-Type: application/json','--data-binary',"@$jsonPath")
    }
    $arguments += "$base/$route"
    $code = & curl.exe @arguments
    if ($LASTEXITCODE -ne 0) { throw "curl failed for $route" }
    $text = [System.IO.File]::ReadAllText($response)
    $value = if ($text.Trim()) { $text | ConvertFrom-Json } else { $null }
    return [pscustomobject]@{ Code = [int]$code; Json = $value }
}
function Metadata([string]$name, [long]$length, [string]$checksum) {
    return @{info = @{alias = 'Copig integration test'; version = '2.2'; deviceType = 'mobile'; fingerprint = 'sender-id'; protocol = 'https'; port = 53317; download = $false}; files = @{testfile = @{id = 'testfile'; fileName = $name; size = $length; fileType = 'video/mp4'; sha256 = $checksum}}}
}
function Prepare([object]$metadata) { return Request "prepare-upload?pin=$pin" 'POST' $metadata }
function Upload([object]$session, [string]$path, [string]$token = '') {
    if (-not $token) { $token = $session.Json.files.testfile }
    return Request "upload?sessionId=$($session.Json.sessionId)&fileId=testfile&token=$token" 'POST' $null $path
}
function Cancel([object]$session) { return Request "cancel?sessionId=$($session.Json.sessionId)" }

$process = Start-Process -FilePath $ReceiverPath -ArgumentList @('--headless','--port',"$port",'--pin',$pin,'--output',"`"$files`"",'--state',"`"$state`"") -PassThru -WindowStyle Hidden -RedirectStandardOutput (Join-Path $runRoot 'receiver.log') -RedirectStandardError (Join-Path $runRoot 'receiver-errors.log')
try {
    $ready = $false
    for ($attempt = 0; $attempt -lt 40; $attempt++) {
        if ($process.HasExited) { throw ('Receiver exited: ' + [System.IO.File]::ReadAllText((Join-Path $runRoot 'receiver-errors.log'))) }
        $socket = New-Object System.Net.Sockets.TcpClient
        try { $socket.Connect('127.0.0.1', $port); $ready = $true } catch { } finally { $socket.Dispose() }
        if ($ready) { break }
        Start-Sleep -Milliseconds 200
    }
    Check $ready 'Headless receiver starts'
    $info = Request 'info' 'GET'
    Check ($info.Code -eq 200 -and $info.Json.protocol -eq 'https' -and $info.Json.port -eq $port -and $info.Json.version -eq '2.2') 'Protocol information'
    $connection = New-Object System.Net.Sockets.TcpClient('127.0.0.1', $port)
    $ssl = New-Object System.Net.Security.SslStream($connection.GetStream(), $false, [System.Net.Security.RemoteCertificateValidationCallback]{ param($sender,$certificate,$chain,$errors) return $true })
    try {
        $ssl.AuthenticateAsClient('Copig Receiver')
        $sha = [System.Security.Cryptography.SHA256]::Create()
        $observed = ([BitConverter]::ToString($sha.ComputeHash($ssl.RemoteCertificate.GetRawCertData()))).Replace('-','').ToLowerInvariant()
        $sha.Dispose()
        Check ($info.Json.fingerprint -eq $observed) 'Advertised fingerprint matches TLS certificate'
    } finally { $ssl.Dispose(); $connection.Dispose() }
    $register = Request 'register' 'POST' @{alias = 'Test phone';version='2.2'}
    Check ($register.Code -eq 200 -and $register.Json.fingerprint -eq $observed) 'Register response matches server identity'

    $client = New-Object System.Net.Sockets.UdpClient
    $client.Client.ReceiveTimeout = 3000
    $packet = $utf8.GetBytes('{"alias":"test","version":"2.2","fingerprint":"test-phone","announce":true}')
    [void]$client.Send($packet, $packet.Length, '127.0.0.1', 53317)
    $endpoint = New-Object System.Net.IPEndPoint([System.Net.IPAddress]::Any, 0)
    try {
        $discovered = $utf8.GetString($client.Receive([ref]$endpoint)) | ConvertFrom-Json
        Check ($discovered.fingerprint -eq $observed -and $discovered.announce -eq $false) 'UDP discovery responds to announcement'
    } finally { $client.Dispose() }

    $clip = Join-Path $runRoot 'clip.mp4'
    $bytes = New-Object byte[] 196619
    $random = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    $random.GetBytes($bytes); $random.Dispose()
    [System.IO.File]::WriteAllBytes($clip, $bytes)
    $hash = (Get-FileHash -LiteralPath $clip -Algorithm SHA256).Hash.ToLowerInvariant()
    $metadata = Metadata 'Copig-test.mp4' $bytes.Length $hash
    Check ((Request 'prepare-upload?pin=000000' 'POST' $metadata).Code -eq 401) 'Wrong PIN rejected'
    foreach ($name in @('../escaped.mp4','CON.mp4','clip:stream.mp4','bad\path.mp4','trail.')) {
        Check ((Prepare (Metadata $name $bytes.Length $hash)).Code -eq 400) "Unsafe name rejected: $name"
    }
    Check ((Prepare @{info=@{alias='Test'};files=@{testfile=$null}}).Code -eq 400) 'Null file metadata rejected'
    Check ((Prepare (Metadata 'huge.mp4' (8L*1024*1024*1024+1) $hash)).Code -eq 400) 'Files above 8 GiB rejected'

    $session = Prepare $metadata
    Check ($session.Code -eq 200 -and $session.Json.sessionId -and $session.Json.files.testfile) 'PIN authorizes transfer and random tokens'
    Check ((Prepare $metadata).Code -eq 409) 'A second session cannot take over'
    Check ((Upload $session $clip 'incorrect-token').Code -eq 403) 'Invalid upload token rejected'
    Check ((Request 'cancel?sessionId=unknown').Code -eq 403) 'Invalid cancellation rejected'
    Check ((Upload $session $clip).Code -eq 200) 'File upload acknowledged'
    $receivedPath = Join-Path $files 'Copig-test.mp4'
    Check ((Get-FileHash -LiteralPath $receivedPath -Algorithm SHA256).Hash.ToLowerInvariant() -eq $hash) 'Received file matches exact SHA-256'
    Check ((Upload $session $clip).Code -eq 403) 'Completed session cannot be replayed'

    $duplicate = Prepare $metadata
    Check ((Upload $duplicate $clip).Code -eq 200 -and (Test-Path -LiteralPath (Join-Path $files 'Copig-test (1).mp4'))) 'Existing file kept and duplicate renamed'
    $mismatch = Prepare (Metadata 'bad-checksum.mp4' $bytes.Length ('0'*64))
    Check ((Upload $mismatch $clip).Code -eq 422) 'Checksum mismatch rejected'
    [void](Cancel $mismatch)
    Check (-not (Test-Path -LiteralPath (Join-Path $files 'bad-checksum.mp4'))) 'Bad checksum never commits a file'
    $wrongLength = Prepare (Metadata 'bad-length.mp4' ($bytes.Length+1) $hash)
    Check ((Upload $wrongLength $clip).Code -eq 422) 'Length mismatch rejected'
    Check ((Cancel $wrongLength).Code -eq 200) 'Sender cancellation acknowledged'
    Check ((Upload $wrongLength $clip).Code -eq 403) 'Cancelled session cannot upload'
    $retry = Prepare $metadata
    Check ($retry.Code -eq 200) 'New session works after cancellation'
    [void](Cancel $retry)
    Check ((Get-ChildItem -LiteralPath $files -Filter '.copig-*.part').Count -eq 0) 'No unfinished temporary files remain'
    for ($attempt = 0; $attempt -lt 5; $attempt++) { [void](Request 'prepare-upload?pin=000000' 'POST' $metadata) }
    Check ((Request 'prepare-upload?pin=000000' 'POST' $metadata).Code -eq 429) 'PIN guessing is rate limited'
    Write-Host "$count checks passed. Test files: $files"
} finally {
    if (-not $process.HasExited) { Stop-Process -Id $process.Id -Force }
    $process.Dispose()
}
