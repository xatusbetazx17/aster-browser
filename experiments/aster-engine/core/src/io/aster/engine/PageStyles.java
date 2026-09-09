package io.aster.engine;

import java.util.*;
import java.util.regex.*;

/** A deliberately small CSS cascade: type/class/id compounds, descendants and direct children. */
final class PageStyles {
    private static final Pattern RULE=Pattern.compile("([^{}]+)\\{([^{}]*)\\}");
    private static final Pattern PART=Pattern.compile("([.#]?)([a-zA-Z_][a-zA-Z0-9_-]*|\\*)");
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
    private static final class Rule {
        final List<Simple> parts=new ArrayList<>();final List<Boolean> child=new ArrayList<>();final String declarations;int score;
        Rule(String selector,String declarations){this.declarations=declarations;boolean direct=false;
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
    PageStyles(String source,String external) {
        add(external);
        for(PageMarkup.Tag tag:PageMarkup.tags(source))if(tag.name.equals("style")&&!tag.closing)add(tag.text);
        rules.sort(Comparator.comparingInt(r->r.score)); // stable source order at equal specificity
    }
    private void add(String css) {
        if(css.length()>262144)css=css.substring(0,262144);
        css=css.replaceAll("(?s)/\\*.*?\\*/","");
        // At-rules are unsupported; discard their complete blocks, including nested rules.
        StringBuilder plain=new StringBuilder();
        for(int i=0;i<css.length();) {
            if(css.charAt(i)!='@'){plain.append(css.charAt(i++));continue;}
            int semi=css.indexOf(';',i), brace=css.indexOf('{',i);
            if(semi>=0&&(brace<0||semi<brace)){i=semi+1;continue;}
            if(brace<0)break;int depth=1;i=brace+1;
            while(i<css.length()&&depth>0){char c=css.charAt(i++);if(c=='{')depth++;if(c=='}')depth--;}
        }
        Matcher m=RULE.matcher(plain);
        while(m.find()&&rules.size()<512)for(String selector:m.group(1).split(",")) {
            String s=selector.trim();if(s.length()>128||s.isEmpty())continue;
            try{if(rules.size()<512)rules.add(new Rule(s,m.group(2)));}catch(IllegalArgumentException ignored){/* Unsupported selector. */}
        }
    }
    String declarations(Element element,Map<String,String> attrs) {
        StringBuilder result=new StringBuilder();for(Rule rule:rules)if(rule.matches(element))result.append(rule.declarations).append(';');
        result.append(attrs.getOrDefault("style",""));return result.toString();
    }
    static String property(String css,String key) {
        String result="";for(String part:css.split(";")){String[] v=part.split(":",2);if(v.length==2&&v[0].trim().equalsIgnoreCase(key))result=v[1].trim().toLowerCase(Locale.ROOT);}return result;
    }
}
