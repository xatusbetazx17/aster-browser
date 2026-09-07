package io.aster.desktop;

import io.aster.engine.PageLoader;
import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

/** Real HTTP transfers into a temporary directory, without touching user downloads. */
public final class DownloadTests {
    private static void check(boolean value, String reason) { if(!value) throw new AssertionError(reason); }
    private static void until(BooleanSupplier condition) throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
        while(!condition.getAsBoolean()) { if(System.nanoTime()>end) throw new AssertionError("Download operation timed out"); Thread.sleep(15); }
    }
    private static void reply(HttpExchange exchange,byte[] data,String mime) throws IOException {
        exchange.getResponseHeaders().set("Content-Type",mime); exchange.sendResponseHeaders(200,data.length);
        try(OutputStream out=exchange.getResponseBody()) { out.write(data); } finally { exchange.close(); }
    }
    public static void main(String[] args) throws Exception {
        Path folder=Files.createTempDirectory("aster-download-tests-");
        byte[] payload=new byte[1_100_003]; new Random(194).nextBytes(payload);
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        ExecutorService http=Executors.newCachedThreadPool(); server.setExecutor(http);
        server.createContext("/file",e->{e.getResponseHeaders().set("Content-Disposition","attachment; filename*=UTF-8''aster%20sample.bin");reply(e,payload,"application/octet-stream");});
        server.createContext("/text",e->{e.getResponseHeaders().set("Content-Disposition","attachment; filename=notes.txt");reply(e,"saved text".getBytes(StandardCharsets.UTF_8),"text/plain");});
        server.createContext("/redirect",e->{e.getResponseHeaders().set("Location","/file");e.sendResponseHeaders(302,-1);e.close();});
        server.createContext("/unsafe",e->{e.getResponseHeaders().set("Location","file:///not-a-download");e.sendResponseHeaders(302,-1);e.close();});
        server.createContext("/loop",e->{e.getResponseHeaders().set("Location","/loop");e.sendResponseHeaders(302,-1);e.close();});
        server.createContext("/missing",e->{e.sendResponseHeaders(404,-1);e.close();});
        server.createContext("/stream",e->{
            e.sendResponseHeaders(200,0);
            try(OutputStream out=e.getResponseBody()){for(int i=0;i<200;i++){out.write(new byte[4096]);out.flush();Thread.sleep(20);}}catch(Exception expected){}finally{e.close();}
        });
        server.createContext("/truncated",e->{e.sendResponseHeaders(200,100);try{e.getResponseBody().write(new byte[4]);}finally{e.close();}});
        server.start(); URI base=URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/");
        DownloadManager manager=new DownloadManager(t->{}), small=new DownloadManager(t->{},10_000);
        try {
            try { PageLoader.load(base.resolve("file")); throw new AssertionError("Binary file was rendered as a page"); }
            catch(PageLoader.DownloadRequired offer){check(offer.length==payload.length && DownloadManager.filename(offer.uri,offer.disposition).equals("aster sample.bin"),"Download offer lost name or size");}
            try { PageLoader.load(base.resolve("text")); throw new AssertionError("Text attachment was rendered"); } catch(PageLoader.DownloadRequired expected){}
            check(DownloadManager.filename(base,"attachment; filename=../../CON.txt").equals("_CON.txt"),"Unsafe suggested filename");
            check(DownloadManager.filename(base,"attachment; filename*=UTF-8''caf%C3%A9.txt").equals("café.txt"),"UTF-8 filename lost");
            DownloadManager.Transfer file=manager.start(base.resolve("redirect"),folder.resolve("complete.bin")); until(file::finished);
            check(file.state==DownloadManager.State.COMPLETE && Arrays.equals(payload,Files.readAllBytes(file.target)),"Saved bytes differ from server bytes");
            try { manager.start(base.resolve("file"),file.target); throw new AssertionError("Existing file was overwritten"); } catch(FileAlreadyExistsException expected){}
            check(Arrays.equals(payload,Files.readAllBytes(file.target)),"Existing download was modified");
            DownloadManager.Transfer cancelled=manager.start(base.resolve("stream"),folder.resolve("cancelled.bin")); until(()->cancelled.received>0); cancelled.cancel(); until(cancelled::finished);
            check(cancelled.state==DownloadManager.State.CANCELLED && !Files.exists(cancelled.target),"Cancellation retained a partial target");
            DownloadManager.Transfer limited=small.start(base.resolve("stream"),folder.resolve("too-large.bin")); until(limited::finished);
            check(limited.state==DownloadManager.State.FAILED && !Files.exists(limited.target),"Unknown-length response exceeded byte limit");
            DownloadManager.Transfer known=small.start(base.resolve("file"),folder.resolve("known-large.bin")); until(known::finished);
            check(known.state==DownloadManager.State.FAILED && known.received==0,"Oversized Content-Length was not refused early");
            for(String path:new String[]{"unsafe","loop","missing","truncated"}) {
                DownloadManager.Transfer refused=manager.start(base.resolve(path),folder.resolve(path+".bin")); until(refused::finished);
                check(refused.state==DownloadManager.State.FAILED && !Files.exists(refused.target),"Bad HTTP response published a file: "+path);
            }
            try { DownloadManager.redirect(URI.create("https://example.com/file"),"http://example.com/file"); throw new AssertionError("HTTPS downgrade accepted"); } catch(IOException expected){}
            try { manager.start(URI.create("file:///tmp/test"),folder.resolve("local.bin")); throw new AssertionError("Local source accepted"); } catch(IllegalArgumentException expected){}
            // A file created by someone else while the transfer runs must be preserved.
            DownloadManager.Transfer collision=manager.start(base.resolve("stream"),folder.resolve("collision.bin")); until(()->collision.received>0);
            byte[] owner="keep this existing file".getBytes(StandardCharsets.UTF_8); Files.write(collision.target,owner); until(collision::finished);
            check(collision.state==DownloadManager.State.FAILED && Arrays.equals(owner,Files.readAllBytes(collision.target)),"Final filename collision overwrote data");
            DownloadManager.Transfer first=manager.start(base.resolve("stream"),folder.resolve("close-a.bin"));
            DownloadManager.Transfer second=manager.start(base.resolve("stream"),folder.resolve("close-b.bin"));
            try { manager.start(base.resolve("file"),folder.resolve("third.bin")); throw new AssertionError("Concurrent transfer limit ignored"); } catch(IOException expected){}
            manager.close(); until(()->first.finished()&&second.finished());
            check(!Files.exists(first.target)&&!Files.exists(second.target),"Shutdown left incomplete destination files");
            try(java.util.stream.Stream<Path> paths=Files.list(folder)){check(paths.noneMatch(p->p.getFileName().toString().endsWith(".part")),"Partial files leaked");}
            System.out.println("Download tests passed: exact binary bytes over 1 MB, attachments, filenames, redirects, cancellation, limits, HTTP errors, truncation, collisions and shutdown cleanup.");
        } finally {
            manager.close();small.close();server.stop(0);http.shutdownNow();
            try(java.util.stream.Stream<Path> paths=Files.walk(folder)){paths.sorted(Comparator.reverseOrder()).forEach(p->{try{Files.deleteIfExists(p);}catch(IOException ignored){}});}
        }
    }
}
