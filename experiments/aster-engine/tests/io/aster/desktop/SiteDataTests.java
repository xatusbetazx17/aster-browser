package io.aster.desktop;

import io.aster.engine.*;
import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.prefs.Preferences;
import javax.swing.SwingUtilities;

/** Real server sessions and real QuickJS IPC, including origin attacks and logout. */
public final class SiteDataTests {
    private static void check(boolean value,String why){if(!value)throw new AssertionError(why);}
    private static void receive(SiteData data,URI uri,String... cookies){SiteData.Request r=data.request(uri,false,"GET");r.header(uri);r.receive(uri,Map.of("Set-Cookie",Arrays.asList(cookies)));}
    private static String header(SiteData data,URI uri){return data.request(uri,false,"GET").header(uri);}
    private static void await(ScriptSession js,String expression)throws Exception{long end=System.nanoTime()+8_000_000_000L;do{js.pump();if(Boolean.TRUE.equals(js.eval(expression)))return;Thread.sleep(15);}while(System.nanoTime()<end);throw new AssertionError("Session fixture timed out: "+expression);}
    private static ScriptSession page(SiteData data,SiteData.Storage tab,URI uri)throws Exception{ScriptSession js=new ScriptSession(data,tab);js.start(Engine.parse(uri,"<title>Session fixture</title>"));return js;}
    private static void cookies()throws Exception{
        SiteData data=new SiteData();URI site=URI.create("https://a.example.test/account/login"),other=URI.create("https://b.example.test/"),http=URI.create("http://a.example.test/account/");
        receive(data,site,"secret=token; Secure; HttpOnly; Path=/; SameSite=Strict","view=wide; Path=/account","global=no; Domain=example.test","public=no; Domain=test","bad=none; SameSite=None","__Host-id=good; Secure; Path=/","__Host-bad=bad; Secure","__Http-bad=bad; Secure");
        check(header(data,site).contains("secret=token")&&header(data,site).contains("__Host-id=good"),"Valid secure cookies missing");
        check(!header(data,site).contains("global=")&&!header(data,site).contains("public=")&&!header(data,site).contains("bad="),"Unsafe cookie accepted");
        check(!data.documentCookie(site).contains("secret="),"HttpOnly exposed to script");data.setDocumentCookie(site,"secret=replaced; Secure; Path=/");data.setDocumentCookie(site,"hidden=bad; HttpOnly");
        check(header(data,site).contains("secret=token")&&!header(data,site).contains("hidden="),"Script overwrote or created HttpOnly");
        check(!header(data,site.resolve("/accounting")).contains("view="),"Cookie path prefix lacked boundary");
        check(header(data,http).isEmpty()&&header(data,other).isEmpty()&&header(data,URI.create("https://a.example.test:8443/account")).isEmpty(),"Cookie escaped origin");
        SiteData.Request cross=data.request(other,true,"GET");String sent=cross.header(site);check(!sent.contains("secret=")&&sent.contains("view=wide"),"Cross-origin safe navigation SameSite");
        check(data.request(other,true,"POST").header(site).isEmpty(),"Cross-origin POST sent Lax/Strict cookies");
        check(data.request(other,false,"GET").header(site).isEmpty(),"Third-party cookies sent");
        SiteData.Request chain=data.request(null,true,"GET");check(chain.header(site).contains("secret="),"Typed navigation lost Strict cookie");chain.header(other);check(!chain.header(site).contains("secret="),"Redirect chain restored Strict credentials");
        receive(data,site,"epoch=gone; Expires=Thu, 01 Jan 1970 00:00:00 GMT");check(!header(data,site).contains("epoch="),"Epoch zero became a session cookie");
        receive(data,site,"view=gone; Path=/account; Max-Age=0","expired=x; Expires=Wed, 09 Jun 2021 10:18:14 GMT","persist=yes; Max-Age=60; Expires=Wed, 09 Jun 2021 10:18:14 GMT");
        check(!header(data,site).contains("view=")&&!header(data,site).contains("expired=")&&header(data,site).contains("persist=yes"),"Expiry/deletion/Max-Age precedence");
        SiteData.Request delayed=data.request(site,false,"GET");delayed.header(site);data.clear();delayed.receive(site,Map.of("Set-Cookie",List.of("resurrected=no")));check(data.cookieCount()==0&&delayed.header(site).isEmpty(),"Clear did not revoke pending request");
        receive(data,http,"secure=bad; Secure","__Secure-name=bad","injected=x\r\nCookie: a=b");check(data.cookieCount()==0,"Unsafe HTTP/header cookie accepted");
        for(int i=0;i<80;i++)receive(data,site,"c"+i+"=v");check(data.cookieCount()==50,"Per-origin cookie bound");
    }
    private static void persistence(Path dir)throws Exception{
        Path file=dir.resolve("profile/site-data.bin");SiteData data=new SiteData(file);URI site=URI.create("https://a.example.test/"),other=URI.create("https://b.example.test/");SiteData.Storage tab=new SiteData.Storage();
        receive(data,site,"temporary=one; HttpOnly","saved=two; Secure; HttpOnly; Max-Age=60");data.storage(tab,true,site,"set","theme","🌎 oscuro");data.storage(tab,false,site,"set","tab","first");data.flush();
        SiteData restored=new SiteData(file);check(header(restored,site).contains("saved=two")&&!header(restored,site).contains("temporary="),"Session cookie persisted or persistent cookie lost");
        check("🌎 oscuro".equals(restored.storage(new SiteData.Storage(),true,site,"get","theme",null)),"Persistent local storage lost");
        check(restored.storage(new SiteData.Storage(),false,site,"get","tab",null)==null&&restored.storage(tab,true,other,"get","theme",null)==null,"Storage escaped lifetime/origin");
        restored.clear();restored.flush();SiteData empty=new SiteData(file);check(empty.cookieCount()==0&&empty.storage(tab,true,site,"get","theme",null)==null,"Cleared data returned after restart");
    }
    private static void send(HttpExchange e,int code,String mime,String text)throws IOException{byte[] b=text.getBytes(StandardCharsets.UTF_8);e.getResponseHeaders().set("Content-Type",mime);e.sendResponseHeaders(code,b.length);e.getResponseBody().write(b);e.close();}
    public static void main(String[] args)throws Exception{
        StringBuilder unicode=new StringBuilder();for(int i=0;i<=65535;i++)unicode.append((char)i);check(unicode.toString().equals(Json.parse(Json.quote(unicode.toString()))),"JSON UTF-16 round trip");
        cookies();Path dir=Files.createTempDirectory("aster-site-tests-");persistence(dir);
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);ExecutorService executor=Executors.newCachedThreadPool();server.setExecutor(executor);AtomicInteger protectedAssets=new AtomicInteger();
        server.createContext("/login",e->{e.getRequestBody().readAllBytes();e.getResponseHeaders().add("Set-Cookie","sid=fixture; Path=/; HttpOnly");e.getResponseHeaders().add("Set-Cookie","theme=dark; Path=/; Max-Age=60");e.getResponseHeaders().set("Location","/account");e.sendResponseHeaders(303,-1);e.close();});
        server.createContext("/account",e->{boolean signed=Objects.toString(e.getRequestHeaders().getFirst("Cookie"),"").contains("sid=fixture");send(e,200,"text/html",signed?"<title>Account</title><link rel='stylesheet' href='/theme.css'><h1>Signed in</h1>":"<h1>Signed out</h1>");});
        server.createContext("/theme.css",e->{if(Objects.toString(e.getRequestHeaders().getFirst("Cookie"),"").contains("sid=fixture"))protectedAssets.incrementAndGet();send(e,200,"text/css","h1 { color: #123456 }");});
        server.createContext("/cookie.js",e->{boolean signed=Objects.toString(e.getRequestHeaders().getFirst("Cookie"),"").contains("sid=fixture");send(e,200,"text/javascript","var scriptSigned="+signed+";");});
        server.createContext("/api",e->{e.getResponseHeaders().add("Set-Cookie","api=received; Path=/");send(e,200,"text/plain",Objects.toString(e.getRequestHeaders().getFirst("Cookie"),"none"));});
        server.createContext("/file.mp4",e->{send(e,200,"video/mp4",Objects.toString(e.getRequestHeaders().getFirst("Cookie"),"none"));});
        server.start();URI base=URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/");SiteData data=new SiteData();SiteData.Storage tab=new SiteData.Storage();
        try{
            Engine.Document account=PageLoader.load(base.resolve("login"),"user=test".getBytes(StandardCharsets.UTF_8),data,null);
            check(account.text().contains("Signed in")&&protectedAssets.get()==1,"POST/redirect/page/CSS did not share session");
            check(ResourceLoader.script(base,"/cookie.js",data).contains("true"),"Classic script resource lost session");
            try(ScriptSession js=page(data,tab,base)){
                check(Boolean.TRUE.equals(js.eval("document.cookie.includes('theme=dark')&&!document.cookie.includes('sid=')")),"JS cookie visibility");
                js.eval("document.cookie='client=ok; Path=/';localStorage.setItem('language','es');sessionStorage.setItem('tab','one');var result=null;fetch('/api',{credentials:'include'}).then(r=>r.text()).then(t=>result=t);void 0");
                await(js,"result&&result.includes('sid=fixture')&&result.includes('client=ok')");
                check(data.documentCookie(base).contains("api=received"),"Fetch response did not update cookie jar");
                data.setDocumentCookie(base,"api=gone; Path=/; Max-Age=0");js.eval("result=null;fetch('/api',{credentials:'omit'}).then(r=>r.text()).then(t=>result=t);void 0");await(js,"result==='none'");check(!data.documentCookie(base).contains("api="),"credentials omit accepted Set-Cookie");
                check(Boolean.TRUE.equals(js.eval("localStorage.color='mint';Object.keys(localStorage).includes('color')&&localStorage.getItem('color')==='mint'&&localStorage.length===2")),"Storage property API");
                check(Boolean.TRUE.equals(js.eval("delete localStorage.color;localStorage.color===undefined&&localStorage.getItem('color')===null")),"Storage delete API");
                check(Boolean.TRUE.equals(js.eval("var quota='';try{localStorage.setItem('huge','x'.repeat(65536));}catch(e){quota=e.name;}quota==='QuotaExceededError'&&localStorage.getItem('language')==='es'")),"Quota did not preserve previous data");
                check(Boolean.TRUE.equals(js.eval("localStorage.setItem('unicode','\\ud800'.repeat(20000));var exact=localStorage.getItem('unicode').length===20000;localStorage.removeItem('unicode');exact")),"Storage UTF-16/IPC size mismatch");
                js.eval("document.URL='https://attacker.example/';document.cookie='bound=original; Path=/';void 0");check(data.documentCookie(base).contains("bound=original")&&data.documentCookie(URI.create("https://attacker.example/")).isEmpty(),"Script selected its own trusted origin");
            }
            try(ScriptSession same=page(data,tab,base);ScriptSession newTab=page(data,new SiteData.Storage(),base);ScriptSession other=page(data,tab,URI.create("http://localhost:"+server.getAddress().getPort()+"/"))){
                check(Boolean.TRUE.equals(same.eval("localStorage.language==='es'&&sessionStorage.tab==='one'")),"Reload lost storage");
                check(Boolean.TRUE.equals(newTab.eval("localStorage.language==='es'&&sessionStorage.getItem('tab')===null")),"Tab storage leaked");
                check(Boolean.TRUE.equals(other.eval("localStorage.length===0&&sessionStorage.length===0&&document.cookie===''")),"Origin storage leaked");
                newTab.eval("localStorage.language='en';void 0");check("en".equals(same.eval("localStorage.language")),"Cross-tab synchronous storage stale");
            }
            try(ScriptSession internal=page(data,tab,PageLoader.HOME)){check(Boolean.TRUE.equals(internal.eval("var refused='';try{localStorage.setItem('key','value');}catch(e){refused=e.name;}refused==='SecurityError'")),"Internal page received website storage");}
            ByteArrayOutputStream download=new ByteArrayOutputStream();FileTransfer.save(base.resolve("file.mp4"),download,(n,total)->{},data.request(base,false,"GET"));check(download.toString("UTF-8").contains("sid=fixture"),"Android download core lost session");
            try(MediaRelay relay=new MediaRelay(base,base.resolve("file.mp4"),data);InputStream input=relay.uri().toURL().openStream()){check(new String(input.readAllBytes(),StandardCharsets.UTF_8).contains("sid=fixture"),"Media relay lost session");}
            try(DownloadManager manager=new DownloadManager(null,4096,data)){DownloadManager.Transfer transfer=manager.start(base.resolve("file.mp4"),dir.resolve("download.bin"));long end=System.nanoTime()+5_000_000_000L;while(!transfer.finished()&&System.nanoTime()<end)Thread.sleep(20);check(transfer.state==DownloadManager.State.COMPLETE&&Files.readString(transfer.target).contains("sid=fixture"),"Desktop download lost session");}
            Preferences prefs=Preferences.userRoot().node("io/aster/site-data-test-"+UUID.randomUUID());prefs.put("note-test","keep");PreviewMain[] app={null};
            SwingUtilities.invokeAndWait(()->{app[0]=new PreviewMain(prefs,data);app[0].current().sessionStorage.access(base,"set","tab","clear-me");app[0].clearWebsiteData();check(app[0].current().sessionStorage.access(base,"get","tab",null)==null,"Clear kept tab storage");app[0].dispose();});
            check(data.cookieCount()==0&&prefs.get("note-test","").equals("keep"),"Clear removed notes or kept cookies");prefs.removeNode();
            check(PageLoader.load(base.resolve("account"),null,data,null).text().contains("Signed out"),"Cleared session still signed in");
            System.out.println("Website sessions passed: real POST/redirect/CSS/script/fetch/download/media, cookie security and expiry, bounded synchronous storage, tab/origin isolation, persistence and clear-data logout. No premium-service authentication was tested.");
        }finally{server.stop(0);executor.shutdownNow();try(java.util.stream.Stream<Path> files=Files.walk(dir)){for(Path path:files.sorted(Comparator.reverseOrder()).toArray(Path[]::new))Files.deleteIfExists(path);}}
    }
}
