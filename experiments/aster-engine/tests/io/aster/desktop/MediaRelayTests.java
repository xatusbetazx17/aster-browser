package io.aster.desktop;

import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

public final class MediaRelayTests {
    private static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
    private static byte[] get(URI uri)throws Exception{HttpURLConnection c=(HttpURLConnection)uri.toURL().openConnection();c.setConnectTimeout(2000);c.setReadTimeout(3000);try(InputStream in=c.getInputStream()){return in.readAllBytes();}finally{c.disconnect();}}
    public static void main(String[] args)throws Exception{
        try(StreamSmoke.Fixture source=new StreamSmoke.Fixture();MediaRelay relay=new MediaRelay(source.uri(),source.uri().resolve("master.m3u8"))){
            String master=new String(get(relay.uri()),StandardCharsets.UTF_8);check(!master.contains(source.uri().toString()),"HLS exposed unvalidated upstream URLs to decoder");
            HttpURLConnection head=(HttpURLConnection)relay.uri().toURL().openConnection();head.setRequestMethod("HEAD");try{check(head.getResponseCode()==200&&head.getContentLengthLong()==master.getBytes(StandardCharsets.UTF_8).length,"HLS HEAD length must describe the rewritten GET response");}finally{head.disconnect();}
            URI variant=URI.create(Arrays.stream(master.split("\n")).filter(s->s.startsWith("http")).findFirst().orElseThrow());
            String playlist=new String(get(variant),StandardCharsets.UTF_8);for(String line:playlist.split("\n"))if(line.startsWith("http")){byte[] segment=get(URI.create(line));check(segment.length>1000&&(segment[0]&255)==0x47,"HLS relay changed MPEG-TS segment bytes");}
            check(source.requested.size()==2,"HLS did not fetch both independent segments");
            for(String manifest:Arrays.asList("#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"key\"\nlow0.ts", "#EXTM3U\nhttp://localhost:1/other.ts", "#EXTM3U\nfile:///etc/passwd", "#EXTM3U\n#EXT-X-SESSION-KEY:METHOD=SAMPLE-AES,URI=\"key\"", "#EXTM3U\n#EXT-X-DEFINE:NAME=\"host\",VALUE=\"example.com\"", "#EXTM3U\n#EXT-X-MEDIA:TYPE=AUDIO,URI=unquoted")){
                try{relay.rewrite(source.uri(),manifest);throw new AssertionError("Unsupported HLS accepted");}catch(IOException expected){}
            }
            java.net.http.HttpResponse<Void> browser=java.net.http.HttpClient.newHttpClient().send(java.net.http.HttpRequest.newBuilder(relay.uri()).header("Origin","https://example.com").GET().build(),java.net.http.HttpResponse.BodyHandlers.discarding());check(browser.statusCode()==502,"Web page could use private decoder relay");
        }
        CountDownLatch release=new CountDownLatch(1);HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/progressive.mp4",e->{try{e.getResponseHeaders().set("Content-Type","video/mp4");e.sendResponseHeaders(200,20);e.getResponseBody().write(new byte[10]);e.getResponseBody().flush();release.await(3,TimeUnit.SECONDS);e.getResponseBody().write(new byte[10]);}catch(Exception ignored){}finally{e.close();}});
        server.createContext("/range.mp4",e->{String range=e.getRequestHeaders().getFirst("Range");if(!"bytes=2-4".equals(range)){e.sendResponseHeaders(400,-1);e.close();return;}e.getResponseHeaders().set("Content-Range","bytes 2-4/10");e.sendResponseHeaders(206,3);e.getResponseBody().write(new byte[]{2,3,4});e.close();});server.start();
        URI base=URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/");
        try{
            try(MediaRelay relay=new MediaRelay(base,base.resolve("progressive.mp4"))){
                HttpURLConnection c=(HttpURLConnection)relay.uri().toURL().openConnection();c.setReadTimeout(2000);try(InputStream in=c.getInputStream()){check(in.readNBytes(10).length==10&&release.getCount()==1,"Media waited for the whole file");release.countDown();check(in.readAllBytes().length==10,"Progressive media remainder was lost");}finally{c.disconnect();}
            }
            try(MediaRelay relay=new MediaRelay(base,base.resolve("range.mp4"))){HttpURLConnection c=(HttpURLConnection)relay.uri().toURL().openConnection();c.setRequestProperty("Range","bytes=2-4");check(c.getResponseCode()==206&&"bytes 2-4/10".equals(c.getHeaderField("Content-Range")),"Range headers lost");try(InputStream in=c.getInputStream()){check(Arrays.equals(in.readAllBytes(),new byte[]{2,3,4}),"Range bytes changed");}finally{c.disconnect();}}
            try{new MediaRelay(URI.create("https://example.com/"),base.resolve("range.mp4"));throw new AssertionError("Mixed-content media accepted");}catch(IOException expected){}
            System.out.println("Media transport passed: progressive delivery before EOF, byte ranges, real HLS master/variant/two segments, URL rewriting, encrypted/unsafe playlist refusal and private relay isolation. Native decoder verified separately.");
        }finally{release.countDown();server.stop(0);}
    }
}
