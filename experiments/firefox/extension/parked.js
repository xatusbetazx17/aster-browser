"use strict";
const id = new URLSearchParams(location.search).get("id");
action(document.getElementById("resume"), () => send("restore", {id}));
action(document.getElementById("workspace"), () => send("dashboard"));
send("state").then(data => {
  appearance(data.state.settings);
  const r = data.state.parked.find(r => r.id === id);
  if (!r) throw Error("This page was already resumed or forgotten. Check your workspace.");
  document.title = r.title + " · Parked";
  document.getElementById("title").textContent = r.title;
  document.getElementById("address").textContent = r.url;
}).catch(e => { status(e.message, true); document.getElementById("resume").disabled = true; });
