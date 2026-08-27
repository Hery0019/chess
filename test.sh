#!/usr/bin/env bash
# Runs every test runner against out/ (build first with ./build.sh).
# Headless-safe: the UI tests drive Swing components without a display and
# the network tests use a server on an ephemeral loopback port.
set -euo pipefail
cd "$(dirname "$0")"

JAVA_OPTS="-ea -Djava.awt.headless=true"
java $JAVA_OPTS -cp out test.PerftTest
java $JAVA_OPTS -cp out test.EngineTests
java $JAVA_OPTS -cp out test.UiTests
java $JAVA_OPTS -cp out test.NetTests
java $JAVA_OPTS -cp out test.UciTests
echo "All test runners passed."
