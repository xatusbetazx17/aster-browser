// Aster DRM capsule: a separate Chromium-family runtime for one protected service.
//
// Aster's own WebKitGTK build has no encrypted-media support, so a protected
// stream cannot run in an Aster tab. This capsule uses CastLabs Electron for
// Content Security, which installs its own Widevine CDM on first launch through
// Google's component service. Nothing here bundles, copies or redistributes a
// CDM, and nothing here bypasses DRM.

const { app, BrowserWindow, dialog, session } = require('electron');

let components = null;
try {
  components = require('electron').components;
} catch (_) {
  components = null;
}

function argValue(name, fallback = '') {
  const prefix = `${name}=`;
  const found = process.argv.find((arg) => arg.startsWith(prefix));
  return found ? found.slice(prefix.length) : fallback;
}

function hasFlag(name) {
  return process.argv.includes(name);
}

// The URL arrives from Aster, which already validated it. Re-check here so the
// capsule cannot be pointed at a file:// or javascript: target by any other caller.
function webUrl(value) {
  try {
    const parsed = new URL(value);
    return parsed.protocol === 'https:' || parsed.protocol === 'http:' ? parsed.href : null;
  } catch (_) {
    return null;
  }
}

const rawUrl = argValue('--aster-url');
const targetUrl = webUrl(rawUrl);
const serviceName = argValue('--aster-service', 'Streaming service');
const profileDir = argValue('--aster-profile');
const verifyOnly = hasFlag('--aster-verify');

app.name = 'Aster DRM Capsule';
app.commandLine.appendSwitch('autoplay-policy', 'no-user-gesture-required');
app.commandLine.appendSwitch('enable-features', 'VaapiVideoDecoder,WebRTCPipeWireCapturer');
app.commandLine.appendSwitch('disable-features', 'HardwareMediaKeyHandling');

if (profileDir) {
  app.setPath('userData', profileDir);
}

function installPermissionHandler() {
  session.defaultSession.setPermissionRequestHandler((webContents, permission, callback, details) => {
    // Playback needs these two and a prompt for them only trains people to click Allow.
    if (permission === 'fullscreen' || permission === 'pointerLock') {
      callback(true);
      return;
    }
    const origin = (details && details.requestingUrl) || webContents.getURL() || targetUrl;
    dialog.showMessageBox({
      type: 'question',
      buttons: ['Allow once', 'Deny'],
      defaultId: 1,
      cancelId: 1,
      title: 'Aster capsule permission',
      message: `${serviceName} asks for ${permission}`,
      detail: origin,
      noLink: true
    }).then((result) => callback(result.response === 0)).catch(() => callback(false));
  });
}

// Reports whether this runtime really exposes a Widevine key system. Running the
// capsule is not the same as having a working CDM, and this is what tells them apart.
const PROBE = `(async () => {
  if (typeof navigator.requestMediaKeySystemAccess !== 'function') {
    return { secureContext: window.isSecureContext, widevine: 'absent', detail: 'No EME API' };
  }
  try {
    const access = await navigator.requestMediaKeySystemAccess('com.widevine.alpha', [{
      initDataTypes: ['cenc'],
      videoCapabilities: [{ contentType: 'video/mp4; codecs="avc1.42E01E"' }],
      audioCapabilities: [{ contentType: 'audio/mp4; codecs="mp4a.40.2"' }]
    }]);
    const keys = await access.createMediaKeys();
    return { secureContext: window.isSecureContext, widevine: 'available', keySystem: access.keySystem,
             mediaKeys: Boolean(keys) };
  } catch (error) {
    return { secureContext: window.isSecureContext, widevine: 'declined', detail: String(error && error.name) };
  }
})()`;

async function runVerification() {
  const win = new BrowserWindow({
    show: false,
    webPreferences: { sandbox: true, contextIsolation: true, nodeIntegration: false }
  });
  try {
    // file:// is a trustworthy origin in Chromium, so EME is reachable without a server.
    await win.loadFile('verify.html');
    const report = await win.webContents.executeJavaScript(PROBE, true);
    report.componentStatus = components && typeof components.status === 'function' ? components.status() : null;
    process.stdout.write(`${JSON.stringify(report, null, 2)}\n`);
    return report.widevine === 'available' ? 0 : 1;
  } finally {
    win.destroy();
  }
}

function createWindow() {
  installPermissionHandler();
  const win = new BrowserWindow({
    width: 1280,
    height: 800,
    title: `${serviceName} — Aster DRM capsule`,
    backgroundColor: '#111827',
    webPreferences: { sandbox: true, contextIsolation: true, nodeIntegration: false, webSecurity: true }
  });
  win.webContents.on('render-process-gone', (_event, details) => {
    dialog.showMessageBox(win, {
      type: 'warning',
      title: 'Aster capsule renderer stopped',
      message: 'The capsule renderer stopped. Close and reopen the service from Aster.',
      detail: JSON.stringify(details || {}, null, 2)
    }).catch(() => {});
  });
  win.loadURL(targetUrl);
}

app.whenReady().then(async () => {
  if (components && typeof components.whenReady === 'function') {
    try {
      await components.whenReady();
    } catch (error) {
      // Say so rather than opening a window that silently cannot decrypt anything.
      const message = `The Widevine component did not install: ${error}`;
      if (verifyOnly) {
        process.stderr.write(`${message}\n`);
        app.exit(1);
        return;
      }
      dialog.showErrorBox('Aster capsule', message);
    }
  }

  if (verifyOnly) {
    let code = 1;
    try {
      code = await runVerification();
    } catch (error) {
      process.stderr.write(`Verification failed: ${error}\n`);
    }
    app.exit(code);
    return;
  }

  if (!targetUrl) {
    dialog.showErrorBox('Aster capsule',
      'No service address was supplied. Open a service from Aster, or pass --aster-url=https://…');
    app.exit(2);
    return;
  }
  createWindow();
});

app.on('window-all-closed', () => app.quit());
