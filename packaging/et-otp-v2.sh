#!/bin/sh
# et-otp-v2 portable launcher (Linux). Run: sh et-otp-v2.sh
# Starts the bundled Java runtime; nothing is installed.
# To keep the settings next to this file (e.g. on a USB drive), add
#   -Detotp.config="$DIR/et-otp.properties"
# before -jar below.
DIR="$(cd "$(dirname "$0")" && pwd)"
exec "$DIR/runtime/bin/java" -jar "$DIR/et-otp-v2.jar" "$@"
