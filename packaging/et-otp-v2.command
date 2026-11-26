#!/bin/sh
# et-otp-v2 portable launcher (macOS). Double-click in Finder, or run: sh et-otp-v2.command
# Starts the bundled Java runtime; nothing is installed.
# To keep the settings next to this file (e.g. on a USB drive), add
#   -Detotp.config="$DIR/et-otp.properties"
# before "$@" on the last line.
DIR="$(cd "$(dirname "$0")" && pwd)"
# et-otp-v2.aot holds the classes as loaded and linked for the command line use, so it starts faster. Java uses it
# only if it fits this runtime and jar; else Java starts as usual. The -X options keep the standard output for the
# code: messages of Java go to the standard error output, and none is written about the cache.
if [ -f "$DIR/et-otp-v2.aot" ]; then
    set -- "-XX:AOTCache=$DIR/et-otp-v2.aot" -jar "$DIR/et-otp-v2.jar" "$@"
else
    set -- -jar "$DIR/et-otp-v2.jar" "$@"
fi
exec "$DIR/runtime/bin/java" -Dapple.awt.application.name=et-OTP-v2 -XX:+DisplayVMOutputToStderr -Xlog:disable \
     '-Xlog:all=warning,aot*=off,cds*=off:stderr' "$@"
