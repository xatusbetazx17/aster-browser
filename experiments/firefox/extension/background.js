"use strict";
const C = AsterCore;
let state, stateError, blocked = 0, chain = Promise.resolve();
let shouldBlock = () => false;
const readers = new Map();
const ready = browser.storage.local.get("aster").then(data => {
  state = C.validateState(data.aster || {version: 1, settings: C.DEFAULTS, parked: [], workspaces: []});
  shouldBlock = C.blocker(state.settings);
}).catch(error => { stateError = "Saved Aster data could not be read. It has been preserved. " + error.message; });
function check() { if (stateError) throw Error(stateError); }
function change(edit) {
  const task = chain.then(async () => {
    await ready; check();
    const next = structuredClone(state);
    const result = await edit(next);
    const valid = C.validateState(next);
    await browser.storage.local.set({aster: valid});
    state = valid; shouldBlock = C.blocker(state.settings);
    return result;
  });
  chain = task.catch(() => {});
  return task;
}
const parkedURL = id => browser.runtime.getURL("parked.html") + "?id=" + encodeURIComponent(id);
async function openDashboard() { return browser.tabs.create({url: browser.runtime.getURL("dashboard.html")}); }
async function park(id) {
  const tab = await browser.tabs.get(id);
  if (tab.pinned || tab.audible) throw Error("Unpin the tab or stop its audio before parking it.");
  const record = C.snapshot(tab);
  // Persist first. A navigation failure must never lose the original address.
  await change(next => { next.parked.push(record); });
  await browser.tabs.update(id, {url: parkedURL(record.id)});
  return record;
}
async function containerOptions(cookieStoreId) {
  if (cookieStoreId === "firefox-default") return {};
  if (!browser.contextualIdentities || !await browser.permissions.contains({permissions: ["cookies"]})) throw Error("Enable containers before restoring this page. Its identity will not be changed.");
  try { await browser.contextualIdentities.get(cookieStoreId); } catch { throw Error("The original container is missing. Recreate it or open the saved address manually."); }
  return {cookieStoreId};
}
async function restore(id) {
  await ready; check();
  const record = state.parked.find(r => r.id === id);
  if (!record) throw Error("This saved page no longer exists.");
  const existing = (await browser.tabs.query({})).find(t => t.url === parkedURL(id));
  if (existing) {
    // Updating the existing tab preserves its cookie container.
    await browser.tabs.update(existing.id, {url: record.url, active: true});
  } else {
    await browser.tabs.create({url: record.url, ...await containerOptions(record.cookieStoreId)});
  }
  await change(next => { next.parked = next.parked.filter(r => r.id !== id); });
}
async function readPage(id) {
  const tab = await browser.tabs.get(id);
  if (!C.webURL(tab.url)) throw Error("Open a web article first.");
  if (tab.incognito) throw Error("Reading snapshots are unavailable in private tabs.");
  const result = await browser.tabs.executeScript(id, {file: "extract.js", allFrames: false});
  const article = result && result[0];
  if (!article || !article.text || article.text.length < 80) throw Error("There is not enough readable text on this page.");
  if (readers.size >= 10) readers.delete(readers.keys().next().value);
  const key = crypto.randomUUID();
  readers.set(key, {title: String(article.title).slice(0, 500), text: String(article.text).slice(0, 100000), url: tab.url, created: Date.now()});
  // Page content stays in memory, expires after ten minutes, and is never sent to a model.
  await browser.tabs.create({url: browser.runtime.getURL("reader.html") + "?id=" + key});
}
async function containers() {
  if (!browser.contextualIdentities || !await browser.permissions.contains({permissions: ["cookies"]})) return [];
  try { return await browser.contextualIdentities.query({}); } catch { return []; }
}
async function dispatch(m) {
  await ready; check();
  if (!m || typeof m.type !== "string") throw Error("Invalid request.");
  switch (m.type) {
    case "state": return {state, blocked, tabs: (await browser.tabs.query({currentWindow: true})).filter(t => !t.incognito), containers: await containers(), platform: await browser.runtime.getPlatformInfo(), blockingPermission: await browser.permissions.contains({origins: ["http://*/*", "https://*/*"]})};
    case "dashboard": return openDashboard();
    case "park": return park(m.id);
    case "restore": return restore(m.id);
    case "forget": return change(next => { next.parked = next.parked.filter(r => r.id !== m.id); });
    case "settings": return change(next => { next.settings = C.settings(m.settings); });
    case "import": {
      const imported = C.validateState(m.data);
      return change(next => {
        // Merge with fresh identifiers. Never replace the user's current state or blocking settings.
        next.parked.push(...imported.parked.map(r => ({...r, id: crypto.randomUUID()})));
        next.workspaces.push(...imported.workspaces.map(w => ({...w, id: crypto.randomUUID()})));
      });
    }
    case "workspace-save": {
      const tabs = (await browser.tabs.query({currentWindow: true})).filter(t => !t.incognito && C.webURL(t.url));
      if (!tabs.length) throw Error("Open some web pages in this window first.");
      if (tabs.length > 50) throw Error("A workspace can hold up to 50 web tabs.");
      return change(next => { next.workspaces.push({id: crypto.randomUUID(), name: String(m.name || "").trim(), tabs: tabs.map(C.snapshot)}); });
    }
    case "workspace-delete": return change(next => { next.workspaces = next.workspaces.filter(w => w.id !== m.id); });
    case "workspace-restore": {
      const w = state.workspaces.find(w => w.id === m.id);
      if (!w) throw Error("Workspace not found.");
      const records = w.tabs.map(r => ({...r, id: crypto.randomUUID()}));
      const options = await Promise.all(records.map(r => containerOptions(r.cookieStoreId)));
      await change(next => { next.parked.push(...records); });
      // Restore as unloaded placeholders. No saved website is contacted until Resume.
      for (let i = 0; i < records.length; i++) await browser.tabs.create({url: parkedURL(records[i].id), active: false, ...options[i]});
      return {count: records.length};
    }
    case "read": return readPage(m.id);
    case "article": {
      const article = readers.get(m.id);
      if (!article || Date.now() - article.created > 600000) { readers.delete(m.id); throw Error("Reading snapshot expired. Use Read page from the Aster toolbar again."); }
      readers.delete(m.id);
      return article;
    }
    case "container-create": {
      if (!browser.contextualIdentities) throw Error("Containers are unavailable in this Firefox build.");
      if (!await browser.permissions.contains({permissions: ["cookies"]})) throw Error("Enable container permission first.");
      const name = String(m.name || "").trim().slice(0, 80);
      if (!name) throw Error("Name the container first.");
      return browser.contextualIdentities.create({name, color: "green", icon: "briefcase"});
    }
    case "container-open": return browser.tabs.create({url: C.navigate(m.url), ...await containerOptions(m.cookieStoreId)});
    default: throw Error("Unknown Aster action.");
  }
}
browser.runtime.onMessage.addListener((m, sender) => {
  if (sender.id !== browser.runtime.id || !sender.url || !sender.url.startsWith(browser.runtime.getURL(""))) return;
  return dispatch(m).then(value => ({ok: true, value}), error => ({ok: false, error: error.message}));
});
browser.webRequest.onBeforeRequest.addListener(details => ready.then(() => {
  if (!stateError && shouldBlock(details)) { blocked++; return {cancel: true}; }
  return {};
}), {urls: ["http://*/*", "https://*/*"]}, ["blocking"]);
browser.commands.onCommand.addListener(command => { if (command === "open-aster") openDashboard().catch(console.error); });
