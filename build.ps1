$ErrorActionPreference = 'Stop'
$auth = 'F:/Files/Documents/ProstoMinecraft-Auth'
$jdk = Join-Path $auth '.tools/jdk/jdk-25.0.5+8/bin'
$api = Join-Path $auth 'libraries/dev/folia/folia-api/26.3.build.25-stable/folia-api-26.3.build.25-stable.jar'
$adventure = Join-Path $auth '.tools/velocity.jar'
$joml = Join-Path $auth 'libraries/org/joml/joml/1.10.9/joml-1.10.9.jar'
$classes = Join-Path $PSScriptRoot 'build/classes'
$jar = Join-Path $PSScriptRoot 'build/ProstoMinecraft-End.jar'
if (!(Test-Path "$jdk/javac.exe") -or !(Test-Path $api) -or !(Test-Path $adventure) -or !(Test-Path $joml)) { throw 'JDK 25 or Folia API dependencies not found.' }
New-Item -ItemType Directory -Force -Path $classes | Out-Null
$sources = @(Get-ChildItem (Join-Path $PSScriptRoot 'src/main/java') -Recurse -Filter '*.java' | ForEach-Object FullName)
& "$jdk/javac.exe" -encoding UTF-8 -proc:none -cp "$api;$adventure;$joml" -d $classes $sources
if ($LASTEXITCODE -ne 0) { throw 'Compilation failed.' }
& "$jdk/jar.exe" --create --file $jar -C $classes . -C (Join-Path $PSScriptRoot 'src/main/resources') .
if ($LASTEXITCODE -ne 0) { throw 'Packaging failed.' }
Write-Output "Created $jar"
