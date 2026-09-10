/* Aster DOM/event preview. QuickJS supplies ECMAScript, not browser APIs.
 * Networking and media use explicit bounded brokers; no browser engine is embedded. */
(() => {
  'use strict';
  const nativePads = globalThis.__asterReadGamepads;
  delete globalThis.__asterReadGamepads;
  const records = new Map(), timers = new Map(), errors = [];
  let revision=0, renderedRevision=-1, deliveredRevision=-1, cachedHTML='';
  const changed=()=>{revision++;};
  const mediaCommands=[];let mediaRequest=1;
  function mediaCommand(value){if(mediaCommands.length>=32)throw new RangeError('Too many media commands');mediaCommands.push(value);}
  const voids = new Set('area base br col embed hr img input link meta param source track wbr'.split(' '));
  const hidden = new Set(['script', 'style', 'head', 'template', 'iframe', 'object']);
  let nextId = 1, timerId = 1, clock = 0, title = '', navigation = null, initialScripts = [], body;
  const warn = text => { if (errors.length < 12) errors.push(String(text).slice(0, 300)); };
  const escape = text => String(text).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
  const entity = text => String(text).replace(/&(#x[0-9a-f]+|#\d+|amp|lt|gt|quot|apos|nbsp);/gi, (m, n) => {
    if (n[0] !== '#') return ({amp:'&',lt:'<',gt:'>',quot:'"',apos:"'",nbsp:'\u00a0'})[n] || m;
    let cp = n[1].toLowerCase() === 'x' ? parseInt(n.slice(2),16) : +n.slice(1);
    return String.fromCodePoint(cp > 0 && cp <= 0x10ffff && !(cp >= 0xd800 && cp <= 0xdfff) ? cp : 0xfffd);
  });
  class AsterEvent {
    constructor(type, init = {}) { this.type = String(type); Object.assign(this, init); this.defaultPrevented = false; this.cancelBubble = false; }
    preventDefault() { this.defaultPrevented = true; }
    stopPropagation() { this.cancelBubble = true; }
  }
  class Target {
    constructor() { this._listeners = Object.create(null); }
    addEventListener(type, fn, options) {
      if (typeof fn !== 'function') return;
      const list = this._listeners[type] || (this._listeners[type] = []);
      if (list.length >= 64) throw new RangeError('Listener limit reached');
      if (!list.some(x => x.fn === fn)) list.push({fn, once:!!(options && options.once)});
    }
    removeEventListener(type, fn) { this._listeners[type] = (this._listeners[type] || []).filter(x => x.fn !== fn); }
    dispatchEvent(event) {
      if (!event.target) event.target = this;
      event.currentTarget = this;
      for (const item of [...(this._listeners[event.type] || [])]) {
        if (item.once) this.removeEventListener(event.type, item.fn);
        try { item.fn.call(this,event); } catch (e) { warn(e); }
      }
      const handler = this['on' + event.type];
      if (typeof handler === 'function') { try { if(handler.call(this,event) === false) event.preventDefault(); } catch(e) { warn(e); } }
      if (event.bubbles && !event.cancelBubble && this.parentNode) this.parentNode.dispatchEvent(event);
      return !event.defaultPrevented;
    }
  }
  class Node extends Target {
    constructor(tag, text = '') {
      super(); if (records.size >= 10000) throw new RangeError('DOM node limit reached');
      this._id = nextId++; records.set(this._id,this); this.nodeName = tag === '#text' ? tag : tag.toUpperCase();
      this.nodeType = tag === '#text' ? 3 : 1; this.tagName = this.nodeName;
      this.childNodes = []; this.parentNode = null; this._text = text; this._attrs = Object.create(null);
      this.style = new Proxy(Object.create(null), {set(target,key,value){if(target[key]!==value){target[key]=value;changed();}return true;},deleteProperty(target,key){if(Object.hasOwn(target,key)){delete target[key];changed();}return true;}});
    }
    get children() { return this.childNodes.filter(x => x.nodeType === 1); }
    get firstChild() { return this.childNodes[0] || null; }
    get textContent() { return this.nodeType === 3 ? this._text : this.childNodes.map(x => x.textContent).join(''); }
    set textContent(text) {
      text = String(text); if(text.length > 1000000) throw new RangeError('DOM text limit reached');
      if(this.textContent===text)return;changed();
      if(this.nodeType === 3) { this._text = text; return; }
      if(this.childNodes.length===1 && this.childNodes[0].nodeType===3) { this.childNodes[0]._text=text; return; }
      for(const child of this.childNodes) child.parentNode = null;
      this.childNodes = []; if(text) this.appendChild(new Node('#text',text));
    }
    get innerText() { return this.textContent; } set innerText(s) { this.textContent = s; }
    get id() { return this.getAttribute('id') || ''; } set id(s) { this.setAttribute('id',s); }
    get className() { return this.getAttribute('class') || ''; } set className(s) { this.setAttribute('class',s); }
    get href() { return this.getAttribute('href') || ''; } set href(s) { this.setAttribute('href',s); }
    get src() { return this.getAttribute('src') || ''; } set src(s) { this.setAttribute('src',s); }
    getAttribute(name) { return Object.hasOwn(this._attrs,String(name).toLowerCase()) ? this._attrs[String(name).toLowerCase()] : null; }
    hasAttribute(name) { return this.getAttribute(name) !== null; }
    setAttribute(name, value) {
      name = String(name).toLowerCase(); value = String(value);
      if(!/^[a-z][a-z0-9:_-]*$/.test(name) || value.length > 8192) throw new TypeError('Unsupported attribute');
      if(Object.keys(this._attrs).length >= 64 && !this.hasAttribute(name)) throw new RangeError('Attribute limit reached');
      if(this._attrs[name]!==value){this._attrs[name] = value;changed();}
    }
    removeAttribute(name) { name=String(name).toLowerCase();if(Object.hasOwn(this._attrs,name)){delete this._attrs[name];changed();} }
    appendChild(child) {
      if(!(child instanceof Node)) throw new TypeError('Expected a Node');
      let depth=0; for(let p=this;p;p=p.parentNode) { if(p===child) throw new TypeError('DOM cycle'); if(++depth>64) throw new RangeError('DOM depth limit'); }
      if(child.parentNode) child.parentNode.removeChild(child);
      child.parentNode=this; this.childNodes.push(child); changed();return child;
    }
    append(...nodes) { for(const n of nodes) this.appendChild(n instanceof Node ? n : new Node('#text',String(n))); }
    removeChild(child) { const i=this.childNodes.indexOf(child); if(i<0) throw new TypeError('Not a child'); this.childNodes.splice(i,1); child.parentNode=null;changed(); return child; }
    remove() { if(this.parentNode) this.parentNode.removeChild(this); }
    querySelectorAll(selector) {
      selector=String(selector); let test;
      if(/^#[\w-]+$/.test(selector)) test=n=>n.id===selector.slice(1);
      else if(/^\.[\w-]+$/.test(selector)) test=n=>n.className.split(/\s+/).includes(selector.slice(1));
      else if(/^[a-z][\w-]*$/i.test(selector)) test=n=>n.tagName===selector.toUpperCase();
      else throw new TypeError('Preview selectors support a tag, #id or .class');
      const out=[]; const walk=(n,depth)=>{ if(depth>64) throw new RangeError('DOM depth limit'); for(const child of n.children) { if(test(child)) out.push(child); walk(child,depth+1); } }; walk(this,0); return out;
    }
    querySelector(s) { return this.querySelectorAll(s)[0] || null; }
    click() { this.dispatchEvent(new AsterEvent('click',{bubbles:true})); }
  }
  class HTMLMediaElement extends Node {
    constructor(tag){super(tag);this._media={currentTime:0,duration:null,paused:true,ended:false,readyState:0,volume:1,muted:false,videoWidth:0,videoHeight:0};this._plays=new Map();this.error=null;}
    get currentSrc(){return this._media.src||'';}
    get currentTime(){return this._media.currentTime;}set currentTime(v){v=Number(v);if(!Number.isFinite(v)||v<0)throw new TypeError('Invalid media time');if(!this._canSeek())throw new DOMException('HLS seeking is not supported in this preview','NotSupportedError');this._media.currentTime=v;mediaCommand({kind:'seek',id:this._id,value:v});}
    _canSeek(){return this._media.seekSupported!==false&&!/\.m3u8(?:[?#]|$)/i.test(this.src||this.querySelector('source')?.src||'');}
    get seekable(){const length=this._canSeek()&&Number.isFinite(this.duration)&&this.duration>0?1:0,end=this.duration;const range=i=>{if(Number(i)!==0||!length)throw new DOMException('No seekable range at this index','IndexSizeError');};return Object.freeze({length,start(i){range(i);return 0;},end(i){range(i);return end;}});}
    get duration(){return this._media.duration??NaN;}get paused(){return this._media.paused;}get ended(){return this._media.ended;}get readyState(){return this._media.readyState;}
    get videoWidth(){return this._media.videoWidth;}get videoHeight(){return this._media.videoHeight;}
    get volume(){return this._media.volume;}set volume(v){v=Number(v);if(!Number.isFinite(v)||v<0||v>1)throw new RangeError('Volume must be between 0 and 1');this._media.volume=v;mediaCommand({kind:'volume',id:this._id,value:v});}
    get muted(){return this._media.muted;}set muted(v){this._media.muted=!!v;mediaCommand({kind:'muted',id:this._id,value:!!v});}
    get controls(){return this.hasAttribute('controls');}set controls(v){if(v)this.setAttribute('controls','');else this.removeAttribute('controls');}
    play(){
      if(this._plays.size>=8)return Promise.reject(new RangeError('Too many pending play requests'));
      const request=mediaRequest++,src=this.src||(this.querySelector('source')?.src)||'';
      return new Promise((resolve,reject)=>{this._plays.set(request,{resolve,reject});try{mediaCommand({kind:'play',id:this._id,request,src,volume:this.volume,muted:this.muted,time:this.currentTime});}catch(e){this._plays.delete(request);reject(e);}});
    }
    pause(){mediaCommand({kind:'pause',id:this._id});}
    load(){mediaCommand({kind:'unload',id:this._id});}
  }
  const makeNode=tag=>tag==='video'||tag==='audio'?new HTMLMediaElement(tag):new Node(tag);
  const document = new Target(), win = new Target();
  const root = new Node('html');
  function parse(source) {
    const stack=[root]; let p=0;
    while(p<source.length) {
      const parent=stack[stack.length-1];
      if(source.startsWith('<!--',p)) { const end=source.indexOf('-->',p+4); p=end<0 ? source.length : end+3; continue; }
      if(source[p]!=='<' || !/[a-z/!?]/i.test(source[p+1]||'')) { let end=source.indexOf('<',p+1); if(end<0) end=source.length; parent.appendChild(new Node('#text',entity(source.slice(p,end)))); p=end; continue; }
      let end=p+1, quote='';
      for(;end<source.length;end++) { const c=source[end]; if(quote) { if(c===quote) quote=''; } else if(c==='"'||c==="'") quote=c; else if(c==='>') break; }
      if(end>=source.length) break;
      const token=source.slice(p+1,end); p=end+1; const match=/^\s*(\/?)\s*([a-z][\w-]*)/i.exec(token); if(!match) continue;
      const tag=match[2].toLowerCase();
      if(match[1]) { for(let i=stack.length-1;i>0;i--) if(stack[i].tagName===tag.toUpperCase()) { stack.length=i; break; } continue; }
      if(tag==='html') continue;
      const n=makeNode(tag); const attrs=token.slice(match[0].length), re=/([^\s=/'">]+)(?:\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+)))?/g; let a;
      while((a=re.exec(attrs))) { try { n.setAttribute(a[1],entity(a[2]??a[3]??a[4]??'')); } catch(e) { warn(e); } }
      parent.appendChild(n);
      if(['script','style','title','template','iframe','object'].includes(tag)) {
        const close=new RegExp('</'+tag+'\\s*>','ig'); close.lastIndex=p; const hit=close.exec(source); const raw=source.slice(p,hit ? hit.index : source.length); p=hit ? close.lastIndex : source.length;
        n.appendChild(new Node('#text',tag==='title' ? entity(raw) : raw));
        if(tag==='title') title=entity(raw).slice(0,160);
        if(tag==='script') {
          const type=(n.getAttribute('type')||'').toLowerCase();
          if(!type || type==='text/javascript' || type==='application/javascript') initialScripts.push({src:n.src,code:raw});
          else if(type==='module') warn('Module scripts are not implemented in this preview');
        }
        continue;
      }
      if(!voids.has(tag) && !/\/\s*$/.test(token)) { if(stack.length>=64) throw new RangeError('DOM depth limit'); stack.push(n); }
    }
    body=root.querySelector('body') || root;
  }
  Object.assign(document,{documentElement:root,createElement:tag=>makeNode(String(tag).toLowerCase()),createTextNode:text=>new Node('#text',String(text)),
    querySelector:s=>root.querySelector(s),querySelectorAll:s=>root.querySelectorAll(s),getElementById:id=>{
      for(const n of records.values()) if(n.nodeType===1 && n.id===String(id)) { let p=n; while(p.parentNode) p=p.parentNode; if(p===root || p===document) return n; } return null;
    }});
  Object.defineProperties(document,{body:{get:()=>body},head:{get:()=>root.querySelector('head')},title:{get:()=>title,set:v=>{v=String(v).slice(0,160);if(v!==title){title=v;changed();}}}});
  root.parentNode=document;
  Object.assign(globalThis,{window:globalThis,self:globalThis,document,EventTarget:Target,Event:AsterEvent,KeyboardEvent:AsterEvent,MouseEvent:AsterEvent,Node,HTMLElement:Node,HTMLMediaElement,HTMLVideoElement:HTMLMediaElement,HTMLAudioElement:HTMLMediaElement,
    addEventListener:win.addEventListener.bind(win),removeEventListener:win.removeEventListener.bind(win),dispatchEvent:win.dispatchEvent.bind(win),
    navigator:Object.freeze({userAgent:'AsterEnginePreview/0.7 QuickJS',getGamepads:()=>nativePads()}),
    performance:Object.freeze({now:()=>clock}),console:Object.freeze({log:(...args)=>warn(args.join(' ')),warn:(...args)=>warn(args.join(' ')),error:(...args)=>warn(args.join(' '))})});
  function schedule(fn,ms,repeat,args) {
    if(typeof fn!=='function') throw new TypeError('Timer callback must be a function');
    if(timers.size>=64) throw new RangeError('Timer limit reached');
    const id=timerId++; ms=Math.max(16,Math.min(60000,Number(ms)||0)); timers.set(id,{fn,when:clock+ms,ms,repeat,args}); return id;
  }
  Object.assign(globalThis,{setTimeout:(fn,ms,...args)=>schedule(fn,ms,false,args),setInterval:(fn,ms,...args)=>schedule(fn,ms,true,args),
    clearTimeout:id=>timers.delete(id),clearInterval:id=>timers.delete(id),requestAnimationFrame:fn=>schedule(()=>fn(clock),16,false,[]),cancelAnimationFrame:id=>timers.delete(id)});
  function snapshot(force=true) {
    const result={html:null,title,errors:[...errors],navigation,media:mediaCommands.splice(0),revision};navigation=null;
    if(!force&&deliveredRevision===revision)return result;
    if(renderedRevision===revision){result.html=cachedHTML;deliveredRevision=revision;return result;}
    let count=0, size=0;
    const render=(n,depth)=>{
      if(++count>10000 || depth>64) throw new RangeError('DOM render limit');
      if(n.nodeType===3) { size+=n._text.length; if(size>1000000) throw new RangeError('DOM text limit'); return escape(n._text); }
      const tag=n.tagName.toLowerCase();
      // Keep the live stylesheet nodes when serializing a scripted page. Escaping
      // '<' as CSS prevents stylesheet text from closing its HTML element.
      if(tag==='head') return n.children.filter(c=>c.tagName==='STYLE'||c.tagName==='LINK').map(c=>render(c,depth+1)).join('');
      if(tag==='style') {
        const css=n.textContent; size+=css.length; if(size>1000000) throw new RangeError('DOM text limit');
        let attrs=''; for(const k of ['id','type','media','disabled']) if(n.hasAttribute(k)) attrs+=' '+k+'="'+escape(n.getAttribute(k))+'"';
        return '<style'+attrs+'>'+css.replace(/</g,'\\3c ')+'</style>';
      }
      if(hidden.has(tag)) return '';
      if(!/^[a-z][a-z0-9-]*$/.test(tag)) return '';
      let attrs=''; for(const k of ['href','src','alt','id','class','style','type','controls','width','height','hidden','rel','media','crossorigin','integrity','disabled']) if(n.hasAttribute(k)) attrs+=' '+k+'="'+escape(n.getAttribute(k))+'"';
      const css=Object.entries(n.style).map(([k,v])=>k.replace(/[A-Z]/g,c=>'-'+c.toLowerCase())+':'+String(v)).join(';');
      if(css) attrs=attrs.replace(/ style="[^"]*"/,'')+' style="'+escape(css)+'"';
      attrs+=' data-aster-action="'+n._id+'"';
      return '<'+tag+attrs+'>'+n.childNodes.map(c=>render(c,depth+1)).join('')+(voids.has(tag)?'':'</'+tag+'>');
    };
    const html='<title>'+escape(title)+'</title>'+render(root,0); if(html.length>1000000) throw new RangeError('Rendered page limit');
    cachedHTML=html;renderedRevision=revision;deliveredRevision=revision;result.html=html;return result;
  }
  globalThis.__aster = Object.freeze({
    init(source,url) { parse(source); root.parentNode=document; document.URL=url; document.documentURI=url; document.readyState='loading';
      if(initialScripts.length>32) throw new RangeError('Script count limit');
      if(root.querySelectorAll('meta').some(n=>(n.getAttribute('http-equiv')||'').toLowerCase()==='content-security-policy')) throw new Error('Pages with CSP await a complete policy implementation; scripts remain disabled');
      return initialScripts;
    },
    ready() { initialScripts=[]; document.readyState='interactive'; document.dispatchEvent(new AsterEvent('DOMContentLoaded')); document.readyState='complete'; win.dispatchEvent(new AsterEvent('load')); return null; },
    snapshot,
    mediaUpdate(id,state,event,error,request) {
      const n=records.get(id);if(!(n instanceof HTMLMediaElement))return;
      Object.assign(n._media,state||{});
      if(error){n.error={code:4,message:error};for(const [key,p] of [...n._plays])if(!request||key===request){p.reject(new DOMException(error,event==='denied'?'NotAllowedError':'NotSupportedError'));n._plays.delete(key);}}
      else if(event==='playing'){for(const p of n._plays.values())p.resolve();n._plays.clear();n.error=null;}
      else if(event==='pause'||event==='emptied'){for(const p of n._plays.values())p.reject(new DOMException('Playback was interrupted','AbortError'));n._plays.clear();}
      if(event!=='denied')n.dispatchEvent(new AsterEvent(event));
    },
    tick(time) { clock=Math.max(clock,Number(time)||0); for(const [id,t] of [...timers]) if(t.when<=clock && timers.has(id)) { if(t.repeat) t.when=clock+t.ms; else timers.delete(id); try { t.fn(...t.args); } catch(e) { warn(e); } } return snapshot(false); },
    click(id) { const n=records.get(id); if(!n) return snapshot(); const e=new AsterEvent('click',{bubbles:true}); n.dispatchEvent(e);
      if(!e.defaultPrevented) for(let p=n;p && p instanceof Node;p=p.parentNode) if(p.tagName==='A' && p.href) { navigation=p.href; break; } return snapshot(); },
    key(type,key,code,repeat) { document.dispatchEvent(new AsterEvent(type,{key,code,repeat,bubbles:true})); win.dispatchEvent(new AsterEvent(type,{key,code,repeat})); return snapshot(); }
  });
})();
