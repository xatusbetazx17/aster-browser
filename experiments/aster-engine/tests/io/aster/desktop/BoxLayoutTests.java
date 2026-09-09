package io.aster.desktop;

import io.aster.engine.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.net.URI;
import java.nio.file.*;
import javax.imageio.ImageIO;

public final class BoxLayoutTests {
    private static final URI PAGE=URI.create("https://layout.example.org/");
    private static void check(boolean value,String why){if(!value)throw new AssertionError(why);}
    private static void near(float value,float expected,String why){check(Math.abs(value-expected)<.02,why+": "+value+" != "+expected);}
    private static Engine.Layout layout(String source,float width){return Engine.layout(Engine.parse(PAGE,source),width,(text,style)->text.codePointCount(0,text.length())*8);}
    public static void main(String[] args)throws Exception{
        String style="<style>main.card {width:200px;margin:0 auto;padding:10px;border:2px solid #123456;background:#eaf6f1;border-radius:12px} main > p {margin:0} .card p {color:#125544}</style>";
        Engine.Layout flow=layout(style+"<main class='card'><p><a href='/next'>box</a></p></main><div>after</div>",400);
        Engine.Rect box=flow.boxes.get(1);Engine.Draw word=flow.items.get(0),after=flow.items.get(1);
        near(box.x,88,"Centered content box");near(box.width,224,"Padding/border outside content width");near(word.x,100,"Nested text inset");near(word.y,36,"Vertical padding");near(box.height,49,"Natural box height");near(after.y,73,"Following block flow");
        check(flow.hit(word.x+1,word.y+1).equals(PAGE.resolve("/next")),"Box layout lost link hit target");check(flow.hit(box.x+1,box.y+1)==null,"Border became a link");
        Engine.Layout sized=layout("<main style='width:200px;box-sizing:border-box;padding:10px;border:2px solid red;margin:0 auto'>word</main>",400);
        near(sized.boxes.get(1).width,200,"border-box width");near(sized.items.get(0).x,112,"border-box content inset");
        Engine.Layout responsive=layout("<div style='width:90%;max-width:600px;margin:0 auto;min-height:40px;background:#123456'></div>",360);
        near(responsive.boxes.get(1).width,280.8f,"Percentage width");near(responsive.boxes.get(1).height,40,"Empty min-height box");
        Engine.Layout wide=layout("<div style='width:90%;max-width:600px;margin:0 auto;min-height:40px'></div>",1000);near(wide.boxes.get(1).width,600,"Max width");
        Engine.Layout centered=layout("<section style='width:200px;text-align:center'><p style='margin:0'>one two<br>three</p></section>",400);
        near(centered.items.get(0).x,96,"Inherited line alignment");near(centered.items.get(2).y,49,"Hard line break");
        Engine.Document selectors=Engine.parse(PAGE,"<style>p{color:#000000}main>p{color:#ff0000} main p{font-size:22px}.a > .b .c > p{color:#008000} *{font-size:13px}</style><main><div><p>nested</p></div><p>direct</p></main><div class='a'><div class='b'><div class='c'><p>mixed</p></div></div></div>");
        Engine.Layout matched=Engine.layout(selectors,800,(t,s)->t.length()*8);check(matched.items.get(0).style.color==0xff000000&&matched.items.get(0).style.size==22,"Child selector leaked or universal specificity overrode a type");check(matched.items.get(1).style.color==0xffff0000&&matched.items.get(2).style.color==0xff008000,"Mixed selector matching");
        Engine.Layout hidden=layout("<div style='display:none;background:red;padding:40px'>secret</div><div>visible</div>",400);check(hidden.items.size()==1&&hidden.boxes.size()==2,"Hidden subtree reserved layout space");
        try{layout("<br>".repeat(Engine.MAX_RUNS+1),400);throw new AssertionError("Consecutive line breaks escaped layout limits");}catch(IllegalArgumentException expected){}
        Engine.Layout wrapped=layout("<div style='width:50px;padding:12px'><a href='/n'>abcdefghij</a></div>",200);float previous=-1;for(Engine.Draw d:wrapped.items){check(d.x>=36&&d.x+d.width<=86&&d.y>=previous,"Long word overflow or unsorted culling");previous=d.y;}
        String fixture="<style>main{max-width:680px;margin:12px auto;padding:28px;background:#edf7f3;border:1px solid #c2dcd3;border-radius:18px}main>p{margin:12px 0;color:#40594f}h1{color:#133e35}section{margin:20px 0;padding:18px;background:#ffffff;border:1px solid #c2dcd3;border-radius:12px}section p{margin:0} .label{font-size:13px;color:#267861;font-weight:bold}</style><main><p class='label'>ASTER / ORIGINAL PAGE ENGINE</p><h1>Space for the useful things.</h1><p>This page is painted by Aster. Resize the window: the content stays readable, with real spacing, borders and a bounded column.</p><section><h2>Less noise, clear controls.</h2><p>Site protection blocks matching requests before they leave the browser. Your choices stay on your device.</p></section><section><h2>A place to return to.</h2><p>Read, keep notes and revisit saved pages. <a href='/next'>Continue exploring</a>.</p></section><p>Normal block flow is available. Flexbox, grid, full media streaming and WebRTC are still in development.</p></main>";
        PreviewMain.PageCanvas canvas=new PreviewMain.PageCanvas();canvas.setSize(900,800);canvas.setDocument(Engine.parse(PAGE,fixture));BufferedImage image=new BufferedImage(900,800,BufferedImage.TYPE_INT_RGB);Graphics2D g=image.createGraphics();canvas.paint(g);g.dispose();
        Engine.Rect painted=canvas.layout.boxes.get(1);check(image.getRGB((int)painted.x+25,(int)painted.y+10)==0xffedf7f3,"Actual canvas did not paint CSS background");
        check(canvas.layout.items.size()>60&&canvas.layout.items.stream().anyMatch(d->d.link!=null),"Styled text or links disappeared");ImageIO.write(image,"png",Paths.get(args.length>0?args[0]:".","aster-box-layout.png").toFile());
        System.out.println("Box layout passed: nested flow, CSS box sizing, percentage/max widths, empty boxes, scoped descendant/child styles, alignment, wrapping, link hit testing and actual Java2D pixels.");
    }
}
