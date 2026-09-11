package io.aster.desktop;

import io.aster.engine.PageLoader;
import io.aster.engine.SiteData;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.regex.*;

/** Bounded, user-initiated direct downloads. No shell execution or automatic opening. */
final class DownloadManager implements AutoCloseable {
    static final long MAX_BYTES = 2L * 1024 * 1024 * 1024;
    enum State { STARTING, DOWNLOADING, FINALIZING, CANCELLING, COMPLETE, CANCELLED, FAILED }
    final class Transfer {
        final URI source;
        final SiteData.Request context;
        final Path target;
        volatile State state = State.STARTING;
        volatile long received, total = -1;
        volatile String error = "";
        private volatile boolean cancelled;
        private long lastNotice;
        Transfer(URI source, Path target) { this.source = source; this.target = target;this.context=siteData.request(source,false,"GET").explicitDownload(); }
        boolean finished() { return state == State.COMPLETE || state == State.CANCELLED || state == State.FAILED; }
        synchronized void cancel() {
            if (finished()) return;
            cancelled = true;
            state = State.CANCELLING;
            // The worker always runs its cleanup, including when cancelled before it starts.
            // Read timeouts bound cancellation while a remote server is silent.
            notice(this);
        }
        private void check(long deadline) throws IOException {
            if (cancelled || Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Download cancelled.");
            if (System.nanoTime() > deadline) throw new IOException("Download exceeded the 30 minute transfer limit.");
        }
    }
    private final java.util.List<Transfer> transfers = new ArrayList<>();
    private final ExecutorService workers = Executors.newFixedThreadPool(2, r -> {
        Thread thread = new Thread(r, "aster-download");
        // Allow cleanup to finish after the window closes (read timeout is ten seconds).
        thread.setDaemon(false); return thread;
    });
    private final Consumer<Transfer> listener;
    private final long limit;
    private final SiteData siteData;
    private boolean closed;
    DownloadManager(Consumer<Transfer> listener) { this(listener, MAX_BYTES); }
    DownloadManager(Consumer<Transfer> listener, long limit) { this(listener,limit,new SiteData()); }
    DownloadManager(Consumer<Transfer> listener,long limit,SiteData data){this.listener=listener;this.limit=limit;this.siteData=data;}
    synchronized Transfer start(URI source, Path target) throws IOException {
        PageLoader.validate(source);
        if (closed) throw new IOException("The download manager is closed.");
        if (transfers.size() >= 30) throw new IOException("Clear finished downloads before starting more (30 entries maximum).");
        if (transfers.stream().filter(t -> !t.finished()).count() >= 2) throw new IOException("Two downloads are already active. Wait or cancel one first.");
        Path destination = target.toAbsolutePath().normalize();
        if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) throw new FileAlreadyExistsException("Choose a new filename; Aster does not overwrite existing files.");
        if (destination.getParent() == null || !Files.isDirectory(destination.getParent())) throw new IOException("Choose an existing destination folder.");
        if (transfers.stream().anyMatch(t -> !t.finished() && t.target.equals(destination))) throw new IOException("A download is already using that filename.");
        Transfer transfer = new Transfer(source, destination); transfers.add(transfer);
        workers.submit(() -> download(transfer));
        notice(transfer); return transfer;
    }
    synchronized java.util.List<Transfer> snapshot() { return new ArrayList<>(transfers); }
    synchronized int activeCount() { return (int) transfers.stream().filter(t -> !t.finished()).count(); }
    synchronized void clearFinished() { transfers.removeIf(Transfer::finished); }
    public synchronized void close() {
        if (closed) return;
        closed = true; for (Transfer transfer : transfers) transfer.cancel();
        workers.shutdown(); // Workers remove incomplete files before exiting.
    }
    private void notice(Transfer transfer) { if (listener != null) listener.accept(transfer); }
    static URI redirect(URI from, String location) throws IOException {
        URI next = location == null ? null : PageLoader.link(from, location);
        if (next == null || ("https".equalsIgnoreCase(from.getScheme()) && !"https".equalsIgnoreCase(next.getScheme())))
            throw new IOException("Unsafe or unsupported download redirect refused.");
        return next;
    }
    private void download(Transfer transfer) {
        Path partial = null;
        State outcome = State.FAILED;
        long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(30);
        try {
            URI uri = transfer.source;
            for (int redirects = 0; ; redirects++) {
                transfer.check(deadline);
                HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
                connection.setInstanceFollowRedirects(false); connection.setConnectTimeout(8000); connection.setReadTimeout(10000);
                connection.setRequestProperty("User-Agent", "AsterEnginePreview/0.8");
                connection.setRequestProperty("Accept", "*/*"); connection.setRequestProperty("Accept-Encoding", "identity");
                transfer.context.prepare(connection);
                try {
                    int code = connection.getResponseCode();transfer.context.receive(connection);
                    if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                        if (redirects >= 5) throw new IOException("Download redirected more than five times.");
                        uri = redirect(uri, connection.getHeaderField("Location")); continue;
                    }
                    if (code < 200 || code >= 300 || code == 206) throw new IOException("Website returned HTTP " + code + ".");
                    String encoding = connection.getContentEncoding();
                    if (encoding != null && !encoding.equalsIgnoreCase("identity")) throw new IOException("Server returned unsupported HTTP content encoding: " + encoding);
                    transfer.total = connection.getContentLengthLong();
                    if (transfer.total > limit) throw new IOException("File exceeds the " + bytes(limit) + " download limit.");
                    transfer.check(deadline);
                    partial = Files.createTempFile(transfer.target.getParent(), ".aster-", ".part");
                    transfer.state = State.DOWNLOADING; notice(transfer);
                    try (InputStream input = connection.getInputStream(); OutputStream output = Files.newOutputStream(partial)) {
                        byte[] buffer = new byte[64 * 1024]; int n;
                        while (true) {
                            transfer.check(deadline); n = input.read(buffer); if (n == -1) break;
                            transfer.check(deadline);
                            if (n > limit - transfer.received) throw new IOException("File exceeds the " + bytes(limit) + " download limit.");
                            output.write(buffer, 0, n); transfer.received += n;
                            if (System.nanoTime() - transfer.lastNotice > 150_000_000L) { transfer.lastNotice = System.nanoTime(); notice(transfer); }
                        }
                    }
                    if (transfer.total >= 0 && transfer.received != transfer.total) throw new IOException("Download ended before the complete file arrived.");
                    transfer.check(deadline); transfer.state = State.FINALIZING; notice(transfer);
                    publish(partial, transfer, deadline);
                    outcome = State.COMPLETE;
                    break;
                } finally { connection.disconnect(); }
            }
        } catch (Exception e) {
            transfer.error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            outcome = transfer.cancelled ? State.CANCELLED : State.FAILED;
        } finally {
            if (partial != null) try { Files.deleteIfExists(partial); } catch (IOException e) {
                transfer.error = "Could not remove partial file " + partial + ": " + e.getMessage(); outcome = State.FAILED;
            }
            transfer.state = outcome; notice(transfer);
        }
    }
    private void publish(Path partial, Transfer transfer, long deadline) throws IOException {
        // A new hard link publishes complete bytes atomically and fails if the name exists.
        // Filesystems without links use an exclusive CREATE_NEW copy instead; neither overwrites.
        synchronized (transfer) {
            transfer.check(deadline);
            try { Files.createLink(transfer.target, partial); return; }
            catch (FileAlreadyExistsException e) { throw e; }
            catch (UnsupportedOperationException | IOException noLinks) { /* exclusive-copy fallback below */ }
        }
        boolean created = false, complete = false;
        try (InputStream input = Files.newInputStream(partial)) {
            try (OutputStream output = Files.newOutputStream(transfer.target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                created = true; byte[] buffer = new byte[64 * 1024]; int n;
                while ((n = input.read(buffer)) != -1) { transfer.check(deadline); output.write(buffer, 0, n); }
            }
            synchronized (transfer) { transfer.check(deadline); complete = true; }
        } finally { if (created && !complete) Files.deleteIfExists(transfer.target); }
    }
    static String filename(URI uri, String disposition) {
        String name = null;
        if (disposition != null && disposition.length() < 8192) {
            Matcher extended = Pattern.compile("(?i)(?:^|;)\\s*filename\\*\\s*=\\s*UTF-8'[^']*'([^;]+)").matcher(disposition);
            if (extended.find()) try { name = URLDecoder.decode(extended.group(1).trim().replace("+", "%2B"), "UTF-8"); } catch (Exception ignored) { }
            if (name == null) {
                Matcher regular = Pattern.compile("(?i)(?:^|;)\\s*filename\\s*=\\s*(?:\"([^\"]*)\"|([^;]*))").matcher(disposition);
                if (regular.find()) name = regular.group(1) != null ? regular.group(1) : regular.group(2).trim();
            }
        }
        if (name == null || name.isEmpty()) { name = uri.getPath(); if (name == null || name.endsWith("/")) name = "download.bin"; }
        name = name.replace('\\', '/'); name = name.substring(name.lastIndexOf('/') + 1);
        name = name.replaceAll("[\\p{Cntrl}\\p{Cf}<>:\"/\\\\|?*]", "_").replaceAll("^[ .]+|[ .]+$", "");
        if (name.isEmpty()) name = "download.bin";
        if (name.matches("(?i)(CON|PRN|AUX|NUL|COM[0-9]|LPT[0-9])(?:\\..*)?")) name = "_" + name;
        if (name.length() > 140) name = name.substring(0, name.offsetByCodePoints(0, Math.min(120, name.codePointCount(0, name.length()))));
        return name;
    }
    static String bytes(long count) {
        if (count < 1024) return count + " B";
        if (count < 1024 * 1024) return String.format(Locale.ROOT, "%.1f KiB", count / 1024.0);
        if (count < 1024L * 1024 * 1024) return String.format(Locale.ROOT, "%.1f MiB", count / (1024.0 * 1024));
        return String.format(Locale.ROOT, "%.1f GiB", count / (1024.0 * 1024 * 1024));
    }
}
