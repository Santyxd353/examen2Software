[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$BaseUrl,
    [switch]$AllowHttp,
    [switch]$SkipWebSocket
)
$ErrorActionPreference = 'Stop'
$baseUri = [Uri]$BaseUrl
if (-not $baseUri.IsAbsoluteUri -or ($baseUri.Scheme -ne 'https' -and -not ($AllowHttp -and $baseUri.Scheme -eq 'http'))) {
    throw 'Use an absolute HTTPS URL (AllowHttp is only for local verification).'
}
$origin = $baseUri.GetLeftPart([UriPartial]::Authority)
$health = Invoke-RestMethod -Uri "$origin/api/health" -TimeoutSec 30
if ($health.status -ne 'UP' -or $health.application -ne 'ClassForge') { throw 'Unexpected health response.' }
foreach ($route in @('/', '/login', '/projects/27000000-0000-0000-0000-000000000100/assistant')) {
    $page = Invoke-WebRequest -Uri "$origin$route" -UseBasicParsing -TimeoutSec 30
    if ($page.StatusCode -ne 200 -or $page.Content -notmatch '<app-root') { throw "Angular route failed: $route" }
}
try {
    Invoke-WebRequest -Uri "$origin/api/projects" -UseBasicParsing -TimeoutSec 30 | Out-Null
    throw 'Private project API unexpectedly allowed anonymous access.'
} catch {
    if (-not $_.Exception.Response -or [int]$_.Exception.Response.StatusCode -ne 401) { throw }
}
if (-not $SkipWebSocket) {
    $socket = New-Object System.Net.WebSockets.ClientWebSocket
    $deadline = New-Object System.Threading.CancellationTokenSource
    $deadline.CancelAfter(20000)
    try {
        $socket.Options.SetRequestHeader('Origin', $origin)
        $socketScheme = if ($baseUri.Scheme -eq 'https') { 'wss' } else { 'ws' }
        $socketUri = [Uri]("${socketScheme}://" + $baseUri.Authority + '/ws')
        $socket.ConnectAsync($socketUri, $deadline.Token).GetAwaiter().GetResult() | Out-Null
        if ($socket.State -ne [System.Net.WebSockets.WebSocketState]::Open) { throw 'WebSocket handshake failed.' }
        $socket.CloseAsync([System.Net.WebSockets.WebSocketCloseStatus]::NormalClosure, 'smoke complete', $deadline.Token).GetAwaiter().GetResult() | Out-Null
    } finally { $socket.Dispose(); $deadline.Dispose() }
}
$checks = 'ClassForge health, Angular deep links and protected API'
if (-not $SkipWebSocket) { $checks += ', WebSocket handshake' }
Write-Host "[ok] $checks"
Write-Host 'Complete authenticated two-user collaboration and assistant checks using the runbook.'
