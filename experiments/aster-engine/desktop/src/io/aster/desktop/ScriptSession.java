package io.aster.desktop;

import io.aster.engine.*;
import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** One page, one QuickJS process. No remote Java objects cross this boundary. */
final class ScriptSession implements AutoCloseable {
    private final Process process;
    private final SiteData siteData;
    private final SiteData.Storage sessionStorage;
    private URI page;
    private final DataInputStream input;
    private final DataOutputStream output;
    private volatile boolean closed;
    private volatile PageNetwork network;
    private final ScheduledExecutorService watchdog=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"aster-script-watchdog");t.setDaemon(true);return t;});
    static Path host() throws Exception {
        String override=System.getProperty("aster.script.host"); if(override!=null)return Paths.get(override).toAbsolutePath();
        Path code=Paths.get(ScriptSession.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Path base=Files.isDirectory(code)?code.getParent().resolve("jar"):code.getParent();
        return base.resolve("native").resolve(System.getProperty("os.name").startsWith("Windows")?"aster-script-host.exe":"aster-script-host");
    }
    ScriptSession() throws Exception {
        this(new SiteData(),new SiteData.Storage());
    }
    ScriptSession(SiteData data,SiteData.Storage session)throws Exception{
        siteData=data;sessionStorage=session;
        Path path=host(); if(!Files.isRegularFile(path))throw new IOException("This build does not include the Aster script host");
        ProcessBuilder builder=new ProcessBuilder(path.toString());
        // A fixed executable path and binary pipes; page text is never a shell argument.
        builder.redirectError(ProcessBuilder.Redirect.INHERIT); process=builder.start();
        input=new DataInputStream(new BufferedInputStream(process.getInputStream())); output=new DataOutputStream(new BufferedOutputStream(process.getOutputStream()));
        try(InputStream source=ScriptSession.class.getResourceAsStream("/dom.js")) {
            if(source==null)throw new IOException("Aster DOM bootstrap missing");
            ByteArrayOutputStream bytes=new ByteArrayOutputStream(); byte[] buffer=new byte[8192];int n;while((n=source.read(buffer))!=-1)bytes.write(buffer,0,n);
            eval(new String(bytes.toByteArray(),StandardCharsets.UTF_8));
            eval(PreviewMain.resourceText("/web.js"));
        } catch(Exception e) { close(); throw e; }
    }
    synchronized Object eval(String code) throws IOException { return command(1,code); }
    synchronized void controller(boolean allowed) throws IOException { command(2,allowed?"1":"0"); }
    private Object command(int kind,String code) throws IOException {
        if(closed)throw new IOException("Script session has stopped");
        byte[] bytes=code.getBytes(StandardCharsets.UTF_8); if(bytes.length>2*1024*1024)throw new IOException("Script command size limit");
        ScheduledFuture<?> timeout=watchdog.schedule(this::close,2,TimeUnit.SECONDS);
        try {
            output.writeByte(kind); output.writeInt(bytes.length); output.write(bytes); output.flush();
            int length=input.readInt(),requests=0,rpcBytes=0;
            while(length<0){
                int size=length&0x7fffffff;if(size>262144||++requests>1024||(rpcBytes+=size)>4*1024*1024)throw new IOException("Site-data RPC limit");
                byte[] request=new byte[size];input.readFully(request);
                byte[] answer=siteRequest(new String(request,StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8);
                if(answer.length>262144||(rpcBytes+=answer.length)>4*1024*1024)throw new IOException("Site-data response limit");
                output.writeInt(answer.length);output.write(answer);output.flush();length=input.readInt();
            } if(length<0||length>2*1024*1024)throw new IOException("Script response size limit");
            byte[] response=new byte[length];input.readFully(response);Object value=Json.parse(new String(response,StandardCharsets.UTF_8));
            if(value instanceof Map && ((Map<?,?>)value).containsKey("error"))throw new IOException(String.valueOf(((Map<?,?>)value).get("error")));
            return value;
        } catch(Exception e) { close(); throw new IOException("JavaScript stopped: "+e.getMessage(),e); }
        finally { timeout.cancel(false); }
    }
    private String siteRequest(String raw){
        try{
            if(page==null||SiteData.origin(page).isEmpty())throw new SecurityException("Website data is unavailable for this origin");
            Object parsed=Json.parse(raw);if(!(parsed instanceof Map))throw new SecurityException("Invalid site-data request");
            Map<?,?> request=(Map<?,?>)parsed;String operation=Objects.toString(request.get("op"),"");Object result;
            if(operation.equals("cookie-get"))result=siteData.documentCookie(page);
            else if(operation.equals("cookie-set")){Object value=request.get("value");if(!(value instanceof String))throw new SecurityException("Invalid cookie value");siteData.setDocumentCookie(page,(String)value);result=null;}
            else {
                String area=Objects.toString(request.get("area"),"");if(!area.equals("local")&&!area.equals("session"))throw new SecurityException("Invalid storage area");
                String key=request.get("key") instanceof String?(String)request.get("key"):null,value=request.get("value") instanceof String?(String)request.get("value"):null;
                if(!Arrays.asList("get","set","remove","keys","clear").contains(operation))throw new SecurityException("Invalid storage operation");
                result=siteData.storage(sessionStorage,area.equals("local"),page,operation,key,value);
            }
            Map<String,Object> response=new LinkedHashMap<>();response.put("value",result);return Json.stringify(response);
        }catch(Exception e){return Json.stringify(Map.of("error",e instanceof IllegalArgumentException?"QuotaExceededError":"SecurityError","message","Website data operation refused"));}
    }
    @SuppressWarnings("unchecked") Map<String,Object> start(Engine.Document document) throws IOException {
        if(document.scriptsBlocked)throw new IOException("Pages with Content Security Policy await Aster's policy implementation; scripts remain disabled");
        if(page!=null)throw new IOException("Script session already has an origin");
        page=document.uri;network=new PageNetwork(document.uri,siteData);
        Object value=eval("__aster.init("+Json.quote(document.source)+","+Json.quote(document.uri.toString())+")");
        eval(PreviewMain.resourceText("/storage.js"));
        List<Object> scripts=(List<Object>)value; int total=0;
        for(Object item:scripts) {
            Map<String,Object> script=(Map<String,Object>)item; String src=String.valueOf(script.get("src"));
            String code=src.isEmpty()?String.valueOf(script.get("code")):ResourceLoader.script(document.uri,src,siteData);
            total+=code.length(); if(total>1_000_000)throw new IOException("Combined script source exceeds 1 MB");
            eval(code+"\n;void 0;");
        }
        eval("__aster.ready();void 0"); pump(); return snapshot();
    }
    @SuppressWarnings("unchecked") synchronized void pump() throws IOException {
        if(network==null)return;
        Object commands=eval("__asterWeb.drain()");
        if(!(commands instanceof List)||((List<?>)commands).size()>64)throw new IOException("Invalid page network commands");
        for(Object c:(List<?>)commands){if(!(c instanceof Map))throw new IOException("Invalid page network command");network.accept((Map<String,Object>)c);}
        String events=network.drain();if(!events.equals("[]"))eval("__asterWeb.complete("+events+");void 0");
    }
    @SuppressWarnings("unchecked") Map<String,Object> snapshot() throws IOException { return (Map<String,Object>)eval("__aster.snapshot()"); }
    public void close() {
        if(closed)return; closed=true; if(network!=null)network.close();process.destroyForcibly(); watchdog.shutdownNow();
        // Destroy first: closing a pipe must not block behind a hostile reader/writer.
        try{input.close();}catch(IOException ignored){} try{output.close();}catch(IOException ignored){}
    }
    boolean alive() { return !closed && process.isAlive(); }
}
