"use strict";
let article, utterances = [];
action(document.getElementById("highlight"), async () => {
  if (!article) throw Error("Article is not available.");
  const sentences = AsterCore.summarize(article.text);
  document.getElementById("sentences").replaceChildren(...sentences.map(text => { const li = document.createElement("li"); li.textContent = text; return li; }));
  document.getElementById("summary").hidden = false;
  if (!sentences.length) status("Not enough long sentences to select highlights.");
});
action(document.getElementById("speak"), async () => {
  if (!article || !window.speechSynthesis) throw Error("Speech is unavailable on this device.");
  const voices = speechSynthesis.getVoices().filter(v => v.localService);
  if (!voices.length) throw Error("No local voice is ready. Install a device voice, then try again.");
  speechSynthesis.cancel();
  const voice = voices.find(v => v.lang.startsWith(navigator.language.split("-")[0])) || voices[0];
  utterances = (article.text.match(/[\s\S]{1,350}(?:\s|$)|[\s\S]{1,350}/g) || []).map(part => {
    const u = new SpeechSynthesisUtterance(part); u.voice = voice; u.lang = voice.lang;
    u.onerror = e => { if (e.error !== "canceled" && e.error !== "interrupted") status("Read-aloud failed: " + e.error, true); }; return u;
  });
  utterances.forEach(u => speechSynthesis.speak(u)); status("Reading with " + voice.name + " (device voice).");
});
action(document.getElementById("stop"), async () => { window.speechSynthesis?.cancel(); status("Read-aloud stopped."); });
window.addEventListener("pagehide", () => window.speechSynthesis?.cancel());
send("article", {id:new URLSearchParams(location.search).get("id")}).then(data => {
  article = data; document.title = data.title + " · Aster reading";
  document.getElementById("title").textContent = data.title;
  document.getElementById("article").textContent = data.text;
  if (AsterCore.webURL(data.url)) document.getElementById("source").href = data.url;
}).catch(e => status(e.message, true));
send("state").then(data => appearance(data.state.settings)).catch(e => status(e.message, true));
