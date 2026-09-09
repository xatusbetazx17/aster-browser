"use strict";
async function send(type, rest = {}) {
  const response = await browser.runtime.sendMessage({type, ...rest});
  if (!response || !response.ok) throw Error(response?.error || "Aster background is unavailable. Try reopening the page.");
  return response.value;
}
function status(text, error = false) {
  const element = document.getElementById("status");
  element.textContent = text; element.classList.toggle("error", error);
}
function action(element, callback) {
  element.addEventListener("click", async () => {
    element.disabled = true;
    try { await callback(); } catch (error) { status(error.message, true); }
    finally { element.disabled = false; }
  });
}
function button(text, callback, title) {
  const b = document.createElement("button"); b.textContent = text;
  if (title) b.title = title;
  action(b, callback); return b;
}
function appearance(settings) {
  document.documentElement.dataset.theme = settings.theme;
  document.documentElement.classList.toggle("large", settings.largeControls);
}
