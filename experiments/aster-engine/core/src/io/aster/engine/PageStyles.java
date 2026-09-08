package io.aster.engine;

import java.util.*;
import java.util.regex.*;

/** A deliberately small CSS cascade: type, class, id and compound selectors. */
final class PageStyles {
    private static final Pattern RULE=Pattern.compile("([^{}]+)\\{([^{}]*)\\}");
    private static final Pattern PART=Pattern.compile("([.#]?)([a-zA-Z_][a-zA-Z0-9_-]*|\\*)");
    private static final class Rule {
        final String selector, declarations; final int score;
        Rule(String s,String d,int score){selector=s;declarations=d;this.score=score;}
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
            Matcher parts=PART.matcher(s);int at=0,score=0;
            while(parts.find()&&parts.start()==at){at=parts.end();score+=parts.group(1).equals("#")?100:parts.group(1).equals(".")?10:1;}
            if(at==s.length()&&rules.size()<512)rules.add(new Rule(s,m.group(2),score));
        }
    }
    String declarations(String tag,Map<String,String> attrs) {
        StringBuilder result=new StringBuilder();
        Set<String> classes=new HashSet<>(Arrays.asList(attrs.getOrDefault("class","").split("\\s+")));
        for(Rule rule:rules){boolean match=true;Matcher parts=PART.matcher(rule.selector);
            while(parts.find()){String p=parts.group(1),v=parts.group(2);
                if(p.equals("#")?!v.equals(attrs.get("id")):p.equals(".")?!classes.contains(v):!v.equals("*")&&!v.equalsIgnoreCase(tag)){match=false;break;}}
            if(match)result.append(rule.declarations).append(';');
        }
        result.append(attrs.getOrDefault("style",""));return result.toString();
    }
    static String property(String css,String key) {
        String result="";for(String part:css.split(";")){String[] v=part.split(":",2);if(v.length==2&&v[0].trim().equalsIgnoreCase(key))result=v[1].trim().toLowerCase(Locale.ROOT);}return result;
    }
}
