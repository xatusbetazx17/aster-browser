# Read documents and use the local Aster companion

These tools run inside the **Linux Aster WebKit prototype**. They do not require an Office account, a cloud AI subscription or a second browser.

## Documents and read-aloud

1. Update Aster using the [Linux setup guide](linux.md), including dependencies.
2. Press **Ctrl+O** or choose **Open document** in the Aster menu.
3. Choose a `.docx`, `.doc`, `.pdf`, `.txt` or `.md` file.
4. Use **Read aloud**, **Stop**, the English/Spanish voice selector and the speed slider. A selected passage is read instead of the entire document. Speech is limited to the first 120,000 characters per request.
5. Use the reader's search box and press Enter to find a passage. **Read this page** brings selected webpage text, or the current page's text, into the same reader.

`.docx` reading includes body paragraphs, line breaks and table cells. It is a text reading view, not a Word layout editor: images, page layout, headers/footers, comments and embedded objects are not reproduced. Macros and external document relationships are never executed. `.docm` is unsupported. Save it as `.docx` to read its text.

Older `.doc` files need **antiword**. PDF extraction needs **pdftotext** from Poppler and reads at most 200 pages; scanned PDFs need OCR first. Read-aloud needs **espeak-ng**. The Linux installer adds these on Debian/Ubuntu and Arch. Fedora setup includes Poppler and speech; install antiword separately if available or convert `.doc` to `.docx` in your existing document editor. Files over 32 MB or one million extracted characters are refused with an explanation. Aster does not modify the document.

## Offline commands and excerpts

Press **Ctrl+J** to open the companion. These commands work without a model or network:

- `new tab`, `go back`, `go forward`, `reload`
- `open example.com`, `search networking` (navigation/search itself uses the network)
- `zoom in`, `zoom out`, `bookmark this page`
- `open document`, `read aloud`, `stop reading`
- `fullscreen`, `check streaming`

Some short Spanish commands are also recognized: `nueva pestaña`, `abrir documento`, `lee esto`, `deja de leer` and `buscar ...`.

For summaries or questions, select **Use current page text** or **Use text from: [document]**. Basic mode finds passages in that supplied text. It is extractive assistance, not a generative language model. Clear the reader to use the current webpage again. Document text and assistant output are native text widgets, not executable HTML.

## Optional generative AI, entirely local

The companion can load a user-selected **GGUF instruction/chat model** through `llama-cpp-python`. It runs a local worker process and has no cloud API, server connection, automatic model download or model-issued browser actions. The model must fit your computer's memory. Small models can give poor answers; the browser does not certify their accuracy.

On Debian/Ubuntu, create a separate helper environment (this does not change the browser's GTK environment):

```bash
sudo apt install python3-venv libportaudio2
python3 -m venv "$HOME/.local/share/aster-assistant-env"
"$HOME/.local/share/aster-assistant-env/bin/python" -m pip install --upgrade pip
"$HOME/.local/share/aster-assistant-env/bin/python" -m pip install llama-cpp-python==0.3.35 --extra-index-url https://abetlen.github.io/llama-cpp-python/whl/cpu
"$HOME/.local/share/aster-assistant-env/bin/python" -m pip install vosk==0.3.45 sounddevice==0.5.6
```

CPU wheels are supplied by the library's publisher for supported systems. If no wheel matches your system, follow the [llama-cpp-python build instructions](https://llama-cpp-python.readthedocs.io/en/latest/); a compiler/build toolchain may be needed. On Arch/Fedora, install your distribution's Python virtual-environment support and PortAudio package before creating the same helper environment.

Download a compatible GGUF instruction model from its publisher, check its license and available RAM requirements, then choose that file with **Choose AI model**. The [Qwen official GGUF model page](https://huggingface.co/Qwen/Qwen3-0.6B-GGUF) and [Hugging Face's small instruction GGUF model](https://huggingface.co/HuggingFaceTB/smollm-135M-instruct-v0.2-Q8_0-GGUF) are examples. Models and voices are optional, separate downloads; they are not bundled with Aster.

Launch your managed Aster installation with the helper environment selected:

```bash
ASTER_ASSISTANT_PYTHON="$HOME/.local/share/aster-assistant-env/bin/python" \
  python3 "$HOME/.local/share/aster-testing-webkit/start-aster.py"
```

The helper interpreter is used only for local model/voice workers. It does not replace the browser engine. Model selection is currently for this browser session; use **Use basic mode** to return to offline commands/excerpts. A request is limited to 384 generated tokens and 180 seconds. Inference uses the CPU and unloads after each question, which trades speed for releasing memory between questions.

## Optional voice input

1. Install the helper packages above.
2. Download and extract an English or Spanish model from the [official Vosk model list](https://alphacephei.com/vosk/models). For example, `vosk-model-small-en-us-0.15` is the small US English model.
3. In the companion, press **Choose voice model** and select the extracted folder containing `am/final.mdl`.
4. Press **Listen (8 s)** and speak after microphone initialization. The microphone closes when that capture finishes.
5. Review the recognized words and press **Ask Aster**. Recognition does not automatically execute browser commands.

There is no always-listening wake word. This is an initial local assistant, not Siri feature parity. Model quality, microphone access and speech output on actual hardware still need device testing. Ordinary commands, document text extraction and read-aloud do not need a generative AI model.
