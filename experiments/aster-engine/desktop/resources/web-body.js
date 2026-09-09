/* Buffered HTTP body types. No filesystem handles or renderer dependencies. */
(() => {
  'use strict';
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
    constructor(label='utf-8',options={}){if(!['utf-8','utf8','unicode-1-1-utf-8'].includes(String(label).trim().toLowerCase()))throw new RangeError('Only UTF-8 decoding is implemented');this.fatal=!!options.fatal;this.ignoreBOM=!!options.ignoreBOM;this._pending=new Uint8Array();this._bom=false;this._stream=false;}
    get encoding(){return 'utf-8';}
    decode(value=new Uint8Array(),options={}){
      const incoming=bytes(value);if(incoming.length>1048576)throw new RangeError('Text decoding limit');
      if(!this._stream){this._pending=new Uint8Array();this._bom=false;}
      const b=new Uint8Array(this._pending.length+incoming.length);b.set(this._pending);b.set(incoming,this._pending.length);this._pending=new Uint8Array();this._stream=!!options.stream;let text='';
      const invalid=()=>{if(this.fatal){this._stream=false;throw new TypeError('Invalid UTF-8');}return '\ufffd';};
      for(let i=0;i<b.length;){const start=i,first=b[i++];if(first<128){text+=String.fromCharCode(first);continue;}
        let count=first>=0xc2&&first<=0xdf?1:first>=0xe0&&first<=0xef?2:first>=0xf0&&first<=0xf4?3:0;
        if(!count){text+=invalid();continue;}let cp=first&((1<<(6-count))-1),valid=true;
        for(let j=0;j<count;j++){const c=b[i];
          if(c===undefined&&this._stream){this._pending=b.slice(start);i=b.length;valid=false;break;}
          if(c===undefined||(c&0xc0)!==0x80||(j===0&&((first===0xe0&&c<0xa0)||(first===0xed&&c>=0xa0)||(first===0xf0&&c<0x90)||(first===0xf4&&c>=0x90)))){valid=false;break;}cp=(cp<<6)|(c&63);i++;
        }
        if(this._pending.length)break;text+=valid?String.fromCodePoint(cp):invalid();
      }
      if(text&&!this._bom){this._bom=true;if(!this.ignoreBOM&&text.charCodeAt(0)===0xfeff)text=text.slice(1);}return text;
    }
  }
  const abc='ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';
  function base64(b){let s='';for(let i=0;i<b.length;i+=3){const n=(b[i]<<16)|((b[i+1]||0)<<8)|(b[i+2]||0);s+=abc[n>>>18]+abc[(n>>>12)&63]+(i+1<b.length?abc[(n>>>6)&63]:'=')+(i+2<b.length?abc[n&63]:'=');}return s;}
  const base64Values=new Int16Array(128).fill(-1);for(let i=0;i<abc.length;i++)base64Values[abc.charCodeAt(i)]=i;
  function unbase64(text){text=String(text).replace(/[\t\n\f\r ]/g,'');if(text.length%4===0)text=text.replace(/={1,2}$/,'');if(text.length%4===1||/[^A-Za-z0-9+/]/.test(text))throw new DOMException('Invalid base64','InvalidCharacterError');const out=new Uint8Array(Math.floor(text.length*6/8));let n=0,bits=0,p=0;for(let i=0;i<text.length;i++){n=(n<<6)|base64Values[text.charCodeAt(i)];bits+=6;if(bits>=8){bits-=8;out[p++]=(n>>bits)&255;}}return out;}

  const limit=1048576, encoder=new TextEncoder();
  const nativeRequest=globalThis.__asterSiteRequest, newline=globalThis.__asterNativeLineEnding||'\n';
  delete globalThis.__asterNativeLineEnding;
  const scalar=value=>new TextDecoder('utf-8',{ignoreBOM:true}).decode(encoder.encode(String(value)));
  function join(parts,max=limit){let n=0;for(const part of parts)if((n+=part.length)>max)throw new RangeError('HTTP body exceeds its byte limit');const result=new Uint8Array(n);let p=0;for(const part of parts){result.set(part,p);p+=part.length;}return result;}
  function resolve(value){const result=JSON.parse(nativeRequest(JSON.stringify({op:'url-resolve',value:String(value)})));if(result.error)throw new TypeError('Invalid or unsupported HTTP URL');return result.value;}
  const queries=new WeakMap();
  function formDecode(value){const b=encoder.encode(value.replace(/\+/g,' ')),out=[];for(let i=0;i<b.length;i++){if(b[i]===37&&i+2<b.length&&/^[0-9a-f]{2}$/i.test(String.fromCharCode(b[i+1],b[i+2]))){out.push(parseInt(String.fromCharCode(b[i+1],b[i+2]),16));i+=2;}else out.push(b[i]);}return new TextDecoder('utf-8',{ignoreBOM:true}).decode(new Uint8Array(out));}
  function formEncode(value){let out='';for(const b of encoder.encode(value))out+=b===32?'+':b>=65&&b<=90||b>=97&&b<=122||b>=48&&b<=57||[42,45,46,95].includes(b)?String.fromCharCode(b):'%'+b.toString(16).toUpperCase().padStart(2,'0');return out;}
  class URLSearchParams {
    constructor(init=''){
      queries.set(this,[]);
      if(init!=null&&typeof init==='object'){
        if(typeof init[Symbol.iterator]==='function'){for(const pair of init){const p=[...pair];if(p.length!==2)throw new TypeError('Expected name/value pairs');this.append(p[0],p[1]);}}
        else for(const [k,v] of Object.entries(init))this.append(k,v);
      }else {const text=String(init).replace(/^\?/,'');if(text.length>limit)throw new RangeError('Query limit');for(const part of text.split('&'))if(part){const p=part.indexOf('=');this.append(formDecode(p<0?part:part.slice(0,p)),formDecode(p<0?'':part.slice(p+1)));}}
    }
    get size(){return queries.get(this).length;}
    append(name,value){if(arguments.length<2)throw new TypeError('A value is required');const q=queries.get(this);if(q.length>=4096)throw new RangeError('Query entry limit');q.push([scalar(name),scalar(value)]);}
    set(name,value){if(arguments.length<2)throw new TypeError('A value is required');name=scalar(name);value=scalar(value);const q=queries.get(this);let found=false;for(let i=0;i<q.length;i++)if(q[i][0]===name){if(found)q.splice(i--,1);else{q[i][1]=value;found=true;}}if(!found)this.append(name,value);}
    get(name){name=scalar(name);return queries.get(this).find(p=>p[0]===name)?.[1]??null;}
    getAll(name){name=scalar(name);return queries.get(this).filter(p=>p[0]===name).map(p=>p[1]);}
    has(name,value){name=scalar(name);if(value!==undefined)value=scalar(value);return queries.get(this).some(p=>p[0]===name&&(value===undefined||p[1]===value));}
    delete(name,value){name=scalar(name);if(value!==undefined)value=scalar(value);const q=queries.get(this);for(let i=0;i<q.length;i++)if(q[i][0]===name&&(value===undefined||q[i][1]===value))q.splice(i--,1);}
    sort(){queries.get(this).sort((a,b)=>a[0]<b[0]?-1:a[0]>b[0]?1:0);}
    *entries(){let i=0;const q=queries.get(this);while(i<q.length)yield [...q[i++]];}
    *keys(){for(const p of this)yield p[0];}*values(){for(const p of this)yield p[1];}
    [Symbol.iterator](){return this.entries();}forEach(fn,thisArg){for(const [k,v] of this)fn.call(thisArg,v,k,this);}
    toString(){return [...this].map(([k,v])=>formEncode(k)+'='+formEncode(v)).join('&');}
  }
  const blobs=new WeakMap(),files=new WeakMap();
  function mime(value){value=String(value);return /[^\x20-\x7e]/.test(value)?'':value.toLowerCase();}
  class Blob {
    constructor(parts=[],options={}){
      if(parts==null||typeof parts==='string'||typeof parts[Symbol.iterator]!=='function')throw new TypeError('Expected a sequence of Blob parts');
      const ending=String(options.endings||'transparent');if(!['transparent','native'].includes(ending))throw new TypeError('Invalid line endings');
      const list=[];let total=0,count=0;
      for(const part of parts){if(++count>4096)throw new RangeError('Blob part limit');let b;
        if(part instanceof Blob)b=blobs.get(part).bytes;else if(part instanceof ArrayBuffer||ArrayBuffer.isView(part))b=bytes(part);else{let s=scalar(part);if(ending==='native')s=s.replace(/\r\n|\r|\n/g,newline);b=encoder.encode(s);}
        if((total+=b.length)>limit)throw new RangeError('Blob exceeds 1 MiB');list.push(b);
      }
      blobs.set(this,{bytes:join(list),type:mime(options.type||'')});
    }
    get size(){return blobs.get(this).bytes.length;}get type(){return blobs.get(this).type;}
    slice(start=0,end=this.size,type=''){
      const index=(n,size)=>{n=Number(n);n=Number.isNaN(n)?0:Math.trunc(n);return n<0?Math.max(size+n,0):Math.min(n,size);};
      start=index(start,this.size);end=index(end,this.size);return new Blob([blobs.get(this).bytes.slice(start,Math.max(start,end))],{type});
    }
    async arrayBuffer(){return blobs.get(this).bytes.slice().buffer;}async bytes(){return new Uint8Array(await this.arrayBuffer());}async text(){return new TextDecoder().decode(await this.arrayBuffer());}
  }
  class File extends Blob {
    constructor(parts,name,options={}){if(arguments.length<2)throw new TypeError('File name is required');super(parts,options);const time=options.lastModified===undefined?Date.now():Number(options.lastModified);files.set(this,{name:scalar(name),lastModified:Number.isFinite(time)?Math.trunc(time):0});}
    get name(){return files.get(this).name;}get lastModified(){return files.get(this).lastModified;}get webkitRelativePath(){return '';}
  }
  const forms=new WeakMap();
  function field(value,filename){if(value instanceof Blob)return filename!==undefined||!(value instanceof File)?new File([value],filename===undefined?'blob':filename,{type:value.type,lastModified:value instanceof File?value.lastModified:Date.now()}):value;if(filename!==undefined)throw new TypeError('A filename requires a Blob');return scalar(value);}
  class FormData {
    constructor(form){if(form!==undefined)throw new DOMException('DOM form extraction is not implemented','NotSupportedError');forms.set(this,[]);}
    append(name,value,filename){if(arguments.length<2)throw new TypeError('A value is required');const f=forms.get(this);if(f.length>=1024)throw new RangeError('Form entry limit');f.push([scalar(name),field(value,filename)]);}
    set(name,value,filename){if(arguments.length<2)throw new TypeError('A value is required');name=scalar(name);value=field(value,filename);const f=forms.get(this);let found=false;for(let i=0;i<f.length;i++)if(f[i][0]===name){if(found)f.splice(i--,1);else{f[i][1]=value;found=true;}}if(!found)this.append(name,value);}
    get(name){name=scalar(name);return forms.get(this).find(p=>p[0]===name)?.[1]??null;}getAll(name){name=scalar(name);return forms.get(this).filter(p=>p[0]===name).map(p=>p[1]);}
    has(name){name=scalar(name);return forms.get(this).some(p=>p[0]===name);}delete(name){name=scalar(name);const f=forms.get(this);for(let i=0;i<f.length;i++)if(f[i][0]===name)f.splice(i--,1);}
    *entries(){let i=0;const f=forms.get(this);while(i<f.length)yield [...f[i++]];}*keys(){for(const p of this)yield p[0];}*values(){for(const p of this)yield p[1];}
    [Symbol.iterator](){return this.entries();}forEach(fn,thisArg){for(const [k,v] of this)fn.call(thisArg,v,k,this);}
  }
  const crlf=s=>s.replace(/\r\n|\r|\n/g,'\r\n');
  const escaped=s=>crlf(s).replace(/[\r\n"]/g,c=>c==='"'?'%22':c==='\r'?'%0D':'%0A');
  function extract(value){
    if(value===null||value===undefined)return {bytes:null,type:''};
    if(value instanceof Blob)return {bytes:blobs.get(value).bytes.slice(),type:value.type};
    if(value instanceof URLSearchParams)return {bytes:encoder.encode(value.toString()),type:'application/x-www-form-urlencoded;charset=UTF-8'};
    if(value instanceof FormData){
      const parts=[];let total=0;for(const [name,v] of value){
        const file=v instanceof File;
        const head='Content-Disposition: form-data; name="'+escaped(name)+'"'+(file?'; filename="'+escaped(v.name)+'"':'')+'\r\n'+(file?'Content-Type: '+(v.type||'application/octet-stream')+'\r\n':'')+'\r\n';
        const part=join([encoder.encode(head),file?blobs.get(v).bytes:encoder.encode(crlf(v)),encoder.encode('\r\n')],262144);if((total+=part.length)>262144)throw new RangeError('Multipart body exceeds 256 KiB');parts.push(part);
      }
      // Check for delimiter collisions even if page code can predict Math.random().
      let boundary='';for(let attempt=0;attempt<8;attempt++){boundary='----aster'+Array.from({length:6},()=>Math.floor(Math.random()*0x100000000).toString(16).padStart(8,'0')).join('');if(parts.every(p=>!new TextDecoder().decode(p).includes(boundary)))break;boundary='';}
      if(!boundary)throw new TypeError('Cannot serialize multipart boundary');
      const all=[];for(const p of parts)all.push(encoder.encode('--'+boundary+'\r\n'),p);all.push(encoder.encode('--'+boundary+'--\r\n'));
      return {bytes:join(all,262144),type:'multipart/form-data; boundary='+boundary};
    }
    if(value instanceof ArrayBuffer||ArrayBuffer.isView(value))return {bytes:bytes(value).slice(),type:''};
    return {bytes:encoder.encode(scalar(value)),type:'text/plain;charset=UTF-8'};
  }
  const headerData=new WeakMap();
  function name(value){value=String(value).toLowerCase();if(!/^[!#$%&'*+.^_`|~0-9a-z-]+$/.test(value))throw new TypeError('Invalid HTTP header name');return value;}
  function headerValue(value){value=String(value).replace(/^[\t ]+|[\t ]+$/g,'');if(/[\r\n\0\u0100-\uffff]/.test(value)||value.length>8192)throw new TypeError('Invalid or oversized HTTP header');return value;}
  class Headers {
    constructor(init={}){headerData.set(this,{map:new Map(),immutable:false});if(init!=null&&typeof init[Symbol.iterator]==='function'){for(const pair of init){const p=[...pair];if(p.length!==2)throw new TypeError('Expected header pairs');this.append(p[0],p[1]);}}else for(const [k,v] of Object.entries(init||{}))this.append(k,v);}
    _write(k,v,append){k=name(k);v=headerValue(v);const state=headerData.get(this);if(state.immutable)throw new TypeError('Response headers are immutable');const previous=state.map.get(k);if(state.map.size>=64&&!previous)throw new TypeError('Header count limit');if(append&&previous){if(previous.join(', ').length+v.length>8192)throw new TypeError('Header size limit');previous.push(v);}else state.map.set(k,[v]);}
    set(k,v){this._write(k,v,false);}append(k,v){this._write(k,v,true);}get(k){return headerData.get(this).map.get(name(k))?.join(', ')??null;}has(k){return headerData.get(this).map.has(name(k));}
    delete(k){k=name(k);const state=headerData.get(this);if(state.immutable)throw new TypeError('Response headers are immutable');state.map.delete(k);}
    getSetCookie(){return [...(headerData.get(this).map.get('set-cookie')||[])];}
    *entries(){let i=0;while(true){const all=[...headerData.get(this).map].sort((a,b)=>a[0]<b[0]?-1:a[0]>b[0]?1:0).flatMap(([k,v])=>k==='set-cookie'?v.map(s=>[k,s]):[[k,v.join(', ')]]);if(i>=all.length)return;yield all[i++];}}
    *keys(){for(const p of this)yield p[0];}*values(){for(const p of this)yield p[1];}[Symbol.iterator](){return this.entries();}forEach(fn,thisArg){for(const [k,v] of this)fn.call(thisArg,v,k,this);}
  }
  const bodies=new WeakMap(),responses=new WeakMap();
  function bodyState(value){const state={bytes:value,used:false,error:null,promise:null};state.promise=Promise.resolve(value||new Uint8Array());return state;}
  function deferred(){let resolve,reject;const state=bodyState(null);state.promise=new Promise((a,b)=>{resolve=a;reject=b;});state.promise.catch(()=>{});return {state,resolve,reject};}
  function setBody(object,state){bodies.set(object,state);}
  const bodyMethods={
    get bodyUsed(){return bodies.get(this).used;},
    async arrayBuffer(){const state=bodies.get(this);if(state.used)throw new TypeError('Body already consumed');if(state.error)throw state.error;if(state.bytes!==null||state.pending)state.used=true;const b=await state.promise;if(state.error)throw state.error;return b.slice().buffer;},
    async bytes(){return new Uint8Array(await this.arrayBuffer());},
    async text(){return new TextDecoder().decode(await this.arrayBuffer());},
    async json(){return JSON.parse(await this.text());},
    async blob(){return new Blob([await this.arrayBuffer()],{type:this.headers.get('content-type')||''});}
  };
  function withBody(prototype){Object.defineProperties(prototype,Object.getOwnPropertyDescriptors(bodyMethods));}
  function response(init,state,immutable=true){const r=Object.create(Response.prototype);const headers=new Headers(init.headers);headerData.get(headers).immutable=immutable;responses.set(r,{status:init.status,statusText:init.statusText||'',headers,url:init.url||'',redirected:!!init.redirected,type:init.type||'default'});bodies.set(r,state);return r;}
  class Response {
    constructor(body=null,init={}){
      const status=Number(init.status===undefined?200:init.status),statusText=String(init.statusText||'');if(!Number.isInteger(status)||status<200||status>599)throw new RangeError('Invalid response status');if(/[^\t\x20-\x7e\x80-\xff]/.test(statusText))throw new TypeError('Invalid status text');
      const value=extract(body);if([204,205,304].includes(status)&&value.bytes!==null)throw new TypeError('This status cannot have a body');const headers=new Headers(init.headers);if(value.type&&!headers.has('content-type'))headers.set('content-type',value.type);
      return response({status,statusText,headers},bodyState(value.bytes),false);
    }
    get status(){return responses.get(this).status;}get statusText(){return responses.get(this).statusText;}get headers(){return responses.get(this).headers;}get url(){return responses.get(this).url;}get redirected(){return responses.get(this).redirected;}get type(){return responses.get(this).type;}get ok(){return this.status>=200&&this.status<300;}
    clone(){const state=bodies.get(this);if(state.used)throw new TypeError('Body already consumed');return response(responses.get(this),{...state,used:false},headerData.get(this.headers).immutable);}
    static error(){return response({status:0,type:'error'},bodyState(null));}
    static redirect(url,status=302){status=Number(status);if(![301,302,303,307,308].includes(status))throw new RangeError('Invalid redirect status');return response({status,headers:{location:resolve(url)}},bodyState(null));}
    static json(value,init={}){const text=JSON.stringify(value);if(text===undefined)throw new TypeError('Value is not JSON serializable');const headers=new Headers(init.headers);if(!headers.has('content-type'))headers.set('content-type','application/json');return new Response(text,{...init,headers});}
  }
  withBody(Response.prototype);
  for(const ctor of [URLSearchParams,Blob,File,FormData,Headers,Response])Object.defineProperty(ctor.prototype,Symbol.toStringTag,{value:ctor.name,configurable:true});
  Object.assign(globalThis,{DOMException,TextEncoder,TextDecoder,URLSearchParams,Blob,File,FormData,Headers,Response,
    btoa(value){const s=String(value),b=new Uint8Array(s.length);for(let i=0;i<s.length;i++){if(s.charCodeAt(i)>255)throw new DOMException('Expected Latin-1 text','InvalidCharacterError');b[i]=s.charCodeAt(i);}return base64(b);},
    atob(value){const b=unbase64(value);let s='';for(const c of b)s+=String.fromCharCode(c);return s;}});
  globalThis.__asterHttp=Object.freeze({bytes,base64,unbase64,extract,join,resolve,bodies,bodyState,setBody,withBody,deferred,response});
})();
