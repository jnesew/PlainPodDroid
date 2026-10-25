#!/usr/bin/env bash
# Compile/test current sources with the same verified graph as ordinary builds.
set -euo pipefail
project_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$project_root"
exec ./gradlew --no-daemon testDebugUnitTest compileDebugAndroidTestJavaWithJavac "$@"
