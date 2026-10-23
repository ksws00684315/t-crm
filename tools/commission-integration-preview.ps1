param([int]$AdminPort = 18941, [int]$PortalPort = 18944, [string]$Backend = 'http://127.0.0.1:8890', [string]$PortalBackend = 'http://127.0.0.1:8891')
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$node = (Get-Command node.exe -ErrorAction Stop).Source
$logs = Join-Path $root '.cache/integrated-preview'
[IO.Directory]::CreateDirectory($logs) | Out-Null
foreach ($port in @($AdminPort,$PortalPort)) {
  if (Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue) {
    throw "Port $port is occupied; choose another port instead of stopping existing services."
  }
}
$savedDemo = $env:VITE_COMMISSION_DEMO
$savedAdmin = $env:VITE_ADMIN_API_TARGET
$savedPortal = $env:VITE_KOL_API_TARGET
$savedPreviewStore = $env:VITE_COMMISSION_PREVIEW_STORE_URL
$savedPortalOrigin = $env:VITE_COMMISSION_PREVIEW_PORTAL_ORIGIN
try {
  $env:VITE_COMMISSION_DEMO = 'true'
  $env:VITE_ADMIN_API_TARGET = $Backend
  $env:VITE_KOL_API_TARGET = $PortalBackend
  $env:VITE_COMMISSION_PREVIEW_STORE_URL = "http://127.0.0.1:$AdminPort/__commission_preview__/state"
  $env:VITE_COMMISSION_PREVIEW_PORTAL_ORIGIN = "http://127.0.0.1:$PortalPort"
  $processes = foreach ($entry in @(@{App='admin-ui';Port=$AdminPort},@{App='kol-portal';Port=$PortalPort})) {
    $app = Join-Path $root $entry.App
    $vite = Join-Path $app 'node_modules/vite/bin/vite.js'
    if (-not (Test-Path -LiteralPath $vite)) { throw "Install locked dependencies for $app first." }
    $arguments = '"{0}" --host 127.0.0.1 --mode commission-preview --port {1} --strictPort' -f $vite,$entry.Port
    $process = Start-Process -FilePath $node -ArgumentList $arguments -WorkingDirectory $app -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $logs "$($entry.App).stdout.log") -RedirectStandardError (Join-Path $logs "$($entry.App).stderr.log")
    [pscustomobject]@{app=$entry.App;pid=$process.Id;url="http://127.0.0.1:$($entry.Port)/";backend=$(if ($entry.App -eq 'admin-ui') { $Backend } else { $PortalBackend })}
  }
  $processes | ConvertTo-Json | Tee-Object -FilePath (Join-Path $logs 'processes.json')
} finally {
  $env:VITE_COMMISSION_DEMO = $savedDemo
  $env:VITE_ADMIN_API_TARGET = $savedAdmin
  $env:VITE_KOL_API_TARGET = $savedPortal
  $env:VITE_COMMISSION_PREVIEW_STORE_URL = $savedPreviewStore
  $env:VITE_COMMISSION_PREVIEW_PORTAL_ORIGIN = $savedPortalOrigin
}
