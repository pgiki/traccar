#!/usr/bin/env node
/**
 * White-label helper for traccar-web (Traccar frontend).
 *
 * One config file (branding/branding.json) drives two outputs:
 *   1. Build-time patches applied to the local traccar-web checkout
 *      (logos, hardcoded strings, default colors/keys) BEFORE `vite build`.
 *   2. Runtime branding pushed to a live Traccar server via --seed
 *      (title, colors, logo URLs, links, map keys as Server attributes).
 *
 * The traccar-web submodule is never committed to: patches live only in the
 * working tree and are re-applied after each `git submodule update`.
 *
 * Usage:
 *   node tools/apply-branding.mjs [--config branding/branding.json] [--check]
 *   node tools/apply-branding.mjs --revert
 *   node tools/apply-branding.mjs --seed --server https://gps.example.com \
 *     --email admin --password secret [--config branding/branding.json]
 *
 * Requires: node >= 18, no npm dependencies.
 */
import { execFileSync } from "node:child_process";
import {
  copyFileSync,
  existsSync,
  mkdirSync,
  readFileSync,
  writeFileSync,
} from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const WEB = join(ROOT, "traccar-web");
const BRANDING_DIR = join(ROOT, "branding");

/* Stock Traccar values we replace. Kept here so --revert-free re-runs and
 * --check can detect already-branded files. */
const STOCK = {
  title: "Traccar",
  description: "Traccar GPS Tracking System",
  colorPrimary: "#1a237e",
  locationIqKey: "pk.0f147952a41c555a5b70614039fd148b",
  ordnanceSurveyKey: "EAZ8p83u72FTGiLjLC2MsTAl1ko6XQHC",
  activeMapStyles: "locationIqStreets,locationIqDark,openFreeMap",
  defaultMapStyle: "locationIqStreets",
  traccarOrgHosts: [
    "demo.traccar.org",
    "demo2.traccar.org",
    "demo3.traccar.org",
    "demo4.traccar.org",
    "server.traccar.org",
  ],
};

const HEX = /^#([0-9a-fA-F]{3}|[0-9a-fA-F]{6})$/;

function parseArgs(argv) {
  const args = {
    config: join(BRANDING_DIR, "branding.json"),
    check: false,
    revert: false,
    seed: false,
    server: "",
    email: "",
    password: "",
  };
  for (let i = 2; i < argv.length; i++) {
    const a = argv[i];
    if (a === "--check") args.check = true;
    else if (a === "--revert") args.revert = true;
    else if (a === "--seed") args.seed = true;
    else if (a === "--config") args.config = resolve(ROOT, argv[++i]);
    else if (a === "--server") args.server = argv[++i].replace(/\/+$/, "");
    else if (a === "--email") args.email = argv[++i];
    else if (a === "--password") args.password = argv[++i];
    else if (a === "--help" || a === "-h") {
      printHelp();
      process.exit(0);
    } else fail(`Unknown argument: ${a}`);
  }
  return args;
}

function printHelp() {
  console.log(`Usage:
  node tools/apply-branding.mjs [--config <path>] [--check]   Apply (or preview) build-time branding
  node tools/apply-branding.mjs --revert                      Restore patched files to git HEAD
  node tools/apply-branding.mjs --seed --server <url> --email <admin> --password <pw>
                                                              Push runtime branding to a live server`);
}

function fail(message) {
  console.error(`error: ${message}`);
  process.exit(1);
}

function warn(message) {
  console.error(`warning: ${message}`);
}

function loadConfig(path) {
  if (!existsSync(path)) {
    fail(
      `Config not found: ${path}\n  Copy branding/branding.example.json to branding/branding.json and fill in your values.`,
    );
  }
  const raw = JSON.parse(readFileSync(path, "utf8"));
  for (const key of Object.keys(raw)) {
    if (key.startsWith("$")) delete raw[key];
  }
  if (raw.assets)
    for (const key of Object.keys(raw.assets)) {
      if (key.startsWith("$")) delete raw.assets[key];
    }
  if (raw.links)
    for (const key of Object.keys(raw.links)) {
      if (key.startsWith("$")) delete raw.links[key];
    }
  if (raw.maps)
    for (const key of Object.keys(raw.maps)) {
      if (key.startsWith("$")) delete raw.maps[key];
    }
  return raw;
}

function checkUrl(value, field) {
  if (!value) return;
  try {
    const url = new URL(value);
    if (url.protocol !== "https:" && url.protocol !== "http:") {
      fail(`${field} must be an http(s) URL, got: ${value}`);
    }
  } catch {
    fail(`${field} must be a valid URL, got: ${value}`);
  }
}

function validate(config) {
  if (!config.appName || !config.appName.trim()) fail("appName is required.");
  if (!config.appDescription || !config.appDescription.trim())
    fail("appDescription is required.");
  for (const [field, value] of [
    ["appName", config.appName],
    ["appDescription", config.appDescription],
  ]) {
    if (/["\\]/.test(value))
      fail(`${field} must not contain quotes or backslashes, got: ${value}`);
  }
  if (!HEX.test(config.colorPrimary || ""))
    fail(`colorPrimary must be a hex color, got: ${config.colorPrimary}`);
  if (!HEX.test(config.colorSecondary || ""))
    fail(`colorSecondary must be a hex color, got: ${config.colorSecondary}`);
  for (const field of ["colorPrimaryDark", "colorSecondaryDark"]) {
    const value = config[field];
    if (value !== undefined && value !== "" && !HEX.test(value)) {
      fail(`${field} must be a hex color or empty, got: ${value}`);
    }
  }
  checkUrl(config.server?.ownServerUrl, "server.ownServerUrl");
  for (const [key, value] of Object.entries(config.links || {})) {
    if (key === "announcement") continue;
    checkUrl(value, `links.${key}`);
  }
  const maps = config.maps || {};
  if (maps.defaultStyle && maps.defaultActiveStyles) {
    const active = maps.defaultActiveStyles.split(",").map((s) => s.trim());
    if (!active.includes(maps.defaultStyle)) {
      warn(
        `maps.defaultStyle "${maps.defaultStyle}" is not in maps.defaultActiveStyles; the app will fall back to the OSM style.`,
      );
    }
  }
  if (
    !maps.locationIqKey &&
    (!maps.defaultActiveStyles ||
      maps.defaultActiveStyles.includes("locationIq"))
  ) {
    warn(
      'No maps.locationIqKey configured while LocationIQ styles stay enabled; Traccar\'s built-in demo key will be replaced with an empty key. Set maps.defaultActiveStyles to "osm,openFreeMap" or provide your own key.',
    );
  }
}

/** iCal PRODID tokens allow letters/digits only; derive from the app name. */
function prodIdToken(appName) {
  const token = (appName || "").replace(/[^A-Za-z0-9]/g, "");
  return token || "Brand";
}

const results = [];
function record(file, action, detail) {
  results.push({ file, action, detail });
  console.log(
    `${action === "CHANGE" ? "  [change]" : action === "OK " ? "  [ok]    " : "  [skip]  "} ${file} — ${detail}`,
  );
}

/* Each patch: { file (abs path), name, apply(content, ctx) -> new content or null if no-op } */
function buildPatches(config, brandingDir) {
  const token = prodIdToken(config.appName);
  const ownUrl = config.server?.ownServerUrl || "";
  const maps = config.maps || {};
  const patches = [];

  patches.push({
    file: join(WEB, "index.html"),
    name: "noscript brand text/link",
    apply: (c) => {
      // Anchor on the noscript element so re-branding with new values works.
      const match = c.match(
        /<noscript>Enable JavaScript to use <a href="([^"]*)">[^<]*<\/a>\.<\/noscript>/,
      );
      if (!match) return null; // unexpected content; leave untouched
      const href = ownUrl || match[1];
      const rebuilt = c.replace(
        match[0],
        `<noscript>Enable JavaScript to use <a href="${href}">${config.appDescription}</a>.</noscript>`,
      );
      return rebuilt === c ? null : rebuilt;
    },
  });

  const logoSvg = config.assets?.logoSvg
    ? join(brandingDir, config.assets.logoSvg)
    : "";
  for (const target of [
    join(WEB, "public", "logo.svg"),
    join(WEB, "src", "resources", "images", "logo.svg"),
  ]) {
    patches.push({
      file: target,
      name: "brand logo.svg",
      asset: logoSvg,
      apply: (c, ctx) => {
        if (!ctx.haveAsset) return null;
        const incoming = ctx.assetContent;
        return incoming === c ? null : incoming;
      },
    });
  }

  for (const [key, target] of [
    ["favicon", join(WEB, "public", "favicon.ico")],
    ["appleTouchIcon", join(WEB, "public", "apple-touch-icon-180x180.png")],
  ]) {
    const src = config.assets?.[key]
      ? join(brandingDir, config.assets[key])
      : "";
    patches.push({
      file: target,
      name: `brand ${key}`,
      asset: src,
      binary: true,
      apply: () => "BINARY_COPY", // handled by the asset-copy path
    });
  }

  patches.push({
    file: join(WEB, "src", "login", "ChangeServerPage.jsx"),
    name: "remove Traccar demo servers",
    apply: (c) => {
      const lines = c.split("\n");
      const hasOwn = ownUrl && c.includes(`'${ownUrl}'`);
      const hasStock = STOCK.traccarOrgHosts.some((h) => c.includes(h));
      const hasForeign = lines.some((line) => {
        const m = line.match(/^\s*'(https?:\/\/[^']*)',?\s*$/);
        return (
          m &&
          !/localhost|127\.0\.0\.1/.test(m[1]) &&
          m[1] !== "currentServer" &&
          (!ownUrl || m[1] !== ownUrl)
        );
      });
      if (!hasStock && !hasForeign && (!ownUrl || hasOwn)) return null;
      const next = [];
      for (const line of lines) {
        const m = line.match(/^\s*'(https?:\/\/[^']*)',?\s*$/);
        // Drop stock demo hosts and any previously inserted custom URL; keep localhost entries.
        if (m && !/localhost|127\.0\.0\.1/.test(m[1])) continue;
        next.push(line);
        if (ownUrl && !hasOwn && line.trim() === "currentServer,") {
          next.push(`  '${ownUrl}',`);
        }
      }
      const out = next.join("\n");
      return out === c ? null : out;
    },
  });

  patches.push({
    file: join(WEB, "src", "settings", "CalendarPage.jsx"),
    name: "iCal PRODID brand",
    apply: (c) => {
      let next = c;
      next = next.replace(
        /-\/\/[A-Za-z0-9]*\/\/NONSGML [A-Za-z0-9]*\/\/EN/,
        `-//${token}//NONSGML ${token}//EN`,
      );
      next = next.replace(
        /indexOf\('\/\/[A-Za-z0-9]*\/\/'\)/,
        `indexOf('//${token}//')`,
      );
      return next === c ? null : next;
    },
  });

  patches.push({
    file: join(WEB, "src", "map", "core", "useMapStyles.js"),
    name: "default map keys",
    apply: (c) => {
      let next = c;
      if (maps.locationIqKey !== undefined) {
        if (/'/.test(maps.locationIqKey))
          fail("maps.locationIqKey must not contain quotes.");
        next = next.replace(
          /(useAttributePreference\('locationIqKey'\) \|\| ')[^']*(')/,
          (_, open, close) => `${open}${maps.locationIqKey}${close}`,
        );
      }
      if (maps.ordnanceSurveyKey !== undefined) {
        if (/'/.test(maps.ordnanceSurveyKey))
          fail("maps.ordnanceSurveyKey must not contain quotes.");
        next = next.replace(
          /(useAttributePreference\('ordnanceSurveyKey'\) \|\| ')[^']*(')/,
          (_, open, close) => `${open}${maps.ordnanceSurveyKey}${close}`,
        );
      }
      return next === c ? null : next;
    },
  });

  if (maps.defaultActiveStyles) {
    patches.push({
      file: join(WEB, "src", "map", "core", "MapView.jsx"),
      name: "default map styles",
      apply: (c) => {
        let next = c;
        next = next.replace(
          /('activeMapStyles',\s*)'[^']*'/,
          (_, open) => `${open}'${maps.defaultActiveStyles}'`,
        );
        if (maps.defaultStyle) {
          next = next.replace(
            /(usePreference\('map', ')[^']*('\))/,
            (_, open, close) => `${open}${maps.defaultStyle}${close}`,
          );
        }
        return next === c ? null : next;
      },
    });
  }

  patches.push({
    file: join(WEB, "src", "common", "theme", "palette.js"),
    name: "default palette fallbacks (dark-mode aware)",
    apply: (c) => {
      // Anchor: only touch the known palette module. Unknown layouts are left alone.
      if (!c.includes("validatedColor")) return null;
      // A single dark hex for both modes is invisible on dark backgrounds
      // (e.g. outlined secondary SHOW button on grey[900]). Keep the brand
      // color for light mode, but lighten the resolved base in dark mode.
      // Optional colorPrimaryDark / colorSecondaryDark override the auto
      // lightened value when the brand needs an exact dark-mode shade.
      const primaryDark = config.colorPrimaryDark || "";
      const secondaryDark = config.colorSecondaryDark || "";
      const primaryDarkExpr = primaryDark
        ? `'${primaryDark}'`
        : "lighten(primaryBase, 0.3)";
      const secondaryDarkExpr = secondaryDark
        ? `'${secondaryDark}'`
        : "lighten(secondaryBase, 0.6)";
      const next = `import { grey } from '@mui/material/colors';
import { lighten } from '@mui/material/styles';

const validatedColor = (color) => (/^#([0-9A-Fa-f]{3}){1,2}$/.test(color) ? color : null);

export default (server, darkMode) => {
  const primaryBase = validatedColor(server?.attributes?.colorPrimary) || '${config.colorPrimary}';
  const secondaryBase = validatedColor(server?.attributes?.colorSecondary) || '${config.colorSecondary}';
  return {
    mode: darkMode ? 'dark' : 'light',
    background: {
      default: darkMode ? grey[900] : grey[50],
    },
    primary: {
      main: darkMode ? ${primaryDarkExpr} : primaryBase,
    },
    secondary: {
      main: darkMode ? ${secondaryDarkExpr} : secondaryBase,
    },
    neutral: {
      main: grey[500],
    },
    geometry: {
      main: '#3bb2d0',
    },
    alwaysDark: {
      main: grey[900],
    },
  };
};
`;
      return next === c ? null : next;
    },
  });

  patches.push({
    file: join(
      ROOT,
      "src",
      "main",
      "java",
      "org",
      "traccar",
      "web",
      "OverrideTextFilter.java",
    ),
    name: "backend ${title}/${description}/${colorPrimary} defaults (fresh installs)",
    apply: (c) => {
      // Anchor: only touch the known filter. Unknown layouts are left alone.
      if (!c.includes('server.getString("title"')) return null;
      let next = c;
      next = next.replace(
        /(server\.getString\("title", ")[^"]*("\))/,
        (_, open, close) => `${open}${config.appName}${close}`,
      );
      next = next.replace(
        /(server\.getString\("description", ")[^"]*("\))/,
        (_, open, close) => `${open}${config.appDescription}${close}`,
      );
      next = next.replace(
        /(server\.getString\("colorPrimary", ")[^"]*("\))/,
        (_, open, close) => `${open}${config.colorPrimary}${close}`,
      );
      return next === c ? null : next;
    },
  });

  return patches;
}

function runPatches(config, brandingDir, dryRun) {
  const patches = buildPatches(config, brandingDir);
  let changes = 0;
  for (const patch of patches) {
    const rel = patch.file.replace(`${ROOT}/`, "");
    if (patch.binary) {
      if (!patch.asset) {
        record(rel, "SKIP", `${patch.name}: not configured`);
        continue;
      }
      if (!existsSync(patch.asset)) {
        record(
          rel,
          "SKIP",
          `${patch.name}: asset not found (${patch.asset.replace(`${ROOT}/`, "")})`,
        );
        continue;
      }
      // Idempotent: skip copy when bytes already match.
      let identical = false;
      try {
        if (existsSync(patch.file)) {
          identical = readFileSync(patch.asset).equals(
            readFileSync(patch.file),
          );
        }
      } catch {
        identical = false;
      }
      if (identical) {
        record(rel, "OK ", `${patch.name}: already branded`);
        continue;
      }
      if (dryRun) {
        record(rel, "CHANGE", `${patch.name}: would copy asset`);
        changes++;
        continue;
      }
      mkdirSync(dirname(patch.file), { recursive: true });
      copyFileSync(patch.asset, patch.file);
      record(rel, "CHANGE", `${patch.name}: copied asset`);
      changes++;
      continue;
    }
    if (!existsSync(patch.file)) {
      record(rel, "SKIP", "file not found (upstream layout changed?)");
      continue;
    }
    let assetContent = null;
    let haveAsset = false;
    if (patch.asset) {
      if (!existsSync(patch.asset)) {
        record(
          rel,
          "SKIP",
          `${patch.name}: asset not found (${patch.asset.replace(`${ROOT}/`, "")})`,
        );
        continue;
      }
      assetContent = readFileSync(patch.asset, "utf8");
      haveAsset = true;
    }
    const current = readFileSync(patch.file, "utf8");
    const next = patch.apply(current, { haveAsset, assetContent });
    if (next === null || next === undefined) {
      record(rel, "OK ", `${patch.name}: already branded`);
      continue;
    }
    changes++;
    if (dryRun) {
      record(rel, "CHANGE", `${patch.name}: would update`);
    } else {
      writeFileSync(patch.file, next);
      record(rel, "CHANGE", `${patch.name}: updated`);
    }
  }
  return changes;
}

/* Files the script may touch; --revert restores exactly these to git HEAD. */
function revertFiles() {
  const webFiles = [
    "index.html",
    "public/logo.svg",
    "public/favicon.ico",
    "public/apple-touch-icon-180x180.png",
    "src/resources/images/logo.svg",
    "src/login/ChangeServerPage.jsx",
    "src/settings/CalendarPage.jsx",
    "src/map/core/useMapStyles.js",
    "src/map/core/MapView.jsx",
    "src/common/theme/palette.js",
  ];
  const rootFiles = [
    "src/main/java/org/traccar/web/OverrideTextFilter.java",
  ];
  const git = (dir, args) =>
    execFileSync("git", args, { cwd: dir, encoding: "utf8" });
  const existingWeb = webFiles.filter((f) => existsSync(join(WEB, f)));
  if (existingWeb.length) git(WEB, ["checkout", "--", ...existingWeb]);
  const existingRoot = rootFiles.filter((f) =>
    existsSync(join(ROOT, f)),
  );
  if (existingRoot.length) git(ROOT, ["checkout", "--", ...existingRoot]);
  console.log(
    `Reverted ${existingWeb.length} file(s) in traccar-web to git HEAD.`,
  );
  if (existingRoot.length) {
    console.log(
      `Reverted ${existingRoot.length} file(s) in traccar backend to git HEAD.`,
    );
  }
}

/* Runtime branding: merge config values into the live server's attributes. */
function buildSeedAttributes(config) {
  const maps = config.maps || {};
  const links = config.links || {};
  const attrs = {
    title: config.appName,
    description: config.appDescription,
    colorPrimary: config.colorPrimary,
    colorSecondary: config.colorSecondary,
  };
  const optional = {
    logo: links.logoUrl,
    logoInverted: links.logoInvertedUrl,
    support: links.supportUrl,
    termsUrl: links.termsUrl,
    privacyUrl: links.privacyUrl,
    announcement: links.announcement,
    locationIqKey: maps.locationIqKey,
    ordnanceSurveyKey: maps.ordnanceSurveyKey,
    googleKey: maps.googleKey,
    mapTilerKey: maps.mapTilerKey,
    bingMapsKey: maps.bingMapsKey,
    tomTomKey: maps.tomTomKey,
    hereKey: maps.hereKey,
    mapboxAccessToken: maps.mapboxAccessToken,
    activeMapStyles: maps.defaultActiveStyles,
    map: maps.defaultStyle,
  };
  for (const [key, value] of Object.entries(optional)) {
    if (value !== undefined && value !== "") attrs[key] = value;
  }
  return attrs;
}

async function seedServer(config, { server, email, password }) {
  if (!server || !email || !password) {
    fail("--seed requires --server <url> --email <admin> --password <pw>.");
  }
  const attrs = buildSeedAttributes(config);
  console.log(
    `Seeding ${Object.keys(attrs).length} server attributes on ${server} ...`,
  );

  const jar = {};
  const storeCookies = (res) => {
    for (const cookie of res.headers.getSetCookie?.() || []) {
      const [pair] = cookie.split(";");
      const idx = pair.indexOf("=");
      if (idx > 0) jar[pair.slice(0, idx).trim()] = pair.slice(idx + 1).trim();
    }
  };
  const cookies = () =>
    Object.entries(jar)
      .map(([k, v]) => `${k}=${v}`)
      .join("; ");
  const api = async (path, options = {}) => {
    const res = await fetch(`${server}${path}`, {
      ...options,
      headers: {
        ...(options.headers || {}),
        ...(Object.keys(jar).length ? { Cookie: cookies() } : {}),
      },
    });
    storeCookies(res);
    if (!res.ok)
      fail(
        `${options.method || "GET"} ${path} -> ${res.status} ${res.statusText}: ${await res.text()}`,
      );
    return res;
  };

  const loginBody = new URLSearchParams({ email, password });
  await api("/api/session", { method: "POST", body: loginBody });
  const current = await (await api("/api/server")).json();
  const merged = {
    ...current,
    attributes: { ...(current.attributes || {}), ...attrs },
  };
  const updated = await (
    await api("/api/server", {
      method: "PUT",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(merged),
    })
  ).json();
  console.log("Server attributes updated:");
  for (const key of Object.keys(attrs)) {
    console.log(`  ${key} = ${(updated.attributes || {})[key]}`);
  }
}

async function main() {
  const args = parseArgs(process.argv);
  if (!existsSync(WEB)) fail(`traccar-web checkout not found at ${WEB}.`);
  try {
    const status = execFileSync(
      "git",
      ["submodule", "status", "--", "traccar-web"],
      { cwd: ROOT, encoding: "utf8" },
    ).trim();
    if (status.startsWith("+") || status.startsWith("-")) {
      warn(
        `traccar-web submodule is not at the recorded commit (${status}). Branding anchors assume an up-to-date checkout.`,
      );
    }
  } catch {
    /* git not available; continue */
  }

  if (args.revert) {
    revertFiles();
    return;
  }

  const brandingDir = dirname(resolve(args.config));
  const config = loadConfig(args.config);
  validate(config);

  if (args.seed) {
    await seedServer(config, args);
    return;
  }

  console.log(
    `${args.check ? "Previewing" : "Applying"} branding from ${args.config.replace(`${ROOT}/`, "")}:`,
  );
  const changes = runPatches(config, brandingDir, args.check);
  console.log(
    changes === 0
      ? "Everything is already branded. No changes."
      : `${args.check ? "Would change" : "Changed"} ${changes} file(s).${args.check ? " Re-run without --check to apply." : ""}`,
  );
  if (!args.check && changes > 0) {
    console.log(
      "Next: cd traccar-web && npm run generate-pwa-assets && node tools/apply-branding.mjs --config <same> && npm run build",
    );
    console.log(
      "(generate-pwa-assets rebuilds icons from logo.svg, so re-run this script to restore favicon.ico / apple-touch-icon)",
    );
  }
}

main().catch((error) => fail(error.message || String(error)));
