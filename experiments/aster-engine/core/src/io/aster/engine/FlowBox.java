package io.aster.engine;

import java.util.*;

/** Bounded block/flex tree; layout fragments retain DOM order until painting. */
final class FlowBox {
    final BoxStyle box;
    final Engine.Style style;
    final List<Object> children=new ArrayList<>();
    private List<FlowBox> flexChildren;
    FlowBox(String tag,String declarations,Engine.Style style){box=new BoxStyle(tag,declarations);this.style=style;}

    private List<FlowBox> flexItems(){
        if(flexChildren!=null)return flexChildren;
        flexChildren=new ArrayList<>();FlowBox anonymous=null;
        for(Object child:children){
            if(child instanceof FlowBox){anonymous=null;flexChildren.add((FlowBox)child);}
            else {Engine.Run run=(Engine.Run)child;
                if(anonymous==null){if(!run.style.pre&&run.image==null&&run.text.trim().isEmpty())continue;anonymous=new FlowBox("anonymous","",style);anonymous.box.align=box.align;flexChildren.add(anonymous);}
                anonymous.children.add(run);
            }
        }
        return flexChildren;
    }
    static Engine.Layout layout(FlowBox root,float viewport,Engine.Measure measure){
        Formatter formatter=new Formatter(measure);
        Result result=formatter.format(root,Math.max(52,Math.min(10000,viewport)-48),Float.NaN,Float.NaN,Float.NaN);
        List<Engine.Draw> draws=new ArrayList<>();List<Engine.Rect> rects=new ArrayList<>();
        flatten(result,24,24,draws,rects);
        float bottom=result.height+48;
        for(Engine.Draw draw:draws)bottom=Math.max(bottom,draw.y+draw.height+24);
        for(Engine.Rect rect:rects)bottom=Math.max(bottom,rect.y+rect.height+24);
        return new Engine.Layout(draws,bottom,rects);
    }
    private static void flatten(Result result,float x,float y,List<Engine.Draw> draws,List<Engine.Rect> rects){
        if(rects.size()>=Engine.MAX_RUNS)throw new IllegalArgumentException("Page has too many layout boxes.");
        BoxStyle b=result.node.box;rects.add(new Engine.Rect(x,y,result.width,result.height,b.background,b.borderColor,b.border,b.radius));
        for(Placement p:result.parts){
            if(p.box!=null)flatten(p.box,x+p.x,y+p.y,draws,rects);
            else {
                if(draws.size()>=100000)throw new IllegalArgumentException("Page layout exceeds the preview limit.");
                Engine.Draw d=p.draw,copy=new Engine.Draw(d.text,d.style,d.link,d.action,x+p.x+d.x,y+p.y+d.y,d.width);
                copy.height=d.height;copy.image=d.image;copy.imageKey=d.imageKey;draws.add(copy);
            }
        }
    }
    private static final class Placement {
        final Result box;final Engine.Draw draw;float x,y;
        Placement(Result box,float x,float y){this.box=box;this.draw=null;this.x=x;this.y=y;}
        Placement(Engine.Draw draw){this.box=null;this.draw=draw;}
    }
    private static final class Result {
        final FlowBox node;final float width;float height;
        final List<Placement> parts=new ArrayList<>();
        Result(FlowBox node,float width){this.node=node;this.width=width;}
    }
    private static final class Edges {
        final float[] margin=new float[4],padding=new float[4];final float horizontal,vertical;
        Edges(FlowBox node,float parent){
            for(int i=0;i<4;i++){margin[i]=BoxStyle.resolve(node.box.margin[i],parent,node.style.size);padding[i]=BoxStyle.resolve(node.box.padding[i],parent,node.style.size);}
            horizontal=padding[1]+padding[3]+2*node.box.border;vertical=padding[0]+padding[2]+2*node.box.border;
        }
    }
    private static final class Size {final float min,max;Size(float min,float max){this.min=min;this.max=max;}}
    private static final class Key {
        final float width,height,forcedWidth,forcedHeight;
        Key(float w,float h,float fw,float fh){width=w;height=h;forcedWidth=fw;forcedHeight=fh;}
        public int hashCode(){return Objects.hash(width,height,forcedWidth,forcedHeight);}
        public boolean equals(Object o){if(!(o instanceof Key))return false;Key k=(Key)o;return Float.compare(width,k.width)==0&&Float.compare(height,k.height)==0&&Float.compare(forcedWidth,k.forcedWidth)==0&&Float.compare(forcedHeight,k.forcedHeight)==0;}
    }
    private static final class FlexItem {
        final FlowBox node;final Edges edges;
        float base,min,max,target,hypothetical,violation;boolean frozen;Result result;
        FlexItem(FlowBox node,float width){this.node=node;edges=new Edges(node,width);}
        float outside(boolean column){return column?edges.vertical+edges.margin[0]+edges.margin[2]:edges.horizontal+edges.margin[1]+edges.margin[3];}
    }
    private static final class FlexLine {final List<FlexItem> items=new ArrayList<>();float occupied,cross;}
    private static float bound(float size){return Math.max(0,Math.min(10000,size));}
    private static float length(BoxStyle.Length value,float parent,float font){return value==null||value.unit.equals("%")&&Float.isNaN(parent)?Float.NaN:BoxStyle.resolve(value,parent,font);}
    private static float dimension(BoxStyle.Length value,float parent,float font,float extra,boolean borderBox){float result=length(value,parent,font);return Float.isNaN(result)?result:Math.max(0,result-(borderBox?extra:0));}
    private static float clamp(float value,float min,float max){return Math.max(min,Math.min(max,value));}
    private static float limit(BoxStyle b,boolean vertical,float value,float parent,float font,float extra){
        float min=dimension(vertical?b.minHeight:b.minWidth,parent,font,extra,b.borderBox),max=dimension(vertical?b.maxHeight:b.maxWidth,parent,font,extra,b.borderBox);
        float result=clamp(value,Float.isNaN(min)?0:min,Float.isNaN(max)?(vertical?Float.MAX_VALUE:10000):max);
        return vertical?Math.max(0,result):bound(result);
    }
    private static String alignment(FlexItem item,BoxStyle container){return item.node.box.alignSelf.equals("auto")?container.alignItems:item.node.box.alignSelf;}
    private static float offset(String align,float free){return align.equals("center")?free/2:align.equals("flex-end")||align.equals("end")?free:0;}
    private static float packingStart(String align,float free,int count){if(free<=0)return offset(align,free);return align.equals("space-around")?free/(2*count):align.equals("space-evenly")?free/(count+1):offset(align,free);}
    private static float packingGap(String align,float free,int count){if(free<=0)return 0;return align.equals("space-between")&&count>1?free/(count-1):align.equals("space-around")?free/count:align.equals("space-evenly")?free/(count+1):0;}

    private static final class Formatter {
        final Engine.Measure measure;
        final Map<FlowBox,Size> intrinsic=new IdentityHashMap<>();
        final Map<FlowBox,Map<Key,Result>> cache=new IdentityHashMap<>();
        int work,entries;
        Formatter(Engine.Measure measure){this.measure=measure;}
        void spend(){if(++work>500000)throw new IllegalArgumentException("Page layout exceeds the work limit.");}
        Result format(FlowBox node,float parentWidth,float parentHeight,float forcedWidth,float forcedHeight){
            spend();Key key=new Key(parentWidth,parentHeight,forcedWidth,forcedHeight);Map<Key,Result> memo=cache.get(node);Result cached=memo==null?null:memo.get(key);if(cached!=null)return cached;
            BoxStyle b=node.box;Edges edges=new Edges(node,parentWidth);float font=node.style.size;
            float width=Float.isNaN(forcedWidth)?dimension(b.width,parentWidth,font,edges.horizontal,b.borderBox):forcedWidth;
            if(Float.isNaN(width))width=parentWidth-edges.margin[1]-edges.margin[3]-edges.horizontal;
            width=limit(b,false,width,parentWidth,font,edges.horizontal);
            float height=Float.isNaN(forcedHeight)?dimension(b.height,parentHeight,font,edges.vertical,b.borderBox):forcedHeight;
            if(!Float.isNaN(height))height=limit(b,true,height,parentHeight,font,edges.vertical);
            Result result=new Result(node,width+edges.horizontal);
            float left=edges.padding[3]+b.border,top=edges.padding[0]+b.border;
            float natural=b.flex?flex(result,width,height,left,top):blocks(result,width,height,left,top);
            float usedHeight=Float.isNaN(height)?limit(b,true,natural,parentHeight,font,edges.vertical):height;
            if(b.flex&&Float.isNaN(height)&&Math.abs(usedHeight-natural)>.01f){result.parts.clear();flex(result,width,usedHeight,left,top);}
            result.height=usedHeight+edges.vertical;
            if(++entries>60000)throw new IllegalArgumentException("Page has too many layout measurements.");
            if(memo==null){memo=new HashMap<>();cache.put(node,memo);}memo.put(key,result);return result;
        }
        float blocks(Result result,float width,float height,float left,float top){
            Line line=new Line(result,left,top,width,result.node.box.align);
            for(Object child:result.node.children){spend();
                if(child instanceof FlowBox){line.flush(false);FlowBox node=(FlowBox)child;Edges edges=new Edges(node,width);Result box=format(node,width,height,Float.NaN,Float.NaN);
                    float x=edges.margin[3],free=width-box.width-edges.margin[1]-edges.margin[3];
                    if(free>0&&node.box.margin[3]==null)x+=node.box.margin[1]==null?free/2:free;
                    result.parts.add(new Placement(box,left+x,line.y+edges.margin[0]));line.y+=edges.margin[0]+box.height+edges.margin[2];line.reset();
                }else line.run((Engine.Run)child);
            }
            line.flush(false);return line.y-top;
        }
        Size intrinsic(FlowBox node){
            Size known=intrinsic.get(node);if(known!=null)return known;spend();
            float min=0,max=0,line=0;boolean space=false;
            List<?> children=node.box.flex?node.flexItems():node.children;
            boolean row=node.box.flex&&node.box.direction.startsWith("row");int count=0;
            for(Object child:children){spend();
                if(child instanceof FlowBox){
                    FlowBox box=(FlowBox)child;Size childSize=intrinsic(box);Edges edges=new Edges(box,0);BoxStyle b=box.box;
                    float explicit=dimension(b.width,Float.NaN,box.style.size,edges.horizontal,b.borderBox);
                    float childMin=Float.isNaN(explicit)?childSize.min:explicit,childMax=Float.isNaN(explicit)?childSize.max:explicit;
                    childMin=limit(b,false,childMin,Float.NaN,box.style.size,edges.horizontal)+edges.horizontal+edges.margin[1]+edges.margin[3];
                    childMax=limit(b,false,childMax,Float.NaN,box.style.size,edges.horizontal)+edges.horizontal+edges.margin[1]+edges.margin[3];
                    if(row){max+=childMax;min=node.box.wrap.equals("nowrap")?min+childMin:Math.max(min,childMin);count++;}
                    else{max=Math.max(max,Math.max(line,childMax));min=Math.max(min,childMin);line=0;space=false;}
                }else{
                    Engine.Run run=(Engine.Run)child;
                    if(run.image!=null){max=Math.max(max,Math.max(line,run.imageWidth));min=Math.max(min,run.imageWidth);line=0;space=false;continue;}
                    if(run.hardBreak){max=Math.max(max,line);line=0;space=false;continue;}
                    for(int at=0;at<run.text.length();){int cp=run.text.codePointAt(at);
                        if(Character.isWhitespace(cp)){at+=Character.charCount(cp);if(run.style.pre&&cp=='\n'){max=Math.max(max,line);line=0;}else space=true;continue;}
                        int end=at;while(end<run.text.length()&&!Character.isWhitespace(run.text.codePointAt(end)))end+=Character.charCount(run.text.codePointAt(end));
                        float word=measure.width(run.text.substring(at,end),run.style);min=Math.max(min,word);if(space&&line>0)line+=measure.width(" ",run.style);line+=word;space=false;at=end;
                    }
                }
            }
            if(row&&count>1){float gap=length(node.box.columnGap,Float.NaN,node.style.size);if(!Float.isNaN(gap)){max+=(count-1)*gap;if(node.box.wrap.equals("nowrap"))min+=(count-1)*gap;}}
            Size size=new Size(bound(min),bound(Math.max(max,line)));intrinsic.put(node,size);return size;
        }
        float flex(Result result,float width,float height,float left,float top){
            FlowBox node=result.node;BoxStyle b=node.box;boolean column=b.direction.startsWith("column"),reverse=b.direction.endsWith("reverse"),wrapReverse=b.wrap.equals("wrap-reverse");
            float main=column?height:width,cross=column?width:height;
            float rowGap=length(b.rowGap,height,node.style.size),columnGap=length(b.columnGap,width,node.style.size);
            rowGap=Float.isNaN(rowGap)?0:rowGap;columnGap=Float.isNaN(columnGap)?0:columnGap;
            float mainGap=column?rowGap:columnGap,crossGap=column?columnGap:rowGap;
            List<FlexItem> ordered=new ArrayList<>();
            for(FlowBox child:node.flexItems()){
                spend();FlexItem item=new FlexItem(child,width);BoxStyle s=child.box;float extra=column?item.edges.vertical:item.edges.horizontal;
                float preferred=dimension(column?s.height:s.width,main,child.style.size,extra,s.borderBox);
                float basis=dimension(s.flexBasis,main,child.style.size,extra,s.borderBox);
                if(Float.isNaN(basis)&&s.flexBasis==null&&!s.basisContent)basis=preferred;
                float content;
                if(column){float childWidth=columnWidth(item,width,b);item.result=format(child,width,height,childWidth,Float.NaN);content=item.result.height-extra;}
                else content=intrinsic(child).max;
                item.base=Float.isNaN(basis)?content:basis;
                item.min=dimension(column?s.minHeight:s.minWidth,main,child.style.size,extra,s.borderBox);
                item.max=dimension(column?s.maxHeight:s.maxWidth,main,child.style.size,extra,s.borderBox);
                if(Float.isNaN(item.max))item.max=column?Float.MAX_VALUE:10000;
                if(Float.isNaN(item.min)){item.min=column?content:intrinsic(child).min;if(!Float.isNaN(preferred))item.min=Math.min(item.min,preferred);item.min=Math.min(item.min,item.max);}
                item.hypothetical=clamp(item.base,item.min,item.max);ordered.add(item);
            }
            if(ordered.isEmpty())return 0;
            ordered.sort(Comparator.comparingInt(item->item.node.box.order));
            List<FlexLine> lines=new ArrayList<>();FlexLine line=new FlexLine();lines.add(line);
            for(FlexItem item:ordered){spend();float outer=item.hypothetical+item.outside(column);
                if(!b.wrap.equals("nowrap")&&!Float.isNaN(main)&&!line.items.isEmpty()&&line.occupied+mainGap+outer>main+.01f){line=new FlexLine();lines.add(line);}
                if(!line.items.isEmpty())line.occupied+=mainGap;line.items.add(item);line.occupied+=outer;
            }
            if(Float.isNaN(main))main=line.occupied;
            for(FlexLine l:lines){resolve(l,main,mainGap,column);
                for(FlexItem item:l.items){spend();item.result=format(item.node,width,height,column?columnWidth(item,width,b):item.target,column?item.target:Float.NaN);
                    l.cross=Math.max(l.cross,column?item.result.width+item.edges.margin[1]+item.edges.margin[3]:item.result.height+item.edges.margin[0]+item.edges.margin[2]);}
            }
            float totalCross=(lines.size()-1)*crossGap;for(FlexLine l:lines)totalCross+=l.cross;
            if(Float.isNaN(cross))cross=totalCross;
            if(b.wrap.equals("nowrap"))lines.get(0).cross=cross;
            else if(b.alignContent.equals("stretch")&&cross>totalCross){float addition=(cross-totalCross)/lines.size();for(FlexLine l:lines)l.cross+=addition;}
            totalCross=(lines.size()-1)*crossGap;for(FlexLine l:lines)totalCross+=l.cross;
            float crossCursor=b.wrap.equals("nowrap")?0:packingStart(b.alignContent,cross-totalCross,lines.size());
            float lineGap=crossGap+(b.wrap.equals("nowrap")?0:packingGap(b.alignContent,cross-totalCross,lines.size()));
            Map<FlowBox,Placement> positions=new IdentityHashMap<>();
            for(FlexLine l:lines){
                float occupied=(l.items.size()-1)*mainGap;int autoMargins=0;
                int start=column?(reverse?2:0):(reverse?1:3),end=(start+2)%4,crossStart=column?(wrapReverse?1:3):(wrapReverse?2:0),crossEnd=(crossStart+2)%4;
                for(FlexItem item:l.items){occupied+=item.target+item.outside(column);if(item.node.box.margin[start]==null)autoMargins++;if(item.node.box.margin[end]==null)autoMargins++;}
                float free=main-occupied,auto=autoMargins>0&&free>0?free/autoMargins:0;
                if(auto>0)free=0;
                float cursor=packingStart(b.justify,free,l.items.size()),gap=mainGap+packingGap(b.justify,free,l.items.size());
                for(FlexItem item:l.items){spend();BoxStyle s=item.node.box;String align=alignment(item,b);Edges e=item.edges;
                    boolean autoStart=s.margin[crossStart]==null,autoEnd=s.margin[crossEnd]==null;
                    if(align.equals("stretch")&&!autoStart&&!autoEnd&&Float.isNaN(length(column?s.width:s.height,column?width:height,item.node.style.size))){
                        float stretched=limit(s,!column,Math.max(0,l.cross-e.margin[crossStart]-e.margin[crossEnd]-(column?e.horizontal:e.vertical)),column?width:height,item.node.style.size,column?e.horizontal:e.vertical);
                        item.result=format(item.node,width,height,column?stretched:item.target,column?item.target:stretched);
                    }
                    float crossSize=column?item.result.width:item.result.height,remaining=l.cross-crossSize-e.margin[crossStart]-e.margin[crossEnd];
                    float crossOffset=e.margin[crossStart];
                    if(autoStart||autoEnd){if(autoStart&&remaining>0)crossOffset+=autoEnd?remaining/2:remaining;}
                    else crossOffset+=offset(align,remaining);
                    float physicalCross=wrapReverse?cross-crossCursor-crossOffset-crossSize:crossCursor+crossOffset;
                    cursor+=e.margin[start]+(s.margin[start]==null?auto:0);float mainSize=column?item.result.height:item.result.width;
                    float physicalMain=reverse?main-cursor-mainSize:cursor;
                    positions.put(item.node,new Placement(item.result,left+(column?physicalCross:physicalMain),top+(column?physicalMain:physicalCross)));
                    cursor+=mainSize+e.margin[end]+(s.margin[end]==null?auto:0)+gap;
                }
                crossCursor+=l.cross+lineGap;
            }
            // Search, accessibility and source text must not jump between columns.
            for(FlowBox child:node.flexItems())result.parts.add(positions.get(child));
            return column?main:cross;
        }
        float columnWidth(FlexItem item,float width,BoxStyle container){
            BoxStyle b=item.node.box;if(b.width!=null)return Float.NaN;
            if(container.wrap.equals("nowrap")&&alignment(item,container).equals("stretch")&&b.margin[1]!=null&&b.margin[3]!=null)return Math.max(0,width-item.outside(false));
            return Math.min(intrinsic(item.node).max,Math.max(0,width-item.outside(false)));
        }
        void resolve(FlexLine line,float main,float gap,boolean column){
            float space=main-gap*(line.items.size()-1),hypothetical=0;
            for(FlexItem item:line.items){space-=item.outside(column);hypothetical+=item.hypothetical;}
            boolean grow=hypothetical<space;
            for(FlexItem item:line.items){float factor=grow?item.node.box.grow:item.node.box.shrink;item.frozen=factor==0||(grow?item.base>item.hypothetical:item.base<item.hypothetical);item.target=item.frozen?item.hypothetical:item.base;}
            float initial=space;for(FlexItem item:line.items)initial-=item.target;
            for(int round=0;round<=line.items.size();round++){
                float free=space,factors=0,scaled=0;int active=0;
                for(FlexItem item:line.items){spend();free-=item.frozen?item.target:item.base;if(!item.frozen){active++;float factor=grow?item.node.box.grow:item.node.box.shrink;factors+=factor;scaled+=grow?factor:factor*item.base;}}
                if(active==0)return;
                if(factors<1&&Math.abs(initial*factors)<Math.abs(free))free=initial*factors;
                float violation=0;
                for(FlexItem item:line.items)if(!item.frozen){float factor=grow?item.node.box.grow:item.node.box.shrink*item.base;float proposed=item.base+(scaled==0?0:free*factor/scaled);item.target=clamp(proposed,item.min,item.max);item.violation=item.target-proposed;violation+=item.violation;}
                for(FlexItem item:line.items)if(!item.frozen&&(Math.abs(violation)<.001f||violation>0&&item.violation>0||violation<0&&item.violation<0))item.frozen=true;
            }
            throw new IllegalArgumentException("Flex sizing did not converge.");
        }
        private final class Line {
            final Result result;final float left,width;final String align;float x,y,height=25;boolean space;int start;
            Line(Result result,float left,float y,float width,String align){this.result=result;this.left=left;this.y=y;this.width=Math.max(1,width);this.align=align;reset();}
            void reset(){x=left;height=25;space=false;start=result.parts.size();}
            void flush(boolean empty){
                if(result.parts.size()>start){float shift=align.equals("center")?(left+width-x)/2:align.equals("right")?left+width-x:0;
                    if(shift>0)for(int i=start;i<result.parts.size();i++)result.parts.get(i).x+=shift;
                    y+=height;
                }else if(empty)y+=height;reset();
            }
            void draw(String text,Engine.Run run,float measured){spend();result.parts.add(new Placement(new Engine.Draw(text,run.style,run.link,run.action,x,y,measured)));x+=measured;height=Math.max(height,run.style.size*1.45f);}
            void run(Engine.Run run){
                if(run.newline){if(run.hardBreak)flush(true);return;}
                if(run.image!=null){flush(false);float w=Math.min(run.imageWidth,width),h=run.imageHeight*w/run.imageWidth;draw(run.text,run,w);
                    Engine.Draw d=result.parts.get(result.parts.size()-1).draw;d.image=run.image;d.imageKey=run.imageKey;d.height=h;height=h;flush(false);return;}
                String text=run.text;
                for(int at=0;at<text.length();){int cp=text.codePointAt(at);
                    if(Character.isWhitespace(cp)){at+=Character.charCount(cp);if(run.style.pre&&cp=='\n')flush(true);else if(run.style.pre)x+=measure.width(cp=='\t'?"    ":" ",run.style);else space=true;continue;}
                    int end=at;while(end<text.length()&&!Character.isWhitespace(text.codePointAt(end)))end+=Character.charCount(text.codePointAt(end));
                    String word=text.substring(at,end);float measured=measure.width(word,run.style),gap=space&&x>left?measure.width(" ",run.style):0;
                    if(x>left&&x+gap+measured>left+width){flush(false);gap=0;}x+=gap;space=false;
                    if(measured>width){for(int i=0;i<word.length();){int next=i+Character.charCount(word.codePointAt(i));String unit=word.substring(i,next);i=next;float w=measure.width(unit,run.style);if(x>left&&x+w>left+width)flush(false);draw(unit,run,w);}}
                    else draw(word,run,measured);at=end;
                }
            }
        }
    }
}
