$ErrorActionPreference='Stop'
$projectRoot=Split-Path -Parent $PSScriptRoot
$compilerPath=(Get-Command javac.exe).Source
$javaPath=Join-Path (Split-Path -Parent $compilerPath) 'java.exe'
$testRoot=Join-Path $PSScriptRoot ('account-test-policy-'+[Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $testRoot | Out-Null
New-Item -ItemType File -Path (Join-Path $testRoot '.player-policy-test') | Out-Null
$sources=(Get-ChildItem (Join-Path $projectRoot 'src') -Recurse -Filter '*.java').FullName
& $compilerPath --release 17 -encoding UTF-8 -d $testRoot $sources (Join-Path $PSScriptRoot 'PlayerLifecycleIntegrationTest.java')
if($LASTEXITCODE -ne 0){throw 'Cannot compile lifecycle tests'}
Push-Location $testRoot
try {
    & $javaPath --enable-native-access=ALL-UNNAMED -cp "$testRoot;$(Join-Path $projectRoot 'lib\*')" PlayerLifecycleIntegrationTest (Join-Path $projectRoot 'public')
    if($LASTEXITCODE -ne 0){throw 'Lifecycle tests failed'}
}finally{Pop-Location}
