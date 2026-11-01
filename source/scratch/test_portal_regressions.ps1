$ErrorActionPreference='Stop'
$projectRoot=Split-Path -Parent $PSScriptRoot
$compilerPath=(Get-Command javac.exe).Source
$javaPath=Join-Path (Split-Path -Parent $compilerPath) 'java.exe'
$testRoot=Join-Path $PSScriptRoot ('portal-regressions-'+[Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $testRoot | Out-Null
$sources=(Get-ChildItem (Join-Path $projectRoot 'src') -Recurse -Filter *.java).FullName
$tests=@('ArenaRulesIntegrationTest','SurvivalRulesIntegrationTest','ConnectionNoticesIntegrationTest')
$testSources=$tests | ForEach-Object { Join-Path $PSScriptRoot ($_+'.java') }
& $compilerPath --release 17 -encoding UTF-8 -d $testRoot $sources $testSources
if($LASTEXITCODE -ne 0){throw 'Cannot compile regression tests'}
$markers=@('.arena-test','.survival-test','.presence-test')
for($index=0;$index -lt $tests.Length;$index++){
    $caseRoot=Join-Path $testRoot $tests[$index]
    New-Item -ItemType Directory -Path $caseRoot | Out-Null
    New-Item -ItemType File -Path (Join-Path $caseRoot $markers[$index]) | Out-Null
    Push-Location $caseRoot
    try{
        & $javaPath --enable-native-access=ALL-UNNAMED -cp "$testRoot;$(Join-Path $projectRoot 'lib\*')" $tests[$index] (Join-Path $projectRoot 'public')
        if($LASTEXITCODE -ne 0){throw ('Failed: '+$tests[$index])}
    }finally{Pop-Location}
}
foreach($test in @('test_registration_ui.mjs','test_arena_ui.mjs','test_survival_ui.mjs','test_wheel_presence_ui.mjs')){
    & node (Join-Path $PSScriptRoot $test)
    if($LASTEXITCODE -ne 0){throw ('Failed: '+$test)}
}
