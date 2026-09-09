package io.aster.engine;

import java.io.*;
import java.net.*;
import java.nio.charset.*;
import java.util.Locale;

/** Top-level text navigation only. No cookies, credentials, subresources or external handlers. */
public final class PageLoader {
    public static final URI HOME = URI.create("aster://welcome");
    public static final String WELCOME = "<html><head><title>Aster · Engine preview</title></head><body>"
        + "<p style='color:#247878;font-weight:bold'>A S T E R</p><h1>Your space to explore.</h1>"
        + "<p>A first look at Aster's own rendering engine.</p>"
        + "<h2>Start with something simple</h2><p>Enter an HTTPS address above or try "
        + "<a href='https://example.com'>Example Domain</a>. Basic text pages and links work here.</p>"
        + "<h2>Built for a different direction</h2><p>This preview paints pages with Aster code. "
        + "It does not embed Chrome, Chromium, Firefox, WebKit or Android WebView.</p>"
        + "<h2>Still growing</h2><p>JavaScript apps, images, forms, video, cloud gaming and Prime Video "
        + "are not supported yet. This is an engine development preview, not the full Aster browser.</p>"
        + "<p><b>Tip:</b> use Back, Forward, Home and bookmarks to explore text websites.</p></body></html>";

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
    public static Engine.Document load(URI initial) throws IOException {
        if (HOME.equals(initial)) return Engine.parse(HOME, WELCOME);
        validate(initial);
        URI uri = initial; long deadline = System.nanoTime() + 25_000_000_000L;
        for (int redirects = 0; redirects <= 5; redirects++) {
            if (Thread.currentThread().isInterrupted() || System.nanoTime() > deadline) throw new IOException("Page request cancelled or timed out.");
            HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
            connection.setInstanceFollowRedirects(false); connection.setConnectTimeout(8000); connection.setReadTimeout(8000);
            connection.setRequestProperty("User-Agent", "AsterEnginePreview/0.1");
            connection.setRequestProperty("Accept", "text/html,text/plain;q=0.9");
            connection.setRequestProperty("Accept-Encoding", "identity");
            try {
                int code = connection.getResponseCode();
                if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                    String target = connection.getHeaderField("Location");
                    URI next = target == null ? null : link(uri, target);
                    if (next == null || ("https".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(next.getScheme()))) throw new IOException("Unsafe or unsupported redirect refused.");
                    uri = next; continue;
                }
                if (code < 200 || code >= 300) throw new IOException("Website returned HTTP " + code + ".");
                String type = connection.getContentType(); if (type == null) type = "";
                String mime = type.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
                if (!mime.equals("text/html") && !mime.equals("text/plain")) throw new IOException("This preview opens HTML/text pages only (received " + mime + ").");
                if (connection.getContentLengthLong() > Engine.MAX_SOURCE) throw new IOException("Page exceeds the preview's 1 MB download limit.");
                byte[] data;
                try (InputStream input = connection.getInputStream(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
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
                return Engine.parse(uri, source);
            } finally { connection.disconnect(); }
        }
        throw new IOException("Website redirected more than five times.");
    }
    public static String escape(String s) { return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"); }
    private PageLoader() { }
}
