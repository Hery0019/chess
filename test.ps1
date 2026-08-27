# Runs every test runner against out\ (build first with .\build.ps1).
# Headless-safe: the UI tests drive Swing components without a display and
# the network tests use a server on an ephemeral loopback port.
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot

foreach ($runner in 'test.PerftTest', 'test.EngineTests', 'test.UiTests', 'test.NetTests', 'test.UciTests') {
    & java -ea "-Djava.awt.headless=true" -cp out $runner
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}
Write-Host "All test runners passed."
