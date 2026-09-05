const test = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const path = require('node:path');

function harness(initial, failWrite = false) {
  let receive, saved = initial, writes = 0, updates = 0, created = 0;
  const tabs = [{id:1, url:'https://example.com/', title:'Example', cookieStoreId:'firefox-default'}];
  const noop = {addListener() {}};
  const browser = {
    storage:{local:{get:async()=>({aster:saved}), set:async data=>{ if(failWrite) throw Error('Disk full'); saved=structuredClone(data.aster); writes++; }}},
    runtime:{id:'aster-test', getURL:p=>'moz-extension://aster/'+p, getPlatformInfo:async()=>({os:'linux',arch:'x86-64'}), onMessage:{addListener:f=>{receive=f;}}},
    tabs:{query:async()=>tabs, get:async id=>tabs.find(t=>t.id===id), update:async()=>{updates++;},create:async()=>{created++;}},
    permissions:{contains:async()=>false}, webRequest:{onBeforeRequest:noop},commands:{onCommand:noop}
  };
  const context = vm.createContext({browser, URL, structuredClone, crypto:globalThis.crypto, console});
  for (const name of ['core.js','background.js']) vm.runInContext(fs.readFileSync(path.join(__dirname,'../extension',name),'utf8'),context);
  const send = (type, rest={})=>receive({type,...rest},{id:'aster-test',url:'moz-extension://aster/dashboard.html'});
  return {send,receive:()=>receive,tabs,counts:()=>({writes,updates,created}),saved:()=>saved};
}
test('concurrent workspace writes retain both user actions', async()=>{
  const h=harness();
  const result=await Promise.all([h.send('workspace-save',{name:'One'}),h.send('workspace-save',{name:'Two'})]);
  assert.ok(result.every(r=>r.ok));
  assert.deepEqual(h.saved().workspaces.map(w=>w.name),['One','Two']);
});
test('failed storage never unloads a tab or mutates the live state', async()=>{
  const h=harness(undefined,true);
  const result=await h.send('park',{id:1});
  assert.equal(result.ok,false);
  assert.match(result.error,/Disk full/);
  assert.equal(h.counts().updates,0);
  assert.equal((await h.send('state')).value.state.parked.length,0);
});
test('corrupt storage blocks mutations and preserves the original value', async()=>{
  const initial={version:99,parked:'unreadable'};
  const h=harness(initial);
  assert.equal((await h.send('workspace-save',{name:'New'})).ok,false);
  assert.equal(h.counts().writes,0);
  assert.deepEqual(h.saved(),initial);
});
test('private, pinned and audible tabs cannot be parked', async()=>{
  for(const key of ['incognito','pinned','audible']) {
    const h=harness();h.tabs[0][key]=true;
    assert.equal((await h.send('park',{id:1})).ok,false);
    assert.deepEqual(h.counts(),{writes:0,updates:0,created:0});
  }
});
test('foreign and content-page messages cannot invoke privileged actions', async()=>{
  const h=harness();
  assert.equal(h.receive()({type:'park',id:1},{id:'other',url:'moz-extension://aster/dashboard.html'}),undefined);
  assert.equal(h.receive()({type:'park',id:1},{id:'aster-test',url:'https://example.com/'}),undefined);
  assert.equal(h.counts().updates,0);
});
test('a missing container never restores a page into default cookies', async()=>{
  const h=harness({version:1,settings:{},workspaces:[],parked:[{id:'saved-one',url:'https://example.com/',title:'Example',cookieStoreId:'firefox-container-8',savedAt:1}]});
  const result=await h.send('restore',{id:'saved-one'});
  assert.equal(result.ok,false);
  assert.match(result.error,/container/i);
  assert.equal(h.counts().created,0);
  assert.equal((await h.send('state')).value.state.parked.length,1);
});
test('a copied parked-page URL cannot change the saved cookie identity', async()=>{
  const h=harness({version:1,settings:{},workspaces:[],parked:[{id:'saved-one',url:'https://example.com/',title:'Example',cookieStoreId:'firefox-container-8',savedAt:1}]});
  h.tabs[0].url='moz-extension://aster/parked.html?id=saved-one';
  const result=await h.send('restore',{id:'saved-one'});
  assert.equal(result.ok,false);
  assert.equal(h.counts().updates,0);
  assert.equal(h.counts().created,0);
});
