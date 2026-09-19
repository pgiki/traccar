#!/usr/bin/env python3
"""Build traccar-web brand assets from the official FikaChu mark.

Reads branding/assets/icon-mark.png (192x192, source of truth copied from
fikachu-admin/public/) and generates:
  branding/assets/logo.svg                      240x64 login/PWA logo (mark + wordmark)
  branding/assets/favicon.ico                   multi-size ICO (16/32/48/192)
  branding/assets/apple-touch-icon-180x180.png  180x180 PNG

Re-run after replacing icon-mark.png. Requires: pillow.
"""

import base64
import io
import sys
import json
from pathlib import Path

from PIL import Image

BRANDING = Path(__file__).resolve().parent
MARK = BRANDING / "assets" / "icon-mark.png"

LOGO_W, LOGO_H, MARK_SIZE = 240, 64, 64
branding_json = {}
with open(BRANDING / "branding.json") as f:
    branding_json = json.load(f)

WORDMARK = branding_json["appName"]
INK = branding_json.get("colorInk", "#1c1c1e")


def build_logo(mark_png: bytes) -> str:
    b64 = base64.b64encode(mark_png).decode("ascii")
    return f"""<svg xmlns="http://www.w3.org/2000/svg" width="{LOGO_W}" height="{LOGO_H}" viewBox="0 0 {LOGO_W} {LOGO_H}">
  <image href="data:image/png;base64,{b64}" x="0" y="0" width="{MARK_SIZE}" height="{MARK_SIZE}"/>
  <text x="76" y="41" font-family="Geist, ui-sans-serif, system-ui, sans-serif" font-size="24" font-weight="700" fill="{INK}" textLength="156" lengthAdjust="spacingAndGlyphs">{WORDMARK}</text>
</svg>
"""


def main() -> int:
    if not MARK.exists():
        print(f"missing {MARK}", file=sys.stderr)
        return 1
    mark_png = MARK.read_bytes()
    img = Image.open(io.BytesIO(mark_png)).convert("RGBA")

    (BRANDING / "assets" / "logo.svg").write_text(build_logo(mark_png) + "\n")

    img.save(
        BRANDING / "assets" / "favicon.ico",
        sizes=[(16, 16), (32, 32), (48, 48), (192, 192)],
    )

    icon = img.resize((180, 180), Image.Resampling.LANCZOS)
    icon.save(BRANDING / "assets" / "apple-touch-icon-180x180.png")

    print("wrote logo.svg, favicon.ico, apple-touch-icon-180x180.png")
    return 0


if __name__ == "__main__":
    sys.exit(main())
