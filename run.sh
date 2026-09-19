#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

CONFIG="traccar.xml"
SKIP_JAVA=false
SKIP_FRONTEND=false
BRANDING=false
BRANDING_SERVER=""
BRANDING_EMAIL=""
BRANDING_PASSWORD=""
SKIP_BRANDING_SEED=false

# ── Parse arguments ───────────────────────────────────────────────────────────
while [[ $# -gt 0 ]]; do
  case $1 in
    --skip-java)          SKIP_JAVA=true ;;
    --skip-frontend)      SKIP_FRONTEND=true ;;
    --branding)           BRANDING=true ;;
    --branding-server)    BRANDING_SERVER="$2"; shift ;;
    --branding-email)     BRANDING_EMAIL="$2"; shift ;;
    --branding-password)  BRANDING_PASSWORD="$2"; shift ;;
    --skip-branding-seed) SKIP_BRANDING_SEED=true ;;
    --config)             CONFIG="$2"; shift ;;
    -h|--help)
      echo "Usage: $0 [--skip-java] [--skip-frontend] [--branding] [--config <file>]"
      echo ""
      echo "  --skip-java           Skip Gradle build (use existing target/tracker-server.jar)"
      echo "  --skip-frontend       Skip npm build (use existing traccar-web/build/)"
      echo "  --branding            Run branding deploy (apply patches, build, seed server)"
      echo "  --branding-server     Server URL for branding seed (required with --branding)"
      echo "  --branding-email      Admin email for branding seed (required with --branding)"
      echo "  --branding-password   Admin password for branding seed (required with --branding)"
      echo "  --skip-branding-seed  Skip runtime branding push to the live server"
      echo "  --config <file>       Config file to use (default: traccar.xml)"
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

if [[ "$BRANDING" == true ]]; then
  [[ -f "branding/deploy.sh" ]]  || { echo "ERROR: branding/deploy.sh not found"; exit 1; }
  [[ -z "$BRANDING_SERVER" || -z "$BRANDING_EMAIL" || -z "$BRANDING_PASSWORD" ]] && {
    echo "ERROR: --branding requires --branding-server, --branding-email, and --branding-password";
    exit 1;
  }
fi

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

# ── Step 2.5: Branding deploy ──────────────────────────────────────────────
if [[ "$BRANDING" == true ]]; then
  echo ""
  echo "==> Deploying branding..."
  branding_args=("--server" "$BRANDING_SERVER" "--email" "$BRANDING_EMAIL" "--password" "$BRANDING_PASSWORD")
  if [[ "$SKIP_BRANDING_SEED" == true ]]; then
    branding_args+=("--skip-seed")
  fi
  bash branding/deploy.sh "${branding_args[@]}"
  echo "    Branding deployed."
fi

# ── Step 3: Start server ──────────────────────────────────────────────────────
echo ""
echo "==> Starting Traccar server"
echo "    config     : $CONFIG"
echo "    web UI     : http://localhost:8082"
echo "    OmniEbike  : TCP port 5268"
echo "    Press Ctrl+C to stop"
echo ""

exec java -jar target/tracker-server.jar "$CONFIG"
