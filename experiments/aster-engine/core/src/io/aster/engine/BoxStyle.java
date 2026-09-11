package io.aster.engine;

import java.util.*;

/** Shared normal-flow and flex box declarations. */
final class BoxStyle {
    static final class Length {
        final float value;final String unit;
        Length(float value,String unit){this.value=value;this.unit=unit;}
        float resolve(float parent,float font){return value*(unit.equals("%")?parent/100:unit.equals("em")?font:unit.equals("rem")?17:1);}
    }
    final Length[] margin=new Length[4],padding=new Length[4];
    Length width,minWidth,maxWidth,height,minHeight,maxHeight,flexBasis,rowGap,columnGap;
    int background,borderColor=0xff253446;
    float border=3,radius;
    boolean borderBox,solid;
    String align="left";
    boolean flex,basisContent;
    float grow,shrink=1;
    int order;
    String direction="row",wrap="nowrap",justify="flex-start",alignItems="stretch",alignSelf="auto",alignContent="stretch";
    BoxStyle(String tag,String css){
        Arrays.fill(margin,new Length(tag.equals("p")||tag.matches("h[1-6]")?10:0,"px"));margin[1]=margin[3]=new Length(0,"px");
        Arrays.fill(padding,new Length(0,"px"));
        for(String declaration:css.split(";")){String[] pair=declaration.split(":",2);if(pair.length!=2)continue;
            String key=pair[0].trim().toLowerCase(Locale.ROOT),value=pair[1].trim().toLowerCase(Locale.ROOT);
            if(key.equals("margin")||key.equals("padding")){edges(key.equals("margin")?margin:padding,value,key.equals("margin"));continue;}
            String[] names={"top","right","bottom","left"};boolean edge=false;
            for(int i=0;i<4;i++)if(key.equals("margin-"+names[i])||key.equals("padding-"+names[i])){boolean m=key.startsWith("margin");Length length=length(value);if(length!=null||m&&value.equals("auto"))(m?margin:padding)[i]=length;edge=true;break;}
            if(edge)continue;
            switch(key){
                case "width": if(value.equals("auto"))width=null;else if(length(value)!=null)width=length(value);break;
                case "min-width": if(value.equals("auto"))minWidth=null;else if(length(value)!=null)minWidth=length(value);break;
                case "max-width": if(value.equals("none"))maxWidth=null;else if(length(value)!=null)maxWidth=length(value);break;
                case "height": if(value.equals("auto"))height=null;else if(length(value)!=null)height=length(value);break;
                case "min-height": if(value.equals("auto"))minHeight=null;else if(length(value)!=null)minHeight=length(value);break;
                case "max-height": if(value.equals("none"))maxHeight=null;else if(length(value)!=null)maxHeight=length(value);break;
                case "display": if(Arrays.asList("flex","block","inline","none").contains(value))flex=value.equals("flex");break;
                case "flex-direction": if(direction(value))direction=value;break;
                case "flex-wrap": if(wrapping(value))wrap=value;break;
                case "flex-flow": {String d="row",w="nowrap";boolean valid=true,sd=false,sw=false;for(String part:value.split("\\s+")){if(direction(part)&&!sd){d=part;sd=true;}else if(wrapping(part)&&!sw){w=part;sw=true;}else valid=false;}if(valid){direction=d;wrap=w;}break;}
                case "flex": flex(value);break;
                case "flex-grow": if(number(value)!=null)grow=number(value);break;
                case "flex-shrink": if(number(value)!=null)shrink=number(value);break;
                case "flex-basis": if(value.equals("auto")||value.equals("content")||length(value)!=null){flexBasis=length(value);basisContent=value.equals("content");}break;
                case "order": if(value.matches("[+-]?[0-9]{1,6}"))order=Integer.parseInt(value);break;
                case "justify-content": if(packing(value,false))justify=value;break;
                case "align-content": if(packing(value,true))alignContent=value;break;
                case "align-items": if(alignment(value))alignItems=value;break;
                case "align-self": if(value.equals("auto")||alignment(value))alignSelf=value;break;
                case "gap": {String[] parts=value.split("\\s+");if(parts.length<=2&&gap(parts[0])!=null&&(parts.length==1||gap(parts[1])!=null)){rowGap=gap(parts[0]);columnGap=gap(parts[parts.length-1]);}break;}
                case "row-gap": if(gap(value)!=null)rowGap=gap(value);break;
                case "column-gap": if(gap(value)!=null)columnGap=gap(value);break;
                case "box-sizing": if(value.equals("border-box")||value.equals("content-box"))borderBox=value.equals("border-box");break;
                case "background": case "background-color": Integer color=color(value);if(color!=null)background=color;break;
                case "border": {String[] words=value.split("\\s+");float w=3;int c=borderColor;boolean s=false,valid=true;
                    for(String word:words){Length l=length(word);Integer v=color(word);if(word.equals("solid"))s=true;else if(word.equals("none"))s=false;else if(l!=null&&l.unit.equals("px"))w=l.value;else if(v!=null)c=v;else valid=false;}
                    if(valid){border=Math.min(64,w);borderColor=c;solid=s;}break;}
                case "border-width": Length l=length(value);if(l!=null&&l.unit.equals("px"))border=Math.min(64,l.value);break;
                case "border-style": if(value.equals("solid")||value.equals("none"))solid=value.equals("solid");break;
                case "border-color": Integer c=color(value);if(c!=null)borderColor=c;break;
                case "border-radius": Length rad=length(value);if(rad!=null&&rad.unit.equals("px"))radius=Math.min(200,rad.value);break;
                case "text-align": if(Arrays.asList("left","center","right").contains(value))align=value;break;
                default:break;
            }
        }
        if(!solid)border=0;
    }
    private static boolean direction(String s){return Arrays.asList("row","row-reverse","column","column-reverse").contains(s);}
    private static boolean wrapping(String s){return Arrays.asList("nowrap","wrap","wrap-reverse").contains(s);}
    private static boolean alignment(String s){return Arrays.asList("flex-start","flex-end","start","end","center","stretch").contains(s);}
    private static boolean packing(String s,boolean stretch){return Arrays.asList("flex-start","flex-end","start","end","center","space-between","space-around","space-evenly").contains(s)||stretch&&s.equals("stretch");}
    private static Length gap(String s){return s.equals("normal")?new Length(0,"px"):length(s);}
    private static Float number(String s){return s.matches("(?:[0-9]{1,5}(?:\\.[0-9]{1,4})?|\\.[0-9]{1,4})")?Float.valueOf(s):null;}
    private void flex(String value){
        if(value.equals("none")||value.equals("auto")||value.equals("initial")){grow=value.equals("auto")?1:0;shrink=value.equals("none")?0:1;flexBasis=null;basisContent=false;return;}
        String[] parts=value.split("\\s+");if(parts.length>3)return;
        Float g=null,s=null;Length basis=null;boolean hasBasis=false,content=false,previousNumber=false;
        for(String part:parts){Float n=number(part);if(n!=null&&(g==null||s==null)){if(g!=null&&!previousNumber)return;if(g==null)g=n;else s=n;previousNumber=true;}
            else if(!hasBasis&&(length(part)!=null||part.equals("auto")||part.equals("content"))){hasBasis=true;basis=length(part);content=part.equals("content");previousNumber=false;}else return;}
        if(g==null&&!hasBasis)return;
        grow=g==null?1:g;shrink=s==null?1:s;flexBasis=hasBasis?basis:new Length(0,"%");basisContent=content;
    }
    private static void edges(Length[] target,String value,boolean auto){
        String[] parts=value.split("\\s+");if(parts.length<1||parts.length>4)return;Length[] lengths=new Length[parts.length];
        for(int i=0;i<parts.length;i++){lengths[i]=length(parts[i]);if(lengths[i]==null&&!(auto&&parts[i].equals("auto")))return;}
        target[0]=lengths[0];target[1]=lengths.length>1?lengths[1]:lengths[0];target[2]=lengths.length>2?lengths[2]:lengths[0];target[3]=lengths.length>3?lengths[3]:target[1];
    }
    static Length length(String text){
        if(text.equals("0"))return new Length(0,"px");
        if(!text.matches("(?:[0-9]{1,5}(?:\\.[0-9]{1,4})?|\\.[0-9]{1,4})(?:px|%|em|rem)"))return null;
        int at=0;while(at<text.length()&&(Character.isDigit(text.charAt(at))||text.charAt(at)=='.'))at++;
        float number=Float.parseFloat(text.substring(0,at));return new Length(Math.min(10000,number),text.substring(at));
    }
    static Integer color(String value){
        if(value.equals("transparent"))return 0;
        if(value.matches("#[0-9a-f]{6}"))return 0xff000000|Integer.parseInt(value.substring(1),16);
        if(value.matches("#[0-9a-f]{3}")){String s=value.substring(1);return 0xff000000|Integer.parseInt(""+s.charAt(0)+s.charAt(0)+s.charAt(1)+s.charAt(1)+s.charAt(2)+s.charAt(2),16);}
        String[] names={"black","white","red","green","blue","gray"};int[] colors={0,0xffffff,0xff0000,0x008000,0xff,0x808080};for(int i=0;i<names.length;i++)if(names[i].equals(value))return 0xff000000|colors[i];return null;
    }
    static float resolve(Length value,float parent,float font){return value==null?0:Math.min(10000,value.resolve(parent,font));}
}
