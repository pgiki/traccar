# White-label branding for traccar-web

One config file drives two outputs:

1. **Build-time patches** — logos, hardcoded strings, default colors/map keys,
   applied to the local `traccar-web` checkout before `vite build`.
2. **Runtime branding** — title, colors, logo URLs, links, map keys pushed to a
   live Traccar server as Server attributes (what the admin UI exposes under
   Settings → Server, backed by `GET /api/server`).

The `traccar-web` submodule is never committed to. Patches live only in the
working tree; re-apply them after every `git submodule update`.

## Setup

```sh
cp branding/branding.example.json branding/branding.json
# edit branding.json with your name, colors, URLs, map keys
# drop logo.svg / favicon.ico / apple-touch-icon-180x180.png into branding/assets/
```

`branding/branding.json` is gitignored — real brand values stay out of git.

## Apply (before every frontend build)

```sh
node branding/apply-branding.mjs --check   # preview, changes nothing
node branding/apply-branding.mjs           # apply
cd traccar-web && npm run generate-pwa-assets  # rebuild PWA icons from logo.svg
cd ../ && node branding/apply-branding.mjs           # re-apply: restores brand favicon.ico / apple-touch-icon
cd traccar-web && npm run build
```

What gets patched (all anchor-based, idempotent):

| File                                                              | Change                                                                                       |
| ----------------------------------------------------------------- | -------------------------------------------------------------------------------------------- |
| `traccar-web/index.html`                                          | noscript brand text + link                                                                   |
| `traccar-web/public/logo.svg`, `src/resources/images/logo.svg`    | brand logo (PWA source + login logo)                                                         |
| `traccar-web/public/favicon.ico`, `public/apple-touch-icon-*.png` | brand icons (if provided)                                                                    |
| `src/login/ChangeServerPage.jsx`                                  | drops `demo*.traccar.org` / `server.traccar.org`, inserts your server URL                    |
| `src/settings/CalendarPage.jsx`                                   | iCal `PRODID` `Traccar` → your brand                                                         |
| `src/map/core/useMapStyles.js`                                    | LocationIQ / Ordnance Survey fallback keys → yours                                           |
| `src/map/core/MapView.jsx`                                        | default map styles → yours (only if configured)                                              |
| `src/common/theme/palette.js`                                     | primary/secondary fallbacks → your colors, auto-lightened in dark mode so outlined buttons (e.g. Replay SHOW) stay visible; optional `colorPrimaryDark` / `colorSecondaryDark` pin exact dark shades |
| `src/main/java/org/traccar/web/OverrideTextFilter.java`           | backend `${title}`/`${description}`/`${colorPrimary}` defaults → your brand (fresh installs) |

Revert everything to git HEAD with `node branding/apply-branding.mjs --revert`.

## Push branding to a live server

```sh
node branding/apply-branding.mjs --seed --server https://gps.example.com \
  --email admin --password secret
```

Merges the config into the existing Server attributes (`PUT /api/server`),
so nothing else is wiped. Covers `title`, `description`, colors, logo URLs,
support/terms/privacy URLs, announcement, map keys, and default map styles.

Note: previously exported calendars keep the old `PRODID`; only newly created
ones use the brand. Logo URLs must be publicly reachable — host the files
(e.g. under `traccar-web/public/`, served at `/`) or use absolute URLs.

## Verify

```sh
rg -i traccar traccar-web/build   # expect only map attribution/legal hits
npm run lint --prefix traccar-web
curl -s http://localhost:8082/ | grep -o '<title>[^<]*</title>'
```

Caveat: the submodule sets `ignore = all` in `.gitmodules`, so `git status`
in the parent repo will not show the patched working tree — this is expected.
