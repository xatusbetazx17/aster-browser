package io.aster.engine;

import java.util.*;
import java.util.regex.*;

/** Screen media queries for viewport width/height and orientation. Unknown syntax
 * is not turned into a match by negation. Query em/rem use the initial 16px size. */
final class MediaQuery {
    private static final Pattern FEATURE=Pattern.compile("(?:(min|max)-)?(width|height)\\s*:\\s*([0-9]+(?:\\.[0-9]+)?|\\.[0-9]+)(px|em|rem)?");
    private static final Pattern RANGE=Pattern.compile("(width|height)\\s*(<=|>=|=|<|>)\\s*([0-9]+(?:\\.[0-9]+)?|\\.[0-9]+)(px|em|rem)?");
    private static final Pattern PREFIX=Pattern.compile("^(not|only)\\s+");
    private static final Pattern TYPE=Pattern.compile("^[a-z][a-z0-9-]*");
    static boolean matches(String input,float width,float height){
        if(input.trim().isEmpty())return true;
        if(input.length()>4096)return false;
        for(String query:input.toLowerCase(Locale.ROOT).split(",",-1)){
            Boolean result=query(query.trim(),width,height);if(Boolean.TRUE.equals(result))return true;
        }
        return false;
    }
    private static Boolean query(String text,float width,float height){
        boolean negate=false,only=false;
        Matcher prefix=PREFIX.matcher(text);if(prefix.find()){negate=prefix.group(1).equals("not");only=!negate;text=text.substring(prefix.end()).trim();}
        if(text.isEmpty())return null;
        boolean result=true,typed=false;int at=0;
        if(text.charAt(0)!='('){
            Matcher type=TYPE.matcher(text);if(!type.find())return null;
            String name=type.group();result=name.equals("all")||name.equals("screen");typed=true;at=type.end();
        }else if(only)return null;
        int features=0;
        while(at<text.length()){
            while(at<text.length()&&Character.isWhitespace(text.charAt(at)))at++;
            if(at==text.length())break;
            if(typed||features>0){
                if(!text.startsWith("and",at))return null;at+=3;
                if(at<text.length()&&!Character.isWhitespace(text.charAt(at))&&text.charAt(at)!='(')return null;
                while(at<text.length()&&Character.isWhitespace(text.charAt(at)))at++;
            }
            if(at>=text.length()||text.charAt(at)!='(')return null;
            int end=text.indexOf(')',at+1);if(end<0||++features>16)return null;
            Boolean feature=feature(text.substring(at+1,end).trim(),width,height);if(feature==null)return null;
            result&=feature;at=end+1;
        }
        if(!typed&&features==0)return null;
        return negate?!result:result;
    }
    private static Boolean feature(String value,float width,float height){
        if(value.equals("width"))return width>0;if(value.equals("height"))return height>0;
        if(value.matches("orientation\\s*:\\s*(portrait|landscape)"))return value.endsWith("portrait")?height>=width:width>height;
        Matcher m=FEATURE.matcher(value);
        if(m.matches()){
            float actual=m.group(2).equals("width")?width:height,expected=pixels(m.group(3),m.group(4));
            if(!Float.isFinite(expected))return null;
            return "min".equals(m.group(1))?actual>=expected:"max".equals(m.group(1))?actual<=expected:actual==expected;
        }
        m=RANGE.matcher(value);
        if(m.matches()){
            float actual=m.group(1).equals("width")?width:height,expected=pixels(m.group(3),m.group(4));
            if(!Float.isFinite(expected))return null;
            switch(m.group(2)){case "<":return actual<expected;case ">":return actual>expected;case "<=":return actual<=expected;case ">=":return actual>=expected;default:return actual==expected;}
        }
        return null;
    }
    private static float pixels(String number,String unit){try{float value=Float.parseFloat(number);return unit==null?(value==0?0:Float.NaN):value*(unit.equals("px")?1:16);}catch(NumberFormatException e){return Float.NaN;}}
    private MediaQuery(){}
}
