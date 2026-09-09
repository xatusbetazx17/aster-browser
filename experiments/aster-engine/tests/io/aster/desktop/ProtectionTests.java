package io.aster.desktop;

import io.aster.engine.*;
import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.*;

/** Assert actual server contact, not just what a blocking badge says. */
public final class ProtectionTests {
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    private interface Action{void run()throws Exception;}
    private static void blocked(Action action)throws Exception{try{action.run();throw new AssertionError("Request was not blocked");}catch(IOException expected){check(expected.getMessage().contains("site protection"),expected.toString());}}
    private static void await(ScriptSession js,String expression)throws Exception{long until=System.nanoTime()+5_000_000_000L;do{js.pump();if(Boolean.TRUE.equals(js.eval(expression)))return;Thread.sleep(10);}while(System.nanoTime()<until);throw new AssertionError("Blocked request did not settle: "+expression);}
    public static void main(String[] args)throws Exception{
        URI page=URI.create("https://news.example.org/read");Protection p=new Protection();
        check(!p.reason(page,URI.create("https://ads.doubleclick.net/a")).isEmpty(),"Starter subdomain rule missing");
        for(String url:List.of("https://notdoubleclick.net/","https://doubleclick.net.example.org/","https://example.org/doubleclick.net","https://example.org/?host=doubleclick.net"))check(p.reason(page,URI.create(url)).isEmpty(),"Substring false positive");
        check(!p.reason(page,URI.create("https://DOUBLECLICK.NET./a")).isEmpty(),"Case/trailing dot bypass");
        p.custom("# my list\nads.example.org\nBÜCHER.example\n");check(p.custom().contains("xn--bcher-kva.example"),"IDN rule not canonicalized");
        String before=p.custom();for(String invalid:List.of("https://example.org","*.example.org","||example.org^","0.0.0.0 example.org","com","a..com")){try{p.custom(invalid);throw new AssertionError("Invalid rule accepted");}catch(IllegalArgumentException expected){}check(p.custom().equals(before),"Invalid input partially replaced rules");}
        p.allow(page,true);check(p.reason(page,URI.create("https://doubleclick.net/a")).isEmpty(),"Site exception ignored");
        check(!p.reason(URI.create("http://news.example.org/"),URI.create("https://doubleclick.net/a")).isEmpty(),"Exception crossed origin");
        Path dir=Files.createTempDirectory("aster-protection-test");SiteData data=new SiteData(dir.resolve("site-data.bin"));
        data.protection.custom("127.0.0.1");data.protection.allow(page,true);data.flush();SiteData restored=new SiteData(dir.resolve("site-data.bin"));check(restored.protection.custom().equals("127.0.0.1")&&restored.protection.allowed(page),"Rules/exception did not survive replacement profile reopen");
        AtomicInteger hits=new AtomicInteger(),preflights=new AtomicInteger();HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        URI local=URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/"),alias=URI.create("http://localhost:"+server.getAddress().getPort()+"/");
        server.createContext("/",e->{hits.incrementAndGet();if(e.getRequestMethod().equals("OPTIONS"))preflights.incrementAndGet();String path=e.getRequestURI().getPath();
            if(path.equals("/redirect")){e.getResponseHeaders().set("Location",local.resolve("final").toString());e.sendResponseHeaders(302,-1);e.close();return;}
            byte[] body=(path.equals("/page")?"<link rel='stylesheet' href='/style'><h1>Still readable</h1>":"fixture").getBytes(StandardCharsets.UTF_8);e.getResponseHeaders().set("Content-Type",path.equals("/page")?"text/html":path.equals("/style")?"text/css":path.equals("/script.js")?"text/javascript":"text/plain");e.sendResponseHeaders(200,body.length);e.getResponseBody().write(body);e.close();});server.start();
        try{
            Engine.Document doc=PageLoader.load(local.resolve("page"),null,data,data.request(null,true,"GET"));check(doc.text().contains("Still readable")&&hits.get()==1,"Navigation blocked or stylesheet contacted server");
            blocked(()->PageAssets.fetch(local,local.resolve("image"),true,data.request(local,false,"GET")));
            blocked(()->ResourceLoader.script(local,"script.js",data));check(hits.get()==1,"Blocked native assets contacted server");
            try(ScriptSession js=new ScriptSession(data,new SiteData.Storage())){
                js.start(Engine.parse(local,"<p>Fixture</p>"));
                js.eval("var failed=false;fetch('/api').catch(()=>failed=true);void 0");await(js,"failed");check(hits.get()==1,"Blocked fetch contacted server");
                js.eval("var closed=false;var socket=new WebSocket('ws://127.0.0.1:"+local.getPort()+"/socket');socket.onerror=()=>closed=true;void 0");await(js,"closed");check(hits.get()==1,"Blocked WebSocket contacted server");
            }
            try(ScriptSession js=new ScriptSession(data,new SiteData.Storage())){
                js.start(Engine.parse(alias,"<p>Cross origin</p>"));
                js.eval("var failed=false;fetch("+Json.quote(local.resolve("api").toString())+",{method:'PUT',headers:{'X-Test':'value'},body:'data'}).catch(()=>failed=true);void 0");await(js,"failed");check(preflights.get()==0&&hits.get()==1,"Blocked preflight leaked to server");
                js.eval("var redirected=false;fetch('/redirect').catch(()=>redirected=true);void 0");await(js,"redirected");check(hits.get()==2,"Redirect target escaped blocking");
            }
            try(MediaRelay relay=new MediaRelay(local,local.resolve("video.mp4"),data)){
                HttpURLConnection c=(HttpURLConnection)relay.uri().toURL().openConnection();try{check(c.getResponseCode()==502,"Blocked media relay did not fail");}finally{c.disconnect();}check(hits.get()==2,"Blocked decoder request contacted server");
            }
            ByteArrayOutputStream file=new ByteArrayOutputStream();FileTransfer.save(local.resolve("download"),file,(n,total)->{},data.request(local,false,"GET").explicitDownload());check(file.toString("UTF-8").equals("fixture")&&hits.get()==3,"Explicit file download was blocked");
            check(data.protection.snapshot(local).blocked==6,"Activity did not count actual blocked attempts");
            data.protection.allow(local,true);check(ResourceLoader.script(local,"script.js",data).equals("fixture")&&hits.get()==4,"Site exception did not restore resource contact");
        }finally{server.stop(0);}
        SwingUtilities.invokeAndWait(()->{ProtectionView view=new ProtectionView(data.protection,local,()->{});view.enabled.doClick();check(!data.protection.enabled(),"Native global control not connected");view.exception.doClick();check(!data.protection.allowed(local),"Native site control not connected");});
        data.flush();SiteData reopened=new SiteData(dir.resolve("site-data.bin"));check(!reopened.protection.enabled()&&!reopened.protection.allowed(local),"Settings did not survive restart");check(reopened.protection.snapshot(local).blocked==0,"Browsing activity persisted to disk");
        try(java.util.stream.Stream<Path> paths=Files.walk(dir)){paths.sorted(Comparator.reverseOrder()).forEach(path->{try{Files.delete(path);}catch(IOException e){throw new RuntimeException(e);}});}
        System.out.println("Protection passed: offline domain boundaries, atomic rules, persistent origin exceptions, blocked CSS/images/scripts/fetch/preflights/WebSockets/media/redirects never reached the server; explicit navigation/downloads and native controls verified.");
    }
}
