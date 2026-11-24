#!/bin/sh
set -eu
if [ -z "$PLUGIN_API_CLASSPATH" ]; then
  echo "Set PLUGIN_API_CLASSPATH to Paper API libraries and compile-only plugin APIs." >&2
  exit 2
fi
rm -rf build/classes
mkdir -p build/classes
find src/main/java -name '*.java' -print0 | xargs -0 javac --release 21 -encoding UTF-8 -cp "$PLUGIN_API_CLASSPATH" -d build/classes
cp src/main/resources/plugin.yml build/classes/plugin.yml
jar --create --file build/plugin.jar -C build/classes .
