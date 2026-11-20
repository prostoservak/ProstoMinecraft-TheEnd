$ErrorActionPreference = 'Stop'
& (Join-Path $PSScriptRoot 'build.ps1')
$auth = 'F:/Files/Documents/ProstoMinecraft-Auth'
$jdk = Join-Path $auth '.tools/jdk/jdk-25.0.5+8/bin'
$api = Join-Path $auth 'libraries/dev/folia/folia-api/26.3.build.25-stable/folia-api-26.3.build.25-stable.jar'
$adventure = Join-Path $auth '.tools/velocity.jar'
$classes = Join-Path $PSScriptRoot 'build/classes'
$testClasses = Join-Path $PSScriptRoot 'build/test-classes'
New-Item -ItemType Directory -Force -Path $testClasses | Out-Null
& "$jdk/javac.exe" -encoding UTF-8 -cp "$classes;$api;$adventure" -d $testClasses (Join-Path $PSScriptRoot 'src/test/java/ru/prostominecraft/end/TestEndState.java')
if ($LASTEXITCODE -ne 0) { throw 'Test compilation failed.' }
& "$jdk/java.exe" -cp "$classes;$testClasses;$api;$adventure" ru.prostominecraft.end.TestEndState
if ($LASTEXITCODE -ne 0) { throw 'End state test failed.' }
