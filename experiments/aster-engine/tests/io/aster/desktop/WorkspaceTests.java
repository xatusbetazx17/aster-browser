package io.aster.desktop;

import io.aster.engine.*;
import com.sun.net.httpserver.HttpServer;
import java.awt.*;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import java.util.prefs.Preferences;
import javax.imageio.ImageIO;
import javax.swing.*;

/** Real tab lifecycle, isolated profile, local HTTP and native workspace controls. */
public final class WorkspaceTests {
    static PreviewMain app;
    static void check(boolean value,String why){if(!value)throw new AssertionError(why);}
    static void edt(Runnable task)throws Exception{SwingUtilities.invokeAndWait(task);}
    static void waitFor(BooleanSupplier condition)throws Exception{long until=System.nanoTime()+10_000_000_000L;while(true){AtomicBoolean ready=new AtomicBoolean();edt(()->ready.set(condition.getAsBoolean()));if(ready.get())return;if(System.nanoTime()>until)throw new AssertionError("Workspace fixture timed out");Thread.sleep(20);}}
    static void layout(Container root){root.doLayout();for(Component c:root.getComponents())if(c instanceof Container)layout((Container)c);}
    static JMenuItem menuAction(PreviewMain browser,String group,String label){
        for(Component section:browser.buildMenu().getComponents())if(section instanceof JMenu&&((JMenu)section).getText().equals(group))
            for(Component child:((JMenu)section).getMenuComponents())if(child instanceof JMenuItem&&((JMenuItem)child).getText().equals(label))return (JMenuItem)child;
        throw new AssertionError("Missing menu action: "+group+" / "+label);
    }
    static void render(Path file,int width){app.surface.setSize(width,780);app.fitReader();for(int i=0;i<4;i++)layout(app.surface);app.workspace.setDividerLocation(width>=1050?.62:.5);for(int i=0;i<3;i++)layout(app.surface);
        BufferedImage image=new BufferedImage(width,780,BufferedImage.TYPE_INT_RGB);Graphics2D g=image.createGraphics();app.surface.printAll(g);g.dispose();try{ImageIO.write(image,"png",file.toFile());}catch(Exception e){throw new RuntimeException(e);}
        check(app.reader.getWidth()>=280&&app.reader.getHeight()>=180,"Reader clipped at supported window size");}
    public static void main(String[] args)throws Exception{
        Preferences prefs=Preferences.userRoot().node("io/aster/workspace-test-"+UUID.randomUUID());Path output=Paths.get(args.length>0?args[0]:".");
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);ExecutorService workers=Executors.newCachedThreadPool();server.setExecutor(workers);
        AtomicInteger oneRequests=new AtomicInteger(),twoRequests=new AtomicInteger();CountDownLatch slowEntered=new CountDownLatch(1),releaseSlow=new CountDownLatch(1);
        StringBuilder article=new StringBuilder("<title>Reading together</title><h1>Follow your curiosity.</h1><p>Curiosity crosses boundaries.</p><a href='/two'>Open another page</a>");
        for(int i=0;i<70;i++)article.append("<p>Aster keeps a place for reading, ideas and useful discoveries.</p>");article.append("<p>Curiosity crosses boundaries again.</p>");
        server.createContext("/",exchange->{String path=exchange.getRequestURI().getPath(),body=article.toString();if(path.equals("/one"))oneRequests.incrementAndGet();
            if(path.equals("/two")){twoRequests.incrementAndGet();body="<title>Second workspace</title><h1>A second page</h1>";}
            if(path.equals("/form"))body="<form><input name='note' value='Keep this form'></form>";
            if(path.equals("/slow")){slowEntered.countDown();try{releaseSlow.await(10,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}body="<h1>Late response must not replace the page</h1>";}
            byte[] bytes=body.getBytes(StandardCharsets.UTF_8);try{exchange.getResponseHeaders().set("Content-Type","text/html");exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);}finally{exchange.close();}
        });server.start();URI one=URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/one"),two=one.resolve("/two");
        try{
            edt(()->{app=new PreviewMain(prefs);
                menuAction(app,"File","New tab").doClick();check(app.tabs.getTabCount()==2,"Menu failed to open a tab");
                menuAction(app,"File","Close tab").doClick();check(app.tabs.getTabCount()==1,"Menu failed to close its tab");
                menuAction(app,"Tools","Bookmarks").doClick();check(app.current().location.equals(URI.create("aster:bookmarks")),"Bookmarks menu did not navigate");
                check(!menuAction(app,"File","Save page as…").isEnabled(),"Internal tools offered a website download");
                app.load(app.current(),one,-1);});waitFor(()->app.current().location.equals(one)&&app.current().pending==null);
            edt(()->check(menuAction(app,"File","Save page as…").isEnabled(),"Website cannot be saved through the menu"));
            final PreviewMain.Tab[] first={null},second={null};final int[] beforePark={0,0};
            edt(()->{first[0]=app.current();app.readPage();app.reader.notes.setText("English notes y español.");app.reader.flush();
                check(prefs.get(ReadingTools.noteKey(one.toString()),"").equals("English notes y español."),"Notes not saved to existing profile identity");
                app.reader.notes.append("é".repeat(3100));check(app.reader.notes.getText().equals("English notes y español."),"Oversized pasted note erased or exceeded saved text");
                app.showReader("Local document","A separate reading space.","file:/fixture.txt");check(app.reader.notes.getText().isEmpty(),"Notes leaked between documents");app.readPage();check(app.reader.notes.getText().contains("español"),"Notes lost on document switch");
                render(output.resolve("aster-workspace-reader.png"),1100);render(output.resolve("aster-workspace-reader-narrow.png"),720);
                check(app.workspace.getOrientation()==JSplitPane.VERTICAL_SPLIT,"Narrow reading workspace did not stack");app.closeReader();
                app.surface.setSize(1100,780);for(int i=0;i<4;i++)layout(app.surface);PreviewMain.PageCanvas canvas=first[0].canvas;canvas.ensureLayout();app.findQuery.setText("curiosity crosses");
                check(canvas.findRects.size()==2,"Phrase search did not cross word fragments");int before=canvas.findIndex;app.findNext(1);check(canvas.findIndex!=before,"Next match did not advance");
                Engine.Draw link=canvas.layout.items.stream().filter(d->d.link!=null).findFirst().get();canvas.dispatchEvent(new MouseEvent(canvas,MouseEvent.MOUSE_CLICKED,System.currentTimeMillis(),MouseEvent.CTRL_DOWN_MASK,(int)(link.x+2),(int)(link.y+4),1,false,MouseEvent.BUTTON1));
                check(app.current()==first[0]&&app.tabs.getTabCount()==2,"Ctrl-click stole focus or reused the current tab");second[0]=(PreviewMain.Tab)app.tabs.getComponentAt(1);
            });waitFor(()->second[0].location.equals(two)&&second[0].pending==null);
            edt(()->{app.tabs.setSelectedComponent(second[0]);first[0].canvas.images.put(one,new BufferedImage(20,20,BufferedImage.TYPE_INT_RGB));
                first[0].getVerticalScrollBar().setValue(250);int history=first[0].history.size();beforePark[0]=first[0].index;beforePark[1]=history;check(app.park(first[0]),"Static background page would not park");
                check(first[0].canvas.document==null&&first[0].canvas.layout==null&&first[0].canvas.images.isEmpty()&&first[0].original==null,"Parking retained page resources");
                check(first[0].history.size()==history&&first[0].location.equals(one),"Parking lost navigation history");check(first[0].restoreScroll==250,"Parking lost scroll position");app.tabs.setSelectedComponent(first[0]);
            });waitFor(()->!first[0].parked&&first[0].pending==null&&first[0].location.equals(one));
            check(oneRequests.get()==2,"Resuming did not perform exactly one new page request");
            edt(()->{check(app.current().index==beforePark[0]&&app.current().history.size()==beforePark[1],"Resume inserted an extra history entry");app.openLinkTab(one.resolve("/form"),false);});
            waitFor(()->((PreviewMain.Tab)app.tabs.getComponentAt(2)).location.getPath().equals("/form"));
            edt(()->{PreviewMain.Tab form=(PreviewMain.Tab)app.tabs.getComponentAt(2);check(!app.park(form),"A form page was discarded");form.chip.close.doClick();
                first[0].postPage=true;check(!app.park(first[0]),"POST response was discarded");first[0].postPage=false;
                app.load(first[0],one.resolve("/slow"),-1);});
            check(slowEntered.await(5,TimeUnit.SECONDS),"Slow navigation never reached server");edt(()->{app.stopNavigation();check(first[0].pending==null&&first[0].location.equals(one),"Stop did not retain current page");});releaseSlow.countDown();
            // A replacement request also proves the navigation worker can continue after cancellation.
            edt(()->app.load(first[0],one,-1));waitFor(()->first[0].pending==null&&first[0].canvas.document.text().contains("Follow your curiosity"));
            edt(()->{check(app.park(second[0]),"Second page would not park");app.saveSession();app.dispose();app=new PreviewMain(prefs);});
            int oneBefore=oneRequests.get(),twoBefore=twoRequests.get();edt(()->app.restoreSession());waitFor(()->app.current().pending==null&&app.current().location.equals(one));
            edt(()->{check(app.tabs.getTabCount()==2&&((PreviewMain.Tab)app.tabs.getComponentAt(1)).parked,"Restore fetched all saved tabs eagerly");
                check(oneRequests.get()==oneBefore+1&&twoRequests.get()==twoBefore,"Lazy restore made unexpected website requests");app.tabs.setSelectedIndex(1);});waitFor(()->app.current().pending==null&&app.current().location.equals(two));
            check(twoRequests.get()==twoBefore+1,"Selecting a restored tab did not load exactly once");
            edt(()->{app.autoPark=true;app.openLinkTab(one.resolve("/three"),false);app.openLinkTab(one.resolve("/four"),false);app.openLinkTab(one.resolve("/five"),false);});
            waitFor(()->((PreviewMain.Tab)app.tabs.getComponentAt(0)).parked);
            edt(()->{check(!app.current().parked&&app.current().location.equals(two),"Automatic parking discarded the active page");
                check(prefs.get(ReadingTools.noteKey(one.toString()),"").equals("English notes y español."),"Notes did not survive workspace restart");});
            System.out.println("Workspace passed: real background-link navigation, phrase find/next, parked resource release and resume, protected forms/POST, navigation cancellation, lazy session restore, responsive reader and isolated persistent notes.");
        }finally{releaseSlow.countDown();server.stop(0);workers.shutdownNow();edt(()->{if(app!=null)app.dispose();});prefs.removeNode();}
    }
}
