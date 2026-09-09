# Aster: Widevine integration prerequisites

Status: no Widevine integration agreement, restricted SDK access, browser CDM
integration or successful protected-content session has been provided or verified
for this project. No request has been submitted on the repository owner's behalf.

Google's [overview](https://developers.google.com/widevine/drm/overview) requires
a license agreement and says Widevine does not assess a product/service fee.
Its [contact instructions](https://developers.google.com/widevine/contact/support)
direct new inquiries through General Questions on the Widevine website.
Eligibility, distribution rights, platform restrictions and integration support
must be established directly; a source-code change cannot grant them.

## Technical facts ready for an inquiry

- Project: Aster Browser, https://github.com/xatusbetazx17/aster-browser.
- Owner: GitHub account xatusbetazx17. The owner supplies their legal/contact details directly.
- Distribution intent: an independent browser for desktop Linux, Windows and Android, including eventual Steam Deck use.
- Existing application: Linux GTK/WebKitGTK prototype. Its tested distribution build has no EME API.
- Original engine: early Aster-authored Java HTML/text renderer with Java2D desktop and Android Canvas hosts. No JavaScript, MSE/EME or video decoder integration yet.
- Android: a local MediaDrm capability query is implemented. No provisioning, license-request or playback integration is implemented.
- Intended use: ordinary authorized subscription playback inside Aster, including services such as Prime Video. Aster has no service approval or verified playback result.

## Questions for the Widevine team

1. Is browser CDM integration/distribution available to this project on each target platform, and which agreement/application is required?
2. Which SDKs, headers, reference implementations and test assets may the project access and use after approval?
3. What are the platform requirements for protected decoding, output protection and verification of the browser implementation?
4. On Android, which integration responsibilities remain with Aster when the device already exposes MediaDrm/Widevine?
5. Which updates, signing, security maintenance and redistribution obligations apply to an independently distributed browser?
6. Which tests establish an acceptable integration, and which separate approvals or compatibility decisions remain with streaming services?

## Engineering work still required

After terms/access are settled, develop and validate the platform media path and
the browser's DOM/JavaScript/MSE/EME implementation. Add a CDM adapter using the
authorized platform interfaces; connect key sessions and the service's license
exchange; handle expiration, renewal, rejection and output restrictions. Test
approved fixtures first, then real subscribed service sessions on each supported
package/device. Keep licensed binaries, credentials and private agreement material
out of this public repository.

None of those steps is replaced by toggling a setting, querying MediaDrm, changing
a user agent, copying a different browser's CDM or opening an external browser.
Cloud gaming separately requires a working web platform, WebRTC and physical
input/latency testing; Widevine approval does not provide those features.
