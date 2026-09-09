/* Async XMLHttpRequest over the same CORS/credential broker as fetch. */
(() => {
  'use strict';
  const {transport}=globalThis.__asterNetwork;delete globalThis.__asterNetwork;
  const states=new WeakMap(),types=['','text','json','arraybuffer','blob'];
  class ProgressEvent extends Event {
    constructor(type,init={}){super(type,init);Object.assign(this,{lengthComputable:!!init.lengthComputable,loaded:Math.max(0,Number(init.loaded)||0),total:Math.max(0,Number(init.total)||0)});}
  }
  const progress=(target,type,loaded=0,total=-1)=>target.dispatchEvent(new ProgressEvent(type,{loaded,total:Math.max(0,total),lengthComputable:total>=0}));
  const change=(x,n)=>{states.get(x).ready=n;x.dispatchEvent(new Event('readystatechange'));};
  function reset(s){s.status=0;s.statusText='';s.url='';s.headers=new Headers();s.text='';s.result=null;s.loaded=0;s.total=-1;s.response=null;}
  function active(x,s,token){return states.get(x)===s&&s.token===token&&s.sending;}
  function endError(x,s,token,kind){
    if(!active(x,s,token))return;s.sending=false;clearTimeout(s.timer);reset(s);change(x,4);
    if(s.token!==token)return;
    if(!s.uploadDone){s.uploadDone=true;progress(x.upload,kind);if(s.token!==token)return;progress(x.upload,'loadend');}
    if(s.token!==token)return;progress(x,kind);if(s.token!==token)return;progress(x,'loadend');
    if(kind==='abort'&&s.token===token)s.ready=0;
  }
  function deadline(x,s){clearTimeout(s.timer);if(s.sending&&s.timeout){const remaining=s.timeout-(performance.now()-s.started);s.timer=setTimeout(()=>{if(!s.sending)return;s.failure='timeout';s.controller.abort(new DOMException('Request timed out','TimeoutError'));},Math.max(0,remaining));}}
  class XMLHttpRequest extends EventTarget {
    constructor(){super();states.set(this,{ready:0,token:0,sending:false,type:'',timeout:0,credentials:false,timer:0});reset(states.get(this));Object.defineProperty(this,'upload',{value:new EventTarget(),enumerable:true});}
    get readyState(){return states.get(this).ready;}get status(){const s=states.get(this);return s.ready>=2?s.status:0;}get statusText(){const s=states.get(this);return s.ready>=2?s.statusText:'';}get responseURL(){return states.get(this).url;}
    get responseType(){return states.get(this).type;}set responseType(value){const s=states.get(this);value=String(value);if(s.ready===3||s.ready===4)throw new DOMException('Response already loading','InvalidStateError');if(value==='document')throw new DOMException('XML document responses are not implemented','NotSupportedError');if(types.includes(value))s.type=value;}
    get responseText(){const s=states.get(this);if(s.type!==''&&s.type!=='text')throw new DOMException('Response is not text','InvalidStateError');return s.ready>=3?s.text:'';}
    get response(){const s=states.get(this);return s.type===''||s.type==='text'?this.responseText:s.ready===4?s.result:null;}
    get responseXML(){const s=states.get(this);if(s.type!==''&&s.type!=='document')throw new DOMException('Response is not an XML document','InvalidStateError');return null;}
    get timeout(){return states.get(this).timeout;}set timeout(value){const s=states.get(this);value=Number(value);if(!Number.isFinite(value)||value<0||value>2147483647)throw new TypeError('Invalid timeout');s.timeout=Math.trunc(value);deadline(this,s);}
    get withCredentials(){return states.get(this).credentials;}set withCredentials(value){const s=states.get(this);if(s.ready>1||s.sending)throw new DOMException('Request already sent','InvalidStateError');s.credentials=!!value;}
    open(method,url,async=true,user=null,password=null){
      if(arguments.length<2)throw new TypeError('Method and URL are required');if(!async)throw new DOMException('Synchronous XHR is not implemented','NotSupportedError');if(user!=null||password!=null)throw new DOMException('URL passwords are not implemented','NotSupportedError');
      method=String(method).toUpperCase();if(['CONNECT','TRACE','TRACK'].includes(method))throw new DOMException('Forbidden request method','SecurityError');
      const request=new Request(String(url),{method});const s=states.get(this);const previous=s.ready;
      s.token++;s.controller?.abort();clearTimeout(s.timer);s.sending=false;s.method=method;s.requestURL=request.url;s.requestHeaders=new Headers();s.mime=null;reset(s);s.ready=1;if(previous!==1)this.dispatchEvent(new Event('readystatechange'));
    }
    setRequestHeader(name,value){const s=states.get(this);if(s.ready!==1||s.sending)throw new DOMException('Request is not open','InvalidStateError');s.requestHeaders.append(name,value);}
    getResponseHeader(name){const s=states.get(this);return s.ready>=2?s.headers.get(name):null;}
    getAllResponseHeaders(){const s=states.get(this);return s.ready>=2?[...s.headers].map(([k,v])=>k+': '+v+'\r\n').join(''):'';}
    overrideMimeType(value){const s=states.get(this);if(s.ready===3||s.ready===4)throw new DOMException('Response already loading','InvalidStateError');value=String(value);if(/charset\s*=\s*"?(?!utf-8(?:[";\s]|$))[^;]+/i.test(value))throw new DOMException('Only UTF-8 XHR decoding is implemented','NotSupportedError');s.mime=value;}
    send(body=null){
      const s=states.get(this);if(s.ready!==1||s.sending)throw new DOMException('Request is not open','InvalidStateError');
      s.controller=new AbortController();const request=new Request(s.requestURL,{method:s.method,headers:s.requestHeaders,body:['GET','HEAD'].includes(s.method)?null:body,credentials:s.credentials?'include':'same-origin',signal:s.controller.signal});
      s.sending=true;s.failure='abort';s.started=performance.now();s.uploadDone=body==null||['GET','HEAD'].includes(s.method);s.decoder=new TextDecoder();const token=++s.token;
      const live=()=>active(this,s,token);
      const uploadListeners=['loadstart','progress','load','loadend','abort','error','timeout'].some(k=>typeof this.upload['on'+k]==='function'||(this.upload._listeners[k]||[]).length);
      // Queue before dispatch: abort/open from loadstart cancels this exact request.
      const promise=transport(request,{
        head:r=>{if(!live())return;s.response=r;s.status=r.status;s.statusText=r.statusText;s.url=r.url;s.headers=r.headers;change(this,2);},
        chunk:(bytes,loaded,total)=>{if(!live())return;s.loaded=loaded;s.total=total;if(s.type===''||s.type==='text')s.text+=s.decoder.decode(bytes,{stream:true});change(this,3);if(live())progress(this,'progress',loaded,total);},
        upload:(loaded,total,done)=>{if(!live()||s.uploadDone)return;progress(this.upload,'progress',loaded,total);if(!live())return;if(done){s.uploadDone=true;progress(this.upload,'load',loaded,total);if(live())progress(this.upload,'loadend',loaded,total);}},
        end:(bytes,r)=>{if(!live())return;
          if(s.type===''||s.type==='text')s.text+=s.decoder.decode();
          else if(s.type==='arraybuffer')s.result=bytes.slice().buffer;
          else if(s.type==='blob')s.result=new Blob([bytes],{type:s.mime||r.headers.get('content-type')||''});
          else if(s.type==='json'){try{s.result=JSON.parse(new TextDecoder().decode(bytes));}catch(e){s.result=null;}}
          s.loaded=bytes.length;s.sending=false;clearTimeout(s.timer);change(this,4);if(s.token!==token)return;progress(this,'load',s.loaded,s.total);if(s.token===token)progress(this,'loadend',s.loaded,s.total);
        },
        error:e=>endError(this,s,token,e.name==='AbortError'||e.name==='TimeoutError'?s.failure:'error')
      },uploadListeners);
      promise.catch(e=>endError(this,s,token,'error'));
      progress(this,'loadstart');if(live()&&!s.uploadDone)progress(this.upload,'loadstart');if(live())deadline(this,s);
    }
    abort(){const s=states.get(this);if(s.sending){s.failure='abort';s.controller.abort();}else{s.ready=0;reset(s);}}
  }
  for(const [key,value] of Object.entries({UNSENT:0,OPENED:1,HEADERS_RECEIVED:2,LOADING:3,DONE:4})){Object.defineProperty(XMLHttpRequest,key,{value});Object.defineProperty(XMLHttpRequest.prototype,key,{value});}
  Object.defineProperty(XMLHttpRequest.prototype,Symbol.toStringTag,{value:'XMLHttpRequest',configurable:true});
  Object.assign(globalThis,{XMLHttpRequest,ProgressEvent});
})();
