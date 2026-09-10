package io.aster.engine;

import java.util.*;
import java.util.regex.*;
import java.net.URI;

/** A deliberately small CSS cascade: type/class/id compounds, descendants and direct children. */
final class PageStyles {
    private static final Pattern PART=Pattern.compile("([.#]?)([a-zA-Z_][a-zA-Z0-9_-]*|\\*)");
    private static final Pattern IMPORTANT=Pattern.compile("(?i)\\s*!\\s*important\\s*$");
    private static final int MAX_DECLARATIONS=65536;
    static final class Element {
        final String tag,id;final Set<String> classes;final Element parent;
        Element(String tag,Map<String,String> attrs,Element parent){this.tag=tag;id=attrs.getOrDefault("id","");classes=new HashSet<>(Arrays.asList(attrs.getOrDefault("class","").split("\\s+")));this.parent=parent;}
    }
    private static final class Simple {
        String tag="*";final List<String> ids=new ArrayList<>(),classes=new ArrayList<>();int score;
        Simple(String text){Matcher m=PART.matcher(text);int at=0;
            while(m.find()&&m.start()==at){String p=m.group(1),v=m.group(2);if(v.equals("*")&&(!p.isEmpty()||at>0))throw new IllegalArgumentException();
                if(p.equals("#")){ids.add(v);score+=1000000;}else if(p.equals(".")){classes.add(v);score+=1000;}
                else {if(at>0)throw new IllegalArgumentException();tag=v.toLowerCase(Locale.ROOT);if(!v.equals("*"))score++;}at=m.end();}
            if(at!=text.length()||at==0)throw new IllegalArgumentException();
        }
        boolean matches(Element e){if(!tag.equals("*")&&!tag.equals(e.tag))return false;for(String id:ids)if(!id.equals(e.id))return false;return e.classes.containsAll(classes);}
    }
    private static final class Declarations {
        final String normal,important;
        Declarations(String css){StringBuilder n=new StringBuilder(),i=new StringBuilder();append(n,i,css);normal=n.toString();important=i.toString();}
    }
    private static final class Rule {
        final List<Simple> parts=new ArrayList<>();final List<Boolean> child=new ArrayList<>();final String normal,important;int score;
        Rule(String selector,Declarations declarations){normal=declarations.normal;important=declarations.important;boolean direct=false;
            for(String token:selector.replace(">"," > ").trim().split("\\s+")){
                if(token.equals(">")){if(parts.isEmpty()||direct)throw new IllegalArgumentException();direct=true;continue;}
                Simple part=new Simple(token);parts.add(part);child.add(direct);score+=part.score;direct=false;if(parts.size()>16)throw new IllegalArgumentException();
            }
            if(direct||parts.isEmpty())throw new IllegalArgumentException();
        }
        boolean matches(Element element){
            if(!parts.get(parts.size()-1).matches(element))return false;if(parts.size()==1)return true;
            List<Element> chain=new ArrayList<>();for(Element at=element;at!=null;at=at.parent)chain.add(at);Collections.reverse(chain);
            // Dynamic programming bounds mixed descendant/child matching; no
            // exponential backtracking on repeated classes in hostile markup.
            boolean[] previous=new boolean[chain.size()];
            for(int n=0;n<parts.size();n++){boolean[] next=new boolean[chain.size()];boolean ancestor=false;
                for(int i=0;i<chain.size();i++){boolean allowed=n==0||(child.get(n)?i>0&&previous[i-1]:ancestor);next[i]=allowed&&parts.get(n).matches(chain.get(i));ancestor|=previous[i];}previous=next;}
            return previous[chain.size()-1];
        }
    }
    private final List<Rule> rules=new ArrayList<>();
    private final float width,height;
    boolean responsive;
    PageStyles(String source,String external,URI page,Map<String,String> sheets,float width,float height) {
        this.width=width;this.height=height;add(external,0);
        for(PageMarkup.Tag tag:PageMarkup.tags(source))if(!tag.closing){
            String css=null;
            if(tag.name.equals("style")&&!tag.attrs.containsKey("disabled")&&(tag.attrs.getOrDefault("type","").isEmpty()||tag.attrs.get("type").equalsIgnoreCase("text/css")))css=tag.text;
            else {PageMarkup.Asset asset=PageMarkup.stylesheet(page,tag);if(asset!=null)css=sheets.get(asset.key());}
            if(css!=null){String media=tag.attrs.getOrDefault("media","");if(!media.trim().isEmpty())responsive=true;
                if(MediaQuery.matches(media,width,height))add(css,0);}
        }
        rules.sort(Comparator.comparingInt(r->r.score)); // stable source order at equal specificity
    }
    private void add(String css,int nesting) {
        if(nesting>16)return;
        if(css.length()>262144)css=css.substring(0,262144);
        css=withoutComments(css);
        for(int i=0;i<css.length();) {
            while(i<css.length()&&Character.isWhitespace(css.charAt(i)))i++;if(i==css.length())break;
            int boundary=delimiter(css,i,"{;}");if(boundary<0)break;
            if(css.charAt(boundary)!='{'){i=boundary+1;continue;}
            String header=css.substring(i,boundary).trim();int end=closeBlock(css,boundary);if(end<0)break;
            String body=css.substring(boundary+1,end);i=end+1;
            if(header.toLowerCase(Locale.ROOT).matches("(?s)@media(?:\\s|\\().*")){
                responsive=true;if(MediaQuery.matches(header.substring(6).trim(),width,height))add(body,nesting+1);
            }else if(!header.startsWith("@")&&delimiter(body,0,"{}")<0){
                Declarations declarations;try{declarations=new Declarations(body);}catch(IllegalArgumentException e){continue;}
                for(String selector:header.split(",")){String s=selector.trim();if(s.length()>128||s.isEmpty())continue;
                    try{if(rules.size()<512)rules.add(new Rule(s,declarations));}catch(IllegalArgumentException ignored){/* Unsupported selector. */}}
            }
        }
    }
    // Quotes and escapes keep braces/semicolons in CSS strings from creating rules.
    static int delimiter(String text,int from,String delimiters){
        char quote=0;int parens=0;
        for(int i=from;i<text.length();i++){char c=text.charAt(i);if(c=='\\'){i++;continue;}
            if(quote!=0){if(c==quote)quote=0;continue;}if(c=='\''||c=='"'){quote=c;continue;}
            if(c=='('){parens++;continue;}if(c==')'){parens=Math.max(0,parens-1);continue;}
            if(parens==0&&delimiters.indexOf(c)>=0)return i;
        }return -1;
    }
    private static int closeBlock(String css,int opening){int depth=1,at=opening+1;
        while(at<css.length()){int next=delimiter(css,at,"{}");if(next<0)return -1;
            if(css.charAt(next)=='{')depth++;else if(--depth==0)return next;at=next+1;}return -1;
    }
    private static String withoutComments(String css){StringBuilder out=new StringBuilder();char quote=0;
        for(int i=0;i<css.length();i++){char c=css.charAt(i);
            if(c=='\\'){out.append(c);if(i+1<css.length())out.append(css.charAt(++i));continue;}
            if(quote!=0){out.append(c);if(c==quote)quote=0;continue;}
            if(c=='\''||c=='"'){quote=c;out.append(c);continue;}
            if(c=='/'&&i+1<css.length()&&css.charAt(i+1)=='*'){int end=css.indexOf("*/",i+2);if(end<0)break;i=end+1;out.append(' ');}else out.append(c);
        }return out.toString();
    }
    private static void append(StringBuilder normal,StringBuilder important,String css){
        if(css.length()>MAX_DECLARATIONS)throw new IllegalArgumentException("CSS declarations exceed the preview limit.");
        for(int at=0;at<css.length();){int end=delimiter(css,at,";");if(end<0)end=css.length();String part=css.substring(at,end).trim();at=end+1;
            if(normal.length()+important.length()+part.length()+1>MAX_DECLARATIONS)throw new IllegalArgumentException("CSS declarations exceed the preview limit.");
            Matcher priority=IMPORTANT.matcher(part);
            if(priority.find())important.append(part.substring(0,priority.start())).append(';');else normal.append(part).append(';');}
    }
    String declarations(Element element,Map<String,String> attrs) {
        StringBuilder normal=new StringBuilder(),important=new StringBuilder();
        for(Rule rule:rules)if(rule.matches(element)){
            if(normal.length()+important.length()+rule.normal.length()+rule.important.length()>MAX_DECLARATIONS)throw new IllegalArgumentException("CSS cascade exceeds the preview limit.");
            normal.append(rule.normal);important.append(rule.important);}
        append(normal,important,withoutComments(attrs.getOrDefault("style","")));
        return normal.append(important).toString();
    }
    static String property(String css,String key) {
        String result="";for(String part:css.split(";")){String[] v=part.split(":",2);if(v.length==2&&v[0].trim().equalsIgnoreCase(key))result=v[1].trim().toLowerCase(Locale.ROOT);}return result;
    }
}
