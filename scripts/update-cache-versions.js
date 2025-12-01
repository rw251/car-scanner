#!/usr/bin/env node
const fs = require("fs");
const path = require("path");

const root = path.resolve(__dirname, "..");
const swPath = path.join(root, "service-worker.js");
const scriptPath = path.join(root, "script.js");
const indexPath = path.join(root, "index.html");
const pkgPath = path.join(root, "package.json");

function bumpPatchVersion(v) {
  const parts = String(v || "0.0.0")
    .split(".")
    .map((p) => parseInt(p, 10) || 0);
  if (parts.length < 3) {
    while (parts.length < 3) parts.push(0);
  }
  parts[2] = parts[2] + 1;
  return parts.slice(0, 3).join(".");
}

function replaceInFile(filePath, replacements) {
  if (!fs.existsSync(filePath)) return false;
  let content = fs.readFileSync(filePath, "utf8");
  let out = content;
  replacements.forEach(({ search, replace }) => {
    out = out.replace(search, replace);
  });
  if (out !== content) {
    fs.writeFileSync(filePath, out, "utf8");
    console.log("Patched", path.basename(filePath));
    return true;
  }
  return false;
}

// Read package.json and bump patch
let pkg = {};
if (fs.existsSync(pkgPath)) {
  try {
    pkg = JSON.parse(fs.readFileSync(pkgPath, "utf8")) || {};
  } catch (e) {
    console.error("Failed to parse package.json:", e.message || e);
  }
}
const oldVersion = pkg.version || "0.0.0";
const newVersion = bumpPatchVersion(oldVersion);
pkg.version = newVersion;
fs.writeFileSync(pkgPath, JSON.stringify(pkg, null, 2) + "\n", "utf8");
console.log("Bumped package.json version", oldVersion, "->", newVersion);

const verTag = `v${newVersion}`;

// Update service-worker CACHE_NAME like: const CACHE_NAME = `obd-v1.2.3`;
replaceInFile(swPath, [
  {
    search: /const\s+CACHE_NAME\s*=\s*`[^`]+`\s*;/,
    replace: `const CACHE_NAME = \`obd-${verTag}\`;`,
  },
]);

// Update version in script.js: const version = "v1.0.17";
replaceInFile(scriptPath, [
  {
    search: /const\s+version\s*=\s*"[^"]*"\s*;/,
    replace: `const version = "${verTag}";`,
  },
]);

// Update index.html script tag to include version query so browsers load the
// new script when version changes. Replace only the src attribute so other
// attributes (like `defer`) are preserved regardless of attribute order.
replaceInFile(indexPath, [
  {
    search: /src=("|')\/?script\.js(?:\?[^"']*)?("|')/i,
    replace: `src=\"/script.js?v=${verTag}\"`,
  },
]);

// Done
console.log("Version update complete:", verTag);
