/* Shared pure logic. No browser globals, network requests or third-party code. */
(function (root) {
  "use strict";
  const DEFAULTS = Object.freeze({mode: "off", rules: "", allow: "", protectStreaming: true, theme: "system", largeControls: false});
  const BALANCED = ["doubleclick.net", "googlesyndication.com", "googleadservices.com", "scorecardresearch.com", "taboola.com", "outbrain.com"];
  const STREAMING = ["primevideo.com", "amazon.com", "amazon.co.uk", "amazon.de", "amazon.co.jp", "netflix.com", "disneyplus.com", "hulu.com", "max.com", "tv.apple.com", "youtube.com"];
  function webURL(value) {
    if (typeof value !== "string" || /[\s\\\x00-\x1f\x7f]/.test(value)) return false;
    try { const u = new URL(value); return ["http:", "https:"].includes(u.protocol) && !!u.hostname && !u.username && !u.password; } catch { return false; }
  }
  function navigate(value) {
    const text = String(value).trim();
    if (!text) throw Error("Enter an address or search.");
    if (/^[a-z][\w+.-]*:/i.test(text) && !/^(https?:|localhost:\d)/i.test(text)) {
      throw Error("Only HTTP and HTTPS addresses are supported.");
    }
    if (/^https?:/i.test(text)) { if (!webURL(text)) throw Error("Invalid web address."); return text; }
    if (!/\s/.test(text) && (text.includes(".") || /^localhost(?::\d+)?(?:\/|$)/.test(text))) {
      const candidate = (/^(localhost|127\.0\.0\.1)(:|\/|$)/.test(text) ? "http://" : "https://") + text;
      if (!webURL(candidate)) throw Error("Invalid web address.");
      return candidate;
    }
    return "https://duckduckgo.com/?q=" + encodeURIComponent(text);
  }
  function host(value) {
    let input = String(value).trim().toLowerCase().replace(/^\|\|/, "").replace(/\^$/, "");
    if (!input || /[\s/@?#:*\\]/.test(input)) throw Error("Use hostnames only, such as ads.example.com.");
    input = new URL("https://" + input).hostname.replace(/\.$/, "");
    if (!/^(?:[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\.)*[a-z0-9](?:[a-z0-9-]*[a-z0-9])?$/.test(input)) throw Error("Invalid hostname.");
    return input;
  }
  function matches(value, suffix) { return value === suffix || value.endsWith("." + suffix); }
  function parseRules(text) {
    if (typeof text !== "string" || text.length > 100000) throw Error("Rules must be text, at most 100,000 characters.");
    return text.split(/\r?\n/).map(x => x.trim()).filter(x => x && !/^[!#]/.test(x)).map(line => {
      const allow = /^(allow:|@@)/i.test(line);
      line = line.replace(/^(allow:|block:|@@)/i, "");
      return {allow, host: host(line)};
    });
  }
  function settings(value) {
    const s = {...DEFAULTS, ...value};
    if (!["off", "balanced", "strict", "custom"].includes(s.mode) || !["system", "light", "dark"].includes(s.theme)) throw Error("Invalid settings.");
    if (typeof s.protectStreaming !== "boolean" || typeof s.largeControls !== "boolean") throw Error("Invalid settings.");
    parseRules(s.rules); parseRules(s.allow);
    return Object.fromEntries(Object.keys(DEFAULTS).map(k => [k, s[k]]));
  }
  function blocker(input) {
    const s = settings(input);
    const rules = parseRules(s.rules);
    const allow = [...parseRules(s.allow).map(r => r.host), ...rules.filter(r => r.allow).map(r => r.host)];
    const block = [...rules.filter(r => !r.allow).map(r => r.host), ...(s.mode === "custom" ? [] : BALANCED), ...(s.mode === "strict" ? ["connect.facebook.net", "analytics.google.com"] : [])];
    return details => {
      if (s.mode === "off" || details.type === "main_frame" || !webURL(details.url)) return false;
      const target = new URL(details.url).hostname;
      const page = webURL(details.documentUrl || details.originUrl) ? new URL(details.documentUrl || details.originUrl).hostname : "";
      if (allow.some(h => matches(page, h) || matches(target, h))) return false;
      if (s.protectStreaming && STREAMING.some(h => matches(page, h) || matches(target, h))) return false;
      return block.some(h => matches(target, h));
    };
  }
  function snapshot(tab) {
    if (tab.incognito) throw Error("Private tabs are never saved by Aster.");
    if (!webURL(tab.url)) throw Error("Only HTTP and HTTPS pages can be saved.");
    return {id: crypto.randomUUID(), url: tab.url, title: String(tab.title || tab.url).slice(0, 500), cookieStoreId: tab.cookieStoreId || "firefox-default", savedAt: Date.now()};
  }
  function validateRecord(r) {
    if (!r || typeof r.id !== "string" || !/^[\w-]{1,80}$/.test(r.id) || !webURL(r.url) || typeof r.title !== "string" || r.title.length > 500 || !Number.isFinite(r.savedAt) || !/^firefox-(default|container-\d+)$/.test(r.cookieStoreId)) throw Error("Invalid saved page.");
    return {id: r.id, url: r.url, title: r.title, cookieStoreId: r.cookieStoreId, savedAt: r.savedAt};
  }
  function validateState(value) {
    if (!value || value.version !== 1 || !Array.isArray(value.parked) || !Array.isArray(value.workspaces) || value.parked.length > 500 || value.workspaces.length > 50) throw Error("Invalid Aster backup.");
    const parked = value.parked.map(validateRecord);
    if (new Set(parked.map(r => r.id)).size !== parked.length) throw Error("Duplicate saved page identifiers.");
    const workspaces = value.workspaces.map(w => {
      if (!w || typeof w.id !== "string" || !/^[\w-]{1,80}$/.test(w.id) || typeof w.name !== "string" || !w.name.trim() || w.name.length > 80 || !Array.isArray(w.tabs) || w.tabs.length > 50) throw Error("Invalid workspace.");
      return {id: w.id, name: w.name, tabs: w.tabs.map(validateRecord)};
    });
    if (new Set(workspaces.map(w => w.id)).size !== workspaces.length) throw Error("Duplicate workspace identifiers.");
    return {version: 1, settings: settings(value.settings), parked, workspaces};
  }
  function summarize(text, count = 5) {
    const sentences = String(text).slice(0, 100000).match(/[^.!?\n]+(?:[.!?]+|$)/g) || [];
    const candidates = sentences.map((s, i) => ({text: s.trim(), i})).filter(s => s.text.length > 45);
    const words = s => s.toLowerCase().match(/[\p{L}]{4,}/gu) || [];
    const freq = new Map();
    for (const s of candidates) for (const w of new Set(words(s.text))) freq.set(w, (freq.get(w) || 0) + 1);
    for (const s of candidates) s.score = words(s.text).reduce((a, w) => a + Math.log(1 + freq.get(w)), 0) / Math.sqrt(s.text.length);
    return candidates.sort((a, b) => b.score - a.score).slice(0, count).sort((a, b) => a.i - b.i).map(s => s.text);
  }
  const api = {DEFAULTS, webURL, navigate, host, matches, parseRules, settings, blocker, snapshot, validateState, summarize};
  root.AsterCore = api;
  if (typeof module !== "undefined") module.exports = api;
})(globalThis);
