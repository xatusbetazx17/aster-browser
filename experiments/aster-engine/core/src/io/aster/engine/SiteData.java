package io.aster.engine;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.PosixFileAttributeView;
import java.text.SimpleDateFormat;
import java.util.*;

/** A bounded profile store, deliberately stricter than the web cookie model.
 * Cookies are isolated by scheme/host/port. Parent-domain and third-party cookies
 * are refused until Aster has a public-suffix/site implementation. No global
 * CookieHandler: every native request must explicitly carry its profile context. */
public final class SiteData {
    private static final int COOKIE_LIMIT=256, ORIGIN_COOKIES=50, FILE_LIMIT=2*1024*1024;
    public static final int STORAGE_LIMIT=64*1024, TOTAL_STORAGE_LIMIT=512*1024;
    private static final long MAX_AGE=400L*24*60*60*1000;
    private final List<Cookie> cookies=new ArrayList<>();
    private final Storage local=new Storage();
    private final Path file;
    private long epoch;
    private boolean dirty;
    public SiteData(){file=null;}
    public SiteData(Path file)throws IOException{this.file=file.toAbsolutePath();read();}
    public static String origin(URI uri){
        if(uri==null)return "";
        try{PageLoader.validate(uri);}catch(IllegalArgumentException e){return "";}
        if(uri.getHost().length()>253)return "";
        String scheme=uri.getScheme().toLowerCase(Locale.ROOT);
        return scheme+"://"+uri.getHost().toLowerCase(Locale.ROOT)+":"+(uri.getPort()<0?(scheme.equals("https")?443:80):uri.getPort());
    }
    public synchronized Request request(URI initiator,boolean topLevel,String method){return new Request(origin(initiator),topLevel,method,epoch);}
    public final class Request {
        private final String initiator;private final boolean topLevel;private final long generation;
        private String method,firstTarget;private boolean crossed;
        private Request(String initiator,boolean topLevel,String method,long generation){this.initiator=initiator;this.topLevel=topLevel;this.method=method;this.generation=generation;}
        public void method(String value){method=value;}
        public String header(URI target){synchronized(SiteData.this){
            String to=origin(target);if(firstTarget==null)firstTarget=initiator.isEmpty()?to:initiator;if(!to.equals(firstTarget))crossed=true;
            if(generation!=epoch||to.isEmpty()||!topLevel&&(crossed||!to.equals(initiator)))return "";
            boolean same=!crossed&&(initiator.isEmpty()||to.equals(initiator));
            return select(target,false,same,topLevel&&(method.equals("GET")||method.equals("HEAD")));
        }}
        public void prepare(HttpURLConnection connection){String value=header(URI.create(connection.getURL().toString()));if(!value.isEmpty())connection.setRequestProperty("Cookie",value);}
        public void receive(HttpURLConnection connection){
            URI uri=URI.create(connection.getURL().toString());List<String> fields=new ArrayList<>();
            for(int i=1;i<=256;i++){String name=connection.getHeaderFieldKey(i),value=connection.getHeaderField(i);if(name==null&&value==null)break;if("set-cookie".equalsIgnoreCase(name)&&value!=null)fields.add(value);}
            receive(uri,Collections.singletonMap("Set-Cookie",fields));
        }
        public void receive(URI target,Map<String,List<String>> headers){synchronized(SiteData.this){
            String to=origin(target);if(generation!=epoch||to.isEmpty()||!topLevel&&(crossed||!to.equals(initiator)))return;
            int count=0;for(Map.Entry<String,List<String>> h:headers.entrySet())if("set-cookie".equalsIgnoreCase(h.getKey())&&h.getValue()!=null)
                for(String value:h.getValue()){if(++count>50)return;setCookie(target,value,false);}
        }}
    }
    private static final class Cookie {
        String origin,name,value,path,sameSite;boolean secure,httpOnly;long expires;
    }
    private static boolean pathMatches(String request,String cookie){return request.equals(cookie)||request.startsWith(cookie)&&(cookie.endsWith("/")||request.length()>cookie.length()&&request.charAt(cookie.length())=='/');}
    private static String path(URI uri){String p=uri.getRawPath();return p==null||!p.startsWith("/")?"/":p;}
    private static String defaultPath(URI uri){String p=path(uri);int slash=p.lastIndexOf('/');return slash<=0?"/":p.substring(0,slash);}
    private void expire(){long now=System.currentTimeMillis();if(cookies.removeIf(c->c.expires!=0&&c.expires<=now))dirty=true;}
    private String select(URI uri,boolean script,boolean same,boolean safeNavigation){
        expire();String scope=origin(uri);List<Cookie> selected=new ArrayList<>();
        for(Cookie c:cookies)if(c.origin.equals(scope)&&pathMatches(path(uri),c.path)&&(!script||!c.httpOnly)&&(!c.secure||uri.getScheme().equalsIgnoreCase("https"))
                &&(script||same||!c.sameSite.equals("strict")&&(safeNavigation||c.sameSite.equals("none"))))selected.add(c);
        selected.sort((a,b)->Integer.compare(b.path.length(),a.path.length()));StringBuilder result=new StringBuilder();
        for(Cookie c:selected){if(result.length()+c.name.length()+c.value.length()+3>16384)break;if(result.length()>0)result.append("; ");result.append(c.name).append('=').append(c.value);}
        return result.toString();
    }
    public synchronized String documentCookie(URI uri){return select(uri,true,true,false);}
    public synchronized void setDocumentCookie(URI uri,String value){setCookie(uri,value,true);}
    private void setCookie(URI uri,String input,boolean script){
        String scope=origin(uri);if(scope.isEmpty()||input==null||input.length()>4096||input.matches("(?s).*[\\x00-\\x1f\\x7f-\\uffff].*"))return;
        String[] parts=input.split(";",-1);int equal=parts[0].indexOf('=');if(equal<=0)return;
        Cookie c=new Cookie();c.origin=scope;c.name=parts[0].substring(0,equal).trim();c.value=parts[0].substring(equal+1).trim();c.path=defaultPath(uri);c.sameSite="lax";
        if(!c.name.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+"))return;
        String value=c.value;if(value.startsWith("\"")&&value.endsWith("\"")&&value.length()>=2)value=value.substring(1,value.length()-1);
        if(!value.matches("[\\x21\\x23-\\x2b\\x2d-\\x3a\\x3c-\\x5b\\x5d-\\x7e]*"))return;
        Long maxAge=null,expires=null;boolean domain=false,explicitRoot=false;
        for(int i=1;i<parts.length;i++){
            String[] a=parts[i].trim().split("=",2);String key=a[0].toLowerCase(Locale.ROOT),v=a.length>1?a[1].trim():"";
            if(key.equals("domain")){domain=true;if(v.startsWith("."))v=v.substring(1);if(!v.equalsIgnoreCase(uri.getHost()))return;}
            else if(key.equals("path")&&v.startsWith("/")){if(v.length()>1024)return;c.path=v;explicitRoot=v.equals("/");}
            else if(key.equals("secure"))c.secure=true;
            else if(key.equals("httponly"))c.httpOnly=true;
            else if(key.equals("samesite")){c.sameSite=v.equalsIgnoreCase("Strict")?"strict":v.equalsIgnoreCase("None")?"none":"lax";}
            else if(key.equals("max-age")&&v.matches("-?[0-9]+")){try{maxAge=Long.parseLong(v);}catch(NumberFormatException e){maxAge=v.startsWith("-")?-1:MAX_AGE/1000;}}
            else if(key.equals("expires")){Long date=date(v);if(date!=null)expires=date;}
            else if(key.equals("partitioned"))return;
        }
        boolean https=uri.getScheme().equalsIgnoreCase("https");
        if(c.secure&&!https||c.sameSite.equals("none")&&!c.secure||script&&c.httpOnly)return;
        String prefix=c.name.toLowerCase(Locale.ROOT);
        if((prefix.startsWith("__secure-")||prefix.startsWith("__host-")||prefix.startsWith("__http-"))&&(!c.secure||!https))return;
        if(prefix.startsWith("__host-")&&(domain||!explicitRoot))return;
        if((prefix.startsWith("__http-")||prefix.startsWith("__host-http-"))&&(!c.httpOnly||script))return;
        long now=System.currentTimeMillis();c.expires=maxAge!=null?(maxAge<=0?1:now+Math.min(maxAge,MAX_AGE/1000)*1000):expires==null?0:expires<=now?1:Math.min(expires,now+MAX_AGE);
        expire();Cookie old=null;for(Cookie existing:cookies)if(existing.origin.equals(scope)&&existing.name.equals(c.name)&&existing.path.equals(c.path)){old=existing;break;}
        if(old!=null&&script&&old.httpOnly)return;
        if(old!=null){int at=cookies.indexOf(old);cookies.set(at,c);}else{
            // Refuse excess cookies instead of evicting a site's existing authentication state.
            int count=0;for(Cookie existing:cookies)if(existing.origin.equals(scope))count++;
            if(cookies.size()>=COOKIE_LIMIT||count>=ORIGIN_COOKIES)return;cookies.add(c);
        }
        dirty=true;expire();
    }
    private static Long date(String value){
        for(String pattern:new String[]{"EEE, dd MMM yyyy HH:mm:ss zzz","EEEE, dd-MMM-yy HH:mm:ss zzz","EEE MMM d HH:mm:ss yyyy"})try{
            SimpleDateFormat format=new SimpleDateFormat(pattern,Locale.US);format.setTimeZone(TimeZone.getTimeZone("GMT"));format.setLenient(false);
            java.text.ParsePosition position=new java.text.ParsePosition(0);Date date=format.parse(value,position);if(date!=null&&position.getIndex()==value.length())return date.getTime();
        }catch(IllegalArgumentException ignored){}return null;
    }
    /** Per-tab session storage. Never serialized; each origin gets a separate map. */
    public static final class Storage {
        private final Map<String,LinkedHashMap<String,String>> origins=new LinkedHashMap<>();
        public synchronized void clear(){origins.clear();}
        public synchronized Object access(URI uri,String operation,String key,String value){
            String scope=origin(uri);if(scope.isEmpty())throw new SecurityException("Website storage requires an HTTP or HTTPS origin");
            LinkedHashMap<String,String> values=origins.get(scope);
            if(operation.equals("get"))return values==null?null:values.get(key);
            if(operation.equals("keys"))return values==null?new ArrayList<String>():new ArrayList<>(values.keySet());
            if(operation.equals("remove")){if(values!=null){values.remove(key);if(values.isEmpty())origins.remove(scope);}return null;}
            if(operation.equals("clear")){origins.remove(scope);return null;}
            if(!operation.equals("set"))throw new IllegalArgumentException("Unknown storage operation");
            if(key==null||value==null||(long)(key.length())+value.length()>STORAGE_LIMIT/2)throw new IllegalArgumentException("Website storage quota exceeded");
            int total=0,site=0;for(Map.Entry<String,LinkedHashMap<String,String>> o:origins.entrySet())for(Map.Entry<String,String> e:o.getValue().entrySet()){
                int size=2*(e.getKey().length()+e.getValue().length());total+=size;if(o.getKey().equals(scope))site+=size;
            }
            String old=values==null?null:values.get(key);int delta=2*(key.length()+value.length())-(old==null?0:2*(key.length()+old.length()));
            if(site+delta>STORAGE_LIMIT||total+delta>TOTAL_STORAGE_LIMIT||values==null&&origins.size()>=64||values!=null&&old==null&&values.size()>=256)throw new IllegalArgumentException("Website storage quota exceeded");
            if(values==null){values=new LinkedHashMap<>();origins.put(scope,values);}values.put(key,value);return null;
        }
    }
    public synchronized Object storage(Storage session,boolean persistent,URI uri,String operation,String key,String value){
        Object result=(persistent?local:session).access(uri,operation,key,value);if(persistent&&!operation.equals("get")&&!operation.equals("keys"))dirty=true;return result;
    }
    public synchronized void clear(){epoch++;cookies.clear();local.clear();dirty=true;}
    public synchronized int cookieCount(){expire();return cookies.size();}
    /** Session cookies stay in memory; only explicitly expiring cookies go to disk. */
    public synchronized void flush()throws IOException{
        if(file==null||!dirty)return;expire();Path directory=file.getParent();Files.createDirectories(directory);
        // Query the path attribute view directly: Android app sandboxes can deny
        // the mount-table access needed by getFileStore(), even for owned files.
        PosixFileAttributeView directoryView=Files.getFileAttributeView(directory,PosixFileAttributeView.class);
        if(directoryView!=null)directoryView.setPermissions(PosixFilePermissions.fromString("rwx------"));
        Path temp=Files.createTempFile(directory,"site-data-",".tmp");
        try{
            PosixFileAttributeView tempView=Files.getFileAttributeView(temp,PosixFileAttributeView.class);
            if(tempView!=null)tempView.setPermissions(PosixFilePermissions.fromString("rw-------"));
            try(DataOutputStream out=new DataOutputStream(Files.newOutputStream(temp))){
                out.writeInt(0x41535431);int count=0;for(Cookie c:cookies)if(c.expires>0)count++;out.writeInt(count);
                for(Cookie c:cookies)if(c.expires>0){out.writeUTF(c.origin);out.writeUTF(c.name);out.writeUTF(c.value);out.writeUTF(c.path);out.writeUTF(c.sameSite);out.writeBoolean(c.secure);out.writeBoolean(c.httpOnly);out.writeLong(c.expires);}
                out.writeInt(local.origins.size());for(Map.Entry<String,LinkedHashMap<String,String>> o:local.origins.entrySet()){
                    out.writeUTF(o.getKey());out.writeInt(o.getValue().size());for(Map.Entry<String,String> entry:o.getValue().entrySet()){writeText(out,entry.getKey());writeText(out,entry.getValue());}
                }
            }
            try{Files.move(temp,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(AtomicMoveNotSupportedException e){Files.move(temp,file,StandardCopyOption.REPLACE_EXISTING);}dirty=false;
        }finally{Files.deleteIfExists(temp);}
    }
    private static void writeText(DataOutputStream out,String text)throws IOException{out.writeInt(text.length());for(int i=0;i<text.length();i++)out.writeChar(text.charAt(i));}
    private static String readText(DataInputStream in)throws IOException{int length=in.readInt();if(length<0||length>STORAGE_LIMIT/2)throw new IOException("Invalid storage text length");StringBuilder b=new StringBuilder(length);for(int i=0;i<length;i++)b.append(in.readChar());return b.toString();}
    private void read()throws IOException{
        if(!Files.exists(file))return;if(Files.isSymbolicLink(file)||Files.size(file)>FILE_LIMIT)throw new IOException("Invalid website data file");
        try(DataInputStream in=new DataInputStream(Files.newInputStream(file))){
            if(in.readInt()!=0x41535431)throw new IOException("Unknown website data format");int count=in.readInt();if(count<0||count>COOKIE_LIMIT)throw new IOException("Invalid cookie count");
            for(int i=0;i<count;i++){
                String scope=in.readUTF(),name=in.readUTF(),value=in.readUTF(),path=in.readUTF(),same=in.readUTF();boolean secure=in.readBoolean(),httpOnly=in.readBoolean();long expires=in.readLong();
                URI uri=URI.create(scope);long remaining=(expires-System.currentTimeMillis())/1000;if(remaining<=0)continue;
                setCookie(uri,name+"="+value+"; Path="+path+"; SameSite="+same+"; Max-Age="+Math.min(remaining,MAX_AGE/1000)+(secure?"; Secure":"")+(httpOnly?"; HttpOnly":""),false);
            }
            int origins=in.readInt();if(origins<0||origins>64)throw new IOException("Invalid storage origin count");
            for(int i=0;i<origins;i++){URI uri=URI.create(in.readUTF());int entries=in.readInt();if(entries<0||entries>256)throw new IOException("Invalid storage entry count");for(int j=0;j<entries;j++)local.access(uri,"set",readText(in),readText(in));}
            if(in.read()!=-1)throw new IOException("Trailing website data");dirty=false;
        }catch(RuntimeException e){throw new IOException("Invalid website data",e);}
    }
}
