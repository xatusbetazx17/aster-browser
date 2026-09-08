package io.aster.desktop;

import io.aster.engine.*;
import com.sun.net.httpserver.*;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.prefs.Preferences;
import java.util.zip.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import javax.swing.*;
import javax.imageio.ImageIO;

/** End-to-end fixtures for the shared 0.2 features, without external websites. */
public final class ReadingTests {
    static void check(boolean value,String why){if(!value)throw new AssertionError(why);}
    interface Checked{void run()throws Exception;}
    static void rejects(Checked action)throws Exception{try{action.run();}catch(IllegalArgumentException|IOException expected){return;}throw new AssertionError("Expected refusal");}
    static byte[] docx(String xml)throws Exception{ByteArrayOutputStream b=new ByteArrayOutputStream();try(ZipOutputStream zip=new ZipOutputStream(b)){zip.putNextEntry(new ZipEntry("word/document.xml"));zip.write(xml.getBytes(StandardCharsets.UTF_8));zip.closeEntry();}return b.toByteArray();}
    static void edt(Runnable action)throws Exception{SwingUtilities.invokeAndWait(action);}
    static void waitFor(java.util.function.BooleanSupplier ready)throws Exception{long until=System.nanoTime()+10_000_000_000L;while(!ready.getAsBoolean()){if(System.nanoTime()>until)throw new AssertionError("Fixture timed out");Thread.sleep(25);}}
    public static void main(String[] args)throws Exception{
        URI site=URI.create("https://example.org/page");
        check(PageLoader.searchOrAddress("network engineering").toString().equals("https://html.duckduckgo.com/html/?q=network+engineering"),"Search URL");
        rejects(()->PageLoader.searchOrAddress("javascript:alert(1)"));
        Engine.Document styled=Engine.parse(site,"<style>p {color:red} .green {color:green} #target {color:#123456} .gone {display:none} @media (max-width:1px){p {color:white}}</style><p id='target' class='green'>Visible</p><p class='gone'>Secret</p><img src='/logo.png' width='NaN' height='Infinity' alt='Logo'>");
        check(styled.runs.get(0).style.color==0xff123456&&!styled.text().contains("Secret"),"CSS cascade or hidden content");
        Engine.Layout layout=Engine.layout(styled,320,(t,s)->t.length()*8);
        for(Engine.Draw d:layout.items)check(Float.isFinite(d.width)&&Float.isFinite(d.height),"Unbounded image dimensions");
        PageForms.Form form=PageForms.parse(site,"<form action='/submit' method='post'><input name='q' required><input type='hidden' name='token' value='a&amp;b'><input type='checkbox' name='agree' required></form>").get(0);
        rejects(()->form.submit(Arrays.asList("", "a&b", "on")));rejects(()->form.submit(Arrays.asList("hello", "a&b", null)));
        PageForms.Submission post=form.submit(Arrays.asList("hola mundo", "a&b", "on"));
        check(new String(post.body,StandardCharsets.UTF_8).equals("q=hola+mundo&token=a%26b&agree=on"),"Form encoding");
        rejects(()->PageForms.parse(site,"<form action='https://other.invalid'><input name='x'></form>").get(0).submit(Collections.singletonList("secret")));
        rejects(()->PageForms.parse(site,"<form><select name='x'></select></form>").get(0).submit(Collections.emptyList()));
        String xml="<?xml version='1.0'?><w:document xmlns:w='http://schemas.openxmlformats.org/wordprocessingml/2006/main'><w:body><w:p><w:r><w:t>Hola Marcelo</w:t></w:r></w:p><w:tbl><w:tr><w:tc><w:p><w:r><w:t>Table cell</w:t></w:r></w:p></w:tc></w:tr></w:tbl></w:body></w:document>";
        String extracted=DocumentReader.read("example.docx",new ByteArrayInputStream(docx(xml)));check(extracted.contains("Hola Marcelo")&&extracted.contains("Table cell"),"DOCX paragraphs/tables");
        rejects(()->DocumentReader.read("hostile.docx",new ByteArrayInputStream(docx("<!DOCTYPE x [<!ENTITY y SYSTEM 'file:///secret'>]><x>&y;</x>"))));
        check(DocumentReader.read("notes.txt",new ByteArrayInputStream("English y español".getBytes(StandardCharsets.UTF_8))).contains("español"),"UTF-8 reader");
        try(ScriptSession script=new ScriptSession()){
            script.start(Engine.parse(site,"<h1 id='x'>Hello</h1>"));
            for(int i=0;i<100;i++)check(((Map<?,?>)script.eval("__aster.tick("+(i*50)+")")).get("html")==null,"Idle tick copied full page");
            Map<?,?> changed=(Map<?,?>)script.eval("document.getElementById('x').style.color='red';__aster.tick(5010)");
            check(changed.get("html").toString().contains("color:red"),"Style mutation missed");
            check(((Map<?,?>)script.eval("__aster.tick(5020)")).get("html")==null,"Mutation was repeatedly sent");
            check(((Map<?,?>)script.eval("document.getElementById('x').textContent='Updated';__aster.tick(5030)")).get("html").toString().contains("Updated"),"Text mutation missed");
        }
        BufferedImage logo=new BufferedImage(80,40,BufferedImage.TYPE_INT_RGB);Graphics2D graphics=logo.createGraphics();graphics.setColor(Color.BLUE);graphics.fillRect(0,0,80,40);graphics.dispose();ByteArrayOutputStream image=new ByteArrayOutputStream();ImageIO.write(logo,"png",image);
        check(PreviewMain.decodeImage(image.toByteArray()).getRGB(5,5)==Color.BLUE.getRGB(),"Actual image decoder");rejects(()->PreviewMain.decodeImage(new byte[]{1,2,3}));
        AtomicReference<String> body=new AtomicReference<>();AtomicInteger sheets=new AtomicInteger();
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);URI local=URI.create("http://127.0.0.1:"+server.getAddress().getPort());
        server.createContext("/",exchange->{String path=exchange.getRequestURI().getPath();byte[] payload;String type="text/html";
            if(path.equals("/logo.png")){payload=image.toByteArray();type="image/png";}
            else if(path.equals("/theme.css")){payload=".fixture {color:#123456}".getBytes(StandardCharsets.UTF_8);type="text/css";sheets.incrementAndGet();}
            else if(path.equals("/submit")){body.set(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));payload="<h1>Form received</h1>".getBytes(StandardCharsets.UTF_8);}
            else if(path.equals("/file")){payload=new byte[1200000];Arrays.fill(payload,(byte)29);type="application/octet-stream";}
            else{String html="<title>Reading fixture</title><link rel='stylesheet' href='/theme.css'><h1 class='fixture'>Aster reading</h1><img src='/logo.png' width='240' height='120' alt='Blue image'><p>Images and native reading work together.</p>";payload=html.getBytes(StandardCharsets.UTF_8);}
            if(!path.equals("/logo.png")&&!path.equals("/file")){ByteArrayOutputStream compressed=new ByteArrayOutputStream();try(GZIPOutputStream gzip=new GZIPOutputStream(compressed)){gzip.write(payload);}payload=compressed.toByteArray();exchange.getResponseHeaders().set("Content-Encoding","gzip");}
            exchange.getResponseHeaders().set("Content-Type",type);exchange.sendResponseHeaders(200,payload.length);exchange.getResponseBody().write(payload);exchange.close();});server.start();
        Preferences prefs=Preferences.userRoot().node("io/aster/reading-tests-"+UUID.randomUUID());final PreviewMain[] app={null};
        try{
            Engine.Document loaded=PageLoader.load(local);check(loaded.runs.get(0).style.color==0xff123456&&sheets.get()==1,"Gzip external stylesheet");
            PageLoader.load(local.resolve("/submit"),"q=test".getBytes(StandardCharsets.UTF_8));check(body.get().equals("q=test"),"Actual form POST");
            ByteArrayOutputStream file=new ByteArrayOutputStream();FileTransfer.save(local.resolve("/file"),file,(a,b)->{});check(file.size()==1200000&&file.toByteArray()[1199999]==29,"Android shared direct download bytes");
            rejects(()->PageAssets.fetch(local,URI.create("http://example.invalid/logo.png"),true));
            edt(()->{app[0]=new PreviewMain(prefs);app[0].load(app[0].current(),local,-1);});
            waitFor(()->{AtomicBoolean ready=new AtomicBoolean();try{edt(()->ready.set(!app[0].current().canvas.images.isEmpty()));}catch(Exception e){throw new RuntimeException(e);}return ready.get();});
            edt(()->{PreviewMain.PageCanvas canvas=app[0].current().canvas;canvas.setSize(800,700);BufferedImage rendered=new BufferedImage(800,700,BufferedImage.TYPE_INT_RGB);Graphics2D g=rendered.createGraphics();canvas.paint(g);g.dispose();
                boolean blue=false;for(int y=0;y<700;y+=4)for(int x=0;x<800;x+=4)if(rendered.getRGB(x,y)==Color.BLUE.getRGB())blue=true;check(blue,"Image not painted in application");
                try{ImageIO.write(rendered,"png",Paths.get(args.length>0?args[0]:".","aster-reading.png").toFile());}catch(IOException e){throw new RuntimeException(e);}
                app[0].saveSession();app[0].dispose();app[0]=new PreviewMain(prefs);check(app[0].current().canvas.document.uri.equals(PageLoader.HOME)||"home".equals(PreviewMain.internal(app[0].current().canvas.document.uri)),"Startup fetched saved website automatically");app[0].restoreSession();});
            waitFor(()->{AtomicBoolean ready=new AtomicBoolean();try{edt(()->ready.set(app[0].current().canvas.document.uri.equals(local)));}catch(Exception e){throw new RuntimeException(e);}return ready.get();});
        }finally{edt(()->{if(app[0]!=null)app[0].dispose();});prefs.removeNode();server.stop(0);}
        System.out.println("Reading release passed: search, CSS/gzip, real image decode/paint, native form POST, DOCX security/text, Android download bytes, idle script snapshots and desktop session recovery.");
    }
}
