# Feature validation — 2026-09-06

## Local results

- 27 dependency-free tests pass: navigation/bookmark recovery, Word body/tables/Unicode, rejection of unsafe XML and oversized input, document conversion dependency errors, local command boundaries, extractive answers, speech process invocation and media report honesty.
- 13 install/update tests pass, including interrupted/corrupt downloads, preservation of local edits, no-op update, rollback and platform refusal.
- Python syntax compiles and the Git patch passes whitespace checks.
- The actual Vosk 0.3.45 decoder processed the project's public reference WAV with `vosk-model-small-en-us-0.15` through Aster's voice worker. This validates local recorded-audio decoding, not a physical microphone or error-free recognition. The model ZIP SHA256 was `30f26242c4eb449f948e42cb302dd7a686cb29a3423a8367f99ff41780942498`.
- Real local GGUF inference completed through `generate_local` and the actual `llama-cpp-python` worker using version **0.3.35** on Python 3.12. The test model was the publisher's `HuggingFaceTB/smollm-135M-instruct-v0.2-Q8_0-GGUF`, revision `53066c72a815622e28d006aefbe32df6dd4b1835`, file `smollm-135m-instruct-add-basics-q8_0.gguf`, SHA256 `a98d3857b95b96c156d954780d28f39dcb35b642e72892ee08ddff70719e6220`. This verifies local inference plumbing only. This tiny model produced an unreliable greeting; it is not a recommendation for assistant answer quality.

## Native-runtime gate

The updated GitHub Actions smoke test opens the actual GTK/WebKit app, reads a real DOCX through the native panel, finds document text, runs offline assistant excerpts/commands, exercises fullscreen UI, probes media support, generates and plays an unencrypted VP8 clip and synthesizes real speech audio. It captures home and reader screenshots.

That gate [passed on the initial feature revision](https://github.com/xatusbetazx17/aster-browser/actions/runs/34004393116) with GTK 4.14, libadwaita 1.5 and WebKitGTK **2.52.6**. The runtime reported MSE, Gamepad, pointer lock and fullscreen, and H.264/AAC/VP8/VP9/Opus codec support. **It did not expose WebRTC or the encrypted-media API**, despite enabling the available settings. This distro build cannot currently support the requested WebRTC cloud gaming or Widevine playback. Video success applies to the unencrypted VP8 fixture only.

Check the pull request's Actions for the result corresponding to its current commit. Hosted-runner nested sandboxing is disabled **only for the trusted localhost smoke fixture**, as documented in the workflow. No desktop-sandbox claim follows from that check.

## Not verified or not implemented

- Paid Prime Video/Widevine playback; no CDM is supplied or service support certified.
- Boosteroid/GeForce NOW/Xbox game sessions, physical controllers, microphone capture/output hardware and real network latency.
- Siri-quality conversations, a wake word, or broad voice-command/language accuracy.
- Native Windows/Android browser packages, maintained SteamOS packaging or an original Aster web engine.

Windows CI exercises portable Python document/assistant/updater logic. It is not a Windows browser build. Models and test recordings used during authoring are not included in the source package.
