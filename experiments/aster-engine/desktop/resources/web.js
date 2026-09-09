/* Aster's bounded asynchronous HTTP/WebSocket transport. Java enforces origin policy. */
(() => {
  'use strict';
  const http=globalThis.__asterHttp;delete globalThis.__asterHttp;
  const {bytes,base64,unbase64,extract,join,resolve,bodies,bodyState,setBody,withBody,deferred,response}=http;
  const pending=new Map(),sockets=new Map(),out=[];let next=1;
  function enqueue(value){if(out.length>=64)throw new RangeError('Page connection queue is full');out.push(value);}
  class AbortSignal extends EventTarget {
    constructor(){super();this.aborted=false;this.reason=undefined;}
    throwIfAborted(){if(this.aborted)throw this.reason;}
    static abort(reason){const c=new AbortController();c.abort(reason);return c.signal;}
  }
  class AbortController {
    constructor(){this.signal=new AbortSignal();}
    abort(reason=new DOMException('The operation was aborted','AbortError')){if(this.signal.aborted)return;this.signal.aborted=true;this.signal.reason=reason;this.signal.dispatchEvent(new Event('abort'));}
  }

  const requests=new WeakMap();
  class Request {
    constructor(input,init={}){
      if(arguments.length===0)throw new TypeError('Request URL is required');
      const original=input instanceof Request?requests.get(input):null;
      const url=original?original.url:resolve(String(input));
      const method=String(init.method===undefined?(original?.method||'GET'):init.method).toUpperCase();
      if(!['GET','HEAD','POST','PUT','PATCH','DELETE','OPTIONS'].includes(method))throw new TypeError('Unsupported request method');
      const mode=String(init.mode===undefined?(original?.mode||'cors'):init.mode),credentials=String(init.credentials===undefined?(original?.credentials||'same-origin'):init.credentials),redirect=String(init.redirect===undefined?(original?.redirect||'follow'):init.redirect);
      if(!['same-origin','cors'].includes(mode)||!['omit','same-origin','include'].includes(credentials)||!['follow','error','manual'].includes(redirect))throw new TypeError('Unsupported request policy');
      for(const key of ['integrity','keepalive'])if(init[key])throw new TypeError(key+' is not implemented');
      if(init.cache!==undefined&&!['default','no-store'].includes(init.cache))throw new TypeError('HTTP cache modes are not implemented');
      if(init.referrer!==undefined&&init.referrer!=='')throw new TypeError('Custom referrers are not implemented');
      const headers=new Headers(init.headers===undefined?(original?.headers||{}):init.headers);
      let value=extract(init.body),source=original?bodies.get(input):null;
      if(init.body==null&&source){if(source.used)throw new TypeError('Request body already consumed');value={bytes:source.bytes?.slice()??null,type:''};}
      if(['GET','HEAD'].includes(method)&&value.bytes!==null)throw new TypeError('GET/HEAD cannot include a body');
      if(value.bytes&&value.bytes.length>262144)throw new RangeError('Fetch body exceeds 256 KiB');
      if(value.type&&!headers.has('content-type'))headers.set('content-type',value.type);
      const signal=init.signal===undefined?(original?.signal||new AbortController().signal):(init.signal===null?new AbortController().signal:init.signal);
      if(!(signal instanceof AbortSignal))throw new TypeError('Expected an AbortSignal');
      if(init.body==null&&source&&source.bytes!==null)source.used=true;
      requests.set(this,{url,method,mode,credentials,redirect,headers,signal});setBody(this,bodyState(value.bytes));
    }
    get url(){return requests.get(this).url;}get method(){return requests.get(this).method;}get mode(){return requests.get(this).mode;}get credentials(){return requests.get(this).credentials;}get redirect(){return requests.get(this).redirect;}get headers(){return requests.get(this).headers;}get signal(){return requests.get(this).signal;}
    get cache(){return 'no-store';}get referrer(){return '';}get referrerPolicy(){return 'no-referrer';}get integrity(){return '';}get keepalive(){return false;}
    clone(){const state=bodies.get(this);if(state.used)throw new TypeError('Request body already consumed');return new Request(this.url,{...requests.get(this),body:state.bytes?.slice()??null});}
  }
  withBody(Request.prototype);Object.defineProperty(Request.prototype,Symbol.toStringTag,{value:'Request',configurable:true});
  function transport(request,observer=null,preflight=false){
    if(pending.size>=16)return Promise.reject(new TypeError('Too many pending fetch requests'));
    const signal=request.signal;try{signal.throwIfAborted();}catch(e){return Promise.reject(e);}
    const state=bodies.get(request);if(state.used)return Promise.reject(new TypeError('Request body already consumed'));if(state.bytes!==null)state.used=true;
    const id=next++;
    return new Promise((resolve,reject)=>{
      const p={resolve,reject,signal,request,observer,parts:[],loaded:0,total:-1,body:null,response:null};
      p.abort=()=>{if(!pending.has(id))return;finish(id,signal.reason===undefined?new DOMException('The operation was aborted','AbortError'):signal.reason);try{enqueue({kind:'abort',id});}catch(e){/* Native request still has a 15-second deadline. */}};
      pending.set(id,p);signal.addEventListener('abort',p.abort,{once:true});
      try{enqueue({kind:'fetch',id,url:request.url,method:request.method,headers:Object.fromEntries(request.headers),body:base64(state.bytes||new Uint8Array()),redirect:request.redirect,credentials:request.credentials,mode:request.mode,preflight});}
      catch(e){finish(id,e);}
    });
  }
  function finish(id,error){const p=pending.get(id);if(!p)return;pending.delete(id);p.signal.removeEventListener('abort',p.abort);
    if(error){p.reject(error);if(p.body){p.body.state.error=error;p.body.reject(error);}p.observer?.error(error);}
    else {const data=join(p.parts);if(p.body){p.body.state.bytes=p.body.state.pending?data:null;p.body.resolve(data);}p.observer?.end(data,p.response);}
    p.parts=[];
  }
  async function fetch(input,init={}){return transport(new Request(input,init));}
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

  Object.assign(globalThis,{AbortSignal,AbortController,Request,fetch,WebSocket});
  globalThis.__asterNetwork=Object.freeze({transport});
  globalThis.__asterWeb=Object.freeze({
    drain(){return out.splice(0);},
    complete(events){for(const e of events){
      if(e.kind.startsWith('fetch-')){
        const p=pending.get(e.id);if(!p)continue;
        if(e.kind==='fetch-error'){finish(e.id,new TypeError(e.error));continue;}
        if(e.kind==='fetch-head'){
          if(p.response)continue;p.body=deferred();p.body.state.pending=!e.nullBody;
          p.response=response(e,p.body.state);p.total=e.length>=0?e.length:-1;p.resolve(p.response);p.observer?.head(p.response);continue;
        }
        if(e.kind==='fetch-chunk'){
          if(!p.response)continue;const b=unbase64(e.body);p.loaded+=b.length;if(p.loaded>1048576){p.abort();continue;}p.parts.push(b);p.observer?.chunk(b,p.loaded,p.total);continue;
        }
        if(e.kind==='fetch-upload'){p.observer?.upload(e.loaded,e.total,!!e.done);continue;}
        if(e.kind==='fetch-end')finish(e.id,null);
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
