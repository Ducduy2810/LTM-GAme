param([switch]$Preview)
$ErrorActionPreference='Stop'
$projectRoot=Split-Path -Parent $PSScriptRoot
$compilerPath=(Get-Command javac.exe).Source
$javaPath=Join-Path (Split-Path -Parent $compilerPath) 'java.exe'
$testRoot=Join-Path $PSScriptRoot ('admin-ranking-test-'+[Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $testRoot | Out-Null
New-Item -ItemType File -Path (Join-Path $testRoot '.admin-ranking-test') | Out-Null
$sources=(Get-ChildItem (Join-Path $projectRoot 'src') -Recurse -Filter *.java).FullName
& $compilerPath --release 17 -encoding UTF-8 -d $testRoot $sources (Join-Path $PSScriptRoot 'AdminRankingIntegrationTest.java')
if($LASTEXITCODE -ne 0){throw 'Cannot compile admin ranking tests'}
Write-Output "TEST_ROOT=$testRoot"
Push-Location $testRoot
try{$testArgs=@((Join-Path $projectRoot 'public'));if($Preview){$testArgs+='preview'};& $javaPath --enable-native-access=ALL-UNNAMED -cp "$testRoot;$(Join-Path $projectRoot 'lib\*')" AdminRankingIntegrationTest @testArgs;if($LASTEXITCODE -ne 0){throw 'Admin ranking integration tests failed'}}finally{Pop-Location}
