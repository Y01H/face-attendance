#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
if [ ! -f config/env.sh ]; then bash scripts/init.sh; exit 1; fi
source config/env.sh
if [ ! -f face-attendance.jar ]; then echo 'Missing face-attendance.jar. Build with mvn clean package and copy target/face-attendance.jar here.'; exit 1; fi
java_bin=java
if [ -n "${JAVA_HOME:-}" ]; then java_bin="$JAVA_HOME/bin/java"; fi
exec "$java_bin" -Xms256m -Xmx1024m -jar face-attendance.jar "$@"
