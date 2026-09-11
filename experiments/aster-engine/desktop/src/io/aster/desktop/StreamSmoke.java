package io.aster.desktop;

import io.aster.engine.Engine;
import com.sun.net.httpserver.HttpServer;
import javax.swing.SwingUtilities;
import java.awt.event.MouseEvent;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Explicit --stream-smoke only: actual page JS, local HTTP/HLS, and native decoded frames. */
final class StreamSmoke {
    static final class Fixture implements AutoCloseable {
        final HttpServer server;final AtomicInteger manifests=new AtomicInteger(),segments=new AtomicInteger();
        final Set<String> requested=ConcurrentHashMap.newKeySet();
        final boolean videoOnly=System.getProperty("os.name").startsWith("Windows");
        final boolean hls;
        Fixture()throws Exception{this(true);}
        Fixture(boolean hls)throws Exception{
            this.hls=hls;
            server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),8);
            server.createContext("/",e->{
                String path=e.getRequestURI().getPath(),type;byte[] bytes;
                if(path.equals("/")){type="text/html";bytes=page().getBytes(StandardCharsets.UTF_8);}
                else if(path.equals("/api")){type="application/json";bytes="{\"ready\":true}".getBytes(StandardCharsets.UTF_8);}
                else if(path.equals("/master.m3u8")){type="application/vnd.apple.mpegurl";manifests.incrementAndGet();bytes=("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=64000,RESOLUTION=160x90\nlow.m3u8\n#EXT-X-STREAM-INF:BANDWIDTH=128000,RESOLUTION=160x90\nhigh.m3u8\n").getBytes(StandardCharsets.UTF_8);}
                else if(path.equals("/low.m3u8")||path.equals("/high.m3u8")){String q=path.substring(1,path.indexOf('.'));type="application/vnd.apple.mpegurl";manifests.incrementAndGet();bytes=("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:2\n#EXT-X-MEDIA-SEQUENCE:0\n#EXTINF:2.0,\n"+q+"0.ts\n#EXTINF:2.0,\n"+q+"1.ts\n#EXT-X-ENDLIST\n").getBytes(StandardCharsets.UTF_8);}
                else if(path.matches("/(low|high)[01]\\.ts")){type="video/mp2t";segments.incrementAndGet();requested.add(path);String resource=videoOnly?"video"+path.charAt(path.indexOf('.')-1)+".ts":path.substring(1);bytes=Base64.getMimeDecoder().decode(PreviewMain.resourceText("/hls-"+resource+".b64"));}
                else if(path.equals("/clip.mp4")){type="video/mp4";bytes=Base64.getMimeDecoder().decode(PreviewMain.resourceText("/sample.mp4.b64"));}
                else{e.sendResponseHeaders(404,-1);e.close();return;}
                e.getResponseHeaders().set("Content-Type",type);if(e.getRequestMethod().equals("HEAD")){e.getResponseHeaders().set("Content-Length",Integer.toString(bytes.length));e.sendResponseHeaders(200,-1);}else{e.sendResponseHeaders(200,bytes.length);e.getResponseBody().write(bytes);}e.close();
            });server.start();
        }
        URI uri(){return URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/");}
        private String page(){return "<title>Aster streaming test</title><h1>Video streamed inside Aster</h1><video id='clip' src='"+(hls?"/master.m3u8":"/clip.mp4")+"'></video><button id='play'>Stream</button><p id='result'>Waiting</p><script>"+
            "var v=document.getElementById('clip'),autoplay='',httpReady=false,played=0,pauses=0,once=false,mediaError='',seekResult='',observedTime=0,seekJump=false;fetch('/api').then(r=>r.json()).then(j=>httpReady=j.ready);v.play().catch(e=>autoplay=e.name);"+
            "document.getElementById('play').addEventListener('click',()=>v.play().then(()=>played++).catch(e=>mediaError=e.name));"+
            "v.addEventListener('timeupdate',()=>{if(once&&v.currentTime-observedTime>.5)seekJump=true;observedTime=v.currentTime;document.getElementById('result').textContent='Time: '+v.currentTime.toFixed(1);if(v.currentTime>.25&&!once){once=true;v.pause();}});"+
            "v.addEventListener('pause',()=>{pauses++;try{v.currentTime=1.2;seekResult='accepted';}catch(e){seekResult=e.name;}v.volume=.3;v.muted=true;v.play().then(()=>played++).catch(e=>mediaError=e.name);});"+
            "v.addEventListener('error',()=>mediaError=v.error.message);</script>";}
        public void close(){server.stop(0);}
    }
    interface Check{boolean test()throws Exception;}
    private static void waitFor(String stage,Check test)throws Exception{long end=System.nanoTime()+25_000_000_000L;while(System.nanoTime()<end){if(test.test())return;Thread.sleep(30);}throw new AssertionError("Streaming check timed out: "+stage);}
    private static void edt(Runnable action)throws Exception{SwingUtilities.invokeAndWait(action);}
    static void run(PreviewMain app,String output){new Thread(()->{
        try{for(boolean hls:new boolean[]{true,false})try(Fixture fixture=new Fixture(hls)){
            Path frame=Paths.get(hls?output:output.replaceFirst("\\.png$","")+"-file.png");
            edt(()->app.load(app.current(),fixture.uri(),-1));
            waitFor("HTTP page",()->{boolean[] loaded={false};edt(()->loaded[0]=app.current().original!=null&&app.current().original.uri.equals(fixture.uri()));return loaded[0];});
            edt(()->app.current().runScripts.doClick());
            waitFor("script start",()->{boolean[] loaded={false};edt(()->loaded[0]=app.current().script!=null&&!app.current().scriptStarting);return loaded[0];});
            ScriptSession js=app.current().script;
            waitFor("autoplay refusal and real HTTP JSON",()->Boolean.TRUE.equals(js.eval("autoplay==='NotAllowedError'&&httpReady")));
            edt(()->{
                PreviewMain.PageCanvas c=app.current().canvas;c.setSize(900,800);c.ensureLayout();Engine.Draw draw=c.layout.items.stream().filter(d->d.text.equals("Stream")).findFirst().orElseThrow(()->new AssertionError("Rendered Stream button missing"));
                c.dispatchEvent(new MouseEvent(c,MouseEvent.MOUSE_CLICKED,System.currentTimeMillis(),0,(int)((draw.x+2)*c.scale),(int)((draw.y+4)*c.scale),1,false,MouseEvent.BUTTON1));
            });
            waitFor("page click opened player",()->{boolean[] ready={false};edt(()->ready[0]=app.current().media!=null);return ready[0];});
            AtomicBoolean decoded=new AtomicBoolean();AtomicReference<String> error=new AtomicReference<>();
            edt(()->app.current().media.evidence(frame,()->decoded.set(true),error::set));
            waitFor("decoded blue/red "+(hls?"HLS":"MP4")+" frames",()->{if(error.get()!=null)throw new AssertionError(error.get());return decoded.get();});
            waitFor("page media controls",()->Boolean.TRUE.equals(js.eval("played>=2&&pauses>=1&&v.videoWidth===160&&v.videoHeight===90&&Math.abs(v.volume-.3)<.01&&v.muted&&v.currentTime>=2&&mediaError===''&&"+(hls?"seekResult==='NotSupportedError'&&v.seekable.length===0":"seekResult==='accepted'&&seekJump&&v.seekable.length===1"))));
            if(hls){
                if(fixture.manifests.get()<2||fixture.requested.size()<2)throw new AssertionError("HLS did not fetch a master, variant and two segments");
                for(int restart=1;restart<=3;restart++){
                    int previousSegments=fixture.segments.get();Path evidence=Paths.get(output.replaceFirst("\\.png$","")+"-restart-"+restart+".png");
                    decoded.set(false);edt(()->{app.current().media.evidence(evidence,()->decoded.set(true),error::set);app.current().media.control("restart",null);});
                    waitFor("HLS restart "+restart+" decoded both colors again",()->{if(error.get()!=null)throw new AssertionError(error.get());return decoded.get();});
                    if(fixture.segments.get()<previousSegments+2)throw new AssertionError("HLS restart did not fetch two fresh segments");
                }
            }
            edt(()->app.load(app.current(),URI.create("aster:home"),-1));if(js.alive())throw new AssertionError("Navigation left the streaming page alive");
            System.out.println("Native streaming passed: "+(hls?"HLS master/variant/two segments, seek refusal and three decoder restarts":"progressive MP4 and an observed seek jump")+", real HTTP JSON, autoplay refused, rendered Play click, actual blue/red H.264 frames, page play Promise, pause/resume/volume/mute/events and navigation cleanup. Audio track="+(hls&&!fixture.videoOnly)+". Physical audio, bitrate switching, WebRTC and DRM not tested.");
        }edt(app::finishSmoke);
        }catch(Throwable e){e.printStackTrace();String detail=e.toString();try{ScriptSession js=app.current().script;if(js!=null&&js.alive())detail+="\nPage: "+js.eval("JSON.stringify({autoplay,httpReady,played,pauses,once,mediaError,state:v._media})");MediaPanel media=app.current().media;if(media!=null)detail+="\nNative: "+media.diagnostic();}catch(Exception diagnostic){detail+="\nDiagnostic: "+diagnostic;}System.err.println(detail);try{Files.writeString(Paths.get(output+".log"),detail);}catch(Exception ignored){}try{edt(app::finishSmoke);}catch(Exception ignored){}System.exit(1);}
    },"aster-native-stream-check").start();}
}
