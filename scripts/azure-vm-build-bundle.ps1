[CmdletBinding()]
param(
    [switch]$UseExistingPackage,
    [switch]$SkipTests,
    [switch]$SkipInstall
)
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
if (-not $UseExistingPackage) {
    & (Join-Path $PSScriptRoot 'azure-build-package.ps1') -SkipTests:$SkipTests -SkipInstall:$SkipInstall
}
$artifactPath = Join-Path $repoRoot '.azure-deploy/app.jar'
$hashPath = "$artifactPath.sha256"
if (-not (Test-Path -LiteralPath $hashPath)) { throw 'Build the complete Azure JAR and checksum first.' }
$expectedHash = ((Get-Content -LiteralPath $hashPath -Raw).Trim() -split '\s+')[0]
if ((Get-FileHash -LiteralPath $artifactPath -Algorithm SHA256).Hash -ne $expectedHash) { throw 'JAR checksum mismatch.' }
Add-Type -AssemblyName System.IO.Compression.FileSystem
$jar = [IO.Compression.ZipFile]::OpenRead($artifactPath)
try {
    foreach ($required in @('BOOT-INF/classes/static/index.html','BOOT-INF/classes/application-azure-vm.yml')) {
        if (-not $jar.GetEntry($required)) { throw "Rebuild the Azure JAR: missing $required" }
    }
} finally { $jar.Dispose() }
$bundlePath = Join-Path $repoRoot '.azure-deploy/classforge-azure-vm.zip'
$bundleStream = [IO.File]::Open($bundlePath,[IO.FileMode]::Create,[IO.FileAccess]::Write)
$bundle = [IO.Compression.ZipArchive]::new($bundleStream,[IO.Compression.ZipArchiveMode]::Create)
try {
    [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($bundle,$artifactPath,'app.jar',[IO.Compression.CompressionLevel]::Optimal) | Out-Null
    [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($bundle,$hashPath,'app.jar.sha256',[IO.Compression.CompressionLevel]::Optimal) | Out-Null
    foreach ($name in @('install.sh','enable-https.sh','check-environment.sh','classforge.service',
            'nginx-bootstrap.conf','nginx-https.conf','classforge.env.example',
            'install-codex.sh','install-whisper.sh','whisper-local.py','classforge-whisper.service')) {
        $sourcePath = Join-Path $repoRoot "deploy/azure/vm/$name"
        [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($bundle,$sourcePath,$name,[IO.Compression.CompressionLevel]::Optimal) | Out-Null
    }
} finally { $bundle.Dispose(); $bundleStream.Dispose() }
$bundleHash = (Get-FileHash -LiteralPath $bundlePath -Algorithm SHA256).Hash.ToLowerInvariant()
[IO.File]::WriteAllText("$bundlePath.sha256", "$bundleHash  classforge-azure-vm.zip`n")
Write-Host "Azure VM bundle: $bundlePath"
Write-Host "SHA256: $bundleHash"
