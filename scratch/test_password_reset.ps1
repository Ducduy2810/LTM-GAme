$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$compilerPath = (Get-Command javac.exe -ErrorAction Stop).Source
$jdkBin = Split-Path -Parent $compilerPath
$javaPath = Join-Path $jdkBin 'java.exe'
$keytoolPath = Join-Path $jdkBin 'keytool.exe'
$testRoot = Join-Path $PSScriptRoot ('password-reset-test-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $testRoot | Out-Null
New-Item -ItemType File -Path (Join-Path $testRoot '.password-reset-test') | Out-Null
$keystorePath = Join-Path $testRoot 'smtp-test.p12'

# Certificate and dummy password are only for this local, isolated SMTP server.
& $keytoolPath -genkeypair -alias smtp-test -keyalg RSA -keysize 2048 -storetype PKCS12 -keystore $keystorePath -storepass local-test-only -keypass local-test-only -dname 'CN=localhost' -ext 'SAN=dns:localhost' -validity 2 -noprompt
if ($LASTEXITCODE -ne 0) { throw 'Cannot create local test certificate.' }
$classpath = (Join-Path $projectRoot 'bin') + ';' + (Join-Path $projectRoot 'lib\*')
$sources=(Get-ChildItem (Join-Path $projectRoot 'src') -Recurse -Filter *.java).FullName
& $compilerPath --release 17 -encoding UTF-8 -d $testRoot $sources (Join-Path $PSScriptRoot 'PasswordResetIntegrationTest.java')
if ($LASTEXITCODE -ne 0) { throw 'Cannot compile integration test.' }

Push-Location $testRoot
try {
    & $javaPath --enable-native-access=ALL-UNNAMED "-Djavax.net.ssl.trustStore=$keystorePath" '-Djavax.net.ssl.trustStorePassword=local-test-only' -cp "$testRoot;$classpath" PasswordResetIntegrationTest $keystorePath (Join-Path $projectRoot 'public')
    if ($LASTEXITCODE -ne 0) { throw 'Password reset integration test failed.' }
} finally { Pop-Location }
