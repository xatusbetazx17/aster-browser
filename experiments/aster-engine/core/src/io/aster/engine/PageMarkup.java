package io.aster.engine;

import java.net.URI;
import java.util.*;

/** Shared bounded token scan for the preview's styles and native forms. */
public final class PageMarkup {
    public static final class Tag {
        public final String name, text;
        public final Map<String,String> attrs;
        public final boolean closing;
        Tag(String name, Map<String,String> attrs, boolean closing, String text) { this.name=name; this.attrs=attrs; this.closing=closing; this.text=text; }
    }
    public static List<Tag> tags(String source) {
        if (source.length() > Engine.MAX_SOURCE) throw new IllegalArgumentException("Page too large.");
        List<Tag> tags = new ArrayList<>(); int at = 0;
        // ASCII folding preserves offsets in Unicode documents.
        StringBuilder folded = new StringBuilder(source.length());
        for (int i=0;i<source.length();i++) { char c=source.charAt(i); folded.append(c>='A'&&c<='Z' ? (char)(c+32) : c); }
        String lower = folded.toString();
        while (at < source.length() && tags.size() < 20000) {
            int start = source.indexOf('<',at); if(start<0) break;
            if(source.startsWith("<!--",start)) { int end=source.indexOf("-->",start+4); at=end<0?source.length():end+3; continue; }
            int end=Engine.tagEnd(source,start+1); if(end<0)break;
            String body=source.substring(start+1,end).trim(); at=end+1; boolean closing=body.startsWith("/");
            if(closing)body=body.substring(1).trim(); int n=0;
            while(n<body.length()&&(Character.isLetterOrDigit(body.charAt(n))||body.charAt(n)=='-'))n++;
            if(n==0)continue; String name=body.substring(0,n).toLowerCase(Locale.ROOT), text="";
            Map<String,String> attrs=Engine.attributes(body.substring(n));
            if(!closing&&Arrays.asList("script","style","textarea","title","template","iframe","object").contains(name)) {
                int stop=Engine.rawClose(lower,name,at);
                if(stop<0)break;
                if(name.equals("style")||name.equals("textarea")||name.equals("title"))text=source.substring(at,stop);
                int finish=Engine.tagEnd(source,stop+2); at=finish<0?source.length():finish+1;
                if(Arrays.asList("script","template","iframe","object").contains(name))continue;
            }
            tags.add(new Tag(name,attrs,closing,text));
        }
        return tags;
    }
    public static List<URI> stylesheets(URI page,String source) {
        List<URI> result=new ArrayList<>();
        for(Tag t:tags(source)) if(!t.closing&&t.name.equals("link")&&t.attrs.getOrDefault("rel","").equalsIgnoreCase("stylesheet")&&!t.attrs.containsKey("disabled")) {
            URI uri=PageLoader.link(page,t.attrs.get("href"));
            if(PageAssets.sameOrigin(page,uri)&&result.size()<4&&!result.contains(uri))result.add(uri);
        }
        return result;
    }
    private PageMarkup() { }
}
