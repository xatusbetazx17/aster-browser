package io.aster.engine;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.GZIPInputStream;

/** Bounded, credential-free page assets. Never follows an HTTPS downgrade. */
public final class PageAssets {
    public static final int IMAGE_LIMIT = 2 * 1024 * 1024;
    public static boolean sameOrigin(URI a, URI b) {
        return a != null && b != null && a.getHost() != null && b.getHost() != null
            && a.getScheme().equalsIgnoreCase(b.getScheme()) && a.getHost().equalsIgnoreCase(b.getHost())
            && port(a) == port(b);
    }
    private static int port(URI u) { return u.getPort() >= 0 ? u.getPort() : "https".equalsIgnoreCase(u.getScheme()) ? 443 : 80; }
    public static byte[] fetch(URI page, URI target, boolean image) throws IOException {
        PageLoader.validate(page); PageLoader.validate(target);
        if (!sameOrigin(page, target)) throw new IOException("This preview loads same-origin page assets only.");
        int limit = image ? IMAGE_LIMIT : 65536;
        long deadline = System.nanoTime() + 15_000_000_000L;
        for (int i = 0; i <= 5; i++) {
            if (Thread.currentThread().isInterrupted() || System.nanoTime() > deadline) throw new IOException("Asset load cancelled or timed out.");
            HttpURLConnection c = (HttpURLConnection) target.toURL().openConnection();
            c.setConnectTimeout(5000); c.setReadTimeout(5000); c.setInstanceFollowRedirects(false);
            c.setRequestProperty("User-Agent", "Aster/0.2"); c.setRequestProperty("Accept-Encoding", image ? "identity" : "gzip");
            try {
                int code = c.getResponseCode();
                if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                    URI next = PageLoader.link(target, c.getHeaderField("Location"));
                    if (!sameOrigin(page, next)) throw new IOException("Cross-origin asset redirect refused.");
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
                    return read(in, limit, deadline);
                }
            } finally { c.disconnect(); }
        }
        throw new IOException("Too many asset redirects.");
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
