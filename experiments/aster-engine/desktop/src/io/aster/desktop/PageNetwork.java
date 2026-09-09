package io.aster.desktop;

import io.aster.engine.PageLoader;
import io.aster.engine.SiteData;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

/** Per-page, bounded HTTP/WebSocket broker. The script process never gets a socket. */
final class PageNetwork implements AutoCloseable {
    static final int BODY_LIMIT=1024*1024, SEND_LIMIT=256*1024;
    private static final ExecutorService HTTP_THREADS=Executors.newFixedThreadPool(4,r->{Thread t=new Thread(r,"aster-http-client");t.setDaemon(true);return t;});
    private static final HttpClient CLIENT=HttpClient.newBuilder().executor(HTTP_THREADS).connectTimeout(Duration.ofSeconds(8)).followRedirects(HttpClient.Redirect.NEVER).build();
    private final URI page;
    private final SiteData siteData;
    private final CorsPolicy cors=new CorsPolicy();
    private final ExecutorService workers=Executors.newFixedThreadPool(4,r->{Thread t=new Thread(r,"aster-page-fetch");t.setDaemon(true);return t;});
    private final ScheduledExecutorService deadlines=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"aster-network-deadline");t.setDaemon(true);return t;});
    private final Map<Integer,Transfer> fetches=new HashMap<>();
    private final Map<Integer,SocketPeer> sockets=new HashMap<>();
    private final ArrayDeque<String> events=new ArrayDeque<>();
    private int queuedBytes;
    private boolean closed;
    private String fatal;
    PageNetwork(URI page){this(page,new SiteData());}
    PageNetwork(URI page,SiteData data){this.page=page;this.siteData=data;}
    static URI target(URI page,String raw,boolean socket) throws IOException {
        try {
            URI uri=PageLoader.resolve(page,raw);String scheme=uri.getScheme();
            if(socket){if("http".equalsIgnoreCase(scheme))uri=URI.create("ws"+uri.toString().substring(4));else if("https".equalsIgnoreCase(scheme))uri=URI.create("wss"+uri.toString().substring(5));}
            URI http=uri;
            if(socket){if(!"ws".equalsIgnoreCase(uri.getScheme())&&!"wss".equalsIgnoreCase(uri.getScheme()))throw new IOException("WebSocket URL must use ws or wss");if(uri.getFragment()!=null)throw new IOException("WebSocket fragments are invalid");http=URI.create((uri.getScheme().equalsIgnoreCase("wss")?"https":"http")+uri.toString().substring(uri.getScheme().length()));}
            PageLoader.validate(http);PageLoader.validate(page);
            if(socket&&!ResourceLoader.sameOrigin(page,http))throw new IOException("WebSockets remain same-origin in this preview");
            if("https".equalsIgnoreCase(page.getScheme())&&!"https".equalsIgnoreCase(http.getScheme()))throw new IOException("Insecure page connection refused");
            if(BLOCKED_PORTS.contains(http.getPort()))throw new IOException("Unsafe network port refused");
            if(!socket&&uri.getFragment()!=null)uri=new URI(uri.toString().split("#",2)[0]);
            return uri;
        }catch(IllegalArgumentException|URISyntaxException e){throw new IOException("Invalid connection URL",e);}
    }
    synchronized void accept(Map<String,Object> command) throws IOException {
        if(closed)throw new IOException("Page networking stopped");
        int id=integer(command,"id");String kind=string(command,"kind");
        if(id<=0)throw new IOException("Invalid network request identifier");
        try {
            switch(kind){
                case "fetch": {
                    if(fetches.size()>=4||fetches.containsKey(id))throw new IOException("At most four fetch requests may run per page");
                    URI uri=target(page,string(command,"url"),false);
                    Transfer t=new Transfer(id);fetches.put(id,t);
                    t.deadline=deadlines.schedule(()->t.fail("Fetch exceeded 15 seconds"),15,TimeUnit.SECONDS);
                    SiteData.Request context=siteData.request(page,false,string(command,"method"));
                    t.work=workers.submit(()->fetch(t,uri,command,context));break;
                }
                case "abort": {Transfer t=fetches.remove(id);if(t!=null)t.cancel();break;}
                case "ws-open": {
                    if(sockets.size()>=4||sockets.containsKey(id))throw new IOException("At most four WebSockets may run per page");
                    URI uri=target(page,string(command,"url"),true);SocketPeer peer=new SocketPeer(id);sockets.put(id,peer);
                    WebSocket.Builder builder=CLIENT.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(8)).header("Origin",origin(page));
                    URI http=URI.create((uri.getScheme().equalsIgnoreCase("wss")?"https":"http")+uri.toString().substring(uri.getScheme().length()));
                    SiteData.Request context=siteData.request(page,false,"GET");context.check(http);
                    String cookie=context.header(http);if(!cookie.isEmpty())builder.header("Cookie",cookie);
                    Object protocols=command.get("protocols");
                    if(!(protocols instanceof List)||((List<?>)protocols).size()>16)throw new IOException("Invalid WebSocket protocols");
                    List<?> list=(List<?>)protocols;String[] rest=new String[Math.max(0,list.size()-1)];
                    for(int i=0;i<list.size();i++){String p=String.valueOf(list.get(i));if(!p.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]{1,128}")||list.indexOf(p)!=i)throw new IOException("Invalid or duplicate WebSocket protocol");if(i>0)rest[i-1]=p;}
                    if(!list.isEmpty())builder.subprotocols(list.get(0).toString(),rest);
                    peer.opening=builder.buildAsync(uri,peer);peer.opening.whenComplete((ws,error)->{if(error!=null)peer.fail("WebSocket handshake failed");});break;
                }
                case "ws-send": {SocketPeer peer=sockets.get(id);if(peer==null)throw new IOException("WebSocket is not open");peer.send(command);break;}
                case "ws-close": {SocketPeer peer=sockets.get(id);if(peer!=null)peer.finish(integer(command,"code"),string(command,"reason"));break;}
                default:throw new IOException("Unknown page network command");
            }
        }catch(Exception e){
            if(kind.startsWith("ws-")){SocketPeer peer=sockets.get(id);if(peer!=null)peer.fail(e.getMessage());else emit(Map.of("id",id,"kind","ws-error","error",safe(e)));}
            else {Transfer t=fetches.get(id);if(t!=null)t.fail(safe(e));else emit(Map.of("id",id,"kind","fetch-error","error",safe(e)));}
        }
    }
    // Fetch bad-port list. Explicit navigation is separate from script-initiated connections.
    private static final Set<Integer> BLOCKED_PORTS=Set.of(1,7,9,11,13,15,17,19,20,21,22,23,25,37,42,43,53,69,77,79,87,95,101,102,103,104,109,110,111,113,115,117,119,123,135,137,139,143,161,179,389,427,465,512,513,514,515,526,530,531,532,540,548,554,556,563,587,601,636,989,990,993,995,1719,1720,1723,2049,3659,4045,5060,5061,6000,6566,6665,6666,6667,6668,6669,6697,10080);
    static String origin(URI u){
        String scheme=u.getScheme().toLowerCase(Locale.ROOT);int port=u.getPort();
        return scheme+"://"+u.getHost().toLowerCase(Locale.ROOT)+((port<0||port==(scheme.equals("https")?443:80))?"":":"+port);
    }
    private void fetch(Transfer t,URI uri,Map<String,Object> command,SiteData.Request context){
        try {
            String credentials=Objects.toString(command.get("credentials"),"same-origin"),mode=Objects.toString(command.get("mode"),"cors"),redirect=Objects.toString(command.get("redirect"),"follow");
            if(!Set.of("omit","same-origin","include").contains(credentials))throw new IOException("Invalid credentials mode");
            if(!Set.of("same-origin","cors").contains(mode)||!Set.of("follow","error","manual").contains(redirect))throw new IOException("Unsupported fetch policy");
            boolean credentialed=credentials.equals("include"),tainted=false;String requestOrigin=origin(page);
            String method=string(command,"method").toUpperCase(Locale.ROOT);
            if(!Set.of("GET","HEAD","POST","PUT","PATCH","DELETE","OPTIONS").contains(method))throw new IOException("Unsupported fetch method");
            byte[] bytes=Base64.getDecoder().decode(string(command,"body"));if(bytes.length>SEND_LIMIT)throw new IOException("Fetch body exceeds 256 KiB");
            if((method.equals("GET")||method.equals("HEAD"))&&bytes.length!=0)throw new IOException("GET/HEAD cannot include a body");
            Object h=command.get("headers");if(!(h instanceof Map)||((Map<?,?>)h).size()>32)throw new IOException("Invalid fetch headers");
            Map<String,String> headers=new LinkedHashMap<>();int headerBytes=0;
            for(Map.Entry<?,?> entry:((Map<?,?>)h).entrySet()){
                String name=entry.getKey().toString().toLowerCase(Locale.ROOT),value=entry.getValue().toString();headerBytes+=name.length()+value.length();
                if(!name.matches("[!#$%&'*+.^_`|~0-9a-z-]+")||value.indexOf('\r')>=0||value.indexOf('\n')>=0||value.indexOf(0)>=0||headerBytes>16384)throw new IOException("Invalid fetch header");
                for(int i=0;i<value.length();i++)if(value.charAt(i)>255)throw new IOException("HTTP headers must be byte strings");
                if(name.startsWith("sec-")||name.startsWith("proxy-")||Set.of("cookie","cookie2","set-cookie","host","origin","referer","connection","content-length","accept-encoding","accept-charset","transfer-encoding","te","trailer","upgrade","expect","keep-alive","via","date","dnt","permissions-policy","access-control-request-method","access-control-request-headers","user-agent").contains(name))throw new IOException("Browser-controlled header refused");
                if(Set.of("x-http-method","x-http-method-override","x-method-override").contains(name))for(String part:value.split(","))if(Set.of("CONNECT","TRACE","TRACK").contains(part.trim().toUpperCase(Locale.ROOT)))throw new IOException("Forbidden method override");
                if(headers.put(name,value)!=null)throw new IOException("Duplicate network header name");
            }
            for(int redirects=0;redirects<=5;redirects++){
                uri=target(page,uri.toString(),false);context.check(uri);
                tainted|=!ResourceLoader.sameOrigin(page,uri);
                if(tainted&&mode.equals("same-origin"))throw new IOException("Request mode requires the page origin");
                if(t.cancelled)return;
                SortedSet<String> unsafe=CorsPolicy.unsafeHeaders(headers);
                if(tainted&&(!CorsPolicy.simpleMethod(method)||!unsafe.isEmpty()||Boolean.TRUE.equals(command.get("preflight")))){
                    String key=CorsPolicy.key(uri,requestOrigin,credentialed,method,unsafe);
                    if(!cors.cached(key)){
                        HttpRequest.Builder pre=builder(uri).header("Accept","*/*").header("Origin",requestOrigin).header("Access-Control-Request-Method",method).method("OPTIONS",HttpRequest.BodyPublishers.noBody());
                        if(!unsafe.isEmpty())pre.header("Access-Control-Request-Headers",String.join(",",unsafe));
                        // No cookies, Authorization, body or Set-Cookie processing on a preflight.
                        HttpResponse<InputStream> allowed=send(t,pre.build());
                        try(InputStream ignored=allowed.body()){CorsPolicy.checkPreflight(allowed.statusCode(),allowed.headers(),requestOrigin,credentialed,method,unsafe);cors.remember(key,allowed.headers());}
                        finally{t.stream=null;}
                    }
                }
                if(t.cancelled)return;
                context.check(uri); // A rule may have changed while preflight was in flight.
                HttpRequest.Builder builder=builder(uri);
                // Third-party cookie access remains disabled, including redirect chains returning home.
                boolean cookies=!credentials.equals("omit")&&!tainted;
                context.method(method);String cookie=context.header(uri);if(cookies&&!cookie.isEmpty())builder.header("Cookie",cookie);
                if(!headers.containsKey("accept"))builder.header("Accept","*/*");
                headers.forEach(builder::header);if(tainted||!method.equals("GET")&&!method.equals("HEAD"))builder.header("Origin",requestOrigin);
                HttpRequest request=builder.method(method,new UploadPublisher(t,bytes)).build();
                HttpResponse<InputStream> response=send(t,request);
                try(InputStream input=response.body()){
                    int code=response.statusCode();if(tainted)CorsPolicy.check(response.headers(),requestOrigin,credentialed);
                    if(cookies)context.receive(uri,response.headers().map());
                    if(Set.of(301,302,303,307,308).contains(code)&&response.headers().firstValue("location").isPresent()){
                        if(redirect.equals("error"))throw new IOException("Fetch redirect refused by request policy");
                        if(redirect.equals("manual")){t.event(Map.of("id",t.id,"kind","fetch-head","status",0,"type","opaqueredirect","url","","redirected",false,"headers",Map.of(),"length",0,"nullBody",true));t.event(Map.of("id",t.id,"kind","fetch-end"));return;}
                        URI next=target(page,PageLoader.resolve(uri,response.headers().firstValue("location").get()).toString(),false);
                        if("https".equalsIgnoreCase(uri.getScheme())&&!"https".equalsIgnoreCase(next.getScheme()))throw new IOException("Insecure redirect refused");
                        if(!ResourceLoader.sameOrigin(uri,next)){
                            headers.remove("authorization");
                            if(!ResourceLoader.sameOrigin(page,uri))requestOrigin="null";
                        }
                        if((code==303&&!method.equals("HEAD"))||((code==301||code==302)&&method.equals("POST"))){
                            method="GET";bytes=new byte[0];for(String name:List.of("content-type","content-encoding","content-language","content-location"))headers.remove(name);
                        }
                        uri=next;continue;
                    }
                    if(code<200||code>599)throw new IOException("Unsupported final HTTP status");
                    boolean nullBody=method.equals("HEAD")||Set.of(204,205,304).contains(code);
                    String encoding=response.headers().firstValue("content-encoding").orElse("identity").trim().toLowerCase(Locale.ROOT);
                    long expected=response.headers().firstValueAsLong("content-length").orElse(-1);
                    if(!nullBody&&expected>(encoding.equals("identity")?BODY_LIMIT:2L*BODY_LIMIT))throw new IOException("Fetch response exceeds its byte limit");
                    if(!Set.of("identity","gzip","deflate").contains(encoding))throw new IOException("Unsupported fetch content encoding");
                    Map<String,String> visible=CorsPolicy.visible(response.headers(),tainted,credentialed);
                    t.event(Map.of("id",t.id,"kind","fetch-head","status",code,"type",tainted?"cors":"basic","url",uri.toASCIIString(),"redirected",redirects>0,"headers",visible,"length",encoding.equals("identity")?expected:-1,"nullBody",nullBody));
                    if(!nullBody){
                        InputStream bounded=new LimitedInput(input,2*BODY_LIMIT);
                        InputStream decoded=encoding.equals("gzip")?new GZIPInputStream(bounded):encoding.equals("deflate")?new InflaterInputStream(bounded):bounded;
                        try(InputStream body=decoded){
                            byte[] buffer=new byte[32768];int n,total=0;
                            while((n=body.read(buffer))!=-1){
                                if(t.cancelled)return;if((total+=n)>BODY_LIMIT)throw new IOException("Decoded fetch response exceeds 1 MiB");
                                if(n!=0)t.event(Map.of("id",t.id,"kind","fetch-chunk","body",Base64.getEncoder().encodeToString(Arrays.copyOf(buffer,n))));
                            }
                        }
                    }
                    t.event(Map.of("id",t.id,"kind","fetch-end"));return;
                }finally{t.stream=null;}
            }
            throw new IOException("Fetch redirected more than five times");
        }catch(Exception e){t.event(Map.of("id",t.id,"kind","fetch-error","error",safe(e)));}
        finally{synchronized(this){fetches.remove(t.id,t);}if(t.deadline!=null)t.deadline.cancel(false);}
    }
    private static HttpRequest.Builder builder(URI uri){return HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(12)).header("User-Agent","AsterEnginePreview/0.6").header("Accept-Encoding","gzip, deflate");}
    private HttpResponse<InputStream> send(Transfer t,HttpRequest request)throws IOException,InterruptedException{
        if(t.cancelled)throw new InterruptedIOException("Fetch cancelled");
        HttpResponse<InputStream> response=CLIENT.send(request,HttpResponse.BodyHandlers.ofInputStream());
        t.stream=response.body();if(t.cancelled){response.body().close();throw new InterruptedIOException("Fetch cancelled");}return response;
    }
    private final class UploadPublisher implements HttpRequest.BodyPublisher {
        final Transfer transfer;final byte[] data;
        UploadPublisher(Transfer transfer,byte[] data){this.transfer=transfer;this.data=data;}
        public long contentLength(){return data.length;}
        public void subscribe(Flow.Subscriber<? super ByteBuffer> downstream){
            final boolean report;synchronized(PageNetwork.this){report=!transfer.uploadStarted;transfer.uploadStarted=true;}
            HttpRequest.BodyPublishers.ofByteArray(data).subscribe(new Flow.Subscriber<ByteBuffer>(){
                long sent;
                public void onSubscribe(Flow.Subscription subscription){downstream.onSubscribe(subscription);}
                public void onNext(ByteBuffer buffer){int n=buffer.remaining();downstream.onNext(buffer);sent+=n;if(report)transfer.event(Map.of("id",transfer.id,"kind","fetch-upload","loaded",sent,"total",data.length,"done",false));}
                public void onError(Throwable error){downstream.onError(error);}
                public void onComplete(){downstream.onComplete();if(report)transfer.event(Map.of("id",transfer.id,"kind","fetch-upload","loaded",sent,"total",data.length,"done",true));}
            });
        }
    }
    private static final class LimitedInput extends FilterInputStream {
        private int remaining;
        LimitedInput(InputStream in,int limit){super(in);remaining=limit;}
        public int read()throws IOException{int b=super.read();if(b>=0&&--remaining<0)throw new IOException("Encoded fetch response limit");return b;}
        public int read(byte[] b,int off,int len)throws IOException{int n=in.read(b,off,Math.min(len,Math.max(1,remaining+1)));if(n>0&&(remaining-=n)<0)throw new IOException("Encoded fetch response limit");return n;}
    }
    private synchronized void emit(Map<String,?> event){
        if(closed)return;String json=Json.stringify(event);int bytes=json.getBytes(StandardCharsets.UTF_8).length;
        if(events.size()>=512||queuedBytes+bytes>8*1024*1024){fatal="Page network event queue exceeded its limit";return;}
        events.add(json);queuedBytes+=bytes;
    }
    synchronized String drain() throws IOException {
        if(fatal!=null)throw new IOException(fatal);
        StringJoiner out=new StringJoiner(",","[","]");int size=2;
        while(!events.isEmpty()){String event=events.peek();int n=event.getBytes(StandardCharsets.UTF_8).length;if(size+n>400000)break;events.remove();queuedBytes-=n;out.add(event);size+=n+1;}
        return out.toString();
    }
    private final class Transfer {
        final int id;boolean uploadStarted;volatile InputStream stream;volatile Future<?> work;volatile ScheduledFuture<?> deadline;volatile boolean cancelled;
        Transfer(int id){this.id=id;}
        void event(Map<String,?> value){synchronized(PageNetwork.this){if(!cancelled)emit(value);}}
        void cancel(){cancelled=true;if(work!=null)work.cancel(true);if(deadline!=null)deadline.cancel(false);try{if(stream!=null)stream.close();}catch(IOException ignored){}}
        void fail(String why){synchronized(PageNetwork.this){if(cancelled||!fetches.remove(id,this))return;cancel();emit(Map.of("id",id,"kind","fetch-error","error",why));}}
    }
    private final class SocketPeer implements WebSocket.Listener {
        final int id;WebSocket socket;CompletableFuture<WebSocket> opening;CompletableFuture<?> sends=CompletableFuture.completedFuture(null);int pending;boolean done;
        final StringBuilder text=new StringBuilder();final ByteArrayOutputStream binary=new ByteArrayOutputStream();int textBytes;
        SocketPeer(int id){this.id=id;}
        public void onOpen(WebSocket ws){synchronized(PageNetwork.this){if(closed||done){ws.abort();return;}socket=ws;emit(Map.of("id",id,"kind","ws-open","protocol",ws.getSubprotocol()));ws.request(1);}}
        public CompletionStage<?> onText(WebSocket ws,CharSequence part,boolean last){synchronized(PageNetwork.this){if(done)return null;textBytes+=part.toString().getBytes(StandardCharsets.UTF_8).length;if(textBytes>SEND_LIMIT){fail("WebSocket message exceeds 256 KiB");return null;}text.append(part);if(last){emit(Map.of("id",id,"kind","ws-text","data",text.toString()));text.setLength(0);textBytes=0;}ws.request(1);}return null;}
        public CompletionStage<?> onBinary(WebSocket ws,ByteBuffer part,boolean last){synchronized(PageNetwork.this){if(done)return null;if(binary.size()+part.remaining()>SEND_LIMIT){fail("WebSocket message exceeds 256 KiB");return null;}byte[] b=new byte[part.remaining()];part.get(b);binary.write(b,0,b.length);if(last){emit(Map.of("id",id,"kind","ws-binary","data",Base64.getEncoder().encodeToString(binary.toByteArray())));binary.reset();}ws.request(1);}return null;}
        public CompletionStage<?> onPing(WebSocket ws,ByteBuffer message){ws.request(1);return WebSocket.Listener.super.onPing(ws,message);}
        public CompletionStage<?> onPong(WebSocket ws,ByteBuffer message){ws.request(1);return null;}
        public CompletionStage<?> onClose(WebSocket ws,int code,String reason){synchronized(PageNetwork.this){if(!done){done=true;sockets.remove(id,this);emit(Map.of("id",id,"kind","ws-close","code",code,"reason",reason,"clean",true));}}return ws.sendClose(WebSocket.NORMAL_CLOSURE,"");}
        public void onError(WebSocket ws,Throwable error){fail("WebSocket connection failed");}
        void send(Map<String,Object> command)throws IOException{
            if(done||socket==null)throw new IOException("WebSocket is not open");boolean isBinary=Boolean.TRUE.equals(command.get("binary"));String data=string(command,"data");
            byte[] bytes=isBinary?Base64.getDecoder().decode(data):data.getBytes(StandardCharsets.UTF_8);int count=bytes.length;
            if(count>SEND_LIMIT||pending+count>SEND_LIMIT)throw new IOException("WebSocket send buffer exceeds 256 KiB");pending+=count;
            sends=sends.thenCompose(ignored->isBinary?socket.sendBinary(ByteBuffer.wrap(bytes),true):socket.sendText(data,true));
            sends.whenComplete((ignored,error)->{synchronized(PageNetwork.this){pending-=count;if(error!=null)fail("WebSocket send failed");else if(!done)emit(Map.of("id",id,"kind","ws-sent","bytes",count));}});
        }
        void finish(int code,String reason)throws IOException{
            if(code!=1000&&(code<3000||code>4999))throw new IOException("Invalid WebSocket close code");if(reason.getBytes(StandardCharsets.UTF_8).length>123)throw new IOException("WebSocket close reason too long");
            if(socket==null){fail("WebSocket closed before connecting");return;}sends=sends.thenCompose(ignored->socket.sendClose(code,reason));
            deadlines.schedule(()->fail("WebSocket close timed out"),5,TimeUnit.SECONDS);
        }
        void fail(String error){synchronized(PageNetwork.this){if(done)return;done=true;sockets.remove(id,this);if(socket!=null)socket.abort();if(opening!=null)opening.cancel(true);emit(Map.of("id",id,"kind","ws-error","error",error==null?"WebSocket failed":error));}}
    }
    static String string(Map<String,Object> m,String key)throws IOException{Object v=m.get(key);if(!(v instanceof String)||((String)v).length()>1500000)throw new IOException("Invalid network field: "+key);return (String)v;}
    static int integer(Map<String,Object> m,String key)throws IOException{Object v=m.get(key);if(!(v instanceof Number)||((Number)v).doubleValue()!=((Number)v).intValue())throw new IOException("Invalid integer field");return ((Number)v).intValue();}
    private static String safe(Exception e){String s=e.getMessage();return s==null?e.getClass().getSimpleName():s.substring(0,Math.min(s.length(),300));}
    public synchronized void close(){if(closed)return;closed=true;for(Transfer t:fetches.values())t.cancel();fetches.clear();for(SocketPeer s:new ArrayList<>(sockets.values()))s.fail("Page closed");sockets.clear();workers.shutdownNow();deadlines.shutdownNow();events.clear();queuedBytes=0;}
}
