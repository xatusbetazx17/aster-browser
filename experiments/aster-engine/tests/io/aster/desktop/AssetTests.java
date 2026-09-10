package io.aster.desktop;

import io.aster.engine.*;
import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.zip.GZIPOutputStream;

/** Two actual HTTP origins exercise CDN loading, policy decisions and cookie isolation. */
public final class AssetTests {
    private static void check(boolean value,String why){if(!value)throw new AssertionError(why);}
    private interface Action{void run()throws Exception;}
    private static void refuses(Action action,String reason)throws Exception{
        try{action.run();throw new AssertionError("Expected refusal: "+reason);}
        catch(IOException e){check(e.getMessage().toLowerCase(Locale.ROOT).contains(reason),"Wrong refusal: "+e);}
    }
    private static String hash(byte[] body,int bits)throws Exception{return "sha"+bits+"-"+Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-"+bits).digest(body));}
    private static PageMarkup.Asset sheet(URI page,URI uri,String attrs){return PageMarkup.stylesheetAssets(page,"<link rel='stylesheet' href='"+uri+"' "+attrs+">").get(0);}
    private static byte[] fetch(URI page,URI target,String attrs,SiteData data)throws Exception{return PageAssets.fetch(page,sheet(page,target,attrs),false,data.request(page,false,"GET"));}
    private static void send(HttpExchange e,String type,byte[] body)throws IOException{
        e.getResponseHeaders().set("Content-Type",type);e.sendResponseHeaders(200,body.length);try(OutputStream out=e.getResponseBody()){out.write(body);}finally{e.close();}
    }
    public static void main(String[] args)throws Exception{
        PageAssets.useConnections(AssetConnection::new);
        HttpServer origin=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0),cdn=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        URI page=URI.create("http://localhost:"+origin.getAddress().getPort()+"/"),remote=URI.create("http://127.0.0.1:"+cdn.getAddress().getPort()+"/");
        byte[] css="p{color:#123456}@media(max-width:600px){p{color:#654321}}".getBytes(StandardCharsets.UTF_8);
        String integrity=hash(css,384);AtomicReference<String> cookie=new AtomicReference<>(),requestOrigin=new AtomicReference<>(),localCookie=new AtomicReference<>();
        AtomicInteger hits=new AtomicInteger(),originHits=new AtomicInteger();AtomicReference<String> html=new AtomicReference<>("<p>Start</p>");
        origin.createContext("/",e->{originHits.incrementAndGet();String path=e.getRequestURI().getPath();localCookie.set(e.getRequestHeaders().getFirst("Cookie"));
            if(path.equals("/redirect")){e.getResponseHeaders().set("Location",remote.resolve("style").toString());e.sendResponseHeaders(302,-1);e.close();return;}
            if(path.equals("/style")){e.getResponseHeaders().add("Set-Cookie","asset=ok; Path=/");e.getResponseHeaders().add("Set-Cookie","second=ok; Path=/");send(e,"text/css",css);}
            else{if(path.equals("/csp"))e.getResponseHeaders().set("Content-Security-Policy","default-src 'self'");send(e,"text/html",html.get().getBytes(StandardCharsets.UTF_8));}});
        cdn.createContext("/",e->{hits.incrementAndGet();String path=e.getRequestURI().getPath();cookie.set(e.getRequestHeaders().getFirst("Cookie"));requestOrigin.set(e.getRequestHeaders().getFirst("Origin"));
            e.getResponseHeaders().set("Set-Cookie","thirdparty=refused; Path=/");
            if(path.equals("/cors")){String value=e.getRequestHeaders().getFirst("Origin");if(value!=null)e.getResponseHeaders().set("Access-Control-Allow-Origin",value);}
            if(path.equals("/star"))e.getResponseHeaders().set("Access-Control-Allow-Origin","*");
            if(path.equals("/wrong"))e.getResponseHeaders().set("Access-Control-Allow-Origin","https://wrong.invalid");
            if(path.equals("/multiple")){e.getResponseHeaders().add("Access-Control-Allow-Origin","*");e.getResponseHeaders().add("Access-Control-Allow-Origin",page.toString());}
            if(path.equals("/private"))e.getResponseHeaders().set("Cross-Origin-Resource-Policy","same-origin");
            if(path.equals("/site"))e.getResponseHeaders().set("Cross-Origin-Resource-Policy","same-site");
            if(path.equals("/back")){e.getResponseHeaders().set("Location",page.resolve("style").toString());e.sendResponseHeaders(302,-1);e.close();return;}
            if(path.equals("/unsafe")){e.getResponseHeaders().set("Location","file:///etc/passwd");e.sendResponseHeaders(302,-1);e.close();return;}
            byte[] bytes=path.equals("/large")?new byte[65537]:path.equals("/transport-large")?new byte[PageAssets.IMAGE_LIMIT+1]:css;
            if(path.equals("/gzip")){ByteArrayOutputStream out=new ByteArrayOutputStream();try(GZIPOutputStream gzip=new GZIPOutputStream(out)){gzip.write(bytes);}bytes=out.toByteArray();e.getResponseHeaders().set("Content-Encoding","gzip");}
            try{send(e,path.equals("/mime")?"text/html":"text/css",bytes);}catch(IOException expected){e.close();}});
        origin.start();cdn.start();
        try{
            SiteData data=new SiteData();data.setDocumentCookie(page,"firstparty=secret; Path=/");data.setDocumentCookie(remote,"cdn=secret; Path=/");
            check(Arrays.equals(fetch(page,remote.resolve("style"),"",data),css),"CDN stylesheet not loaded");
            check(cookie.get()==null&&requestOrigin.get()==null&&!data.documentCookie(remote).contains("thirdparty"),"Cross-origin cookies escaped isolation");
            fetch(page,page.resolve("style"),"",data);check(localCookie.get().contains("firstparty=secret"),"First-party asset cookie missing");
            check(data.documentCookie(page).contains("asset=ok")&&data.documentCookie(page).contains("second=ok"),"Multiple first-party Set-Cookie headers lost by transport");
            check(Arrays.equals(fetch(page,remote.resolve("cors"),"crossorigin='anonymous' integrity='"+integrity+"'",data),css),"CORS/SRI stylesheet missing");
            check(requestOrigin.get().equals(page.toString().substring(0,page.toString().length()-1))&&cookie.get()==null,"Origin not sent or CORS leaked cookies");
            fetch(page,remote.resolve("star"),"crossorigin",data);
            for(String path:List.of("style","wrong","multiple"))refuses(()->fetch(page,remote.resolve(path),"crossorigin",data),"cors");
            for(String path:List.of("private","site"))refuses(()->fetch(page,remote.resolve(path),"",data),"policy");
            int before=hits.get();refuses(()->fetch(page,remote.resolve("style"),"crossorigin='use-credentials'",data),"credentialed");
            refuses(()->fetch(page,remote.resolve("style"),"integrity='"+integrity+"'",data),"requires cors");check(hits.get()==before,"Unsupported credential/integrity mode contacted CDN");
            fetch(page,page.resolve("style"),"integrity='"+integrity+"'",data);
            String badStrong=hash("different".getBytes(StandardCharsets.UTF_8),512),goodWeak=hash(css,256);
            refuses(()->fetch(page,page.resolve("style"),"integrity='"+goodWeak+" "+badStrong+"'",data),"integrity");
            fetch(page,page.resolve("style"),"integrity='"+badStrong+" "+hash(css,512)+"'",data);
            refuses(()->fetch(page,page.resolve("style"),"integrity='sha384-invalid'",data),"integrity");
            check(Arrays.equals(fetch(page,remote.resolve("gzip"),"",data),css),"CDN gzip stylesheet changed");
            refuses(()->fetch(page,remote.resolve("large"),"",data),"size");
            refuses(()->fetch(page,remote.resolve("transport-large"),"",data),"request failed");
            refuses(()->fetch(page,remote.resolve("mime"),"",data),"type");
            refuses(()->fetch(page,remote.resolve("unsafe"),"",data),"redirect");
            fetch(page,page.resolve("redirect"),"",data);check(cookie.get()==null,"Redirect forwarded first-party cookies to CDN");
            fetch(page,remote.resolve("back"),"",data);check(localCookie.get()==null,"Cookies resumed after crossing origin and returning");
            before=hits.get();data.protection.custom("127.0.0.1");
            refuses(()->fetch(page,remote.resolve("style"),"",data),"site protection");
            refuses(()->fetch(page,page.resolve("redirect"),"",data),"site protection");check(hits.get()==before,"Blocked CDN or redirect contacted server");data.protection.custom("");
            URI secure=URI.create("https://example.org/");before=hits.get();refuses(()->PageAssets.fetch(secure,remote.resolve("style"),false),"mixed-content");check(hits.get()==before,"Mixed-content target contacted server");
            String link="<link rel='stylesheet' href='"+remote.resolve("cors")+"' crossorigin integrity='"+integrity+"'>";
            html.set("<head><style>p{color:red}</style>"+link+"<style media='print'>p{color:blue}</style></head><p>CDN page</p>");
            Engine.Document loaded=PageLoader.load(page,null,data,null);check(loaded.runs.get(0).style.color==0xff123456,"External stylesheet source order lost");
            check(Engine.forViewport(loaded,400,700).runs.get(0).style.color==0xff654321,"Fetched media rule was not retained");
            try(ScriptSession script=new ScriptSession()){
                Map<String,Object> snapshot=script.start(loaded);Engine.Document interactive=Engine.parseInteractive(loaded,snapshot.get("html").toString());
                check(interactive.runs.get(0).style.color==0xff123456,"Script startup lost fetched CSS");
                check(Engine.withoutActions(interactive).runs.get(0).style.color==0xff123456,"Stopping scripts lost fetched CSS");
                Map<?,?> changed=(Map<?,?>)script.eval("document.querySelector('link').setAttribute('integrity','sha384-invalid');__aster.tick(1)");
                check(Engine.parseInteractive(interactive,changed.get("html").toString()).runs.get(0).style.color==0xffff0000,"Changed integrity reused an earlier stylesheet response");
            }
            html.set("<head>"+link+"<style>p{color:red}</style></head><p>Last inline wins</p>");check(PageLoader.load(page).runs.get(0).style.color==0xffff0000,"Later inline source order lost");
            html.set("<head>"+link.replace("<link ","<link media='(max-width:500px)' ")+"</head><p>Media attribute</p>");
            loaded=PageLoader.load(page);check(loaded.runs.get(0).style.color==Engine.DEFAULT.color&&Engine.forViewport(loaded,400,700).runs.get(0).style.color==0xff654321,"Link media condition lost");
            before=hits.get();loaded=PageLoader.load(page.resolve("csp"));check(loaded.scriptsBlocked&&hits.get()==before,"CSP fallback fetched external stylesheet");
            check(PageMarkup.stylesheetAssets(page,"<link rel='alternate stylesheet' href='"+remote+"'><link rel='stylesheet' disabled href='"+remote+"'>").isEmpty(),"Inactive stylesheet selected");
            String img="<img src='"+remote.resolve("image")+"'",images=img+"><div style='display:none'>"+img+" crossorigin></div>";
            check(PageMarkup.images(page,images).size()==2,"Responsive hidden image or distinct fetch policy omitted");
            Engine.Document imageDoc=Engine.parse(page,img+">"+img+" crossorigin>");check(!imageDoc.runs.get(0).imageKey.equals(imageDoc.runs.get(1).imageKey),"Image cache ignores element fetch policy");
        }finally{origin.stop(0);cdn.stop(0);}
        System.out.println("CDN assets passed: actual cross-origin HTTP, CORS/SRI, strongest hashes, gzip and limits, cookie isolation, redirects, protection, CSP, source order and QuickJS stylesheet retention.");
    }
}
