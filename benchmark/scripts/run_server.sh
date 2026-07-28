#!/usr/bin/env bash
# Starts the benchmark server with JFR enabled (and async-profiler when present).
#
# Usage:
#   ./run_server.sh              # plain run with JFR
#   PROFILER=/path/libasyncProfiler.so ./run_server.sh   # attach async-profiler at startup
#
# JFR output: benchmark/server/bench.jfr (analyze with JDK Mission Control or `jfr print`)
# async-profiler: profile with `asprof -d 60 -f flame.html <pid>` while the benchmark runs,
# or use the PROFILER env var to start with the agent loaded.
set -euo pipefail

DIR="$(cd "$(dirname "$0")/.." && pwd)"
SERVER_DIR="$DIR/server"
cd "$SERVER_DIR"

JVM_FLAGS=(
  -Xms4G -Xmx4G
  -XX:+UseG1GC
  -XX:+ParallelRefProcEnabled
  -XX:MaxGCPauseMillis=50
  # JFR: continuous recording, dumped on exit; low overhead (<2%).
  -XX:StartFlightRecording=filename=bench.jfr,settings=profile,maxsize=512m,dumponexit=true
  -XX:FlightRecorderOptions=stackdepth=256
  # Make GC behavior observable in logs too.
  -Xlog:gc*:file=gc.log:time,uptime:filecount=3,filesize=16m
)

if [[ -n "${PROFILER:-}" ]]; then
  JVM_FLAGS+=("-agentpath:$PROFILER=start,event=cpu,file=profile.html")
fi

exec java "${JVM_FLAGS[@]}" -jar paper.jar --nogui
