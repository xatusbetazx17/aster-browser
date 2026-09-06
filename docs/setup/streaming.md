# Streaming, cloud gaming and engine constraints

## Original engine development update

The new [original-engine preview](../../experiments/aster-engine/README.md) implements
Aster's own small HTML/text parser, layout and link renderer. It has Windows/Linux
desktop and native Android hosts and actual package/test workflows. This is a
separate engine foundation; it does not replace the WebKit prototype described below.

The preview has no JavaScript, video, WebRTC, MSE or EME. Its Android menu uses
the platform's [MediaDrm API](https://developer.android.com/reference/android/media/MediaDrm)
to report whether the device exposes Widevine. That is a real local API query,
but it does not request a license, provision a device, play encrypted media or
bridge Android DRM into website JavaScript. It makes no Prime Video claim.

To finish the original-engine path requires, in order: a standards-capable
DOM/CSS implementation and renderer isolation; JavaScript/DOM/event integration;
network/storage/origin/permission APIs; graphics/media/codec playback and MSE;
WebRTC with ICE/DTLS/SRTP and real audio/video/controller tests; then EME plus
an authorized platform CDM integration, license exchange and service tests.
The parser tests and native packages are evidence for the first small step only.

For Widevine, the repository owner must obtain the necessary integration agreement
and access from [Google's official contact route](https://developers.google.com/widevine/contact/support).
The [overview](https://developers.google.com/widevine/drm/overview) states that a
license agreement is required and that Widevine itself assesses no product/service
fee. The agreement does not by itself supply Aster's web/media implementation or
guarantee Amazon acceptance. No application/agreement has been submitted on the
owner's behalf, and no proprietary CDM or credentials are present in this repository.

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

The actual Ubuntu CI package (WebKitGTK 2.52.6) did **not** expose WebRTC or encrypted-media APIs. Enabling settings cannot add features compiled out of that engine. A maintained engine build with these features and its required integration must come before a cloud-game/DRM trial. WebKit's [GTK build options](https://github.com/WebKit/WebKit/blob/main/Source/cmake/OptionsGTK.cmake) put WebRTC and encrypted media behind experimental build options by default; this repository does not ship such a rebuilt engine.

## Independence and native ports

Aster's interface, reader, local assistant and browser controls are its own application code. The current full webpage renderer is still **WebKitGTK/JavaScriptCore**, a third-party engine. Nothing in this revision implements or renames that engine as an original Aster engine.

The requirement to use no engine from any other browser requires an original
HTML/CSS/DOM/JavaScript engine, isolation/networking, graphics, media, WebRTC and
DRM implementation. The GTK/WebKit application remains a Linux prototype. The
new Windows/Android original-engine preview implements actual text rendering and
navigation, but it is not the completed native port of that browser.

The document/assistant Python tests still do not establish a Windows port of those
features. The original-engine workflow separately builds and launches its desktop
app and tests its Android APK in an emulator. Physical Android devices, Steam Deck,
stable signing/update distribution and full-browser features still need validation
and development. Legacy Qt/Chromium and WebView packages remain historical artifacts.
