# Streaming, cloud gaming and engine constraints

## What this revision actually changes

The Linux prototype enables WebKit's available WebRTC, Media Source, encrypted-media, WebGL and fullscreen settings. Camera/microphone, mouse capture and protected-media requests now have native **Allow this request / Deny** dialogs. Requests from background tabs are denied; pending prompts are cancelled when you switch tabs or navigate. Other permission types remain unsupported. No permanent permission grants are written by Aster.

The top-level page origin is shown without URL queries or credentials. A request can originate in embedded content; this version's WebKit permission interface does not provide every requesting frame's origin. The dialog says so. Use **Stop camera and microphone** in the Aster menu to stop capture across tabs. A capture button appears when the engine reports active capture.

Website fullscreen hides Aster's controls; **Escape/F11** returns to the browser. **Companion → Play** opens Boosteroid, Prime Video, Xbox Cloud Gaming and GeForce NOW in ordinary Aster tabs. Any valid HTTP/HTTPS website can be entered in the address bar. The presence of a service shortcut does not claim that its player or game works.

**Check this page's streaming support** inspects codecs, MSE, WebRTC, controller/mouse/fullscreen APIs and requests temporary Widevine key-system access. Run it on an HTTPS page. It distinguishes an absent API, declined/unsupported key system and unverified available access. It does not contact a license server, decode a protected stream, change the user agent, install a CDM or launch another browser.

## Why Prime Video is not solved by a browser setting

Protected playback needs a compatible encrypted-media implementation, an integrated content decryption module, codecs, the service's license exchange and any device/output restrictions it requires. Google's [Widevine documentation](https://developers.google.com/widevine/drm/overview) describes the license agreement and integration; [Prime Video's computer requirements](https://www.primevideo.com/help?nodeId=GUX9FYHU5D8LC9EJ) list supported browsers. Aster is not currently on that list. WebKit's encrypted-media switch does not supply Widevine or make Aster service-approved.

There is no licensed Widevine integration or successful Prime Video session in this repository. Copying a CDM from an unrelated browser or changing a browser name is not an implementation of that integration. A legitimate integration/distribution arrangement, platform implementation and subscribed service tests are still needed. No HD/4K claim is made.

## Cloud gaming checks

The [Boosteroid requirements](https://help.boosteroid.com/en/content/general-requirements-for-your-digital-environment) cover the device and connection. In Aster, testing must also cover WebRTC negotiation, available H.264/VP8 codecs, low-latency decoding, controller mapping, pointer capture, microphone voice chat and a real game session. WebKit capability availability varies by distribution build. API detection alone proves none of those end-to-end results.

The CI fixture decodes and plays an authored, unencrypted VP8 clip. It uses no paid account or public service. Prime Video, Boosteroid, GeForce NOW and Xbox Cloud Gaming have **not** passed an Aster service-level test. A subscribed test account, supported physical hardware/controller and the actual target package are still required for those checks.

## Independence and native ports

Aster's interface, reader, local assistant and browser controls are its own application code. The current full webpage renderer is still **WebKitGTK/JavaScriptCore**, a third-party engine. Nothing in this revision implements or renames that engine as an original Aster engine.

The requirement to use no engine from any other browser would require an original HTML/CSS/DOM/JavaScript engine, isolation/networking, graphics, media, WebRTC and DRM implementation. The current GTK/WebKit application is a Linux prototype. A Windows EXE or Android APK containing an empty interface or a different installed browser would not satisfy the requested native product. Native Windows/Android builds remain blocked on a compatible engine/port implementation; they have not been produced by this change.

The portable document/assistant logic is tested on Windows as well as Linux, but those tests are not a Windows browser build. Android and Steam Deck need their own packages and device validation. The original Qt/Chromium Windows and Android WebView packages remain historical artifacts, not the new engine-independent browser.
