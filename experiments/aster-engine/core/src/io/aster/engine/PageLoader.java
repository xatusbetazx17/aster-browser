package io.aster.engine;

import java.io.*;
import java.net.*;
import java.nio.charset.*;
import java.util.Locale;
import java.util.zip.GZIPInputStream;

/** Bounded page navigation, native form POST and same-origin stylesheets. No cookies or external handlers. */
public final class PageLoader {
    public static final URI HOME = URI.create("aster://welcome");
    public static final String WELCOME = "<html><head><title>Aster · Engine preview</title></head><body>"
        + "<p style='color:#247878;font-weight:bold'>A S T E R</p><h1>Your space to explore.</h1>"
        + "<p>Search, read and keep useful things together.</p>"
        + "<h2>Start exploring</h2><p>Search above, enter a website address or try "
        + "<a href='https://example.com'>Example Domain</a>. Simple pages, images and basic forms work here.</p>"
        + "<h2>Read your way</h2><p>Open the menu to read a page, find text, keep notes or "
        + "open a Word or text document. Read aloud uses an installed English or Spanish voice.</p>"
        + "<h2>Still growing</h2><p>Aster 0.2 is an independent browser preview. Full web apps, "
        + "account sign-in, PDF, cloud gaming and protected streaming remain unfinished.</p>"
        + "<p><b>Tip:</b> use Tabs and bookmarks in the menu to keep exploring.</p></body></html>";

    public static URI address(String input) {
        String text = input.trim();
        if (text.equals(HOME.toString())) return HOME;
        if (text.isEmpty() || text.length() > 8192 || text.matches(".*[\\x00-\\x20\\x7f].*")) throw new IllegalArgumentException("Enter a website address, such as https://example.com.");
        if (!text.contains("://")) {
            // Bare host:port is accepted; script/file/data schemes are never treated as hosts.
            if (text.matches("^[A-Za-z][A-Za-z0-9+.-]*:.*") && !text.matches("^[A-Za-z0-9.-]+:[0-9]+(?:/.*)?$")) throw new IllegalArgumentException("Only HTTP and HTTPS pages are supported.");
            text = "https://" + text;
        }
        URI uri = URI.create(text).normalize(); validate(uri); return uri;
    }
    public static URI searchOrAddress(String input) {
        String text=input.trim();
        if(text.isEmpty()||text.length()>8192)throw new IllegalArgumentException("Enter a website address or search words.");
        if(text.contains("://")||text.matches("^[A-Za-z][A-Za-z0-9+.-]*:.*")||!text.matches(".*\\s.*")&&(text.contains(".")||text.equals("localhost")))return address(text);
        try{return URI.create("https://html.duckduckgo.com/html/?q="+URLEncoder.encode(text,"UTF-8"));}catch(Exception e){throw new IllegalArgumentException("Invalid search.",e);}
    }
    public static void validate(URI uri) {
        if (uri == null || !("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null || uri.getUserInfo() != null || uri.toString().length() > 8192
                || uri.getPort() > 65535 || uri.getPort() == 0)
            throw new IllegalArgumentException("Only HTTP/HTTPS website addresses without embedded passwords are supported.");
    }
    public static URI link(URI base, String href) {
        if (href == null) return null;
        try { URI uri = base.resolve(href.trim()).normalize(); validate(uri); return uri; }
        catch (IllegalArgumentException e) { return null; }
    }
    /** A response intended for explicit saving, not HTML rendering. No file is written here. */
    public static final class DownloadRequired extends IOException {
        public final URI uri;
        public final String contentType, disposition;
        public final long length;
        DownloadRequired(URI uri, String type, String disposition, long length) {
            super("This response is a file download. Choose Save As to keep it.");
            this.uri = uri; this.contentType = type; this.disposition = disposition; this.length = length;
        }
    }
    public static Engine.Document load(URI initial) throws IOException {
        return load(initial,null);
    }
    public static Engine.Document load(URI initial,byte[] formBody) throws IOException {
        if (HOME.equals(initial)) return Engine.parse(HOME, WELCOME);
        if(formBody!=null&&formBody.length>65536)throw new IOException("Form too large.");
        boolean form=formBody!=null;
        validate(initial);
        URI uri = initial; long deadline = System.nanoTime() + 25_000_000_000L;
        for (int redirects = 0; redirects <= 5; redirects++) {
            if (Thread.currentThread().isInterrupted() || System.nanoTime() > deadline) throw new IOException("Page request cancelled or timed out.");
            HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
            connection.setInstanceFollowRedirects(false); connection.setConnectTimeout(8000); connection.setReadTimeout(8000);
            connection.setRequestProperty("User-Agent", "AsterEnginePreview/0.2");
            connection.setRequestProperty("Accept", "text/html,text/plain;q=0.9");
            connection.setRequestProperty("Accept-Encoding", "gzip");
            try {
                if(formBody!=null){connection.setRequestMethod("POST");connection.setDoOutput(true);
                    connection.setRequestProperty("Content-Type","application/x-www-form-urlencoded");
                    connection.setRequestProperty("Origin",uri.getScheme()+"://"+uri.getRawAuthority());
                    connection.setFixedLengthStreamingMode(formBody.length);try(OutputStream out=connection.getOutputStream()){out.write(formBody);}}
                int code = connection.getResponseCode();
                if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                    String target = connection.getHeaderField("Location");
                    URI next = target == null ? null : link(uri, target);
                    if (next == null || ("https".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(next.getScheme()))) throw new IOException("Unsafe or unsupported redirect refused.");
                    if(form&&!PageAssets.sameOrigin(initial,next))throw new IOException("Cross-origin form redirect refused.");
                    if(code==301||code==302||code==303)formBody=null;
                    uri = next; continue;
                }
                if (code < 200 || code >= 300) throw new IOException("Website returned HTTP " + code + ".");
                String type = connection.getContentType(); if (type == null) type = "";
                String mime = type.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
                String disposition = connection.getHeaderField("Content-Disposition");
                if ((!mime.equals("text/html") && !mime.equals("text/plain")) ||
                        (disposition != null && disposition.split(";", 2)[0].trim().equalsIgnoreCase("attachment")))
                    throw new DownloadRequired(uri, mime, disposition, connection.getContentLengthLong());
                if (connection.getContentLengthLong() > Engine.MAX_SOURCE) throw new IOException("Page exceeds the preview's 1 MB download limit.");
                byte[] data;
                String encoding=connection.getContentEncoding();
                if(encoding!=null&&!encoding.equalsIgnoreCase("identity")&&!encoding.equalsIgnoreCase("gzip"))throw new IOException("Unsupported page content encoding.");
                try (InputStream raw=connection.getInputStream();InputStream input = "gzip".equalsIgnoreCase(encoding)?new GZIPInputStream(raw):raw; ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[8192]; int n;
                    while ((n = input.read(buffer)) != -1) {
                        if (bytes.size() + n > Engine.MAX_SOURCE) throw new IOException("Page exceeds the preview's 1 MB download limit.");
                        if (Thread.currentThread().isInterrupted() || System.nanoTime() > deadline) throw new IOException("Page request cancelled or timed out.");
                        bytes.write(buffer, 0, n);
                    }
                    data = bytes.toByteArray();
                }
                Charset charset = StandardCharsets.UTF_8;
                for (String parameter : type.split(";")) if (parameter.trim().toLowerCase(Locale.ROOT).startsWith("charset=")) {
                    String value = parameter.trim().substring(8).replace("\"", "").trim();
                    try { charset = Charset.forName(value); } catch (IllegalArgumentException ignored) { }
                }
                String source = new String(data, charset);
                if (mime.equals("text/plain")) source = "<pre>" + escape(source) + "</pre>";
                boolean csp=connection.getHeaderField("Content-Security-Policy")!=null;
                for(PageMarkup.Tag t:PageMarkup.tags(source))if(t.name.equals("meta")&&t.attrs.getOrDefault("http-equiv","").equalsIgnoreCase("content-security-policy"))csp=true;
                StringBuilder css=new StringBuilder();
                if(!csp&&mime.equals("text/html"))for(URI sheet:PageMarkup.stylesheets(uri,source)){
                    if(Thread.currentThread().isInterrupted()||System.nanoTime()>deadline)break;
                    try{css.append(new String(PageAssets.fetch(uri,sheet,false),StandardCharsets.UTF_8)).append('\n');}catch(IOException ignored){/* Page content remains usable when styling fails. */}
                }
                Engine.Document document=Engine.parse(uri, source,css.toString());
                return csp ? document.blockScripts() : document;
            } finally { connection.disconnect(); }
        }
        throw new IOException("Website redirected more than five times.");
    }
    public static String escape(String s) { return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"); }
    private PageLoader() { }
}
