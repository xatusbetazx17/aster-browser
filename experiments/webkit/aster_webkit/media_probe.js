// Function body for WebKit.call_async_javascript_function in an isolated world.
const video = document.createElement('video');
const audio = document.createElement('audio');
const report = {
  origin: location.origin, secure: globalThis.isSecureContext === true,
  mse: typeof MediaSource !== 'undefined',
  webrtc: typeof RTCPeerConnection === 'function',
  gamepad: typeof navigator.getGamepads === 'function',
  pointerLock: typeof document.documentElement.requestPointerLock === 'function',
  fullscreen: !!document.fullscreenEnabled,
  codecs: {
    'H.264': video.canPlayType('video/mp4; codecs="avc1.42E01E"'),
    'AAC': audio.canPlayType('audio/mp4; codecs="mp4a.40.2"'),
    'VP8': video.canPlayType('video/webm; codecs="vp8"'),
    'VP9': video.canPlayType('video/webm; codecs="vp9"'),
    'Opus': audio.canPlayType('audio/webm; codecs="opus"')
  },
  widevine: 'Not available: encrypted-media API is missing'
};
if (!report.secure) {
  report.widevine = 'Open an HTTPS page to check protected media';
} else if (typeof navigator.requestMediaKeySystemAccess === 'function') {
  let timer;
  try {
    const access = navigator.requestMediaKeySystemAccess('com.widevine.alpha', [{
      initDataTypes: ['cenc'], sessionTypes: ['temporary'],
      distinctiveIdentifier: 'not-allowed', persistentState: 'not-allowed',
      videoCapabilities: [{contentType: 'video/mp4; codecs="avc1.42E01E"'}],
      audioCapabilities: [{contentType: 'audio/mp4; codecs="mp4a.40.2"'}]
    }]).then(() => 'Key-system access available; actual playback is unverified',
            error => 'Unavailable or declined (' + (error.name || 'error') + ')');
    report.widevine = await Promise.race([
      access,
      new Promise(resolve => {timer = setTimeout(() => resolve('Check timed out; access unverified'), 8000);})
    ]);
  } finally { clearTimeout(timer); }
}
return JSON.stringify(report);
