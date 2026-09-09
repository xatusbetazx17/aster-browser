"use strict";
let selected;
browser.tabs.query({active: true, currentWindow: true}).then(tabs => { selected = tabs[0]; document.getElementById("page").textContent = selected?.title || "Current page"; }).catch(e => status(e.message, true));
action(document.getElementById("workspace"), async () => { await send("dashboard"); window.close(); });
action(document.getElementById("read"), async () => { if (!selected) throw Error("No page selected."); await send("read", {id: selected.id}); window.close(); });
action(document.getElementById("park"), async () => { if (!selected) throw Error("No page selected."); await send("park", {id: selected.id}); window.close(); });
send("state").then(data => appearance(data.state.settings)).catch(e => status(e.message, true));
