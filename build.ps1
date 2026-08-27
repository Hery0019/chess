# Compiles everything into out\ and packages an executable chess.jar.
# Usage: .\build.ps1        then        java -jar chess.jar
#                                       java -cp chess.jar app.ServerMain [port]   (online relay server)
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot

# `jar` is not always on PATH (Oracle's javapath shims only expose
# java/javac): fall back to JAVA_HOME, then to the running JDK's home.
if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME 'bin\jar.exe'))) {
    $jar = Join-Path $env:JAVA_HOME 'bin\jar.exe'
} elseif (Get-Command jar -ErrorAction SilentlyContinue) {
    $jar = 'jar'
} else {
    $props = cmd /c "java -XshowSettings:properties -version 2>&1"
    $jdkHome = ($props | Select-String 'java\.home = (.*)').Matches[0].Groups[1].Value.Trim()
    $jar = Join-Path $jdkHome 'bin\jar.exe'
}

if (Test-Path out) { Remove-Item -Recurse -Force out }
New-Item -ItemType Directory out | Out-Null
$sources = Get-ChildItem -Recurse -Filter *.java src | ForEach-Object { $_.FullName }
# -serial: Swing subclasses never get serialised; the warning is pure noise.
& javac --release 21 -Xlint:all,-serial -d out $sources
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

Set-Content -Path out\MANIFEST.MF -Value "Main-Class: app.Main" -Encoding ascii
& $jar --create --file chess.jar --manifest out\MANIFEST.MF -C out app -C out engine -C out game -C out net -C out ui
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
Write-Host "Built chess.jar  (run: java -jar chess.jar)"
