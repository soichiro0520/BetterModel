#!/usr/bin/env bash
# Sets up a Paper benchmark server under benchmark/server.
#
# Usage: ./setup_server.sh [minecraft-version]
# Requires: curl, jq (optional; falls back to python3), java 21+.
set -euo pipefail

MC_VERSION="${1:-1.21.8}"
DIR="$(cd "$(dirname "$0")/.." && pwd)"
SERVER_DIR="$DIR/server"
mkdir -p "$SERVER_DIR/plugins"

echo "== resolving latest Paper build for $MC_VERSION"
BUILDS_JSON="$(curl -fsSL "https://api.papermc.io/v2/projects/paper/versions/$MC_VERSION/builds")"
if command -v jq >/dev/null; then
  BUILD="$(echo "$BUILDS_JSON" | jq '[.builds[] | select(.channel=="default")] | last | .build')"
  JAR="$(echo "$BUILDS_JSON" | jq -r '[.builds[] | select(.channel=="default")] | last | .downloads.application.name')"
else
  BUILD="$(echo "$BUILDS_JSON" | python3 -c 'import json,sys; b=[x for x in json.load(sys.stdin)["builds"] if x["channel"]=="default"][-1]; print(b["build"])')"
  JAR="$(echo "$BUILDS_JSON" | python3 -c 'import json,sys; b=[x for x in json.load(sys.stdin)["builds"] if x["channel"]=="default"][-1]; print(b["downloads"]["application"]["name"])')"
fi

echo "== downloading Paper build $BUILD"
curl -fsSL -o "$SERVER_DIR/paper.jar" \
  "https://api.papermc.io/v2/projects/paper/versions/$MC_VERSION/builds/$BUILD/downloads/$JAR"

echo "eula=true" > "$SERVER_DIR/eula.txt"

cat > "$SERVER_DIR/server.properties" <<'EOF'
gamemode=creative
online-mode=false
spawn-protection=0
view-distance=16
simulation-distance=10
motd=BetterModel benchmark
EOF

echo "== building BetterModel + benchmark plugin"
(cd "$DIR/.." && ./gradlew :platform:paper:build :benchmark-plugin:build)

cp "$DIR/../build/libs/"bettermodel-*paper*.jar "$SERVER_DIR/plugins/" 2>/dev/null \
  || cp "$DIR/../build/libs/"*.jar "$SERVER_DIR/plugins/"
cp "$DIR/../benchmark-plugin/build/libs/BetterModel-Benchmark"*.jar "$SERVER_DIR/plugins/"

echo "== installing benchmark models"
mkdir -p "$SERVER_DIR/plugins/BetterModel/models"
cp "$DIR/models/"*.bbmodel "$SERVER_DIR/plugins/BetterModel/models/" 2>/dev/null || {
  echo "   (no models yet - run generate_model.py first)"
}

echo "done. start with: ./run_server.sh"
