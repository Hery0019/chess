#!/usr/bin/env bash
# Compiles everything into out/ and packages an executable chess.jar.
# Usage: ./build.sh        then        java -jar chess.jar
#                                      java -cp chess.jar app.ServerMain [port]   (online relay server)
set -euo pipefail
cd "$(dirname "$0")"

# `jar` is not always on PATH (e.g. Oracle's Windows javapath shims only
# expose java/javac): fall back to JAVA_HOME, then to the running JDK's home.
if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/jar" ]; then
    JAR="$JAVA_HOME/bin/jar"
elif command -v jar >/dev/null 2>&1; then
    JAR=jar
else
    JDK_HOME=$(java -XshowSettings:properties -version 2>&1 | sed -n 's/^ *java\.home = //p' | tr -d '\r')
    JAR="$JDK_HOME/bin/jar"
fi

rm -rf out
mkdir -p out
# -serial: Swing subclasses never get serialised; the warning is pure noise.
javac --release 21 -Xlint:all,-serial -d out $(find src -name "*.java")

printf 'Main-Class: app.Main\n' > out/MANIFEST.MF
"$JAR" --create --file chess.jar --manifest out/MANIFEST.MF \
    -C out app -C out engine -C out game -C out net -C out ui
echo "Built chess.jar  (run: java -jar chess.jar)"
