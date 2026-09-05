const test = require('node:test');
const assert = require('node:assert/strict');
const C = require('../extension/core.js');
test('navigation rejects executable schemes, credentials and misleading URLs', () => {
  for (const url of ['javascript:alert(1)', 'data:text/html,hi', 'file:///tmp/a', 'https://user:pass@example.com', 'https://example.com\\@evil.test', 'https://example.com:99999']) assert.throws(() => C.navigate(url));
  assert.equal(C.navigate('example.com'), 'https://example.com');
  assert.equal(C.navigate('localhost:8080/test'), 'http://localhost:8080/test');
  assert.equal(C.navigate('some words'), 'https://duckduckgo.com/?q=some%20words');
});
test('blocking respects hostname boundaries, page exceptions and streaming', () => {
  const block = C.blocker({...C.DEFAULTS, mode:'balanced', rules:'block:ads.example.com\n@@||safe.ads.example.com^', allow:'news.example.com'});
  const request = (url, documentUrl = 'https://normal.test/') => ({url, documentUrl, type:'script'});
  assert.equal(block(request('https://ads.example.com/ad.js')), true);
  assert.equal(block(request('https://safe.ads.example.com/ad.js')), false);
  assert.equal(block(request('https://notads.example.com/ad.js')), false);
  assert.equal(block(request('https://ads.example.com.evil.test/ad.js')), false);
  assert.equal(block(request('https://ads.example.com/ad.js', 'https://news.example.com/')), false);
  assert.equal(block(request('https://ads.example.com/ad.js', 'https://primevideo.com/')), false);
  assert.equal(block(request('https://ads.example.com/ad.js', 'https://fakeprimevideo.com/')), true);
  assert.equal(block({...request('https://ads.example.com/'), type:'main_frame'}), false);
});
test('custom blocking and explicit streaming override are separate', () => {
  const req = {url:'https://doubleclick.net/ad', documentUrl:'https://primevideo.com/', type:'script'};
  assert.equal(C.blocker({...C.DEFAULTS, mode:'custom'})(req), false);
  assert.equal(C.blocker({...C.DEFAULTS, mode:'balanced', protectStreaming:false})(req), true);
  assert.equal(C.blocker(C.DEFAULTS)(req), false);
});
test('unsupported rules fail visibly rather than changing their meaning', () => {
  for (const rules of ['||ads.test^$script', '/banner.js', '*.test', 'https://ads.test/path']) assert.throws(() => C.parseRules(rules));
  assert.deepEqual(C.parseRules('! comment\nblock:ads.test\nallow:cdn.test'), [{host:'ads.test', allow:false}, {host:'cdn.test', allow:true}]);
});
test('private pages are never persisted and containers survive snapshots', () => {
  assert.throws(() => C.snapshot({incognito:true, url:'https://example.com'}));
  assert.throws(() => C.snapshot({url:'about:config'}));
  const r = C.snapshot({url:'https://example.com', title:'Example', cookieStoreId:'firefox-container-2'});
  const valid = C.validateState({version:1, settings:C.DEFAULTS, parked:[r], workspaces:[]});
  assert.equal(valid.parked[0].cookieStoreId, 'firefox-container-2');
});
test('backups reject corrupt or executable records before import', () => {
  const r = C.snapshot({url:'https://example.com'});
  const value = {version:1, settings:C.DEFAULTS, parked:[r], workspaces:[]};
  assert.throws(() => C.validateState({...value, parked:[{...r, url:'javascript:alert(1)'}]}));
  assert.throws(() => C.validateState({...value, parked:[r,r]}));
  assert.throws(() => C.validateState({...value, parked:[{...r, cookieStoreId:'firefox-private'}]}));
  assert.throws(() => C.validateState({...value, settings:{...C.DEFAULTS, mode:'mystery'}}));
  assert.throws(() => C.validateState({...value, workspaces:[{id:'x', name:'', tabs:[]}]}));
});
test('local highlights are excerpts and retain original order', () => {
  const sentences = ['Aster workspaces keep saved browser tabs together for later reading.', 'Aster parked tabs wait until the reader chooses to resume the browser page.', 'A third independent sentence contains enough words for a useful selection.'];
  const summary = C.summarize(sentences.join(' '), 2);
  assert.equal(summary.length, 2);
  assert.ok(summary.every(x => sentences.includes(x)));
  assert.ok(sentences.indexOf(summary[0]) < sentences.indexOf(summary[1]));
});
