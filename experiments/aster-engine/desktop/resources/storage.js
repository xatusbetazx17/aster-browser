/* Synchronous storage goes through the native broker, bound to the loaded URI.
 * A page cannot select another origin by changing document.URL or location. */
(() => {
  'use strict';
  const nativeRequest=globalThis.__asterSiteRequest;
  delete globalThis.__asterSiteRequest;
  const parse=JSON.parse, stringify=JSON.stringify;
  function request(value){
    const answer=parse(nativeRequest(stringify(value)));
    if(answer.error)throw new DOMException(answer.message,answer.error);
    return answer.value;
  }
  const areas=new WeakMap();
  function access(self,op,key,value){
    const area=areas.get(self);if(!area)throw new TypeError('Illegal Storage receiver');
    return request({area,op,key,value});
  }
  class Storage {
    constructor(){throw new TypeError('Illegal constructor');}
    get length(){return access(this,'keys').length;}
    key(index){return access(this,'keys')[Number(index)>>>0]??null;}
    getItem(key){if(arguments.length<1)throw new TypeError('A key is required');return access(this,'get',String(key));}
    setItem(key,value){if(arguments.length<2)throw new TypeError('A key and value are required');access(this,'set',String(key),String(value));}
    removeItem(key){if(arguments.length<1)throw new TypeError('A key is required');access(this,'remove',String(key));}
    clear(){access(this,'clear');}
  }
  function create(area){
    const target=Object.create(Storage.prototype);areas.set(target,area);
    const storage=new Proxy(target,{
      get(t,k,receiver){if(typeof k==='symbol'||k in t)return Reflect.get(t,k,receiver);return access(t,'get',k)??undefined;},
      set(t,k,value){if(typeof k!=='string')return false;access(t,'set',k,String(value));return true;},
      deleteProperty(t,k){if(typeof k==='string')access(t,'remove',k);return true;},
      ownKeys(t){return access(t,'keys');},
      has(t,k){return k in t||typeof k==='string'&&access(t,'get',k)!==null;},
      getOwnPropertyDescriptor(t,k){if(typeof k!=='string')return undefined;const value=access(t,'get',k);return value===null?undefined:{value,writable:true,enumerable:true,configurable:true};},
      defineProperty(t,k,d){if(typeof k!=='string'||!('value' in d)||d.get||d.set)return false;access(t,'set',k,String(d.value));return true;},
      preventExtensions(){return false;}
    });areas.set(storage,area);return storage;
  }
  const local=create('local'),session=create('session');
  Object.defineProperties(globalThis,{
    Storage:{value:Storage,configurable:true},
    localStorage:{get(){access(local,'keys');return local;},enumerable:true},
    sessionStorage:{get(){access(session,'keys');return session;},enumerable:true}
  });
  Object.defineProperty(document,'cookie',{enumerable:true,get(){return request({op:'cookie-get'});},set(value){request({op:'cookie-set',value:String(value)});}});
})();
