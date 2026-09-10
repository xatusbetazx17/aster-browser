package io.aster.engine;

import java.net.URI;
import java.util.*;

/** Shared bounded token scan for the preview's styles and native forms. */
public final class PageMarkup {
    /** Fetch policy is part of the cache key: one element cannot reuse another's
     * response to bypass its crossorigin or integrity requirements. */
    public static final class Asset {
        public final URI uri;
        public final boolean cors, credentials;
        public final String integrity;
        Asset(URI uri,Map<String,String> attrs,boolean stylesheet) {
            this.uri=uri;cors=attrs.containsKey("crossorigin");
            credentials="use-credentials".equalsIgnoreCase(attrs.get("crossorigin"));
            integrity=stylesheet?attrs.getOrDefault("integrity","").trim():"";
        }
        public String key(){return uri+"\n"+cors+"\n"+credentials+"\n"+integrity;}
    }
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
        for(Asset asset:stylesheetAssets(page,source))if(!result.contains(asset.uri))result.add(asset.uri);
        return result;
    }
    static Asset stylesheet(URI page,Tag tag){
        if(tag.closing||!tag.name.equals("link")||tag.attrs.containsKey("disabled"))return null;
        List<String> rel=Arrays.asList(tag.attrs.getOrDefault("rel","").toLowerCase(Locale.ROOT).trim().split("\\s+"));
        String type=tag.attrs.getOrDefault("type","").trim();
        if(!rel.contains("stylesheet")||rel.contains("alternate")||!type.isEmpty()&&!type.equalsIgnoreCase("text/css"))return null;
        return asset(page,tag.attrs,"href",true);
    }
    static Asset image(URI page,Map<String,String> attrs){return asset(page,attrs,"src",false);}
    private static Asset asset(URI page,Map<String,String> attrs,String attribute,boolean stylesheet){
        URI uri=PageLoader.link(page,attrs.get(attribute));
        return PageAssets.allowed(page,uri)?new Asset(uri,attrs,stylesheet):null;
    }
    public static List<Asset> stylesheetAssets(URI page,String source){return assets(page,source,true);}
    public static List<Asset> images(URI page,String source){return assets(page,source,false);}
    private static List<Asset> assets(URI page,String source,boolean stylesheets){
        List<Asset> result=new ArrayList<>();Set<String> keys=new HashSet<>();
        for(Tag tag:tags(source)){
            Asset asset=stylesheets?stylesheet(page,tag):!tag.closing&&tag.name.equals("img")?image(page,tag.attrs):null;
            if(asset!=null&&keys.add(asset.key())){result.add(asset);if(result.size()>=(stylesheets?4:8))break;}
        }return result;
    }
    private PageMarkup() { }
}
