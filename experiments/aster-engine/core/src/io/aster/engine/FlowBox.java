package io.aster.engine;

import java.util.*;

/** A bounded block tree alongside the text runs used by reading/accessibility. */
final class FlowBox {
    final BoxStyle box;final Engine.Style style;final List<Object> children=new ArrayList<>();
    FlowBox(String tag,String declarations,Engine.Style style){box=new BoxStyle(tag,declarations);this.style=style;}
    static Engine.Layout layout(FlowBox root,float viewport,Engine.Measure measure){
        Flow flow=new Flow(measure);float height=flow.box(root,24,24,Math.max(52,Math.min(10000,viewport)-48));
        return new Engine.Layout(flow.items,height+24,flow.rects);
    }
    private static final class Flow {
        final Engine.Measure measure;final List<Engine.Draw> items=new ArrayList<>();final List<Engine.Rect> rects=new ArrayList<>();
        Flow(Engine.Measure measure){this.measure=measure;}
        float box(FlowBox node,float left,float top,float available){
            BoxStyle b=node.box;float font=node.style.size;
            float mt=BoxStyle.resolve(b.margin[0],available,font),mr=BoxStyle.resolve(b.margin[1],available,font),mb=BoxStyle.resolve(b.margin[2],available,font),ml=BoxStyle.resolve(b.margin[3],available,font);
            float pt=BoxStyle.resolve(b.padding[0],available,font),pr=BoxStyle.resolve(b.padding[1],available,font),pb=BoxStyle.resolve(b.padding[2],available,font),pl=BoxStyle.resolve(b.padding[3],available,font);
            float extra=pl+pr+2*b.border;
            float width=b.width==null?available-ml-mr-extra:BoxStyle.resolve(b.width,available,font)-(b.borderBox?extra:0);
            if(b.maxWidth!=null)width=Math.min(width,BoxStyle.resolve(b.maxWidth,available,font)-(b.borderBox?extra:0));
            if(b.minWidth!=null)width=Math.max(width,BoxStyle.resolve(b.minWidth,available,font)-(b.borderBox?extra:0));
            width=Math.max(1,Math.min(10000,width));float free=available-ml-mr-width-extra;
            if(free>0){if(b.margin[3]==null)ml+=b.margin[1]==null?free/2:free;else if(b.margin[1]==null)mr+=free;}
            float x=left+ml,y=top+mt;Engine.Rect rect=new Engine.Rect(x,y,width+extra,0,b.background,b.borderColor,b.border,b.radius);
            if(rects.size()>=Engine.MAX_RUNS)throw new IllegalArgumentException("Page has too many layout boxes.");rects.add(rect);
            Line line=new Line(x+pl+b.border,y+pt+b.border,width,b.align);
            for(Object child:node.children){
                if(child instanceof FlowBox){line.flush(false);line.y=box((FlowBox)child,line.left,line.y,width);line.reset();}
                else line.run((Engine.Run)child);
            }
            line.flush(false);
            float contentHeight=line.y-(y+pt+b.border);
            // Percentage min-height requires a definite containing height, which
            // this normal-flow subset does not provide. Ignore it as indefinite.
            if(b.minHeight!=null&&!b.minHeight.unit.equals("%"))contentHeight=Math.max(contentHeight,BoxStyle.resolve(b.minHeight,0,font)-(b.borderBox?pt+pb+2*b.border:0));
            rect.height=Math.max(0,contentHeight)+pt+pb+2*b.border;
            return y+rect.height+mb;
        }
        private final class Line {
            final float left,width;final String align;float x,y,height=25;boolean space;int start;
            Line(float left,float y,float width,String align){this.left=left;this.y=y;this.width=width;this.align=align;reset();}
            void reset(){x=left;height=25;space=false;start=items.size();}
            void flush(boolean empty){
                if(items.size()>start){float shift=align.equals("center")?(left+width-x)/2:align.equals("right")?left+width-x:0;
                    if(shift>0)for(int i=start;i<items.size();i++)items.get(i).x+=shift;
                    y+=height;
                }else if(empty)y+=height;
                reset();
            }
            void draw(String text,Engine.Run run,float measured){
                if(items.size()>=100000)throw new IllegalArgumentException("Page layout exceeds the preview limit.");
                items.add(new Engine.Draw(text,run.style,run.link,run.action,x,y,measured));x+=measured;height=Math.max(height,run.style.size*1.45f);
            }
            void run(Engine.Run run){
                if(run.newline){if(run.hardBreak)flush(true);return;}
                if(run.image!=null){flush(false);float w=Math.min(run.imageWidth,width),h=run.imageHeight*w/run.imageWidth;draw(run.text,run,w);
                    Engine.Draw d=items.get(items.size()-1);d.image=run.image;d.imageKey=run.imageKey;d.height=h;height=h;flush(false);return;}
                String text=run.text;
                for(int at=0;at<text.length();){
                    int cp=text.codePointAt(at);
                    if(Character.isWhitespace(cp)){at+=Character.charCount(cp);
                        if(run.style.pre&&cp=='\n')flush(true);
                        else if(run.style.pre)x+=measure.width(cp=='\t'?"    ":" ",run.style);
                        else space=true;
                        continue;
                    }
                    int end=at;while(end<text.length()&&!Character.isWhitespace(text.codePointAt(end)))end+=Character.charCount(text.codePointAt(end));
                    String word=text.substring(at,end);float measured=measure.width(word,run.style),gap=space&&x>left?measure.width(" ",run.style):0;
                    if(x>left&&x+gap+measured>left+width){flush(false);gap=0;}
                    x+=gap;space=false;
                    if(measured>width){for(int i=0;i<word.length();){int next=i+Character.charCount(word.codePointAt(i));String unit=word.substring(i,next);i=next;float w=measure.width(unit,run.style);if(x>left&&x+w>left+width)flush(false);draw(unit,run,w);}}
                    else draw(word,run,measured);
                    at=end;
                }
            }
        }
    }
}
