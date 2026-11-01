param([string]$OutputDirectory)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
if (-not $OutputDirectory) { $OutputDirectory = Join-Path $PSScriptRoot ('network-results-' + (Get-Date -Format 'yyyyMMdd-HHmmss')) }
$OutputDirectory = [System.IO.Path]::GetFullPath($OutputDirectory)
New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
$compilerPath = (Get-Command javac.exe -ErrorAction Stop).Source
$javaPath = Join-Path (Split-Path -Parent $compilerPath) 'java.exe'
$testRoot = Join-Path $PSScriptRoot ('network-benchmark-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $testRoot | Out-Null
New-Item -ItemType File -Path (Join-Path $testRoot '.network-benchmark') | Out-Null
$sources = (Get-ChildItem (Join-Path $projectRoot 'src') -Recurse -Filter '*.java').FullName
& $compilerPath --release 17 -encoding UTF-8 -d $testRoot $sources (Join-Path $PSScriptRoot 'NetworkBenchmark.java')
if ($LASTEXITCODE -ne 0) { throw 'Cannot compile benchmark' }
Write-Output "ISOLATED_DATABASE=$testRoot\data\game.db"
Push-Location $testRoot
try {
    & $javaPath --enable-native-access=ALL-UNNAMED -cp "$testRoot;$(Join-Path $projectRoot 'lib\*')" NetworkBenchmark (Join-Path $projectRoot 'public') $OutputDirectory
    if ($LASTEXITCODE -ne 0) { throw 'Network benchmark failed' }
} finally { Pop-Location }
Write-Output "RESULTS=$OutputDirectory"
