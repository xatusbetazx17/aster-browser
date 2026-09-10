package io.aster.desktop;

import io.aster.engine.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.net.URI;
import java.nio.file.*;
import javax.imageio.ImageIO;
import javax.swing.*;

/** Resizing the actual page canvas must recascade CSS and preserve hit targets. */
public final class ResponsiveTests {
    private static final URI PAGE=URI.create("https://responsive.example.org/");
    private static void check(boolean value,String why){if(!value)throw new AssertionError(why);}
    private static Engine.Document viewport(String html,float width,float height){return Engine.forViewport(Engine.parse(PAGE,html),width,height);}
    private static void query(String media,float width,float height,boolean expected){
        Engine.Document document=viewport("<style>@media "+media+" {p{color:red}}</style><p>Query</p>",width,height);
        check((document.runs.get(0).style.color==0xffff0000)==expected,"Wrong media match: "+media+" at "+width+"x"+height);
    }
    private static BufferedImage paint(PreviewMain.PageCanvas canvas,int width,int height){
        canvas.setSize(width,height);BufferedImage image=new BufferedImage(width,height,BufferedImage.TYPE_INT_RGB);Graphics2D g=image.createGraphics();canvas.paint(g);g.dispose();return image;
    }
    private static int color(String html){return Engine.parse(PAGE,html).runs.get(0).style.color;}
    public static void main(String[] args)throws Exception{
        query("screen and (max-width:600px)",600,700,true);query("(max-width:600px)",601,700,false);
        query("only screen and (min-width:30em) and (max-height:50rem)",480,800,true);
        query("(min-width:30em)",479,800,false);query("(width:480px) and (height:800px)",480,800,true);
        query("(width > 480px)",481,800,true);query("(width <= 480px)",481,800,false);
        query("(min-width:0)",1,800,true);query("(min-width:1)",500,800,false);
        query("(orientation:portrait)",600,600,true);query("(orientation:landscape)",700,600,true);
        query("print",800,600,false);query("not print",800,600,true);query("not\tscreen",800,600,false);
        query("not screen and (max-width:600px)",800,600,true);query("screen and (unknown:1), (min-width:800px)",800,600,true);
        query("not (unknown:1)",800,600,false);query("screen and",800,600,false);query("only (width:800px)",800,600,false);
        check(color("<style>#x{color:red!important}p{color:green}</style><p id='x' style='color:blue'>Priority</p>")==0xffff0000,"Author important lost to inline normal");
        check(color("<style>#x{color:red!important}</style><p id='x' style='color:blue ! IMPORTANT'>Priority</p>")==0xff0000ff,"Inline important lost");
        check(color("<style>p{color:red!important}p{color:blue!important;color:green}</style><p>Order</p>")==0xff0000ff,"Important source order wrong");
        check(color("<style>@unknown{p{color:blue}}@media screen{@media(min-width:600px){p{content:'}';color:red}}}</style><p>Nested</p>")==0xffff0000,"Nested media, unknown at-rule or quoted brace parsing failed");
        check(color("<style>@supports (display:grid){@media screen{p{color:blue}}}p{color:red}</style><p>Scoped</p>")==0xffff0000,"Unsupported at-rule leaked nested rules");
        check(color("<style>/* comment { } */p{color:/* bounded */red}</style><p>Comment</p>")==0xffff0000,"CSS comments changed declarations");
        check(viewport("<style media='(max-width:500px)'>p{color:red}</style><p>Media</p>",400,800).runs.get(0).style.color==0xffff0000,"Style media attribute ignored");
        check(viewport("<style media='print'>p{display:none}</style><p>Visible</p>",400,800).text().equals("Visible"),"Print-only style changed screen output");
        String hidden="<style>.small{display:none}@media(max-width:500px){.small{display:block}.wide{display:none}}</style><p class='small'>Small</p><p class='wide'>Wide</p>";
        Engine.Document small=viewport(hidden,400,800);check(small.text().equals("Small"),"Hidden small content not restored");
        check(Engine.forViewport(small,900,600).text().equals("Wide"),"Resize did not restore wide content");
        check(Engine.forViewport(small.blockScripts(),900,600).scriptsBlocked,"Resize cleared CSP script block");
        check(Engine.forViewport(small,400,800)==small,"Same viewport reparsed unnecessarily");
        String nested="@media screen{".repeat(64)+"p{color:red}"+"}".repeat(64);
        check(color("<style>"+nested+"</style><p>Bounded</p>")==Engine.DEFAULT.color,"CSS nesting limit failed");
        try{Engine.parse(PAGE,"<style>"+("p{color:red;"+"padding:1px;".repeat(100)+"}").repeat(100)+"</style><p>Bounded cascade</p>");throw new AssertionError("Repeated matching rules exceeded the CSS budget");}catch(IllegalArgumentException expected){}
        String fixture="<style>main{max-width:680px;margin:0 auto;padding:26px;background:#edf7f3;border:1px solid #c2dcd3;border-radius:14px}h1{color:#133e35}p{color:#40594f}.mobile{display:none}@media(max-width:600px){main{padding:12px;background:#e9f2ff}.mobile{display:block}.desktop{display:none}h1{font-size:25px}}@media(orientation:portrait){.orientation{color:#2469ad}}</style><main><h1>A page that fits.</h1><p class='desktop'>The wider layout has room to breathe.</p><p class='mobile'>The compact layout keeps the useful content visible.</p><p>Resize the window and the same page changes its spacing, background and content.</p><p class='orientation'>Orientation follows the viewport.</p><p><a href='/next'>Keep exploring</a></p></main>";
        SwingUtilities.invokeAndWait(()->{try{
            PreviewMain.PageCanvas canvas=new PreviewMain.PageCanvas();canvas.setDocument(Engine.parse(PAGE,fixture));
            BufferedImage wide=paint(canvas,900,650);check(canvas.document.text().contains("wider layout")&&!canvas.document.text().contains("compact layout"),"Wide canvas has wrong content");
            Engine.Rect box=canvas.layout.boxes.get(1);check(wide.getRGB((int)box.x+8,(int)box.y+25)==0xffedf7f3,"Wide CSS background not painted");
            BufferedImage narrow=paint(canvas,390,700);check(canvas.document.text().contains("compact layout")&&!canvas.document.text().contains("wider layout"),"Narrow canvas did not recascade");
            box=canvas.layout.boxes.get(1);check(narrow.getRGB((int)box.x+4,(int)box.y+25)==0xffe9f2ff,"Narrow CSS background not painted");
            Engine.Draw link=canvas.layout.items.stream().filter(d->d.link!=null).findFirst().orElseThrow();check(PAGE.resolve("/next").equals(canvas.layout.hit(link.x+1,link.y+1)),"Responsive link hit target drifted");
            paint(canvas,900,650);check(canvas.document.text().contains("wider layout"),"Second resize retained narrow styles");
            canvas.scale=2;paint(canvas,900,650);check(canvas.document.text().contains("compact layout"),"Zoom did not change CSS viewport");
            canvas.scale=1;JViewport viewport=new JViewport();viewport.setView(canvas);viewport.setExtentSize(new Dimension(500,300));
            canvas.setDocument(Engine.parse(PAGE,"<style>@media(orientation:landscape){p{color:red}}</style><p>Viewport height</p>"));
            canvas.setSize(500,4000);canvas.ensureLayout();check(canvas.document.runs.get(0).style.color==0xffff0000,"Media used scrollable document height");
            viewport.setExtentSize(new Dimension(500,800));canvas.ensureLayout();check(canvas.document.runs.get(0).style.color==Engine.DEFAULT.color,"Height-only resize did not recascade");
            Path folder=Paths.get(args.length>0?args[0]:".");ImageIO.write(wide,"png",folder.resolve("aster-responsive-wide.png").toFile());ImageIO.write(narrow,"png",folder.resolve("aster-responsive-narrow.png").toFile());
        }catch(Exception e){throw new RuntimeException(e);}});
        System.out.println("Responsive CSS passed: viewport media queries, nested conditions, media attributes, important cascade, bounds, live resize/zoom, restored content, actual painted pixels and link hit testing.");
    }
}
