/* Aster's bounded, asynchronous page network API. Policy is enforced again in Java. */
(() => {
  'use strict';
  const pending=new Map(), sockets=new Map(), out=[];
  let next=1;
  function enqueue(value) { if(out.length>=64)throw new RangeError('Page connection queue is full');out.push(value); }
  class DOMException extends Error { constructor(message='',name='Error'){super(message);this.name=String(name);} }
  class TextEncoder {
    get encoding(){return 'utf-8';}
    encode(value=''){
      const bytes=[];for(const char of String(value)){let c=char.codePointAt(0);if(c>=0xd800&&c<=0xdfff)c=0xfffd;
        if(c<128)bytes.push(c);else if(c<2048)bytes.push(192|(c>>6),128|(c&63));else if(c<65536)bytes.push(224|(c>>12),128|((c>>6)&63),128|(c&63));else bytes.push(240|(c>>18),128|((c>>12)&63),128|((c>>6)&63),128|(c&63));
        if(bytes.length>1048576)throw new RangeError('Text encoding limit');
      }return new Uint8Array(bytes);
    }
  }
  function bytes(value){if(value instanceof ArrayBuffer)return new Uint8Array(value);if(ArrayBuffer.isView(value))return new Uint8Array(value.buffer,value.byteOffset,value.byteLength);throw new TypeError('Expected an ArrayBuffer or view');}
  class TextDecoder {
    constructor(label='utf-8',options={}){if(!['utf-8','utf8','unicode-1-1-utf-8'].includes(String(label).trim().toLowerCase()))throw new RangeError('Only UTF-8 decoding is implemented');this.fatal=!!options.fatal;this.ignoreBOM=!!options.ignoreBOM;}
    get encoding(){return 'utf-8';}
    decode(value=new Uint8Array(),options={}){
      if(options.stream)throw new TypeError('Streaming TextDecoder is not implemented');const b=bytes(value);let text='';
      const invalid=()=>{if(this.fatal)throw new TypeError('Invalid UTF-8');return '\ufffd';};
      for(let i=0;i<b.length;){const first=b[i++];if(first<128){text+=String.fromCharCode(first);continue;}
        let count=first>=0xc2&&first<=0xdf?1:first>=0xe0&&first<=0xef?2:first>=0xf0&&first<=0xf4?3:0;
        if(!count){text+=invalid();continue;}let cp=first&((1<<(6-count))-1),valid=true;
        for(let j=0;j<count;j++){const c=b[i];if(c===undefined||(c&0xc0)!==0x80||(j===0&&((first===0xe0&&c<0xa0)||(first===0xed&&c>=0xa0)||(first===0xf0&&c<0x90)||(first===0xf4&&c>=0x90)))){valid=false;break;}cp=(cp<<6)|(c&63);i++;}
        text+=valid?String.fromCodePoint(cp):invalid();
      }return !this.ignoreBOM&&text.charCodeAt(0)===0xfeff?text.slice(1):text;
    }
  }
  const abc='ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';
  function base64(b){let s='';for(let i=0;i<b.length;i+=3){const n=(b[i]<<16)|((b[i+1]||0)<<8)|(b[i+2]||0);s+=abc[n>>>18]+abc[(n>>>12)&63]+(i+1<b.length?abc[(n>>>6)&63]:'=')+(i+2<b.length?abc[n&63]:'=');}return s;}
  function unbase64(text){text=String(text).replace(/[\t\n\f\r ]/g,'');if(text.length%4===0)text=text.replace(/={1,2}$/,'');if(text.length%4===1||/[^A-Za-z0-9+/]/.test(text))throw new DOMException('Invalid base64','InvalidCharacterError');const out=[];let n=0,bits=0;for(const c of text){n=(n<<6)|abc.indexOf(c);bits+=6;if(bits>=8){bits-=8;out.push((n>>bits)&255);}}return new Uint8Array(out);}
  class AbortSignal extends EventTarget {
    constructor(){super();this.aborted=false;this.reason=undefined;}
    throwIfAborted(){if(this.aborted)throw this.reason;}
    static abort(reason){const c=new AbortController();c.abort(reason);return c.signal;}
  }
  class AbortController {
    constructor(){this.signal=new AbortSignal();}
    abort(reason=new DOMException('The operation was aborted','AbortError')){if(this.signal.aborted)return;this.signal.aborted=true;this.signal.reason=reason;this.signal.dispatchEvent(new Event('abort'));}
  }
  class Headers {
    constructor(init={}){this._values=new Map();if(init instanceof Headers||Array.isArray(init)){for(const [k,v] of init)this.append(k,v);}else for(const [k,v] of Object.entries(init))this.append(k,v);}
    _name(name){name=String(name).toLowerCase();if(!/^[!#$%&'*+.^_`|~0-9a-z-]+$/.test(name))throw new TypeError('Invalid HTTP header name');return name;}
    set(name,value){name=this._name(name);value=String(value).trim();if(/[\r\n\0]/.test(value)||value.length>8192||this._values.size>=64&&!this._values.has(name))throw new TypeError('Invalid or oversized HTTP header');this._values.set(name,value);}
    append(name,value){const previous=this.get(name);this.set(name,previous===null?value:previous+', '+value);}
    get(name){return this._values.get(this._name(name))??null;}has(name){return this._values.has(this._name(name));}delete(name){this._values.delete(this._name(name));}
    entries(){return this._values.entries();}keys(){return this._values.keys();}values(){return this._values.values();}[Symbol.iterator](){return this.entries();}
    forEach(fn,thisArg){for(const [k,v] of this)fn.call(thisArg,v,k,this);}
  }
  const bodies=new WeakMap();
  class Response {
    constructor(body=null,init={}){
      this.status=init.status??200;if(!Number.isInteger(this.status)||this.status<200||this.status>599)throw new RangeError('Invalid response status');
      this.statusText=String(init.statusText||'');this.headers=new Headers(init.headers);this.url=String(init.url||'');this.redirected=!!init.redirected;this.type='basic';this.bodyUsed=false;
      bodies.set(this,body===null?new Uint8Array():typeof body==='string'?new TextEncoder().encode(body):bytes(body).slice());
    }
    get ok(){return this.status>=200&&this.status<300;}
    async arrayBuffer(){if(this.bodyUsed)throw new TypeError('Response body has already been consumed');this.bodyUsed=true;return bodies.get(this).slice().buffer;}
    async text(){return new TextDecoder().decode(await this.arrayBuffer());}
    async json(){return JSON.parse(await this.text());}
    clone(){if(this.bodyUsed)throw new TypeError('Response body has already been consumed');return new Response(bodies.get(this),this);}
  }
  async function fetch(input,init={}){
    if(pending.size>=16)throw new TypeError('Too many pending fetch requests');
    if(typeof input!=='string')throw new TypeError('This preview fetch accepts a URL string');
    if(init.mode&&init.mode!=='same-origin'&&init.mode!=='cors')throw new TypeError('Unsupported fetch mode');
    if(init.credentials&&!['omit','same-origin','include'].includes(init.credentials))throw new TypeError('Invalid credentials mode');
    if(init.redirect&&init.redirect!=='follow'&&init.redirect!=='error')throw new TypeError('Manual redirects are not implemented');
    for(const key of ['integrity','keepalive'])if(init[key])throw new TypeError(key+' is not implemented');
    const method=String(init.method||'GET').toUpperCase(),headers=new Headers(init.headers);
    if(!['GET','HEAD','POST','PUT','PATCH','DELETE','OPTIONS'].includes(method))throw new TypeError('Unsupported fetch method');
    let body=new Uint8Array();if(init.body!==undefined&&init.body!==null){body=typeof init.body==='string'?new TextEncoder().encode(init.body):bytes(init.body);if(typeof init.body==='string'&&!headers.has('content-type'))headers.set('content-type','text/plain;charset=UTF-8');}
    if(body.length>262144)throw new RangeError('Fetch body exceeds 256 KiB');if(['GET','HEAD'].includes(method)&&init.body!=null)throw new TypeError('GET/HEAD cannot include a body');
    const signal=init.signal;if(signal!==undefined&&!(signal instanceof AbortSignal))throw new TypeError('Expected an AbortSignal');if(signal)signal.throwIfAborted();
    const id=next++;
    return new Promise((resolve,reject)=>{
      const abort=()=>{if(!pending.delete(id))return;reject(signal.reason);enqueue({kind:'abort',id});};
      pending.set(id,{resolve,reject,signal,abort});if(signal)signal.addEventListener('abort',abort,{once:true});
      try{enqueue({kind:'fetch',id,url:input,method,headers:Object.fromEntries(headers),body:base64(body),redirect:init.redirect||'follow',credentials:init.credentials||'same-origin'});}catch(e){pending.delete(id);if(signal)signal.removeEventListener('abort',abort);reject(e);}
    });
  }
  class WebSocket extends EventTarget {
    constructor(url,protocols=[]){
      super();if(typeof protocols==='string')protocols=[protocols];if(!Array.isArray(protocols)||protocols.length>16||new Set(protocols).size!==protocols.length||protocols.some(p=>typeof p!=='string'||!/^[!#$%&'*+.^_`|~0-9A-Za-z-]{1,128}$/.test(p)))throw new DOMException('Invalid WebSocket protocols','SyntaxError');
      url=String(url);if(url.includes('#')||url.length>8192)throw new DOMException('Invalid WebSocket URL','SyntaxError');
      if(sockets.size>=4)throw new RangeError('At most four WebSockets may run');
      this.url=url;this.protocol='';this.extensions='';this.readyState=0;this.bufferedAmount=0;this._id=next++;this._binaryType='arraybuffer';
      enqueue({kind:'ws-open',id:this._id,url,protocols});sockets.set(this._id,this);
    }
    get binaryType(){return this._binaryType;}set binaryType(v){if(v!=='arraybuffer')throw new TypeError('Only arraybuffer WebSocket messages are implemented');this._binaryType=v;}
    send(data){
      if(this.readyState===0)throw new DOMException('WebSocket is connecting','InvalidStateError');
      const binary=typeof data!=='string',encoded=binary?bytes(data):new TextEncoder().encode(data);
      if(encoded.length>262144||this.bufferedAmount+encoded.length>262144)throw new RangeError('WebSocket send buffer exceeds 256 KiB');
      if(this.readyState!==1){this.bufferedAmount+=encoded.length;return;}
      enqueue({kind:'ws-send',id:this._id,binary,data:binary?base64(encoded):data});this.bufferedAmount+=encoded.length;
    }
    close(code=1000,reason=''){
      if(code!==1000&&(!Number.isInteger(code)||code<3000||code>4999))throw new DOMException('Invalid WebSocket close code','InvalidAccessError');
      reason=String(reason);if(new TextEncoder().encode(reason).length>123)throw new DOMException('Close reason too long','SyntaxError');
      if(this.readyState>=2)return;enqueue({kind:'ws-close',id:this._id,code,reason});this.readyState=2;
    }
  }
  for(const [name,value] of Object.entries({CONNECTING:0,OPEN:1,CLOSING:2,CLOSED:3})){Object.defineProperty(WebSocket,name,{value});Object.defineProperty(WebSocket.prototype,name,{value});}
  Object.assign(globalThis,{DOMException,TextEncoder,TextDecoder,AbortSignal,AbortController,Headers,Response,fetch,WebSocket,
    btoa(value){const s=String(value),b=new Uint8Array(s.length);for(let i=0;i<s.length;i++){if(s.charCodeAt(i)>255)throw new DOMException('Expected Latin-1 text','InvalidCharacterError');b[i]=s.charCodeAt(i);}return base64(b);},
    atob(value){const b=unbase64(value);let s='';for(const c of b)s+=String.fromCharCode(c);return s;}});
  globalThis.__asterWeb=Object.freeze({
    drain(){return out.splice(0);},
    complete(events){for(const e of events){
      if(e.kind.startsWith('fetch-')){const p=pending.get(e.id);if(!p)continue;pending.delete(e.id);if(p.signal)p.signal.removeEventListener('abort',p.abort);
        if(e.kind==='fetch-error')p.reject(new TypeError(e.error));else p.resolve(new Response(unbase64(e.body),{status:e.status,headers:e.headers,url:e.url,redirected:e.redirected}));
        continue;
      }
      const ws=sockets.get(e.id);if(!ws)continue;
      if(e.kind==='ws-open'){if(ws.readyState!==0)continue;ws.readyState=1;ws.protocol=e.protocol;ws.dispatchEvent(new Event('open'));}
      else if(e.kind==='ws-text'||e.kind==='ws-binary'){if(ws.readyState===1)ws.dispatchEvent(new Event('message',{data:e.kind==='ws-text'?e.data:unbase64(e.data).buffer,origin:document.URL}));}
      else if(e.kind==='ws-sent')ws.bufferedAmount=Math.max(0,ws.bufferedAmount-e.bytes);
      else if(e.kind==='ws-close'||e.kind==='ws-error'){ws.readyState=3;sockets.delete(e.id);if(e.kind==='ws-error')ws.dispatchEvent(new Event('error'));ws.dispatchEvent(new Event('close',{code:e.code||1006,reason:e.reason||'',wasClean:!!e.clean}));}
    }return null;}
  });
})();
