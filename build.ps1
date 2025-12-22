# Compiles everything into out\ and packages an executable chess.jar.
# Usage: .\build.ps1        then        java -jar chess.jar
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot

if (Test-Path out) { Remove-Item -Recurse -Force out }
New-Item -ItemType Directory out | Out-Null
$sources = Get-ChildItem -Recurse -Filter *.java src | ForEach-Object { $_.FullName }
# -serial: Swing subclasses never get serialised; the warning is pure noise.
& javac --release 21 -Xlint:all,-serial -d out $sources
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

Set-Content -Path out\MANIFEST.MF -Value "Main-Class: app.Main" -Encoding ascii
& jar --create --file chess.jar --manifest out\MANIFEST.MF -C out app -C out engine -C out game -C out ui
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
Write-Host "Built chess.jar  (run: java -jar chess.jar)"
