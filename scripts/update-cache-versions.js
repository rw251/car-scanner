#!/usr/bin/env node
const fs = require('fs');
const path = require('path');

function nowVersion() {
  const d = new Date();
  const y = d.getUTCFullYear();
  const m = String(d.getUTCMonth() + 1).padStart(2, '0');
  const day = String(d.getUTCDate()).padStart(2, '0');
  const hh = String(d.getUTCHours()).padStart(2, '0');
  const mm = String(d.getUTCMinutes()).padStart(2, '0');
  const ss = String(d.getUTCSeconds()).padStart(2, '0');
  return `${y}${m}${day}${hh}${mm}${ss}`;
}

const root = path.resolve(__dirname, '..');
const swPath = path.join(root, 'service-worker.js');
const scriptPath = path.join(root, 'script.js');

const ver = 'obd-' + nowVersion();
console.log('Updating cache/version to', ver);

function replaceInFile(filePath, replacements) {
  if (!fs.existsSync(filePath)) return false;
  let content = fs.readFileSync(filePath, 'utf8');
  let out = content;
  replacements.forEach(({ search, replace }) => {
    out = out.replace(search, replace);
  });
  if (out !== content) {
    fs.writeFileSync(filePath, out, 'utf8');
    console.log('Patched', path.basename(filePath));
    return true;
  }
  return false;
}

// Update service-worker CACHE_NAME like: const CACHE_NAME = `obd-1.0.18`;
replaceInFile(swPath, [
  {
    search: /const\s+CACHE_NAME\s*=\s*`[^`]+`\s*;/,
    replace: `const CACHE_NAME = \`${ver}\`;`,
  },
]);

// Update version in script.js: const version = "v1.0.17";
replaceInFile(scriptPath, [
  {
    search: /const\s+version\s*=\s*"[^"]*"\s*;/,
    replace: `const version = "${ver}";`,
  },
]);

// Done
console.log('Version update complete.');
