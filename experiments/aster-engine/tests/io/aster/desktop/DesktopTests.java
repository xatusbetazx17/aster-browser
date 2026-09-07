package io.aster.desktop;

import io.aster.engine.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.prefs.Preferences;
import java.util.concurrent.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.*;
import javax.imageio.ImageIO;
import javax.swing.*;

/** Behavior tests against the real desktop controls, with an isolated profile. */
public final class DesktopTests {
    private static PreviewMain app;
    private static void check(boolean value, String reason) { if(!value) throw new AssertionError(reason); }
    private static void edt(Runnable task) throws Exception { SwingUtilities.invokeAndWait(task); }
    private static void layout(Container c) { c.doLayout(); for(Component child:c.getComponents()) if(child instanceof Container) layout((Container)child); }
    private static void render(String path, int width) {
        app.surface.setSize(width,780); for(int i=0;i<3;i++) layout(app.surface);
        BufferedImage image=new BufferedImage(width,780,BufferedImage.TYPE_INT_RGB);
        Graphics2D g=image.createGraphics(); app.surface.printAll(g); g.dispose();
        try { ImageIO.write(image,"png",new File(path)); } catch(Exception e) { throw new RuntimeException(e); }
        check(app.current().getViewport().getView().getWidth() <= width,"Internal page overflowed window");
    }
    private static <T> T find(Container root,Class<T> type) {
        for(Component c:root.getComponents()) { if(type.isInstance(c)) return type.cast(c); if(c instanceof Container) { T result=find((Container)c,type); if(result!=null) return result; } } return null;
    }
    private static void await(URI uri) throws Exception {
        long deadline=System.nanoTime()+10_000_000_000L;
        while(System.nanoTime()<deadline) { boolean[] ready={false}; edt(()->ready[0]=app.current().canvas.document.uri.equals(uri)); if(ready[0])return; Thread.sleep(20); }
        throw new AssertionError("Desktop navigation timed out: "+uri);
    }
    public static void main(String[] args) throws Exception {
        Preferences prefs=Preferences.userRoot().node("io/aster/ui-test-"+UUID.randomUUID());
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        byte[] downloadBytes="Aster download from a real link".getBytes(StandardCharsets.UTF_8);
        server.createContext("/download",exchange->{
            exchange.getResponseHeaders().set("Content-Type","application/octet-stream");
            exchange.getResponseHeaders().set("Content-Disposition","attachment; filename=aster-test.txt");
            exchange.sendResponseHeaders(200,downloadBytes.length);exchange.getResponseBody().write(downloadBytes);exchange.close();
        });
        server.createContext("/",exchange->{
            byte[] bytes=(exchange.getRequestURI().getPath().equals("/b") ? "<title>Second page</title><p>Arrived</p>" : "<title>First page</title><h1>Navigation fixture</h1><a href='/b'>Continue to second page</a>").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","text/html"); exchange.sendResponseHeaders(200,bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        }); server.start();
        URI first=URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/a"), second=first.resolve("/b");
        String output=args.length>0 ? args[0] : ".";
        Path downloadFolder=Files.createTempDirectory("aster-desktop-download-");
        try {
            edt(()->{
                app=new PreviewMain(prefs);
                check(app.current().canvas.document.uri.equals(PageLoader.HOME),"Welcome missing before window display");
                check(!app.status.isVisible(),"Permanent status bar remains");
                render(output+"/aster-ui-home.png",1100); render(output+"/aster-ui-narrow.png",720);
                PreviewMain.Tab original=app.current(); app.newTab(); PreviewMain.Tab selected=app.current();
                original.chip.close.doClick(); check(app.current()==selected && app.tabs.getTabCount()==1,"Background close affected selected tab");
                selected.chip.close.doClick(); check(app.tabs.getTabCount()==1 && app.current()!=selected,"Closing final tab did not create welcome");
                check(app.strip.getComponentCount()==2,"Closed tab chips leaked");
                app.load(app.current(),URI.create("aster:settings"),-1);
                JComboBox<?> size=find(app.current(),JComboBox.class); check(size!=null,"Reading-size setting missing");
                Object before=size.getSelectedItem(); size.dispatchEvent(new MouseWheelEvent(size,MouseEvent.MOUSE_WHEEL,System.currentTimeMillis(),0,5,5,0,false,MouseWheelEvent.WHEEL_UNIT_SCROLL,3,1));
                check(size.getSelectedItem().equals(before),"Closed setting changed on wheel scroll"); size.setSelectedItem("150%");
                JCheckBox motion=find(app.current(),JCheckBox.class); motion.doClick(); check(app.reducedMotion,"Reduced motion setting failed");
                check(app.pageScale==1.5 && prefs.getInt("pageScale",0)==150,"Reading size was not saved");
                render(output+"/aster-ui-settings.png",1100);
                check(PageLoader.link(first,"aster:settings")==null,"Web content gained internal-page access");
                try { PreviewMain.target("aster:unknown"); throw new AssertionError("Unknown internal URI accepted"); } catch(IllegalArgumentException expected) { }
                app.load(app.current(),first,-1);
            });
            await(first);
            edt(()->{
                check(app.current().getViewport().getView()==app.current().canvas,"Web canvas not restored after settings");
                app.saveBookmark(); check(prefs.getInt("count",0)==1,"Bookmark not saved");
                render(output+"/aster-ui-web.png",1100);
                PreviewMain.PageCanvas canvas=app.current().canvas; canvas.ensureLayout();
                Engine.Draw link=canvas.layout.items.stream().filter(d->d.link!=null).findFirst().get();
                canvas.dispatchEvent(new MouseEvent(canvas,MouseEvent.MOUSE_CLICKED,System.currentTimeMillis(),0,(int)((link.x+2)*canvas.scale),(int)((link.y+5)*canvas.scale),1,false,MouseEvent.BUTTON1));
            });
            await(second);
            edt(()->app.move(-1)); await(first);
            edt(()->{
                app.load(app.current(),URI.create("aster:history"),-1); check(app.visits.size()>=2,"Successful visits missing from session history");
                render(output+"/aster-ui-history.png",1100);
                app.load(app.current(),URI.create("aster:bookmarks"),-1); render(output+"/aster-ui-bookmarks.png",1100);
                app.load(app.current(),URI.create("aster:downloads"),-1); render(output+"/aster-ui-downloads.png",1100);
            });
            edt(()->app.load(app.current(),first.resolve("/download"),-1)); await(first.resolve("/download"));
            edt(()->{
                check(app.current().getViewport().getView()!=app.current().canvas,"File response did not show Save As offer");
                JButton save=find(app.current().getViewport(),JButton.class); check(save!=null && save.getText().equals("Save as…"),"Save As button missing");
                app.destinationChooser=name->{check(name.equals("aster-test.txt"),"Server filename lost in Save As");return downloadFolder.resolve(name);};
                save.doClick(); check(app.downloads.snapshot().size()==1,"Save As did not start a transfer");
            });
            long deadline=System.nanoTime()+10_000_000_000L;
            while(!app.downloads.snapshot().get(0).finished()){if(System.nanoTime()>deadline)throw new AssertionError("UI download timed out");Thread.sleep(20);}
            check(Arrays.equals(downloadBytes,Files.readAllBytes(downloadFolder.resolve("aster-test.txt"))),"UI download saved wrong bytes");
            edt(()->{
                render(output+"/aster-ui-downloads-complete.png",1100);
                app.dispose(); app=new PreviewMain(prefs);
                check(app.pageScale==1.5 && app.reducedMotion && prefs.getInt("count",0)==1,"Settings/bookmarks did not survive restart");
                check(app.visits.isEmpty(),"Session history persisted unexpectedly");
                System.out.println("Desktop UI passed: synchronous welcome, background/final-tab close, wheel protection, settings persistence, real HTTP navigation, scaled link click, Back, history, bookmarks and an actual Save As file transfer.");
            });
        } finally { server.stop(0); edt(()->{ if(app!=null)app.dispose(); }); prefs.removeNode();
            try(java.util.stream.Stream<Path> files=Files.walk(downloadFolder)){files.sorted(Comparator.reverseOrder()).forEach(p->{try{Files.deleteIfExists(p);}catch(Exception ignored){}});}
        }
    }
}
