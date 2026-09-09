package io.aster.engine;

import java.io.*;
import java.net.*;

/** Direct, explicitly granted downloads for native Android Save As. */
public final class FileTransfer {
    public interface Progress { void update(long received,long total); }
    public static void save(URI initial,OutputStream output,Progress progress)throws IOException {
        save(initial,output,progress,new SiteData().request(initial,false,"GET"));
    }
    public static void save(URI initial,OutputStream output,Progress progress,SiteData.Request request)throws IOException {
        PageLoader.validate(initial);URI uri=initial;long limit=512L*1024*1024,deadline=System.nanoTime()+900_000_000_000L;
        for(int redirect=0;redirect<=5;redirect++){
            if(Thread.currentThread().isInterrupted())throw new IOException("Download cancelled.");
            HttpURLConnection c=(HttpURLConnection)uri.toURL().openConnection();c.setInstanceFollowRedirects(false);c.setConnectTimeout(8000);c.setReadTimeout(8000);
            c.setRequestProperty("User-Agent","AsterEnginePreview/0.4");c.setRequestProperty("Accept-Encoding","identity");
            request.prepare(c);
            try{int code=c.getResponseCode();request.receive(c);if(code==301||code==302||code==303||code==307||code==308){
                URI next=PageLoader.link(uri,c.getHeaderField("Location"));if(next==null||uri.getScheme().equalsIgnoreCase("https")&&!next.getScheme().equalsIgnoreCase("https"))throw new IOException("Unsafe download redirect refused.");uri=next;continue;}
                if(code!=200)throw new IOException("Download returned HTTP "+code);
                if(c.getContentEncoding()!=null&&!c.getContentEncoding().equalsIgnoreCase("identity"))throw new IOException("Encoded downloads are unsupported.");
                long expected=c.getContentLengthLong(),received=0,nextUpdate=0;if(expected>limit)throw new IOException("Download exceeds the 512 MiB Android preview limit.");
                try(InputStream input=c.getInputStream()){byte[] buf=new byte[32768];int n;
                    while((n=input.read(buf))!=-1){if(Thread.currentThread().isInterrupted()||System.nanoTime()>deadline)throw new IOException("Download cancelled or timed out.");
                        if(received+n>limit)throw new IOException("Download exceeds size limit.");output.write(buf,0,n);received+=n;
                        if(System.nanoTime()>nextUpdate){progress.update(received,expected);nextUpdate=System.nanoTime()+500_000_000L;}}
                }
                if(expected>=0&&received!=expected)throw new IOException("Download ended before the expected size.");progress.update(received,expected);return;
            }finally{c.disconnect();}
        }
        throw new IOException("Too many download redirects.");
    }
    private FileTransfer() { }
}
