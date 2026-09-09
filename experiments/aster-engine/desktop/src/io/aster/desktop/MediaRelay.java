package io.aster.desktop;

import io.aster.engine.PageLoader;
import io.aster.engine.SiteData;
import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.*;

/** Validated progressive/HLS transport for the native decoder, never a public proxy. */
final class MediaRelay implements AutoCloseable {
    private static final Set<String> TAGS=new HashSet<>(Arrays.asList("EXTM3U","EXTINF","EXT-X-VERSION","EXT-X-TARGETDURATION","EXT-X-MEDIA-SEQUENCE","EXT-X-ENDLIST","EXT-X-PLAYLIST-TYPE","EXT-X-DISCONTINUITY","EXT-X-DISCONTINUITY-SEQUENCE","EXT-X-INDEPENDENT-SEGMENTS","EXT-X-PROGRAM-DATE-TIME","EXT-X-BYTERANGE","EXT-X-STREAM-INF","EXT-X-MEDIA","EXT-X-MAP","EXT-X-START","EXT-X-I-FRAME-STREAM-INF","EXT-X-I-FRAMES-ONLY"));
    private final URI page,initial;
    private final SiteData data;
    private final HttpServer server;
    private final ExecutorService workers=new ThreadPoolExecutor(4,4,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(16),r->{Thread t=new Thread(r,"aster-media-relay");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    private final String token=UUID.randomUUID().toString();
    private final Map<String,Item> items=new LinkedHashMap<>();
    private final Map<String,String> paths=new HashMap<>();
    private final Set<HttpURLConnection> active=ConcurrentHashMap.newKeySet();
    private final AtomicLong transferred=new AtomicLong();
    private final URI entry;
    private volatile boolean closed;
    private static final class Item{final URI uri;final boolean playlist;Item(URI uri,boolean playlist){this.uri=uri;this.playlist=playlist;}}
    MediaRelay(URI page,URI initial)throws IOException{
        this(page,initial,new SiteData());
    }
    MediaRelay(URI page,URI initial,SiteData data)throws IOException{
        this.data=data;this.page=page;this.initial=initial;validate(initial);
        if(!supported(initial))throw new IOException("Use a direct MP4, M4A, MP3, WAV or unencrypted M3U8 URL");
        server=HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"),0),8);server.setExecutor(workers);server.createContext("/",this::serve);
        entry=URI.create(register(initial,initial.getPath().toLowerCase(Locale.ROOT).endsWith(".m3u8")));server.start();
    }
    static boolean supported(URI u){return u!=null&&u.getPath()!=null&&u.getPath().toLowerCase(Locale.ROOT).matches(".*\\.(mp4|m4a|mp3|wav|m3u8)");}
    URI uri(){return entry;}
    private URI validate(URI uri)throws IOException{
        PageLoader.validate(uri);if(!ResourceLoader.sameOrigin(initial,uri))throw new IOException("Cross-origin media redirects and playlist sources are not supported");
        if("https".equalsIgnoreCase(page.getScheme())&&!"https".equalsIgnoreCase(uri.getScheme()))throw new IOException("Mixed-content media refused");
        if(uri.toString().length()>8192||uri.getFragment()!=null)throw new IOException("Invalid media resource URL");return uri;
    }
    private synchronized String register(URI uri,boolean playlist)throws IOException{
        validate(uri);String key=uri.toString()+"|"+playlist,path=paths.get(key);
        if(path==null){if(items.size()>=2048)throw new IOException("HLS resource limit reached");String suffix=".bin",p=uri.getPath().toLowerCase(Locale.ROOT);for(String ext:Arrays.asList(".mp4",".m4a",".mp3",".wav",".ts",".m4s",".aac"))if(p.endsWith(ext))suffix=ext;path="/"+token+"/"+items.size()+(playlist?".m3u8":suffix);items.put(path,new Item(uri,playlist));paths.put(key,path);}
        return "http://127.0.0.1:"+server.getAddress().getPort()+path;
    }
    String rewrite(URI base,String manifest)throws IOException{
        if(manifest.length()>131072||!manifest.startsWith("#EXTM3U"))throw new IOException("Invalid or oversized HLS playlist");
        StringBuilder out=new StringBuilder();boolean nextPlaylist=false;int lines=0;
        for(String raw:manifest.split("\\r?\\n")){
            String line=raw.trim();if(++lines>4096)throw new IOException("HLS line limit");
            if(line.isEmpty())continue;
            if(!line.startsWith("#")){out.append(register(resolve(base,line),nextPlaylist)).append('\n');nextPlaylist=false;continue;}
            if(line.startsWith("#EXT-X-KEY:")){if(!line.equals("#EXT-X-KEY:METHOD=NONE"))throw new IOException("Encrypted HLS requires a separate authorized integration");continue;}
            if(line.startsWith("#EXT")){
                String tag=line.substring(1).split(":",2)[0];if(!TAGS.contains(tag))throw new IOException("Unsupported HLS tag: "+tag);
                nextPlaylist=tag.equals("EXT-X-STREAM-INF");
                if(line.contains("URI=")){
                    if(!Arrays.asList("EXT-X-MAP","EXT-X-MEDIA","EXT-X-I-FRAME-STREAM-INF").contains(tag))throw new IOException("Unsupported HLS URI attribute");
                    Matcher m=Pattern.compile("(?<=[:,])URI=\"([^\"]+)\"").matcher(line);StringBuffer rewritten=new StringBuffer();int count=0;
                    while(m.find()){m.appendReplacement(rewritten,Matcher.quoteReplacement("URI=\""+register(resolve(base,m.group(1)),!tag.equals("EXT-X-MAP"))+"\""));count++;}m.appendTail(rewritten);
                    if(count!=1)throw new IOException("Invalid HLS URI attribute");line=rewritten.toString();
                }
                out.append(line).append('\n');
            }
            // Non-standard comment lines are omitted; they cannot introduce decoder URLs.
            if(out.length()>512000)throw new IOException("Rewritten HLS playlist limit");
        }
        if(nextPlaylist)throw new IOException("HLS variant URL missing");return out.toString();
    }
    private URI resolve(URI base,String value)throws IOException{try{return validate(base.resolve(value));}catch(IllegalArgumentException e){throw new IOException("Invalid HLS source URL",e);}}
    private void serve(HttpExchange exchange){
        HttpURLConnection c=null;boolean sent=false;
        try{
            String host="127.0.0.1:"+server.getAddress().getPort();Headers request=exchange.getRequestHeaders();
            if(closed||!host.equalsIgnoreCase(request.getFirst("Host"))||request.containsKey("Origin")||request.containsKey("Referer"))throw new IOException("Media relay request refused");
            if(!exchange.getRequestMethod().equals("GET")&&!exchange.getRequestMethod().equals("HEAD"))throw new IOException("Media relay method refused");
            Item item;synchronized(this){item=items.get(exchange.getRequestURI().toString());}if(item==null)throw new IOException("Unknown media source");
            String range=request.getFirst("Range");if(range!=null&&!range.matches("bytes=(?:[0-9]{1,12}-[0-9]{0,12}|-[0-9]{1,12})"))throw new IOException("Invalid media byte range");
            URI uri=item.uri;SiteData.Request context=data.request(page,false,exchange.getRequestMethod());
            for(int redirects=0;;redirects++){
                if(redirects>5)throw new IOException("Too many media redirects");validate(uri);c=(HttpURLConnection)uri.toURL().openConnection();active.add(c);
                c.setConnectTimeout(8000);c.setReadTimeout(8000);c.setInstanceFollowRedirects(false);c.setRequestProperty("Accept-Encoding","identity");c.setRequestProperty("User-Agent","AsterEnginePreview/0.6");
                if(range!=null&&!item.playlist)c.setRequestProperty("Range",range);if(exchange.getRequestMethod().equals("HEAD")&&!item.playlist)c.setRequestMethod("HEAD");
                context.prepare(c);int code=c.getResponseCode();context.receive(c);if(!Arrays.asList(301,302,303,307,308).contains(code))break;
                String location=c.getHeaderField("Location");if(location==null)throw new IOException("Media redirect lacks a destination");uri=resolve(uri,location);active.remove(c);c.disconnect();c=null;
            }
            int code=c.getResponseCode();if(code!=200&&code!=206)throw new IOException("Media returned HTTP "+code);
            if(c.getContentEncoding()!=null&&!c.getContentEncoding().equalsIgnoreCase("identity"))throw new IOException("Encoded media transport is unsupported");
            long length=c.getContentLengthLong(),limit=item.playlist?131072:64L*1024*1024;
            if(length>limit)throw new IOException("Media resource size limit");
            Headers headers=exchange.getResponseHeaders();headers.set("Cache-Control","no-store");headers.set("X-Content-Type-Options","nosniff");
            if(exchange.getRequestMethod().equals("HEAD")&&!item.playlist){headers.set("Content-Type",Objects.toString(c.getContentType(),"application/octet-stream"));if(length>=0)headers.set("Content-Length",Long.toString(length));exchange.sendResponseHeaders(code,-1);sent=true;return;}
            try(InputStream input=c.getInputStream()){
                if(item.playlist){ByteArrayOutputStream out=new ByteArrayOutputStream();transfer(input,out,limit);byte[] body=rewrite(uri,new String(out.toByteArray(),StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8);headers.set("Content-Type","application/vnd.apple.mpegurl");boolean head=exchange.getRequestMethod().equals("HEAD");headers.set("Content-Length",Integer.toString(body.length));exchange.sendResponseHeaders(200,head?-1:body.length);sent=true;if(!head)exchange.getResponseBody().write(body);}
                else{
                    String type=c.getContentType();headers.set("Content-Type",type==null?"application/octet-stream":type);
                    if(code==206){String contentRange=c.getHeaderField("Content-Range");if(contentRange==null||!contentRange.matches("bytes [0-9]+-[0-9]+/(?:[0-9]+|\\*)"))throw new IOException("Invalid media range response");headers.set("Content-Range",contentRange);}
                    headers.set("Accept-Ranges","bytes");exchange.sendResponseHeaders(code,length>=0?length:0);sent=true;long read=transfer(input,exchange.getResponseBody(),limit);if(length>=0&&length!=read)throw new IOException("Truncated media resource");
                }
            }
        }catch(Exception e){if(!sent)try{byte[] b="Media source is unavailable or unsupported".getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(502,b.length);exchange.getResponseBody().write(b);}catch(IOException ignored){}}
        finally{if(c!=null){active.remove(c);c.disconnect();}exchange.close();}
    }
    private long transfer(InputStream in,OutputStream out,long limit)throws IOException{
        long total=0,until=System.nanoTime()+60_000_000_000L;byte[] b=new byte[16384];int n;
        while((n=in.read(b))!=-1){if(closed||Thread.currentThread().isInterrupted()||System.nanoTime()>until)throw new IOException("Media request cancelled or timed out");total+=n;if(total>limit||transferred.addAndGet(n)>1024L*1024*1024)throw new IOException("Media streaming byte limit");out.write(b,0,n);out.flush();}return total;
    }
    public void close(){if(closed)return;closed=true;server.stop(0);for(HttpURLConnection c:active)c.disconnect();active.clear();workers.shutdownNow();synchronized(this){items.clear();paths.clear();}}
}
