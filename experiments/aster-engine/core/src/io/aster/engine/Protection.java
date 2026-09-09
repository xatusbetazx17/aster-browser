package io.aster.engine;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;

/** Offline hostname blocking shared by every page transport. No remote service,
 * script injection, cosmetic hiding or claims of full EasyList compatibility. */
public final class Protection {
    public static final int CUSTOM_LIMIT=10000;
    // Aster's small, independently curated starter set. Exact domains and their
    // subdomains only; never URL substrings or whole social/video/CDN services.
    private static final Set<String> STARTER=Collections.unmodifiableSet(new HashSet<>(Arrays.asList((
        "doubleclick.net googlesyndication.com googleadservices.com googletagservices.com " +
        "google-analytics.com analytics.google.com adservice.google.com " +
        "adnxs.com adsrvr.org advertising.com adform.net adform.com " +
        "rubiconproject.com pubmatic.com openx.net casalemedia.com criteo.com criteo.net " +
        "taboola.com outbrain.com scorecardresearch.com quantserve.com " +
        "zedo.com serving-sys.com smartadserver.com amazon-adsystem.com " +
        "media.net 2mdn.net moatads.com doubleverify.com adsafeprotected.com " +
        "bat.bing.com clarity.ms hotjar.com hotjar.io mouseflow.com " +
        "luckyorange.com fullstory.com ads.twitter.com analytics.twitter.com " +
        "ads.linkedin.com ads.tiktok.com analytics.tiktok.com").split(" "))));
    private final Set<String> custom=new HashSet<>(), exceptions=new HashSet<>();
    private final LinkedHashMap<String,Log> logs=new LinkedHashMap<>();
    private final Path file;
    private boolean enabled=true,dirty;
    private static final class Log {long count;final LinkedHashMap<String,Long> hosts=new LinkedHashMap<>();}
    public static final class Snapshot {
        public final boolean enabled; public final long blocked;
        public final Map<String,Long> hosts;
        Snapshot(boolean enabled,Log log){this.enabled=enabled;blocked=log==null?0:log.count;
            hosts=Collections.unmodifiableMap(log==null?new LinkedHashMap<>():new LinkedHashMap<>(log.hosts));}
    }
    public Protection(){file=null;}
    Protection(Path file)throws IOException{this.file=file;read();}
    public int starterCount(){return STARTER.size();}
    public synchronized boolean enabled(){return enabled;}
    public synchronized void enabled(boolean value){if(enabled!=value){enabled=value;dirty=true;}}
    public synchronized boolean allowed(URI page){return exceptions.contains(SiteData.origin(page));}
    public synchronized void allow(URI page,boolean allow){String origin=SiteData.origin(page);
        if(origin.isEmpty())throw new IllegalArgumentException("Open a website to change its protection.");
        if(allow&&!exceptions.contains(origin)&&exceptions.size()>=256)throw new IllegalArgumentException("Site exception limit reached (256).");
        if(allow?exceptions.add(origin):exceptions.remove(origin))dirty=true;
    }
    /** Atomic replacement: one ASCII/IDN hostname per line; comments start with #.
     * URLs, wildcard patterns and hosts-file/ABP syntax are deliberately refused. */
    public synchronized void custom(String input){
        if(input.length()>1024*1024)throw new IllegalArgumentException("Use at most 1 MiB of hostname rules.");
        Set<String> next=new HashSet<>();
        for(String line:input.split("\\r?\\n")){line=line.trim();if(line.isEmpty()||line.startsWith("#"))continue;
            next.add(hostname(line));if(next.size()>CUSTOM_LIMIT)throw new IllegalArgumentException("Use at most 10,000 hostname rules.");}
        if(!custom.equals(next)){custom.clear();custom.addAll(next);dirty=true;}
    }
    public synchronized String custom(){List<String> list=new ArrayList<>(custom);Collections.sort(list);return String.join("\n",list);}
    private static String hostname(String value){
        if(value.length()>253||value.indexOf(':')>=0||value.indexOf('/')>=0)throw new IllegalArgumentException("Enter hostnames only, one per line.");
        String name;
        try{name=IDN.toASCII(value,IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);}catch(IllegalArgumentException e){throw new IllegalArgumentException("Invalid hostname rule.");}
        if(name.endsWith("."))name=name.substring(0,name.length()-1);
        if(name.length()>253||!name.contains(".")||name.contains(".."))throw new IllegalArgumentException("Use a complete hostname such as ads.example.org.");
        for(String label:name.split("\\."))if(!label.matches("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?"))throw new IllegalArgumentException("Invalid hostname rule.");
        return name;
    }
    private static String normalized(URI uri){if(uri==null||uri.getHost()==null)return "";String h=uri.getHost().toLowerCase(Locale.ROOT);return h.endsWith(".")?h.substring(0,h.length()-1):h;}
    private static boolean matches(Set<String> rules,String host){
        for(String part=host;!part.isEmpty();){if(rules.contains(part))return true;int dot=part.indexOf('.');if(dot<0)break;part=part.substring(dot+1);}return false;
    }
    public synchronized String reason(URI page,URI target){
        if(!enabled||exceptions.contains(SiteData.origin(page)))return "";
        String host=normalized(target);if(matches(custom,host))return "Custom hostname rule";
        return matches(STARTER,host)?"Aster ad/tracker starter rule":"";
    }
    /** Call before opening a connection, including each redirect and preflight. */
    public synchronized void check(URI page,URI target)throws IOException{
        String reason=reason(page,target);if(reason.isEmpty())return;
        String origin=SiteData.origin(page),host=normalized(target);Log log=logs.get(origin);
        if(log==null){if(logs.size()>=64)logs.remove(logs.keySet().iterator().next());log=new Log();logs.put(origin,log);}
        if(log.count<Long.MAX_VALUE)log.count++;
        if(log.hosts.containsKey(host)){long n=log.hosts.get(host);log.hosts.put(host,n==Long.MAX_VALUE?n:n+1);}
        else{if(log.hosts.size()>=64)log.hosts.remove(log.hosts.keySet().iterator().next());log.hosts.put(host,1L);}
        throw new IOException("Blocked by site protection: "+host+" ("+reason+")");
    }
    public synchronized Snapshot snapshot(URI page){return new Snapshot(enabled&&!allowed(page),logs.get(SiteData.origin(page)));}
    public synchronized void clearActivity(){logs.clear();}
    public synchronized void flush()throws IOException{
        if(file==null||!dirty)return;Files.createDirectories(file.getParent());
        PosixFileAttributeView directory=Files.getFileAttributeView(file.getParent(),PosixFileAttributeView.class);
        if(directory!=null)directory.setPermissions(PosixFilePermissions.fromString("rwx------"));
        Path temp=Files.createTempFile(file.getParent(),"protection-",".tmp");
        try{
            PosixFileAttributeView view=Files.getFileAttributeView(temp,PosixFileAttributeView.class);if(view!=null)view.setPermissions(PosixFilePermissions.fromString("rw-------"));
            try(DataOutputStream out=new DataOutputStream(Files.newOutputStream(temp))){out.writeInt(0x41535031);out.writeBoolean(enabled);write(out,custom);write(out,exceptions);}
            try{Files.move(temp,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(AtomicMoveNotSupportedException e){Files.move(temp,file,StandardCopyOption.REPLACE_EXISTING);}dirty=false;
        }finally{Files.deleteIfExists(temp);}
    }
    private static void write(DataOutputStream out,Set<String> values)throws IOException{List<String> list=new ArrayList<>(values);Collections.sort(list);out.writeInt(list.size());for(String value:list)out.writeUTF(value);}
    private void read()throws IOException{
        if(!Files.exists(file))return;if(Files.size(file)>4*1024*1024)throw new IOException("Protection settings too large.");
        try(DataInputStream in=new DataInputStream(Files.newInputStream(file))){
            if(in.readInt()!=0x41535031)throw new IOException("Unknown protection settings format.");enabled=in.readBoolean();
            int n=in.readInt();if(n<0||n>CUSTOM_LIMIT)throw new IOException("Protection rule limit.");for(int i=0;i<n;i++)custom.add(hostname(in.readUTF()));
            n=in.readInt();if(n<0||n>256)throw new IOException("Protection exception limit.");for(int i=0;i<n;i++){String value=in.readUTF();if(!value.equals(SiteData.origin(URI.create(value))))throw new IOException("Invalid protection exception.");exceptions.add(value);}
            if(in.read()!=-1)throw new IOException("Unexpected protection settings data.");
        }catch(IllegalArgumentException e){throw new IOException("Invalid protection settings.",e);}
    }
}
