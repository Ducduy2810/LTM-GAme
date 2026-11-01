param([switch]$Preview,[switch]$StudyPreview)
if ($StudyPreview) { $Preview = $true }
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$compilerPath = (Get-Command javac.exe).Source
$javaPath = Join-Path (Split-Path -Parent $compilerPath) 'java.exe'
$testRoot = Join-Path $PSScriptRoot ('portal-test-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $testRoot | Out-Null
New-Item -ItemType File -Path (Join-Path $testRoot '.portal-test') | Out-Null
$classpath = (Join-Path $projectRoot 'bin') + ';' + (Join-Path $projectRoot 'lib\*')
$sources = (Get-ChildItem (Join-Path $projectRoot 'src') -Recurse -Filter *.java).FullName
& $compilerPath --release 17 -encoding UTF-8 -d $testRoot $sources (Join-Path $PSScriptRoot 'PortalIntegrationTest.java')
if ($LASTEXITCODE -ne 0) { throw 'Cannot compile portal test.' }
Write-Output "TEST_ROOT=$testRoot"
Push-Location $testRoot
try {
    $testArgs = @((Join-Path $projectRoot 'public'))
    if ($Preview) { $testArgs += 'preview' }
    if ($StudyPreview) { $testArgs += 'study' }
    & $javaPath --enable-native-access=ALL-UNNAMED -cp "$testRoot;$classpath" PortalIntegrationTest @testArgs
    if ($LASTEXITCODE -ne 0) { throw 'Portal integration test failed.' }
} finally { Pop-Location }
