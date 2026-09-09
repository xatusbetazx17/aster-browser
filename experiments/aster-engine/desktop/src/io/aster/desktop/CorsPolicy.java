package io.aster.desktop;

import java.io.IOException;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpHeaders;
import java.util.*;

/** Fetch CORS decisions. This class never opens a connection or grants cookie access. */
final class CorsPolicy {
    private static final Set<String> RESPONSE_SAFE=Set.of("cache-control","content-language","content-length","content-type","expires","last-modified","pragma");
    private final LinkedHashMap<String,Long> permissions=new LinkedHashMap<>();

    static boolean simpleMethod(String method){return Set.of("GET","HEAD","POST").contains(method);}
    static boolean safeHeader(String name,String value){
        if(value.length()>128)return false;
        switch(name){
            case "accept":return !unsafeBytes(value);
            case "accept-language":case "content-language":return value.matches("[0-9A-Za-z *,.;=\\-]*");
            case "content-type":return !unsafeBytes(value)&&Set.of("application/x-www-form-urlencoded","multipart/form-data","text/plain").contains(value.split(";",2)[0].trim().toLowerCase(Locale.ROOT));
            case "range":
                if(!value.matches("bytes=[0-9]+-[0-9]*"))return false;
                String[] range=value.substring(6).split("-",-1);
                return range[1].isEmpty()||new BigInteger(range[0]).compareTo(new BigInteger(range[1]))<=0;
            default:return false;
        }
    }
    private static boolean unsafeBytes(String value){
        for(int i=0;i<value.length();i++){char c=value.charAt(i);if(c<32&&c!='\t'||c==127||"\"():<>?@[\\]{}".indexOf(c)>=0)return true;}
        return false;
    }
    static SortedSet<String> unsafeHeaders(Map<String,String> headers){
        SortedSet<String> unsafe=new TreeSet<>();int safeSize=0;
        for(Map.Entry<String,String> h:headers.entrySet())if(safeHeader(h.getKey(),h.getValue()))safeSize+=h.getValue().length();else unsafe.add(h.getKey());
        if(safeSize>1024)unsafe.addAll(headers.keySet());
        return unsafe;
    }
    static void check(HttpHeaders headers,String origin,boolean credentials)throws IOException{
        String allowed=single(headers,"access-control-allow-origin");
        if(!(allowed.equals(origin)||!credentials&&allowed.equals("*")))throw new IOException("CORS response did not allow this page origin");
        if(credentials&&!single(headers,"access-control-allow-credentials").equals("true"))throw new IOException("CORS response did not allow credentials");
    }
    private static String single(HttpHeaders headers,String name){
        List<String> values=headers.allValues(name);return values.size()==1?values.get(0).trim():"";
    }
    private static Set<String> tokens(HttpHeaders headers,String name,boolean lower)throws IOException{
        Set<String> result=new HashSet<>();int size=0;
        for(String line:headers.allValues(name)){
            if((size+=line.length())>16384)throw new IOException("CORS header limit");
            for(String raw:line.split(",",-1)){
                String value=raw.trim();if(value.isEmpty())continue;
                if(!value.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+"))throw new IOException("Malformed CORS permission list");
                result.add(lower?value.toLowerCase(Locale.ROOT):value);
            }
        }
        return result;
    }
    static void checkPreflight(int status,HttpHeaders headers,String origin,boolean credentials,String method,Set<String> unsafe)throws IOException{
        check(headers,origin,credentials);
        if(status<200||status>=300)throw new IOException("CORS preflight was refused");
        Set<String> methods=tokens(headers,"access-control-allow-methods",false),names=tokens(headers,"access-control-allow-headers",true);
        if(!simpleMethod(method)&&!methods.contains(method)&&!(!credentials&&methods.contains("*")))throw new IOException("CORS preflight did not allow the method");
        for(String name:unsafe)if(!names.contains(name)&&!(names.contains("*")&&!credentials&&!name.equals("authorization")))throw new IOException("CORS preflight did not allow a request header");
    }
    static Map<String,String> visible(HttpHeaders headers,boolean cors,boolean credentials)throws IOException{
        Set<String> exposed=cors?tokens(headers,"access-control-expose-headers",true):Set.of();
        boolean all=!cors||!credentials&&exposed.contains("*");Map<String,String> result=new TreeMap<>();int size=0;
        for(Map.Entry<String,List<String>> h:headers.map().entrySet()){
            String name=h.getKey().toLowerCase(Locale.ROOT);
            if(name.equals("set-cookie")||name.equals("set-cookie2")||!all&&!RESPONSE_SAFE.contains(name)&&!exposed.contains(name))continue;
            String value=String.join(", ",h.getValue());if((size+=name.length()+value.length())>32768||result.size()>=64)throw new IOException("Fetch response header limit");
            result.put(name,value);
        }
        return result;
    }
    static String key(URI uri,String origin,boolean credentials,String method,Set<String> names){return uri.toASCIIString()+"\n"+origin+"\n"+credentials+"\n"+method+"\n"+String.join(",",names);}
    synchronized boolean cached(String key){Long until=permissions.get(key);if(until==null)return false;if(until<=System.nanoTime()){permissions.remove(key);return false;}return true;}
    synchronized void remember(String key,HttpHeaders headers){
        long seconds=5;try{seconds=Long.parseLong(single(headers,"access-control-max-age"));}catch(NumberFormatException ignored){}
        seconds=Math.max(0,Math.min(300,seconds));if(seconds==0)return;
        if(permissions.size()>=64)permissions.remove(permissions.keySet().iterator().next());permissions.put(key,System.nanoTime()+seconds*1_000_000_000L);
    }
}
