package io.aster.desktop;

import io.aster.engine.*;
import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.zip.*;

/** Actual independent HTTP origins and the packaged QuickJS process, without a substitute browser. */
public final class CompatibilityTests {
    private static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
    private static void yes(ScriptSession js,String expression,String why)throws Exception{check(Boolean.TRUE.equals(js.eval(expression)),why);}
    private static void await(ScriptSession js,String condition)throws Exception{
        long deadline=System.nanoTime()+8_000_000_000L;
        do{js.pump();if(Boolean.TRUE.equals(js.eval(condition)))return;Thread.sleep(10);}while(System.nanoTime()<deadline);
        throw new AssertionError("Timed out: "+condition+"; "+js.eval("JSON.stringify({result:globalThis.result,failure:globalThis.failure,events:globalThis.events})"));
    }
    private static void evaluate(ScriptSession js,String code)throws Exception{js.eval("result=null;failure=null;"+code+";void 0");}
    private static void text(HttpExchange e,int status,String value)throws IOException{byte[] b=value.getBytes(StandardCharsets.UTF_8);e.getResponseHeaders().set("Content-Type","text/plain;charset=UTF-8");e.sendResponseHeaders(status,b.length);e.getResponseBody().write(b);e.close();}
    private static void cors(HttpExchange e,String origin){e.getResponseHeaders().set("Access-Control-Allow-Origin",origin);}
    private static void redirect(HttpExchange e,int status,String target)throws IOException{e.getResponseHeaders().set("Location",target);e.sendResponseHeaders(status,-1);e.close();}
    private static URI origin(HttpServer server){return URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/");}
    private static byte[] zip(String value,boolean gzip)throws IOException{ByteArrayOutputStream out=new ByteArrayOutputStream();try(OutputStream z=gzip?new GZIPOutputStream(out):new DeflaterOutputStream(out)){z.write(value.getBytes(StandardCharsets.UTF_8));}return out.toByteArray();}
    public static void main(String[] args)throws Exception{
        URI url=URI.create("https://example.test/dir/page?old=1#before");
        check(PageLoader.link(url,"?new=2").toString().equals("https://example.test/dir/page?new=2"),"Query navigation changed the path");
        check(PageLoader.link(url,"").toString().equals("https://example.test/dir/page?old=1"),"Empty reference changed the path");
        check(PageLoader.link(url,"#next").toString().equals("https://example.test/dir/page?old=1#next"),"Fragment navigation lost the query");
        check(PageNetwork.origin(URI.create("https://EXAMPLE.test:443/a")).equals("https://example.test"),"Serialized origin retained default port/case");
        for(String bad:new String[]{"http://example.test/","https://user:pass@example.test/","https://example.test:25/","file:///x"})try{PageNetwork.target(url,bad,false);throw new AssertionError("Unsafe connection URL accepted");}catch(IOException expected){}
        check(CorsPolicy.safeHeader("range","bytes=0-5")&&!CorsPolicy.safeHeader("range","bytes=-5")&&!CorsPolicy.safeHeader("range","bytes=9-1")&&!CorsPolicy.safeHeader("content-type","text/plain,application/json"),"CORS request-header safelist");
        ExecutorService pool=Executors.newCachedThreadPool(r->{Thread t=new Thread(r,"aster-compat-fixture");t.setDaemon(true);return t;});
        HttpServer a=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0),b=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0),c=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        for(HttpServer s:List.of(a,b,c))s.setExecutor(pool);
        URI one=origin(a),two=origin(b),three=origin(c);String pageOrigin=PageNetwork.origin(one);
        AtomicInteger deniedWrites=new AtomicInteger(),preflights=new AtomicInteger(),apiWrites=new AtomicInteger(),slowStarted=new AtomicInteger(),homeHits=new AtomicInteger();
        AtomicReference<String> preflightHeaders=new AtomicReference<>(""),receivedAuth=new AtomicReference<>(""),receivedCookie=new AtomicReference<>(""),receivedType=new AtomicReference<>(""),receivedBody=new AtomicReference<>(""),thirdOrigin=new AtomicReference<>("");
        CountDownLatch streamHeaders=new CountDownLatch(1),releaseStream=new CountDownLatch(1);
        a.createContext("/echo",e->{receivedType.set(Objects.toString(e.getRequestHeaders().getFirst("Content-Type"),""));byte[] bytes=e.getRequestBody().readAllBytes();receivedBody.set(new String(bytes,StandardCharsets.UTF_8));text(e,200,e.getRequestMethod()+":"+receivedBody.get());});
        a.createContext("/dir/page",e->text(e,200,e.getRequestURI().toString()));
        a.createContext("/leave",e->redirect(e,302,two.resolve("public").toString()));
        a.createContext("/auth-leave",e->redirect(e,307,two.resolve("receive").toString()));
        a.createContext("/home",e->{homeHits.incrementAndGet();receivedCookie.set(Objects.toString(e.getRequestHeaders().getFirst("Cookie"),""));cors(e,"null");text(e,200,"back home");});
        a.createContext("/slow",e->{slowStarted.incrementAndGet();try{Thread.sleep(800);text(e,200,"late");}catch(Exception ignored){e.close();}});
        a.createContext("/stream",e->{try{e.getResponseHeaders().set("Content-Type","text/plain");e.getResponseHeaders().set("X-Early","yes");e.sendResponseHeaders(200,0);e.getResponseBody().write("first ".getBytes(StandardCharsets.UTF_8));e.getResponseBody().flush();streamHeaders.countDown();releaseStream.await(5,TimeUnit.SECONDS);byte[] bytes="🌎 last".getBytes(StandardCharsets.UTF_8);e.getResponseBody().write(bytes,0,2);e.getResponseBody().flush();Thread.sleep(100);e.getResponseBody().write(bytes,2,bytes.length-2);}catch(Exception ignored){}finally{e.close();}});
        a.createContext("/xhr-stream",e->{try{e.sendResponseHeaders(200,0);e.getResponseBody().write("first ".getBytes(StandardCharsets.UTF_8));e.getResponseBody().flush();Thread.sleep(150);byte[] v="🌎 last".getBytes(StandardCharsets.UTF_8);e.getResponseBody().write(v,0,2);e.getResponseBody().flush();Thread.sleep(150);e.getResponseBody().write(v,2,v.length-2);}catch(Exception ignored){}finally{e.close();}});
        a.createContext("/limit",e->text(e,200,"a".repeat(1048576)));
        a.createContext("/json",e->{e.getResponseHeaders().set("X-Answer","yes");text(e,200,"{\"ok\":true}");});
        a.createContext("/bad-json",e->text(e,200,"no JSON"));
        a.createContext("/empty",e->{e.sendResponseHeaders(204,-1);e.close();});
        for(String path:List.of("gzip","deflate","bomb"))a.createContext("/"+path,e->{byte[] bytes=zip(path.equals("bomb")?"x".repeat(1048577):"compressed 🌎",!path.equals("deflate"));e.getResponseHeaders().set("Content-Encoding",path.equals("deflate")?"deflate":"gzip");e.sendResponseHeaders(200,bytes.length);e.getResponseBody().write(bytes);e.close();});
        b.createContext("/public",e->{cors(e,"*");e.getResponseHeaders().set("X-Hidden","do not expose");e.getResponseHeaders().set("X-Visible","shared");e.getResponseHeaders().set("Access-Control-Expose-Headers","X-Visible, Set-Cookie");e.getResponseHeaders().add("Set-Cookie","foreign=refused; Path=/");text(e,200,"public data");});
        b.createContext("/denied",e->{if(!e.getRequestMethod().equals("OPTIONS"))deniedWrites.incrementAndGet();text(e,200,"PRIVATE BODY");});
        b.createContext("/api",e->{cors(e,pageOrigin);e.getResponseHeaders().set("Access-Control-Allow-Credentials","true");if(e.getRequestMethod().equals("OPTIONS")){
            preflights.incrementAndGet();preflightHeaders.set(Objects.toString(e.getRequestHeaders().getFirst("Access-Control-Request-Headers"),""));check(e.getRequestHeaders().getFirst("Cookie")==null&&e.getRequestHeaders().getFirst("Authorization")==null&&e.getRequestBody().readAllBytes().length==0,"Preflight included credentials/body");
            e.getResponseHeaders().set("Access-Control-Allow-Methods","PUT");e.getResponseHeaders().set("Access-Control-Allow-Headers","content-type, x-feature, authorization");e.getResponseHeaders().set("Access-Control-Max-Age","60");e.getResponseHeaders().add("Set-Cookie","preflight=refused; Path=/");e.sendResponseHeaders(204,-1);e.close();
        }else{apiWrites.incrementAndGet();receivedCookie.set(Objects.toString(e.getRequestHeaders().getFirst("Cookie"),""));receivedAuth.set(Objects.toString(e.getRequestHeaders().getFirst("Authorization"),""));text(e,200,new String(e.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));}});
        b.createContext("/wild-headers",e->{cors(e,"*");e.getResponseHeaders().set("Access-Control-Allow-Headers","*");e.getResponseHeaders().set("Access-Control-Allow-Methods","*");if(e.getRequestMethod().equals("OPTIONS")){e.sendResponseHeaders(204,-1);e.close();}else{deniedWrites.incrementAndGet();text(e,200,"bad authorization");}});
        b.createContext("/wrong-method",e->{cors(e,pageOrigin);e.getResponseHeaders().set("Access-Control-Allow-Methods","put");e.getResponseHeaders().set("Access-Control-Allow-Headers","content-type");if(e.getRequestMethod().equals("OPTIONS")){e.sendResponseHeaders(204,-1);e.close();}else{deniedWrites.incrementAndGet();text(e,200,"wrong method case");}});
        b.createContext("/duplicate",e->{cors(e,pageOrigin);e.getResponseHeaders().add("Access-Control-Allow-Origin","*");text(e,200,"secret");});
        b.createContext("/credential-wildcard",e->{cors(e,"*");e.getResponseHeaders().set("Access-Control-Allow-Credentials","true");text(e,200,"secret");});
        b.createContext("/credential",e->{cors(e,pageOrigin);e.getResponseHeaders().set("Access-Control-Allow-Credentials","true");e.getResponseHeaders().set("Access-Control-Expose-Headers","*");e.getResponseHeaders().set("X-Hidden","secret");receivedCookie.set(Objects.toString(e.getRequestHeaders().getFirst("Cookie"),""));text(e,200,"credential rule checked");});
        b.createContext("/receive",e->{cors(e,pageOrigin);receivedAuth.set(Objects.toString(e.getRequestHeaders().getFirst("Authorization"),""));receivedCookie.set(Objects.toString(e.getRequestHeaders().getFirst("Cookie"),""));text(e,200,"received");});
        b.createContext("/redirect-denied",e->redirect(e,302,one.resolve("home").toString()));
        b.createContext("/redirect-third",e->{cors(e,pageOrigin);redirect(e,302,three.resolve("target").toString());});
        b.createContext("/redirect-home",e->{cors(e,pageOrigin);redirect(e,302,one.resolve("home").toString());});
        c.createContext("/target",e->{thirdOrigin.set(e.getRequestHeaders().getFirst("Origin"));cors(e,"null");text(e,200,"third origin");});
        for(HttpServer s:List.of(a,b,c))s.start();SiteData data=new SiteData();data.setDocumentCookie(one,"home=private; Path=/");data.setDocumentCookie(two,"foreign=private; Path=/");
        try(ScriptSession js=new ScriptSession(data,new SiteData.Storage())){
            js.start(Engine.parse(one.resolve("dir/page?old=1"),"<title>Compatibility</title><p id='result'>waiting</p>"));
            js.eval("var result=null,failure=null,events=[],other="+Json.quote(two.toString())+";void 0");
            bodies(js,receivedType,receivedBody);
            evaluate(js,"fetch('?new=2').then(r=>r.text()).then(v=>result=v)");await(js,"result==='/dir/page?new=2'");
            evaluate(js,"fetch(other+'public').then(async r=>result=[r.type,r.status,r.headers.get('x-visible'),r.headers.get('x-hidden'),r.headers.get('set-cookie'),await r.text()])");await(js,"result!==null");yes(js,"JSON.stringify(result)===JSON.stringify(['cors',200,'shared',null,null,'public data'])","CORS response filtering");
            check(!data.documentCookie(two).contains("refused"),"Cross-origin response stored a cookie");
            evaluate(js,"fetch(other+'api',{method:'PUT',credentials:'include',headers:{'Content-Type':'application/json','X-Feature':'enabled',Authorization:'Bearer explicit-test'},body:'{\"n\":1}'}).then(r=>r.json()).then(v=>result=v.n)");await(js,"result===1");
            check(preflights.get()==1&&apiWrites.get()==1&&preflightHeaders.get().equals("authorization,content-type,x-feature")&&receivedCookie.get().isEmpty()&&receivedAuth.get().equals("Bearer explicit-test"),"Actual preflight/header/credential enforcement");
            evaluate(js,"fetch(other+'api',{method:'PUT',credentials:'include',headers:{'Content-Type':'application/json','X-Feature':'enabled',Authorization:'Bearer explicit-test'},body:'{\"n\":2}'}).then(r=>r.json()).then(v=>result=v.n)");await(js,"result===2");check(preflights.get()==1&&apiWrites.get()==2,"Validated per-page preflight cache not reused");
            check(!data.documentCookie(two).contains("preflight"),"Preflight stored Set-Cookie");
            for(String code:List.of("fetch(other+'denied',{method:'PUT',body:'do not write'})","fetch(other+'wild-headers',{method:'PUT',headers:{Authorization:'secret'}})","fetch(other+'wrong-method',{method:'PUT',headers:{'Content-Type':'application/json'}})")){
                int before=deniedWrites.get();evaluate(js,code+".catch(e=>failure=e.name)");await(js,"failure==='TypeError'");check(before==deniedWrites.get(),"Refused preflight sent the actual write");
            }
            for(String code:List.of("fetch(other+'denied')","fetch(other+'duplicate')","fetch(other+'public',{mode:'same-origin'})","fetch(other+'credential-wildcard',{credentials:'include'})","fetch(other+'public',{credentials:'include'})")){
                evaluate(js,code+".then(r=>r.text()).then(t=>result=t).catch(e=>failure=e.name)");await(js,"failure==='TypeError'");yes(js,"result===null","Denied CORS leaked body");
            }
            evaluate(js,"fetch(other+'credential',{credentials:'include'}).then(async r=>result=[r.headers.get('x-hidden'),await r.text()])");await(js,"result!==null");yes(js,"result[0]===null&&result[1]==='credential rule checked'","Credentialed wildcard exposed a private header");check(receivedCookie.get().isEmpty(),"Third-party cookies escaped policy");
            evaluate(js,"fetch('/leave').then(async r=>result=[r.type,r.redirected,await r.text()])");await(js,"result!==null");yes(js,"result[0]==='cors'&&result[1]&&result[2]==='public data'","Same-to-cross redirect failed");
            evaluate(js,"fetch('/auth-leave',{headers:{Authorization:'Bearer private'}}).then(r=>r.text()).then(v=>result=v)");await(js,"result==='received'");check(receivedAuth.get().isEmpty()&&receivedCookie.get().isEmpty(),"Cross redirect forwarded credentials");
            int before=homeHits.get();evaluate(js,"fetch(other+'redirect-denied').catch(e=>failure=e.name)");await(js,"failure==='TypeError'");check(homeHits.get()==before,"Redirect followed before checking CORS");
            evaluate(js,"fetch(other+'redirect-third').then(r=>r.text()).then(v=>result=v)");await(js,"result==='third origin'");check(thirdOrigin.get().equals("null"),"Cross-to-cross redirect retained a nonopaque Origin");
            evaluate(js,"fetch(other+'redirect-home',{credentials:'same-origin'}).then(r=>r.text()).then(v=>result=v)");await(js,"result==='back home'");check(receivedCookie.get().isEmpty(),"Tainted redirect regained same-origin cookies");
            evaluate(js,"fetch('/leave',{redirect:'manual'}).then(async r=>result=[r.status,r.type,r.url,[...r.headers].length,await r.text(),r.clone().type])");await(js,"result!==null");yes(js,"JSON.stringify(result)===JSON.stringify([0,'opaqueredirect','',0,'','opaqueredirect'])","Manual redirect disclosed private metadata/body");
            for(String path:List.of("gzip","deflate")){evaluate(js,"fetch('/"+path+"').then(r=>r.text()).then(v=>result=v)");await(js,"result==='compressed 🌎'");}
            evaluate(js,"fetch('/bomb').then(r=>r.text()).then(v=>result=v).catch(e=>failure=e.name)");await(js,"failure==='TypeError'");yes(js,"result===null","Decompression limit failed");
            evaluate(js,"fetch('/empty').then(async r=>result=[r.status,await r.text(),r.bodyUsed,await r.text()])");await(js,"result!==null");yes(js,"JSON.stringify(result)===JSON.stringify([204,'',false,''])","Null response body semantics");
            evaluate(js,"var controller=new AbortController();fetch('/stream',{signal:controller.signal}).then(r=>{result=r.headers.get('x-early');globalThis.early=r;return r.text();}).catch(e=>failure=e.name)");
            await(js,"result==='yes'");check(streamHeaders.getCount()==0&&releaseStream.getCount()==1,"Fetch headers waited for full body");
            js.eval("controller.abort();void 0");await(js,"failure==='AbortError'");releaseStream.countDown();
            evaluate(js,"fetch('/limit').then(r=>r.text()).then(v=>result=v.length).catch(e=>failure=e.name)");await(js,"result===1048576");
            xhr(js,slowStarted);
            yes(js,"typeof MediaSource==='undefined'&&typeof navigator.requestMediaKeySystemAccess==='undefined'&&typeof RTCPeerConnection==='undefined'","Unimplemented streaming API advertised");
            System.out.println("Web compatibility passed: independent CORS origins, denied writes/data, preflight/cache/credentials, redirect taint and authorization stripping, buffered bodies/multipart, early headers, progressive UTF-8 XHR, gzip/deflate bounds, cancellation and request reuse. Premium services and full web standards remain unverified.");
        }finally{releaseStream.countDown();for(HttpServer s:List.of(a,b,c))s.stop(0);pool.shutdownNow();}
    }
    private static void bodies(ScriptSession js,AtomicReference<String> type,AtomicReference<String> body)throws Exception{
        yes(js,"(()=>{let p=new URLSearchParams('?a=1&a=2&space=a+b&unicode=%F0%9F%8C%8E&bad=%C0%AF&bom=%EF%BB%BF');return p.size===6&&p.getAll('a').join(',')==='1,2'&&p.get('space')==='a b'&&p.get('unicode')==='🌎'&&p.get('bad')==='��'&&p.get('bom')==='\\ufeff';})()","Form URL decoding");
        yes(js,"(()=>{let p=new URLSearchParams([['z',3],['a',1],['a',2],['q','~ !🌎\\ud800']]);p.sort();p.delete('a','1');p.set('z',4);return p.toString()==='a=2&q=%7E+%21%F0%9F%8C%8E%EF%BF%BD&z=4'&&p.has('a',2)&&!p.has('a',1);})()","Query sorting, scalar conversion and value-specific deletion");
        yes(js,"(()=>{let p=new URLSearchParams('a=1');let seen=[];p.forEach((v,k)=>{seen.push(k);if(k==='a')p.append('b',2);});return seen.join(',')==='a,b';})()","Query iteration was not live");
        yes(js,"(()=>{let d=new TextDecoder();return d.decode(new Uint8Array([0xef,0xbb]),{stream:true})===''&&d.decode(new Uint8Array([0xbf,0xf0,0x9f]),{stream:true})===''&&d.decode(new Uint8Array([0x8c,0x8e]),{stream:true})==='🌎'&&d.decode()==='';})()","Streaming UTF-8/BOM boundary");
        evaluate(js,"var bytes=new Uint8Array([0,1,255]);var blob=new Blob([bytes,'🌎'],{type:'Application/Octet-Stream'});bytes[0]=99;blob.slice(0,3).arrayBuffer().then(b=>result=[...new Uint8Array(b)].join(','))");await(js,"result==='0,1,255'");
        evaluate(js,"var file=new File(['file-data'],'résumé.txt',{type:'text/plain',lastModified:123});var form=new FormData();form.append('title','line1\\nline2');form.append('upload',file);form.append('title','second');fetch('/echo',{method:'POST',body:form}).then(r=>r.text()).then(v=>result=v)");await(js,"result!==null");
        check(type.get().startsWith("multipart/form-data; boundary=")&&body.get().contains("name=\"title\"\r\n\r\nline1\r\nline2")&&body.get().contains("filename=\"résumé.txt\"")&&body.get().contains("file-data")&&body.get().endsWith("--\r\n"),"Multipart upload did not reach server with exact field/file bytes");
        evaluate(js,"fetch('/echo',{method:'POST',body:new URLSearchParams({q:'hello 🌎',repeat:'a b'})}).then(r=>r.text()).then(v=>result=v)");await(js,"result==='POST:q=hello+%F0%9F%8C%8E&repeat=a+b'");check(type.get().equals("application/x-www-form-urlencoded;charset=UTF-8"),"Encoded form MIME");
        evaluate(js,"var original=new Request('/echo',{method:'POST',body:'clone-body'});var copy=original.clone();fetch(original).then(r=>r.text()).then(v=>result=v)");await(js,"result==='POST:clone-body'");yes(js,"original.bodyUsed&&!copy.bodyUsed&&original.url.endsWith('/echo')","Request consumption/clone");
        evaluate(js,"fetch(copy).then(r=>r.text()).then(v=>result=v)");await(js,"result==='POST:clone-body'");evaluate(js,"fetch(copy).catch(e=>failure=e.name)");await(js,"failure==='TypeError'");
        evaluate(js,"Response.json({n:2}).clone().json().then(v=>result=v.n)");await(js,"result===2");
        evaluate(js,"fetch('/json').then(r=>{try{r.headers.set('X-Forged','x');result=false;}catch(e){result=e.name==='TypeError';}})");await(js,"result===true");
        yes(js,"(()=>{try{new Response('',{status:204});return false;}catch(e){return e.name==='TypeError';}})()","Null-body status accepted a body");
        yes(js,"(()=>{let h=new Headers([['X-Z','1'],['x-a','2'],['Set-Cookie','a=1'],['Set-Cookie','b=2']]);return [...h.keys()].join(',')==='set-cookie,set-cookie,x-a,x-z'&&h.getSetCookie().join('|')==='a=1|b=2';})()","Header ordering/cookie separation");
    }
    private static void xhr(ScriptSession js,AtomicInteger slowStarted)throws Exception{
        evaluate(js,"events=[];var x=new XMLHttpRequest();x.onreadystatechange=()=>events.push(x.readyState);x.onload=()=>{result=[x.status,x.response.ok,x.responseURL.endsWith('/json'),x.getResponseHeader('x-answer')];};x.onerror=()=>failure='error';x.open('GET','/json');x.responseType='json';x.send()");await(js,"result!==null");yes(js,"JSON.stringify(result)===JSON.stringify([200,true,true,'yes'])&&events.join(',')==='1,2,3,4'","XHR actual JSON and state transitions");
        evaluate(js,"x=new XMLHttpRequest();x.open('GET','/bad-json');x.responseType='json';x.onload=()=>result=x.response===null&&x.status===200;x.send()");await(js,"result===true");
        evaluate(js,"events=[];x=new XMLHttpRequest();x.open('GET','/xhr-stream');x.onprogress=()=>events.push(x.responseText);x.onload=()=>result=x.responseText;x.send()");await(js,"result==='first 🌎 last'");yes(js,"events.includes('first ')&&events.length>=2&&events.every(t=>!t.includes('�'))","XHR partial text/UTF-8 boundary failed");
        evaluate(js,"x=new XMLHttpRequest();x.open('GET','/json');x.responseType='arraybuffer';x.onload=()=>result=new TextDecoder().decode(x.response);x.send()");await(js,"result==='{\"ok\":true}'");
        evaluate(js,"x=new XMLHttpRequest();x.open('GET','/json');x.responseType='blob';x.onload=()=>{x.response.text().then(t=>result=t);};x.send()");await(js,"result==='{\"ok\":true}'");
        evaluate(js,"events=[];x=new XMLHttpRequest();x.open('POST','/echo');x.upload.onprogress=e=>events.push('upload:'+e.loaded);x.upload.onload=()=>events.push('uploaded');x.onload=()=>result=x.responseText;x.send('from-xhr')");await(js,"result==='POST:from-xhr'");yes(js,"events.includes('upload:8')&&events.includes('uploaded')","XHR upload progress was not reported by publisher");
        evaluate(js,"x=new XMLHttpRequest();x.open('GET',other+'public');x.onload=()=>result=x.responseText+'|'+x.getResponseHeader('x-hidden');x.onerror=()=>failure='error';x.send()");await(js,"result==='public data|null'");
        evaluate(js,"events=[];x=new XMLHttpRequest();x.open('GET',other+'denied');x.onerror=()=>events.push('error');x.onload=()=>events.push('load');x.onloadend=()=>result=[x.status,x.responseText,events.join(',')];x.send()");await(js,"result!==null");yes(js,"JSON.stringify(result)===JSON.stringify([0,'','error'])","XHR CORS error leaked response");
        evaluate(js,"events=[];x=new XMLHttpRequest();x.open('GET','/slow');x.onabort=()=>events.push('abort');x.onload=()=>events.push('load');x.onloadend=()=>result=events.join(',');x.send()");await(js,"true");long until=System.nanoTime()+2_000_000_000L;while(slowStarted.get()==0&&System.nanoTime()<until)Thread.sleep(5);js.eval("x.abort();void 0");await(js,"result==='abort'");yes(js,"x.readyState===0&&x.status===0","XHR abort did not reset state");
        evaluate(js,"events=[];x.open('GET','/slow');x.timeout=20;x.ontimeout=()=>events.push('timeout');x.onloadend=()=>result=events.join(',');x.send()");js.eval("__aster.tick(performance.now()+50);void 0");await(js,"result==='timeout'");
        evaluate(js,"x.timeout=0;x.open('GET','/slow');x.send();x.open('GET','/json');x.onload=()=>result=x.responseText;x.onloadend=null;x.send()");await(js,"result==='{\"ok\":true}'");
        evaluate(js,"events=[];x=new XMLHttpRequest();x.open('GET','/slow');x.onloadstart=()=>x.abort();x.onabort=()=>events.push('abort');x.onloadend=()=>result=events.join(',');x.send()");await(js,"result==='abort'");
        yes(js,"(()=>{try{new XMLHttpRequest().open('GET','/json',false);return false;}catch(e){return e.name==='NotSupportedError';}})()","Synchronous XHR should be explicitly unavailable");
    }
}
