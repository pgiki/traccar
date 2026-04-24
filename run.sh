#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

CONFIG="traccar.xml"
SKIP_JAVA=false
SKIP_FRONTEND=false

# ── Parse arguments ───────────────────────────────────────────────────────────
while [[ $# -gt 0 ]]; do
  case $1 in
    --skip-java)     SKIP_JAVA=true ;;
    --skip-frontend) SKIP_FRONTEND=true ;;
    --config)        CONFIG="$2"; shift ;;
    -h|--help)
      echo "Usage: $0 [--skip-java] [--skip-frontend] [--config <file>]"
      echo ""
      echo "  --skip-java       Skip Gradle build (use existing target/tracker-server.jar)"
      echo "  --skip-frontend   Skip npm build (use existing traccar-web/build/)"
      echo "  --config <file>   Config file to use (default: traccar.xml)"
      exit 0
      ;;
    *) echo "Unknown option: $1  (run with --help for usage)"; exit 1 ;;
  esac
  shift
done

# ── Prerequisite checks ───────────────────────────────────────────────────────
echo "==> Checking prerequisites..."
command -v java >/dev/null  || { echo "ERROR: 'java' not found — please install a JDK (17+)"; exit 1; }
command -v javac >/dev/null || {
  echo "ERROR: 'javac' not found — you have a JRE but Gradle needs a full JDK (compiler)."
  echo "       On Ubuntu: sudo apt install openjdk-17-jdk-headless"
  echo "       Then:       sudo update-alternatives --config java   (pick the same JDK)"
  exit 1
}
command -v node >/dev/null  || { echo "ERROR: 'node' not found — please install Node.js (18+)"; exit 1; }
command -v npm  >/dev/null  || { echo "ERROR: 'npm'  not found — please install Node.js (18+)"; exit 1; }
[[ -f "gradlew" ]]          || { echo "ERROR: 'gradlew' not found — run this script from the traccar project root"; exit 1; }
[[ -f "$CONFIG" ]]          || { echo "ERROR: config file '$CONFIG' not found"; exit 1; }

java_version=$(java -version 2>&1 | head -1)
echo "    java  : $java_version"
echo "    node  : $(node --version)"
echo "    npm   : $(npm --version)"
echo "    config: $CONFIG"

# ── Step 1: Build Java server ─────────────────────────────────────────────────
if [[ "$SKIP_JAVA" == false ]]; then
  echo ""
  echo "==> Building Java server (gradlew jar copyDependencies)..."
  ./gradlew jar copyDependencies --quiet
  echo "    Built: target/tracker-server.jar"
else
  echo ""
  echo "==> Skipping Java build (--skip-java)"
fi

[[ -f "target/tracker-server.jar" ]] || {
  echo "ERROR: target/tracker-server.jar not found — run without --skip-java to build it first"
  exit 1
}

# ── Step 2: Build frontend ────────────────────────────────────────────────────
if [[ "$SKIP_FRONTEND" == false ]]; then
  echo ""
  echo "==> Building frontend (npm install + npm run build)..."
  (cd traccar-web && npm install --silent && npm run build --silent)
  echo "    Built: traccar-web/build/"
else
  echo ""
  echo "==> Skipping frontend build (--skip-frontend)"
fi

[[ -f "traccar-web/build/index.html" ]] || {
  echo "ERROR: traccar-web/build/index.html not found — run without --skip-frontend to build it first"
  exit 1
}

# ── Step 3: Start server ──────────────────────────────────────────────────────
echo ""
echo "==> Starting Traccar server"
echo "    config     : $CONFIG"
echo "    web UI     : http://localhost:8082"
echo "    OmniEbike  : TCP port 5264"
echo "    Press Ctrl+C to stop"
echo ""

exec java -jar target/tracker-server.jar "$CONFIG"
