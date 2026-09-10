package io.aster.engine;

import java.io.*;
import java.net.*;
import java.security.*;
import java.util.*;
import java.util.zip.GZIPInputStream;

/** Bounded page assets. Cross-origin responses never send or accept profile cookies. */
public final class PageAssets {
    public static final int IMAGE_LIMIT = 2 * 1024 * 1024;
    private static final Set<Integer> BLOCKED_PORTS=new HashSet<>(Arrays.asList(1,7,9,11,13,15,17,19,20,21,22,23,25,37,42,43,53,69,77,79,87,95,101,102,103,104,109,110,111,113,115,117,119,123,135,137,139,143,161,179,389,427,465,512,513,514,515,526,530,531,532,540,548,554,556,563,587,601,636,989,990,993,995,1719,1720,1723,2049,3659,4045,5060,5061,6000,6566,6665,6666,6667,6668,6669,6697,10080));
    public interface Connections { HttpURLConnection open(URI uri) throws IOException; }
    private static volatile Connections connections=uri->(HttpURLConnection)uri.toURL().openConnection();
    /** The desktop installs its Java HTTP client before loading pages. Android
     * uses its native URLConnection, which supports the Origin header. */
    public static void useConnections(Connections factory){connections=Objects.requireNonNull(factory);}
    public static boolean sameOrigin(URI a, URI b) {
        return a != null && b != null && a.getHost() != null && b.getHost() != null
            && a.getScheme().equalsIgnoreCase(b.getScheme()) && a.getHost().equalsIgnoreCase(b.getHost())
            && port(a) == port(b);
    }
    private static int port(URI u) { return u.getPort() >= 0 ? u.getPort() : "https".equalsIgnoreCase(u.getScheme()) ? 443 : 80; }
    public static boolean allowed(URI page,URI target){
        try{PageLoader.validate(page);PageLoader.validate(target);return !BLOCKED_PORTS.contains(port(target))&&(!"https".equalsIgnoreCase(page.getScheme())||"https".equalsIgnoreCase(target.getScheme()));}
        catch(IllegalArgumentException e){return false;}
    }
    public static byte[] fetch(URI page, URI target, boolean image) throws IOException {
        return fetch(page,target,image,new SiteData().request(page,false,"GET"));
    }
    public static byte[] fetch(URI page,URI target,boolean image,SiteData.Request request)throws IOException{
        return fetch(page,new PageMarkup.Asset(target,Collections.emptyMap(),!image),image,request);
    }
    public static byte[] fetch(URI page,PageMarkup.Asset asset,boolean image,SiteData.Request request)throws IOException{
        URI target=asset.uri;
        if(!allowed(page,target))throw new IOException("Unsafe or mixed-content page asset refused.");
        if(asset.integrity.length()>8192)throw new IOException("Integrity metadata exceeds limit.");
        boolean tainted=false;String scheme=page.getScheme().toLowerCase(Locale.ROOT);
        String origin=scheme+"://"+page.getHost().toLowerCase(Locale.ROOT)+(port(page)==(scheme.equals("https")?443:80)?"":":"+port(page));
        int limit = image ? IMAGE_LIMIT : 65536;
        long deadline = System.nanoTime() + 15_000_000_000L;
        for (int i = 0; i <= 5; i++) {
            if (Thread.currentThread().isInterrupted() || System.nanoTime() > deadline) throw new IOException("Asset load cancelled or timed out.");
            tainted|=!sameOrigin(page,target);
            if(tainted&&asset.credentials)throw new IOException("Credentialed cross-origin assets are not supported.");
            if(tainted&&!asset.integrity.isEmpty()&&!asset.cors)throw new IOException("Cross-origin integrity requires CORS.");
            HttpURLConnection c = connections.open(target);
            c.setConnectTimeout(5000); c.setReadTimeout(5000); c.setInstanceFollowRedirects(false);
            c.setRequestProperty("User-Agent", "AsterEnginePreview/0.7"); c.setRequestProperty("Accept-Encoding", image ? "identity" : "gzip");
            try {
                if(asset.cors&&tainted){c.setRequestProperty("Origin",origin);
                    if(!origin.equals(c.getRequestProperty("Origin")))throw new IOException("Native asset transport cannot send the CORS origin.");}
                request.prepare(c);
                int code = c.getResponseCode();request.receive(c);
                if(tainted){
                    if(asset.cors){String allow=c.getHeaderField("Access-Control-Allow-Origin");
                        if(!origin.equals(allow)&&!"*".equals(allow))throw new IOException("Asset CORS permission refused.");}
                    else {String policy=c.getHeaderField("Cross-Origin-Resource-Policy");
                        // same-site stays conservative until the engine has a public-suffix implementation.
                        if("same-origin".equals(policy)||"same-site".equals(policy))throw new IOException("Asset resource policy refused cross-origin use.");}
                }
                if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                    URI next = PageLoader.link(target, c.getHeaderField("Location"));
                    if (!allowed(page,next)||!allowed(target,next)) throw new IOException("Unsafe asset redirect refused.");
                    if(tainted&&!sameOrigin(target,next))origin="null";
                    target = next; continue;
                }
                if (code != 200) throw new IOException("Asset returned HTTP " + code);
                String type = Objects.toString(c.getContentType(), "").split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
                if (image ? !Arrays.asList("image/png", "image/jpeg", "image/gif").contains(type) : !type.equals("text/css"))
                    throw new IOException("Unsupported asset type: " + type);
                if (c.getContentLengthLong() > limit) throw new IOException("Asset exceeds size limit.");
                String encoding = Objects.toString(c.getContentEncoding(), "identity");
                if (!encoding.equalsIgnoreCase("identity") && !(encoding.equalsIgnoreCase("gzip") && !image)) throw new IOException("Unsupported asset encoding.");
                try (InputStream raw = c.getInputStream(); InputStream in = encoding.equalsIgnoreCase("gzip") ? new GZIPInputStream(raw) : raw) {
                    byte[] bytes=read(in, limit, deadline);verifyIntegrity(bytes,asset.integrity);return bytes;
                }
            } finally { c.disconnect(); }
        }
        throw new IOException("Too many asset redirects.");
    }
    private static void verifyIntegrity(byte[] bytes,String metadata)throws IOException{
        if(metadata.isEmpty())return;
        if(metadata.length()>8192)throw new IOException("Integrity metadata exceeds limit.");
        int strongest=0;List<String> hashes=new ArrayList<>();
        for(String token:metadata.split("\\s+")){
            int dash=token.indexOf('-');if(dash<0)continue;String name=token.substring(0,dash);
            int strength=name.equals("sha512")?512:name.equals("sha384")?384:name.equals("sha256")?256:0;
            if(strength==0||strength<strongest)continue;
            if(strength>strongest){hashes.clear();strongest=strength;}
            hashes.add(token.substring(dash+1).split("\\?",2)[0]);
        }
        if(strongest==0)throw new IOException("No supported integrity hash.");
        try{byte[] actual=MessageDigest.getInstance("SHA-"+strongest).digest(bytes);
            for(String hash:hashes)try{if(MessageDigest.isEqual(actual,Base64.getDecoder().decode(hash)))return;}catch(IllegalArgumentException ignored){}
        }catch(NoSuchAlgorithmException e){throw new IOException("Integrity algorithm is unavailable.",e);}
        throw new IOException("Asset integrity check failed.");
    }
    public static byte[] read(InputStream in, int limit, long deadline) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] buf = new byte[8192]; int n;
        while ((n = in.read(buf)) != -1) {
            if (Thread.currentThread().isInterrupted() || System.nanoTime() > deadline) throw new IOException("Reading cancelled or timed out.");
            if (out.size() + n > limit) throw new IOException("Content exceeds size limit.");
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }
    private PageAssets() { }
}
