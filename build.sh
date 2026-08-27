#!/usr/bin/env bash
# Compiles everything into out/ and packages an executable chess.jar.
# Usage: ./build.sh        then        java -jar chess.jar
set -euo pipefail
cd "$(dirname "$0")"

rm -rf out
mkdir -p out
# -serial: Swing subclasses never get serialised; the warning is pure noise.
javac --release 21 -Xlint:all,-serial -d out $(find src -name "*.java")

printf 'Main-Class: app.Main\n' > out/MANIFEST.MF
jar --create --file chess.jar --manifest out/MANIFEST.MF \
    -C out app -C out engine -C out game -C out ui
echo "Built chess.jar  (run: java -jar chess.jar)"
