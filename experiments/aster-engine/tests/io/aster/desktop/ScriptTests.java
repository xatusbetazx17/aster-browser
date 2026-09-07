package io.aster.desktop;

import io.aster.engine.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.nio.file.*;

/** Real QuickJS processes, real DOM mutations, real HTTP resources, hostile scripts. */
public final class ScriptTests {
    private static void check(boolean ok,String reason) { if(!ok)throw new AssertionError(reason); }
    private static void refused(String code) throws Exception {
        long start=System.nanoTime();
        try(ScriptSession js=new ScriptSession()) { try { js.eval(code);throw new AssertionError("Unbounded script succeeded"); } catch(java.io.IOException expected) { check(!js.alive(),"Failing script process survived"); } }
        check(System.nanoTime()-start<4_000_000_000L,"Script watchdog exceeded four seconds");
    }
    public static void main(String[] args) throws Exception {
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/app.js",e->{byte[] b="document.getElementById('external').textContent='Fetched same-origin JS';".getBytes(StandardCharsets.UTF_8);e.getResponseHeaders().set("Content-Type","text/javascript");e.sendResponseHeaders(200,b.length);e.getResponseBody().write(b);e.close();});
        server.createContext("/cross",e->{e.getResponseHeaders().set("Location","http://localhost:"+server.getAddress().getPort()+"/app.js");e.sendResponseHeaders(302,-1);e.close();});
        server.createContext("/wrong",e->{e.getResponseHeaders().set("Content-Type","text/html");e.sendResponseHeaders(200,1);e.getResponseBody().write('x');e.close();});server.start();
        byte[] clip="direct media bytes".getBytes(StandardCharsets.UTF_8);
        server.createContext("/clip.mp4",e->{e.getResponseHeaders().set("Content-Type","video/mp4");e.sendResponseHeaders(200,clip.length);e.getResponseBody().write(clip);e.close();});
        server.createContext("/large.mp4",e->{e.sendResponseHeaders(200,67108865);e.close();});
        URI base=URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/");
        try {
            try(ScriptSession js=new ScriptSession()) {
                Engine.Document doc=Engine.parse(base,"<title>Before</title><p id='external'></p><p id='count'>0</p><button id='go'>Add</button><script src='/app.js'></script><script>let count=0;document.title='Running';document.getElementById('go').addEventListener('click',()=>document.getElementById('count').textContent=++count);document.addEventListener('keydown',e=>document.getElementById('count').textContent=e.key);document.addEventListener('DOMContentLoaded',()=>Promise.resolve().then(()=>document.getElementById('external').append(' + ready')));setTimeout(()=>document.getElementById('count').textContent='Timer fired',40);</script>");
                Map<String,Object> snapshot=js.start(doc);
                check(snapshot.get("html").toString().contains("Fetched same-origin JS + ready"),"External scripts/Promise/ready event failed");
                check(snapshot.get("title").equals("Running"),"Document title did not change");
                int id=((Number)js.eval("document.getElementById('go')._id")).intValue();
                js.eval("__aster.click("+id+")");check(Engine.parseInteractive(base,js.snapshot().get("html").toString()).text().contains("1"),"Click did not update rendered text");
                js.eval("__aster.key('keydown','ArrowLeft','ArrowLeft',false)");check(js.snapshot().get("html").toString().contains("ArrowLeft"),"Keyboard event did not reach document");
                js.eval("__aster.tick(50)");check(js.snapshot().get("html").toString().contains("Timer fired"),"Timer did not fire");
                check(Boolean.TRUE.equals(js.eval("typeof std==='undefined' && typeof os==='undefined' && typeof require==='undefined' && typeof process==='undefined' && typeof fetch==='undefined' && typeof WebSocket==='undefined' && typeof RTCPeerConnection==='undefined' && typeof MediaSource==='undefined' && typeof navigator.requestMediaKeySystemAccess==='undefined'")),"Host exposed unsupported APIs or system access");
                check(Boolean.TRUE.equals(js.eval("navigator.getGamepads().length===0")),"Controllers exposed before native permission");
                js.controller(true);check(js.eval("navigator.getGamepads()") instanceof List,"Controller provider did not return gamepad snapshots");js.controller(false);
                check(Boolean.TRUE.equals(js.eval("navigator.getGamepads().length===0")),"Controller permission was not revoked");
                check(Boolean.TRUE.equals(js.eval("(2n**64n).toString()==='18446744073709551616' && [1,2,3].map(x=>x*2).join(',')==='2,4,6'")),"Actual ECMAScript runtime failed");
            }
            for(String path:new String[]{"/cross","/wrong"}) try{ResourceLoader.script(base,path);throw new AssertionError("Unsafe script response accepted");}catch(java.io.IOException expected){}
            Path downloaded=ResourceLoader.media(base,base.resolve("/clip.mp4"));try{check(Arrays.equals(clip,Files.readAllBytes(downloaded)),"Media fetch changed bytes");}finally{Files.delete(downloaded);}
            for(String path:new String[]{"/large.mp4","/live.m3u8"})try{Path unexpected=ResourceLoader.media(base,base.resolve(path));Files.deleteIfExists(unexpected);throw new AssertionError("Unsupported/oversized media accepted");}catch(java.io.IOException expected){}
            try{ResourceLoader.media(URI.create("https://example.com/"),base.resolve("/clip.mp4"));throw new AssertionError("Mixed media accepted");}catch(java.io.IOException expected){}
            try(ScriptSession js=new ScriptSession()) { try{js.start(Engine.parse(base,"<script>1</script>").blockScripts());throw new AssertionError("CSP header bypassed");}catch(java.io.IOException expected){} }
            try(ScriptSession js=new ScriptSession()) { try{js.start(Engine.parse(base,"<meta http-equiv='Content-Security-Policy' content=\"script-src 'none'\"><script>1</script>"));throw new AssertionError("CSP meta bypassed");}catch(java.io.IOException expected){} }
            refused("while(true){}");refused("const a=[];while(true)a.push(new Array(500000).fill('memory'));");refused("function f(){return f()}f();");
            refused("function f(){Promise.resolve().then(f)}f();");
            // A stalled native host cannot freeze the Swing process; protocol timeout kills it.
            System.out.println("JavaScript tests passed: real QuickJS, same-origin scripts, DOM/title/click/key updates, timers, Promises, controller permission/revocation, CSP refusal, MIME/redirect checks, CPU/memory/stack bounds. No physical controller tested.");
        } finally { server.stop(0); }
    }
}
