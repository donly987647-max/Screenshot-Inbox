#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")/.."
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
kotlinc app/src/main/java/app/captureinbox/Core.kt tools/ParserCheck.kt -include-runtime -d "$OUT/parser-check.jar"
java -jar "$OUT/parser-check.jar"
