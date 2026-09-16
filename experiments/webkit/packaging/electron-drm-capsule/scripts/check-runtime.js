// Reports whether the capsule runtime is installed. It does not check Widevine;
// `npm run verify` does that, because only a real key-system request can.
const fs = require('fs');
const path = require('path');

const root = path.join(__dirname, '..');
const candidates = [
  path.join(root, 'node_modules', '.bin', process.platform === 'win32' ? 'electron.cmd' : 'electron'),
  path.join(root, 'node_modules', 'electron', 'dist', process.platform === 'win32' ? 'electron.exe' : 'electron')
];

const found = candidates.filter((candidate) => fs.existsSync(candidate));
if (found.length === 0) {
  console.error('Aster DRM capsule runtime is not installed.');
  console.error('Run: bash experiments/webkit/tools/setup_chromium_drm_capsule.sh');
  process.exit(1);
}
console.log('Aster DRM capsule runtime:', found[0]);
console.log('Run "npm run verify" to check whether Widevine is actually available in it.');
