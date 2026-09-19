#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR/.."

SERVER=""
EMAIL=""
PASSWORD=""
SKIP_SEED=false

while [[ $# -gt 0 ]]; do
  case $1 in
    --server)     SERVER="$2"; shift ;;
    --email)      EMAIL="$2"; shift ;;
    --password)   PASSWORD="$2"; shift ;;
    --skip-seed)  SKIP_SEED=true ;;
    -h|--help)
      echo "Usage: $0 --server <url> --email <admin> --password <pw> [--skip-seed]"
      echo ""
      echo "  Deploy branding: apply patches, build frontend, seed live server."
      echo "  --skip-seed    Skip runtime branding push to the live server."
      exit 0
      ;;
    *) echo "Unknown option: $1  (run with --help for usage)"; exit 1 ;;
  esac
  shift
done

if [[ "$SKIP_SEED" == false && ( -z "$SERVER" || -z "$EMAIL" || -z "$PASSWORD" ) ]]; then
  echo "ERROR: --server, --email, and --password are required (or use --skip-seed)."
  echo "Run with --help for usage."
  exit 1
fi

echo "==> Deploying branding..."
echo "    Server: $SERVER"
echo "    Email:  $EMAIL"

echo ""
echo "==> Step 1: Apply build-time branding patches"
node branding/apply-branding.mjs

echo ""
echo "==> Step 2: Install frontend dependencies + rebuild PWA assets from logo.svg"
cd traccar-web && npm install --silent && npm run generate-pwa-assets --silent
cd ..

echo ""
echo "==> Step 3: Re-apply branding (restore favicon / apple-touch-icon)"
node branding/apply-branding.mjs

echo ""
echo "==> Step 4: Build frontend"
cd traccar-web && npm run build --silent
cd ..

echo ""
echo "==> Step 5: Build complete"
echo "    Built: traccar-web/build/"

if [[ "$SKIP_SEED" == false ]]; then
  echo ""
  echo "==> Step 5: Push runtime branding to live server"
  node branding/apply-branding.mjs --seed --server "$SERVER" --email "$EMAIL" --password "$PASSWORD"
else
  echo ""
  echo "==> Step 5: Skipping server seed (--skip-seed)"
fi

echo ""
echo "==> Branding deployment complete!"
