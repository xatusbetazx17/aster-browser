package io.aster.desktop;

import io.aster.engine.PageLoader;
import io.aster.engine.SiteData;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Locale;

/** Script/media subresources use the same explicit URL/redirect limits as navigation. */
final class ResourceLoader {
    static boolean sameOrigin(URI a,URI b) { return a.getScheme().equalsIgnoreCase(b.getScheme()) && a.getHost().equalsIgnoreCase(b.getHost()) && port(a)==port(b); }
    private static int port(URI u) { return u.getPort()<0 ? (u.getScheme().equalsIgnoreCase("https") ? 443 : 80) : u.getPort(); }
    static String script(URI page,String src) throws IOException {
        return script(page,src,new SiteData());
    }
    static String script(URI page,String src,SiteData data)throws IOException{
        URI uri=PageLoader.link(page,src); if(uri==null || !sameOrigin(page,uri)) throw new IOException("Only same-origin classic scripts are supported");
        ByteArrayOutputStream out=new ByteArrayOutputStream(); copy(page,uri,1_000_000,true,out,data.request(page,false,"GET")); return new String(out.toByteArray(),StandardCharsets.UTF_8);
    }
    static Path media(URI page,URI uri) throws IOException {
        String path=uri.getPath().toLowerCase(Locale.ROOT);
        String extension=path.endsWith(".mp4") ? ".mp4" : path.endsWith(".m4a") ? ".m4a" : path.endsWith(".mp3") ? ".mp3" : path.endsWith(".wav") ? ".wav" : null;
        if(extension==null) throw new IOException("Direct MP4, M4A, MP3 and WAV links are supported; live playlists and DRM are unfinished");
        Path file=Files.createTempFile("aster-media-",extension);
        try(OutputStream out=Files.newOutputStream(file)) { copy(page,uri,64*1024*1024,false,out,new SiteData().request(page,false,"GET")); return file; }
        catch(Throwable e) { Files.deleteIfExists(file); throw e; }
    }
    private static void copy(URI page,URI initial,int limit,boolean script,OutputStream out,SiteData.Request request) throws IOException {
        PageLoader.validate(initial); URI uri=initial; long deadline=System.nanoTime()+60_000_000_000L;
        for(int redirects=0;redirects<=5;redirects++) {
            if("https".equalsIgnoreCase(page.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme())) throw new IOException("Mixed-content resource refused");
            if(script && !sameOrigin(page,uri)) throw new IOException("Cross-origin script redirect refused");
            HttpURLConnection c=(HttpURLConnection)uri.toURL().openConnection();
            c.setInstanceFollowRedirects(false); c.setConnectTimeout(8000); c.setReadTimeout(8000); c.setRequestProperty("Accept-Encoding","identity"); c.setRequestProperty("User-Agent","AsterEnginePreview/0.4");
            request.prepare(c);
            try {
                int code=c.getResponseCode();request.receive(c);
                if(code==301||code==302||code==303||code==307||code==308) { URI next=PageLoader.link(uri,c.getHeaderField("Location")); if(next==null || ("https".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(next.getScheme())))throw new IOException("Unsafe resource redirect"); uri=next; continue; }
                if(code<200||code>=300)throw new IOException("Resource returned HTTP "+code);
                String encoding=c.getContentEncoding(); if(encoding!=null&&!encoding.equalsIgnoreCase("identity"))throw new IOException("Encoded resource response not supported");
                String type=c.getContentType(); type=type==null?"":type.split(";",2)[0].trim().toLowerCase(Locale.ROOT);
                if(script && !type.equals("text/javascript")&&!type.equals("application/javascript")&&!type.equals("application/ecmascript")&&!type.equals("text/ecmascript")) throw new IOException("Script response has an unsupported MIME type");
                long expected=c.getContentLengthLong(); if(expected>limit)throw new IOException("Resource exceeds the preview size limit");
                long total=0; byte[] buffer=new byte[8192];
                try(InputStream input=c.getInputStream()) { int n; while((n=input.read(buffer))!=-1) { total+=n; if(total>limit)throw new IOException("Resource exceeds the preview size limit"); if(Thread.currentThread().isInterrupted()||System.nanoTime()>deadline)throw new IOException("Resource cancelled or timed out"); out.write(buffer,0,n); } }
                if(expected>=0 && total!=expected)throw new IOException("Incomplete resource response"); return;
            } finally { c.disconnect(); }
        } throw new IOException("Resource redirected more than five times");
    }
    private ResourceLoader() {}
}
