$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$compilerPath = (Get-Command javac.exe -ErrorAction Stop).Source
$javaPath = Join-Path (Split-Path -Parent $compilerPath) 'java.exe'
$testRoot = Join-Path $PSScriptRoot ('arena-test-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $testRoot | Out-Null
New-Item -ItemType File -Path (Join-Path $testRoot '.arena-test') | Out-Null
$classpath = (Join-Path $projectRoot 'bin') + ';' + (Join-Path $projectRoot 'lib\*')
& $compilerPath --release 17 -encoding UTF-8 -cp $classpath -d $testRoot (Join-Path $PSScriptRoot 'ArenaRulesIntegrationTest.java')
if ($LASTEXITCODE -ne 0) { throw 'Cannot compile arena integration test.' }
Push-Location $testRoot
try {
    & $javaPath --enable-native-access=ALL-UNNAMED -cp "$testRoot;$classpath" ArenaRulesIntegrationTest
    if ($LASTEXITCODE -ne 0) { throw 'Arena integration test failed.' }
} finally { Pop-Location }
