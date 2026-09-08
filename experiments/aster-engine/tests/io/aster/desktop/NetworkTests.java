package io.aster.desktop;

import io.aster.engine.Engine;
import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Real HTTP and RFC 6455 peers, executed through the packaged QuickJS bridge. */
public final class NetworkTests {
    private static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
    private static void await(ScriptSession js,String expression)throws Exception{
        long until=System.nanoTime()+8_000_000_000L;
        do{js.pump();if(Boolean.TRUE.equals(js.eval(expression)))return;Thread.sleep(15);}while(System.nanoTime()<until);
        throw new AssertionError("Timed out waiting for "+expression+"; "+js.eval("JSON.stringify({result:globalThis.result,error:globalThis.failure,events:globalThis.events})"));
    }
    private static ScriptSession page(URI uri)throws Exception{ScriptSession js=new ScriptSession();js.start(Engine.parse(uri,"<title>Network test</title><p id='result'>Waiting</p>"));return js;}
    public static void main(String[] args)throws Exception{
        ExecutorService pool=Executors.newCachedThreadPool(r->{Thread t=new Thread(r,"aster-http-fixture");t.setDaemon(true);return t;});
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.setExecutor(pool);
        AtomicInteger echoRequests=new AtomicInteger(),slowRequests=new AtomicInteger();
        server.createContext("/data",e->{byte[] bytes="{\"message\":\"Hola 🌎\"}".getBytes(StandardCharsets.UTF_8);e.getResponseHeaders().set("Content-Type","application/json");e.getResponseHeaders().add("Set-Cookie","secret=hidden");e.getResponseHeaders().add("X-Test","real-http");e.sendResponseHeaders(200,bytes.length);e.getResponseBody().write(bytes);e.close();});
        server.createContext("/echo",e->{echoRequests.incrementAndGet();byte[] body=e.getRequestBody().readAllBytes();byte[] b=(e.getRequestMethod()+":"+new String(body,StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8);e.sendResponseHeaders(201,b.length);e.getResponseBody().write(b);e.close();});
        server.createContext("/redirect",e->{e.getResponseHeaders().set("Location","/echo");e.sendResponseHeaders(303,-1);e.close();});
        server.createContext("/cross",e->{e.getResponseHeaders().set("Location","http://localhost:"+server.getAddress().getPort()+"/echo");e.sendResponseHeaders(302,-1);e.close();});
        server.createContext("/large",e->{e.sendResponseHeaders(200,1048577);e.close();});
        server.createContext("/missing",e->{e.sendResponseHeaders(404,4);e.getResponseBody().write("gone".getBytes(StandardCharsets.UTF_8));e.close();});
        server.createContext("/binary",e->{byte[] b={0,1,2,(byte)255,(byte)128};e.sendResponseHeaders(200,b.length);e.getResponseBody().write(b);e.close();});
        server.createContext("/slow",e->{slowRequests.incrementAndGet();try{Thread.sleep(1500);e.sendResponseHeaders(200,1);e.getResponseBody().write(1);}catch(Exception ignored){}finally{e.close();}});
        server.start();URI base=URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/");
        try {
            try(ScriptSession js=page(base)){
                js.eval("var result=null,failure=null;fetch('/data').then(async r=>{if(!r.ok||r.headers.get('set-cookie')!==null||r.headers.get('x-test')!=='real-http')throw Error('Headers failed');let twin=r.clone();result=(await r.json()).message+' / '+(await twin.text());document.getElementById('result').textContent=result;return r.text();}).catch(e=>failure=e.name);void 0");
                await(js,"failure==='TypeError' && result.includes('Hola 🌎')");check(js.snapshot().get("html").toString().contains("Hola 🌎"),"HTTP result did not reach Aster DOM");
                js.eval("result=null;fetch('/echo',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({n:3})}).then(r=>r.text()).then(v=>result=v);void 0");await(js,"result==='POST:{\"n\":3}'");
                js.eval("result=null;fetch('/redirect',{method:'POST',body:'erase-me'}).then(async r=>result=[r.redirected,r.url,await r.text()]);void 0");await(js,"result!==null");check(Boolean.TRUE.equals(js.eval("result[0] && result[1].endsWith('/echo') && result[2]==='GET:'")),"303 method/body redirect rules failed");
                js.eval("result=null;fetch('/missing').then(async r=>result=[r.ok,r.status,await r.text()]);void 0");await(js,"result!==null");check(Boolean.TRUE.equals(js.eval("!result[0]&&result[1]===404&&result[2]==='gone'")),"HTTP error was not exposed as a Response");
                js.eval("result=null;fetch('/binary').then(r=>r.arrayBuffer()).then(b=>result=[...new Uint8Array(b)].join(','));void 0");await(js,"result==='0,1,2,255,128'");
                for(String code:new String[]{"fetch('/large')","fetch('/cross')","fetch('file:///etc/passwd')","fetch('/echo',{headers:{Cookie:'no'}})","fetch('/echo',{method:'TRACE'})","fetch('/echo',{credentials:'include'})","fetch('/redirect',{redirect:'error'})"}){
                    js.eval("failure=null;"+code+".catch(e=>failure=e.name);void 0");await(js,"failure==='TypeError'");
                }
                int before=echoRequests.get();js.eval("var c=new AbortController();c.abort();failure=null;fetch('/echo',{signal:c.signal}).catch(e=>failure=e.name);void 0");await(js,"failure==='AbortError'");check(before==echoRequests.get(),"Already aborted fetch made an HTTP request");
                js.eval("c=new AbortController();failure=null;fetch('/slow',{signal:c.signal}).catch(e=>failure=e.name);void 0");js.pump();long end=System.nanoTime()+2_000_000_000L;while(slowRequests.get()==0&&System.nanoTime()<end)Thread.sleep(10);check(slowRequests.get()>0,"Abort test never started HTTP request");js.eval("c.abort();void 0");await(js,"failure==='AbortError'");
                check(Boolean.TRUE.equals(js.eval("new TextDecoder().decode(new TextEncoder().encode('A🌎ñ\\ud800'))==='A🌎ñ�' && atob(btoa('A\\u00ff'))==='A\\u00ff'")),"UTF-8/base64 round trip failed");
                check(Boolean.TRUE.equals(js.eval("typeof RTCPeerConnection==='undefined' && typeof MediaSource==='undefined' && typeof navigator.requestMediaKeySystemAccess==='undefined'")),"Unimplemented streaming APIs claimed support");
            }
            try(EchoSocket peer=new EchoSocket();ScriptSession js=page(peer.origin())){
                js.eval("var result=null,failure=null,events=[];var ws=new WebSocket('/socket','aster-test');ws.onopen=()=>{events.push('open');ws.send('hello 🌎');ws.send(new Uint8Array([1,0,255]));ws.send('fragment');};ws.onmessage=e=>events.push(typeof e.data==='string'?e.data:[...new Uint8Array(e.data)].join(','));ws.onclose=e=>events.push('close:'+e.code+':'+e.wasClean);ws.onerror=()=>events.push('error');void 0");
                await(js,"events.includes('hello 🌎')&&events.includes('1,0,255')&&events.includes('fragmented')&&ws.bufferedAmount===0");
                check(Boolean.TRUE.equals(js.eval("ws.protocol==='aster-test' && ws.readyState===WebSocket.OPEN")),"WebSocket negotiation/state failed");
                js.eval("ws.close(1000,'done');void 0");await(js,"events.includes('close:1000:true')&&ws.readyState===WebSocket.CLOSED");check(peer.origin().toString().replaceFirst("/$","").equals(peer.originHeader),"Handshake did not send page Origin");
            }
            try(EchoSocket peer=new EchoSocket()){
                ScriptSession js=page(peer.origin());try{js.eval("var result=null,failure=null,events=[];var ws=new WebSocket('/socket');ws.onopen=()=>events.push('open');void 0");await(js,"events.includes('open')");}finally{js.close();}
                check(peer.ended.await(3,TimeUnit.SECONDS),"Closing the page left its WebSocket open");
            }
            try(ScriptSession js=page(base)){js.eval("var result=null,failure=null,events=[];var ws=new WebSocket('ws://localhost:1/');ws.onerror=()=>events.push('error');ws.onclose=e=>events.push(e.code);void 0");await(js,"events.includes('error')&&events.includes(1006)");}
            System.out.println("Page network passed: actual HTTP/JSON/POST/binary/errors/redirects, AbortController, origin/header limits, real WebSocket handshake/text/binary/fragments/ping/close and page-close cancellation. No WebRTC or cloud service playback claimed.");
        }finally{server.stop(0);pool.shutdownNow();}
    }
    static final class EchoSocket implements AutoCloseable {
        final ServerSocket server;final Thread thread;final CountDownLatch ended=new CountDownLatch(1);volatile Socket client;volatile String originHeader;
        EchoSocket()throws IOException{server=new ServerSocket(0,2,InetAddress.getByName("127.0.0.1"));thread=new Thread(this::run,"aster-websocket-fixture");thread.setDaemon(true);thread.start();}
        URI origin(){return URI.create("http://127.0.0.1:"+server.getLocalPort()+"/");}
        void run(){try(Socket socket=server.accept()){
            client=socket;socket.setSoTimeout(10000);InputStream in=socket.getInputStream();OutputStream out=socket.getOutputStream();StringBuilder head=new StringBuilder();
            while(!head.toString().endsWith("\r\n\r\n")){int b=in.read();if(b<0||head.length()>16384)throw new IOException("Handshake invalid");head.append((char)b);}
            Map<String,String> headers=new HashMap<>();for(String line:head.toString().split("\r\n")){int p=line.indexOf(':');if(p>0)headers.put(line.substring(0,p).toLowerCase(Locale.ROOT),line.substring(p+1).trim());}
            originHeader=headers.get("origin");String accept=Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest((headers.get("sec-websocket-key")+"258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.US_ASCII)));
            out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: "+accept+"\r\n"+(headers.containsKey("sec-websocket-protocol")?"Sec-WebSocket-Protocol: aster-test\r\n":"")+"\r\n").getBytes(StandardCharsets.US_ASCII));out.flush();frame(out,0x89,new byte[]{4});
            while(true){int op=in.read(),length=in.read();if(op<0||length<0)break;boolean masked=(length&128)!=0;length&=127;if(length==126)length=(in.read()<<8)|in.read();if(length>262144||length==127)throw new IOException("Fixture frame too large");byte[] mask=masked?in.readNBytes(4):new byte[4],data=in.readNBytes(length);if(data.length!=length)break;for(int i=0;i<data.length;i++)if(masked)data[i]^=mask[i%4];
                int kind=op&15;if(kind==8){frame(out,0x88,data);break;}if(kind==10)continue;
                if(kind==1&&new String(data,StandardCharsets.UTF_8).equals("fragment")){frame(out,0x01,"frag".getBytes(StandardCharsets.UTF_8));frame(out,0x80,"mented".getBytes(StandardCharsets.UTF_8));}else frame(out,0x80|kind,data);
            }
        }catch(Exception ignored){}finally{ended.countDown();}}
        static void frame(OutputStream out,int opcode,byte[] bytes)throws IOException{out.write(opcode);if(bytes.length<126)out.write(bytes.length);else{out.write(126);out.write(bytes.length>>8);out.write(bytes.length&255);}out.write(bytes);out.flush();}
        public void close()throws IOException{server.close();if(client!=null)client.close();}
    }
}
