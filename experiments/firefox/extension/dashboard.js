"use strict";
const $ = id => document.getElementById(id);
let latest;
function item(title, detail, buttons) {
  const row = document.createElement("div"); row.className = "item";
  const heading = document.createElement("strong"); heading.textContent = title;
  const subtitle = document.createElement("span"); subtitle.className = "muted"; subtitle.textContent = detail;
  const controls = document.createElement("div"); controls.className = "row"; controls.append(...buttons);
  row.append(heading, subtitle, controls); return row;
}
function empty(container, text) { if (!container.children.length) { const p = document.createElement("p"); p.className = "muted"; p.textContent = text; container.append(p); } }
function renderTabs() {
  $("tabs").replaceChildren();
  const filter = $("filter").value.toLowerCase();
  for (const tab of latest.tabs.filter(t => AsterCore.webURL(t.url) && (t.title + t.url).toLowerCase().includes(filter))) {
    const buttons = [button("Switch", () => browser.tabs.update(tab.id, {active:true}))];
    buttons.push(button(tab.pinned ? "Unpin" : "Pin", async () => { await browser.tabs.update(tab.id, {pinned:!tab.pinned}); await refresh(false); }));
    buttons.push(button(tab.mutedInfo?.muted ? "Unmute" : "Mute", async () => { await browser.tabs.update(tab.id, {muted:!tab.mutedInfo?.muted}); await refresh(false); }));
    if (!tab.audible && !tab.pinned) buttons.push(button("Park", async () => {
      if (!confirm("Unload this page and save its address? Save any unfinished forms first.")) return;
      await send("park", {id:tab.id}); await refresh(false);
    }));
    $("tabs").append(item(tab.title || tab.url, new URL(tab.url).hostname + (tab.audible ? " · playing audio" : ""), buttons));
  }
  empty($("tabs"), "No matching web tabs in this window.");
}
async function refresh(loadSettings = true) {
  latest = await send("state");
  appearance(latest.state.settings); renderTabs();
  $("parked").replaceChildren();
  for (const record of latest.state.parked) $("parked").append(item(record.title, record.url, [
    button("Resume", async () => { await send("restore", {id:record.id}); await refresh(false); }),
    button("Forget", async () => { if (!confirm("Forget this saved address?")) return; await send("forget", {id:record.id}); await refresh(false); })
  ]));
  empty($("parked"), "Nothing parked. Your saved pages will wait here.");
  $("workspaces").replaceChildren();
  for (const w of latest.state.workspaces) $("workspaces").append(item(w.name, `${w.tabs.length} saved pages`, [
    button("Restore parked", async () => { const r = await send("workspace-restore", {id:w.id}); status(`Opened ${r.count} parked tabs.`); await refresh(false); }),
    button("Delete", async () => { if (!confirm("Delete this workspace snapshot?")) return; await send("workspace-delete", {id:w.id}); await refresh(false); })
  ]));
  empty($("workspaces"), "Save a collection of tabs to return to later.");
  $("containers").replaceChildren();
  for (const c of latest.containers) $("containers").append(item(c.name, "Separate cookie identity", [button("Open website", async () => {
    const url = prompt("Address or search to open in " + c.name);
    if (url) await send("container-open", {cookieStoreId:c.cookieStoreId, url});
  })]));
  empty($("containers"), "No containers enabled. Availability depends on the Firefox build.");
  if (loadSettings) {
    const s = latest.state.settings;
    $("mode").value = s.mode; $("theme").value = s.theme; $("rules").value = s.rules; $("allow").value = s.allow;
    $("protect-streaming").checked = s.protectStreaming; $("large-controls").checked = s.largeControls;
  }
  $("blocking-status").textContent = `${latest.blockingPermission ? "Website access enabled" : "Website access not granted"}; mode: ${latest.state.settings.mode}. ${latest.blocked} requests blocked since this background session started.`;
  $("platform").textContent = `Firefox platform: ${latest.platform.os} / ${latest.platform.arch}. DRM playback has not been tested by this page. Device compatibility is not a certification.`;
}
function form(id, callback) { $(id).addEventListener("submit", async event => { event.preventDefault(); try { await callback(); } catch (e) { status(e.message, true); } }); }
form("search-form", () => browser.tabs.create({url:AsterCore.navigate($("search").value)}));
form("workspace-form", async () => { await send("workspace-save", {name:$("workspace-name").value}); $("workspace-name").value = ""; await refresh(false); status("Workspace saved."); });
form("settings-form", async () => {
  const settings = AsterCore.settings({mode:$("mode").value, theme:$("theme").value, rules:$("rules").value, allow:$("allow").value, protectStreaming:$("protect-streaming").checked, largeControls:$("large-controls").checked});
  await send("settings", {settings}); await refresh(); status("Settings saved. Reload open websites to apply blocking changes.");
});
action($("blocking-permission"), async () => {
  const ok = await browser.permissions.request({origins:["http://*/*", "https://*/*"]});
  status(ok ? "Website access enabled. Choose a blocking mode and save settings." : "Website access was not granted."); await refresh(false);
});
action($("revoke-blocking"), async () => { await browser.permissions.remove({origins:["http://*/*", "https://*/*"]}); await refresh(false); status("Website access removed."); });
action($("containers-permission"), async () => { const ok = await browser.permissions.request({permissions:["cookies"]}); status(ok ? "Container access enabled." : "Container access was not granted."); await refresh(false); });
form("container-form", async () => { await send("container-create", {name:$("container-name").value}); $("container-name").value = ""; await refresh(false); });
action($("refresh"), () => refresh(false));
$("filter").addEventListener("input", () => { if (latest) renderTabs(); });
form("command-form", async () => {
  const input = $("command").value.trim();
  const match = input.match(/^\/(open|search)\s+(.+)$/s);
  if (match) { await browser.tabs.create({url:match[1] === "open" ? AsterCore.navigate(match[2]) : "https://duckduckgo.com/?q=" + encodeURIComponent(match[2])}); $("command-result").textContent = "Opened in a new tab."; }
  else if (input === "/stats") { await refresh(false); $("command-result").textContent = `${latest.tabs.length} tabs in this window; ${latest.state.parked.length} saved parked pages; ${latest.state.workspaces.length} workspaces. Memory usage is not measured.`; }
  else $("command-result").textContent = "/open ADDRESS — open a website\n/search WORDS — search DuckDuckGo\n/stats — show tab counts\n/help — show these commands\nUse Park beside a tab to unload it. For reading and highlights, use the Aster toolbar on an article. This desk is a command parser, not a language model.";
});
action($("export"), async () => {
  const {state} = await send("state");
  const url = URL.createObjectURL(new Blob([JSON.stringify(state, null, 2)], {type:"application/json"}));
  const a = document.createElement("a"); a.href = url; a.download = "aster-backup.json"; a.click(); setTimeout(() => URL.revokeObjectURL(url), 30000);
});
$("import").addEventListener("change", async event => {
  try {
    const file = event.target.files[0]; if (!file) return;
    if (file.size > 2000000) throw Error("Backup must be under 2 MB.");
    const data = AsterCore.validateState(JSON.parse(await file.text()));
    if (!confirm(`Add ${data.parked.length} saved pages and ${data.workspaces.length} workspaces from this backup?`)) return;
    await send("import", {data}); await refresh(false); status("Backup imported. Current settings were kept.");
  } catch (e) { status(e.message, true); } finally { event.target.value = ""; }
});
refresh().catch(e => status(e.message, true));
