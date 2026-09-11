package io.aster.desktop;

import io.aster.engine.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.net.URI;
import java.nio.file.*;
import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;

/** Observable geometry, interaction and actual native-canvas pixels. */
public final class FlexLayoutTests {
    private static final URI PAGE=URI.create("https://flex.example.org/");
    private static void check(boolean value,String why){if(!value)throw new AssertionError(why);}
    private static void near(float value,float expected,String why){check(Math.abs(value-expected)<.04,why+": "+value+" != "+expected);}
    private static Engine.Layout layout(String html){return Engine.layout(Engine.parseInteractive(PAGE,html),648,(t,s)->t.codePointCount(0,t.length())*8);}
    private static Engine.Draw word(Engine.Layout l,String text){return l.items.stream().filter(d->d.text.equals(text)).findFirst().orElseThrow(()->new AssertionError("Missing text "+text));}
    private static String row(String css,String children){return "<main style='display:flex;width:300px;"+css+"'>"+children+"</main>";}
    private static String box(String css,String text){return "<div style='"+css+"'>"+text+"</div>";}
    private static BufferedImage paint(PreviewMain.PageCanvas c,int w,int h){c.setSize(w,h);BufferedImage image=new BufferedImage(w,h,BufferedImage.TYPE_INT_RGB);Graphics2D g=image.createGraphics();c.paint(g);g.dispose();return image;}
    public static void main(String[] args)throws Exception{
        Engine.Layout l=layout(row("gap:12px",box("flex:1;min-width:0","one")+box("flex:2;min-width:0","two")));
        near(l.boxes.get(2).width,96,"1:2 flex growth");near(l.boxes.get(3).width,192,"Weighted growth");near(word(l,"two").x,132,"Row gap");near(word(l,"one").y,word(l,"two").y,"Row alignment");
        l=layout(row("",box("flex:1;min-width:0;max-width:60px","cap")+box("flex:1;min-width:0","rest")));
        near(l.boxes.get(2).width,60,"Maximum freezes");near(l.boxes.get(3).width,240,"Redistribute remaining growth");
        l=layout(row("",box("width:200px;min-width:180px","minimum")+box("width:200px;min-width:0","shrinks")));
        near(l.boxes.get(2).width,180,"Minimum freezes");near(l.boxes.get(3).width,120,"Redistribute remaining shrink");
        l=layout(row("width:150px",box("width:100px;min-width:0","first")+box("width:200px;min-width:0","second")));
        near(l.boxes.get(2).width,50,"Shrink weighted by base");near(l.boxes.get(3).width,100,"Larger base shrinks more");
        l=layout(row("",box("flex:.25;min-width:0","a")+box("flex:.25;min-width:0","b")));
        near(l.boxes.get(2).width,75,"Partial grow does not consume all free space");near(l.boxes.get(3).width,75,"Fractional growth");
        l=layout(row("width:100px",box("width:100px;flex-shrink:.25;min-width:0","a")+box("width:100px;flex-shrink:.25;min-width:0","b")));
        near(l.boxes.get(2).width,75,"Partial shrink retains overflow");
        l=layout(row("width:60px",box("","longword")+box("","other")));
        near(l.boxes.get(2).width,64,"Automatic minimum preserves longest word");near(l.boxes.get(3).width,40,"Content minimum overflow");
        l=layout(row("gap:10px;flex-wrap:wrap",box("flex:0 0 140px","a")+box("flex:0 0 140px","b")+box("flex:0 0 140px","c"))+"<p style='margin:0'>after</p>");
        near(word(l,"b").x,174,"Wrapped first line");near(word(l,"c").y,59,"Wrapped row gap");near(word(l,"after").y,84,"Next block clears flex lines");
        l=layout(row("height:100px;align-items:center;justify-content:space-between",box("width:40px;height:20px","a")+box("width:40px;height:40px","b")));
        near(l.boxes.get(2).y,64,"Cross-axis center");near(l.boxes.get(3).y,54,"Unequal centered heights");near(l.boxes.get(3).x,284,"Space between");
        l=layout(row("height:100px",box("width:40px;max-height:70px","a")+box("width:40px;align-self:flex-end;height:20px","b")));
        near(l.boxes.get(2).height,70,"Stretch respects max-height");near(l.boxes.get(3).y,104,"align-self overrides container");
        l=layout(row("height:100px",box("width:40px;height:20px;margin:auto 0","a")+box("width:40px;margin-left:auto","b")));
        near(l.boxes.get(2).y,64,"Cross auto margins");near(l.boxes.get(3).x,284,"Main auto margin");
        l=layout(row("flex-direction:row-reverse",box("width:50px;order:2","a")+box("width:50px;order:-1","b")));
        near(word(l,"b").x,274,"Reverse starts at right");near(word(l,"a").x,224,"Order before reversal");check(l.readingItems.get(0).text.equals("a"),"Visual order changed DOM reading order");
        l=layout(row("flex-direction:column;height:200px;gap:10px",box("flex:1;min-height:0","a")+box("flex:1;min-height:0","b")));
        near(l.boxes.get(2).height,95,"Column flex growth");near(l.boxes.get(3).y,129,"Column gap");near(l.boxes.get(2).width,300,"Column cross stretch");
        l=layout(row("flex-direction:column-reverse;height:100px;align-items:center",box("width:50px;height:25px","a")+box("width:50px;height:25px","b")));
        near(word(l,"a").y,99,"Column reversal");near(word(l,"a").x,149,"Column cross centering");
        l=layout(row("flex-flow:column wrap;height:70px;gap:10px;align-content:flex-start",box("width:40px;height:30px;flex:none","a")+box("width:40px;height:30px;flex:none","b")+box("width:40px;height:30px;flex:none","c")));
        near(word(l,"b").y,64,"Column wrapping first column");near(word(l,"c").x,74,"Column wrapping second column");
        l=layout(row("flex-wrap:wrap-reverse;width:110px;height:100px;gap:10px;align-content:space-between",box("width:60px;height:25px;flex:none","a")+box("width:60px;height:25px;flex:none","b")));
        near(word(l,"a").y,99,"Reverse wrap first line");near(word(l,"b").y,24,"Reverse wrap second line");
        l=layout(row("",box("flex:1;min-width:0;box-sizing:border-box;padding:10px;border:2px solid red","a")+box("flex:1;min-width:0;box-sizing:border-box;padding:10px;border:2px solid blue","b")));
        near(l.boxes.get(2).width,150,"Flex padding/border sizing");near(word(l,"a").x,36,"Flex text inset");
        l=layout(row("gap:8px","   text <span style='width:50px'>span <b>bold</b></span> <button data-aster-action='7'>click</button><span hidden>secret</span>"));
        near(word(l,"span").x,64,"Anonymous flex item and whitespace");check(l.items.stream().noneMatch(d->d.text.equals("secret")),"Hidden flex item reserved space");Engine.Draw click=word(l,"click");check(l.actionAt(click.x+1,click.y+1)==7,"Moved action hit target wrong");
        l=layout(row("gap:10px","<meta name='description' content='metadata'><link rel='stylesheet' href='/style.css'>"+box("width:40px","a")+box("width:40px","b")));near(word(l,"a").x,24,"Metadata generated flex items");near(word(l,"b").x,74,"Metadata inserted flex gaps");
        l=layout(row("height:100px",box("flex:1;min-width:0;display:flex;flex-direction:column",box("height:50%;min-height:0","half"))));
        near(l.boxes.get(3).height,50,"Nested percentage height after stretch");
        l=layout(row("flex-direction:column",box("flex:1","auto")+box("flex:1","height")));
        near(l.boxes.get(1).height,50,"Indefinite percentage basis falls back to content");
        l=layout(row("min-height:100px;align-items:center",box("height:20px","minimum")));near(word(l,"minimum").y,64,"Min-height cross alignment");
        l=layout("<main>"+box("","line<br>".repeat(450))+"</main><div>tail</div>");check(word(l,"tail").y>11200,"Long normal-flow content height was truncated");
        l=layout(row("",box("flex:0 0 60px;flex:1 auto 2","valid")+box("flex:0 0 60px","next")));near(l.boxes.get(2).width,60,"Invalid shorthand leaves valid flex declaration intact");
        try(ScriptSession js=new ScriptSession()){
            Engine.Document original=Engine.parse(PAGE,"<main id='cards' style='display:flex;gap:10px'><a href='/a'>first</a><button id='go'>second</button></main><script>document.getElementById('go').addEventListener('click',()=>document.getElementById('cards').style.flexDirection='column');</script>");
            Engine.Document scripted=Engine.parseInteractive(original,js.start(original).get("html").toString());Engine.Layout before=Engine.layout(scripted,648,(t,s)->t.length()*8);
            Engine.Draw button=word(before,"second");int target=before.actionAt(button.x+1,button.y+1);check(target>0,"Scripted flex button has no action");
            js.eval("__aster.click("+target+")");Engine.Layout changed=Engine.layout(Engine.parseInteractive(scripted,js.snapshot().get("html").toString()),648,(t,s)->t.length()*8);
            near(word(changed,"second").y,word(changed,"first").y+35,"QuickJS style mutation did not reflow flex content");
            check(Boolean.TRUE.equals(js.eval("(()=>{let s=document.getElementById('cards').style;if(s.display!=='flex'||s.gap!=='10px')return false;s.setProperty('gap','12px','important');if(s.getPropertyPriority('gap')!=='important')return false;if(s.removeProperty('gap')!=='12px'||s.gap!=='')return false;s.cssText='display:flex;gap:8px;color:red!important;color:blue';if(s.color!=='red')return false;s.flexDirection='row';if(!document.getElementById('cards').getAttribute('style').includes('gap: 8px'))return false;s.setProperty('gap','5px;color:green');if(s.gap!=='8px')return false;document.getElementById('cards').setAttribute('style','display:block');return s.display==='block'&&s.gap==='';})()")),"Live style declaration read/write/priority/removal diverged from attribute");
            check(Boolean.TRUE.equals(js.eval("(()=>{let s=document.getElementById('cards').style,v='url(\"x;/*keep*/y\")';s.cssText='background-image:'+v+';gap:2px';s.gap='4px';return s.getPropertyValue('background-image')===v&&s.gap==='4px';})()")),"Style mutation corrupted a quoted value or function");
        }
        l=layout(row("gap:10px",box("width:120px","alpha<br>continuation")+box("width:120px","neighbor")));
        for(int i=1;i<l.items.size();i++)check(l.items.get(i).y>=l.items.get(i-1).y,"Paint culling is not sorted");
        Engine.Draw continuation=word(l,"continuation");check(l.firstVisible(continuation.y)<l.items.size(),"Second line disappears from culling");
        String nested="<span style='display:flex;min-width:0;flex:1'>".repeat(40)+"deep"+"</span>".repeat(40);
        check(word(layout(nested),"deep")!=null,"Nested flex measurement did not terminate");
        String many=box("flex:1;min-width:0","x").repeat(800);check(layout(row("flex-wrap:wrap",many)).items.size()==800,"Bounded large flex row lost items");
        String fixture="<style>main{max-width:780px;margin:0 auto;padding:22px;background:#edf7f3;border-radius:14px}h1{color:#133e35}.cards{display:flex;gap:16px;flex-wrap:wrap}.card{flex:1 1 180px;min-width:0;padding:18px;background:#ffffff;border:1px solid #c2dcd3;border-radius:10px}.card p{margin:0}.card h2{font-size:21px;color:#133e35}.nav{display:flex;gap:20px;justify-content:space-between;margin:0 0 18px}.note{color:#40594f}@media(max-width:550px){main{padding:12px}.cards{flex-direction:column}.nav{flex-wrap:wrap}h1{font-size:25px}}</style><main><nav class='nav'><a href='/home'>Aster</a><a href='/read'>Reading</a><a href='/saved'>Saved pages</a></nav><h1>A page with room to adapt.</h1><p class='note'>The same content fits a wide workspace and a small screen.</p><section class='cards'><article class='card'><h2>Read comfortably</h2><p>Keep your place across lines and columns. The words stay in reading order.</p><p><a href='/next'>Continue reading</a></p></article><article class='card'><h2>Keep what matters</h2><p>Return to useful pages. Resize the window and these cards wrap into a column.</p></article><article class='card'><h2>Stay in control</h2><p>Links and page actions move with their content, with consistent spacing.</p></article></section><p class='note'>Rendered by Aster's own page engine.</p></main>";
        SwingUtilities.invokeAndWait(()->{try{
            PreviewMain.PageCanvas canvas=new PreviewMain.PageCanvas();canvas.setDocument(Engine.parse(PAGE,fixture));
            BufferedImage wide=paint(canvas,960,640);java.util.List<Engine.Rect> cards=new java.util.ArrayList<>();for(Engine.Rect r:canvas.layout.boxes)if(r.background==0xffffffff)cards.add(r);check(cards.size()==3,"Missing canvas cards");near(cards.get(0).y,cards.get(1).y,"Actual canvas wide cards");near(cards.get(0).y,cards.get(2).y,"Actual canvas third card");
            canvas.findText="place across lines";canvas.search();check(canvas.findRects.size()==1,"Column phrase search was interleaved with neighboring text");
            BufferedImage narrow=paint(canvas,390,1000);Engine.Draw read=word(canvas.layout,"comfortably"),keep=word(canvas.layout,"matters");check(keep.y>read.y+100,"Actual narrow canvas did not become a column");
            Engine.Draw link=word(canvas.layout,"Continue");check(PAGE.resolve("/next").equals(canvas.layout.hit(link.x+1,link.y+1)),"Reflowed link is not clickable");
            Engine.Rect card=canvas.layout.boxes.stream().filter(b->b.background==0xffffffff).findFirst().orElseThrow();check(narrow.getRGB((int)card.x+8,(int)card.y+15)==0xffffffff,"Flex card background was not painted");
            canvas.scale=1.5;paint(canvas,390,1000);link=word(canvas.layout,"Continue");check(PAGE.resolve("/next").equals(canvas.layout.hit(link.x+1,link.y+1)),"Zoom lost flex hit target");
            Path folder=Paths.get(args.length>0?args[0]:".");ImageIO.write(wide,"png",folder.resolve("aster-flex-wide.png").toFile());ImageIO.write(narrow,"png",folder.resolve("aster-flex-narrow.png").toFile());
        }catch(Exception e){throw new RuntimeException(e);}});
        System.out.println("Flex layout passed: rows/columns, wrapping/reversal/order, growth/shrink/min/max, gaps/auto margins/alignment, nested percentages, bounded work, DOM-order search, resize/zoom, link/action hits and actual painted pixels.");
    }
}
