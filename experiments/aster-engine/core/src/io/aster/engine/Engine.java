package io.aster.engine;

import java.net.URI;
import java.util.*;

/** Aster's deliberately small HTML text/layout engine. No embedded browser or JS VM. */
public final class Engine {
    public static final int MAX_SOURCE = 1_000_000, MAX_RUNS = 20_000, MAX_DEPTH = 64;
    private static final Set<String> BLOCKS = set("p div article section main header footer nav aside h1 h2 h3 h4 h5 h6 ul ol li blockquote pre table tr hr");
    private static final Set<String> VOID = set("br hr img meta link input source area base embed wbr col param");
    private static Set<String> set(String words) { return new HashSet<>(Arrays.asList(words.split(" "))); }

    public static final class Style {
        public final float size;
        public final int color;
        public final boolean bold, italic, pre;
        public Style(float size, int color, boolean bold, boolean italic, boolean pre) {
            this.size = size; this.color = color; this.bold = bold; this.italic = italic; this.pre = pre;
        }
    }
    public static final Style DEFAULT = new Style(17, 0xff253446, false, false, false);
    public static final class Run {
        public final String text;
        public final Style style;
        public final URI link;
        public final boolean newline;
        Run(String text, Style style, URI link, boolean newline) {
            this.text = text; this.style = style; this.link = link; this.newline = newline;
        }
    }
    public static final class Document {
        public final URI uri;
        public final String title;
        public final List<Run> runs;
        Document(URI uri, String title, List<Run> runs) {
            this.uri = uri; this.title = title; this.runs = Collections.unmodifiableList(runs);
        }
        public String text() {
            StringBuilder out = new StringBuilder();
            for (Run run : runs) out.append(run.newline ? "\n" : run.text);
            return out.toString().trim();
        }
    }
    private static final class Frame {
        final String tag; final Style style; final URI link;
        Frame(String tag, Style style, URI link) { this.tag = tag; this.style = style; this.link = link; }
    }

    public static Document parse(URI uri, String source) {
        if (source.length() > MAX_SOURCE) throw new IllegalArgumentException("Page exceeds the preview's 1 MB text limit.");
        List<Run> runs = new ArrayList<>();
        List<Frame> stack = new ArrayList<>();
        stack.add(new Frame("root", DEFAULT, null));
        // ASCII folding keeps source offsets intact (Unicode case folding can change length).
        StringBuilder folded = new StringBuilder(source.length());
        for (int i = 0; i < source.length(); i++) { char c = source.charAt(i); folded.append(c >= 'A' && c <= 'Z' ? (char) (c + 32) : c); }
        String lower = folded.toString(), title = "Untitled page";
        int pos = 0;
        while (pos < source.length()) {
            Frame current = stack.get(stack.size() - 1);
            if (source.startsWith("<!--", pos)) {
                int end = source.indexOf("-->", pos + 4); pos = end < 0 ? source.length() : end + 3; continue;
            }
            if (source.charAt(pos) != '<' || pos + 1 >= source.length() ||
                    !(Character.isLetter(source.charAt(pos + 1)) || "/!?".indexOf(source.charAt(pos + 1)) >= 0)) {
                int end = source.indexOf('<', pos + 1); if (end < 0) end = source.length();
                add(runs, entities(source.substring(pos, end)), current); pos = end; continue;
            }
            int end = tagEnd(source, pos + 1);
            if (end < 0) { add(runs, entities(source.substring(pos)), current); break; }
            String inside = source.substring(pos + 1, end).trim(); pos = end + 1;
            if (inside.isEmpty() || inside.charAt(0) == '!' || inside.charAt(0) == '?') continue;
            boolean closing = inside.charAt(0) == '/';
            if (closing) inside = inside.substring(1).trim();
            int nameEnd = 0;
            while (nameEnd < inside.length() && (Character.isLetterOrDigit(inside.charAt(nameEnd)) || inside.charAt(nameEnd) == '-')) nameEnd++;
            String tag = inside.substring(0, nameEnd).toLowerCase(Locale.ROOT);
            if (tag.isEmpty()) continue;
            if (closing) {
                if (BLOCKS.contains(tag)) newline(runs, current);
                for (int j = stack.size() - 1; j > 0; j--) if (stack.get(j).tag.equals(tag)) {
                    while (stack.size() > j) stack.remove(stack.size() - 1); break;
                }
                continue;
            }
            // Raw content is skipped, never interpreted as markup or executed.
            if (set("script style title head template iframe object").contains(tag)) {
                int stop = rawClose(lower, tag, pos);
                if (tag.equals("title") && stop >= 0) title = entities(source.substring(pos, stop)).replaceAll("\\s+", " ").trim();
                if (tag.equals("head") && stop >= 0) {
                    int t = lower.indexOf("<title", pos);
                    if (t >= 0 && t < stop) {
                        int ts = tagEnd(source, t + 1), te = rawClose(lower, "title", ts + 1);
                        if (ts >= 0 && te >= ts && te < stop) title = entities(source.substring(ts + 1, te)).replaceAll("\\s+", " ").trim();
                    }
                }
                int closeEnd = stop < 0 ? -1 : tagEnd(source, stop + 2);
                pos = closeEnd < 0 ? source.length() : closeEnd + 1; continue;
            }
            Map<String, String> attrs = attributes(inside.substring(nameEnd));
            if (BLOCKS.contains(tag) || tag.equals("br")) newline(runs, current);
            Style style = style(tag, attrs.get("style"), current.style);
            URI link = current.link;
            if (tag.equals("a")) link = PageLoader.link(uri, attrs.get("href"));
            if (link != null) style = new Style(style.size, 0xff2469ad, style.bold, style.italic, style.pre);
            Frame frame = new Frame(tag, style, link);
            if (tag.equals("li")) add(runs, "• ", frame);
            if (tag.equals("img")) add(runs, "[Image: " + attrs.getOrDefault("alt", "no description") + "]", frame);
            if (!VOID.contains(tag) && !inside.endsWith("/")) {
                if (stack.size() >= MAX_DEPTH) throw new IllegalArgumentException("Page nesting exceeds the preview limit.");
                stack.add(frame);
            }
        }
        if (title.length() > 160) title = title.substring(0, 160);
        return new Document(uri, title.isEmpty() ? "Untitled page" : title, runs);
    }
    private static int rawClose(String lower, String tag, int from) {
        int p = from;
        while ((p = lower.indexOf("</" + tag, p)) >= 0) {
            int next = p + tag.length() + 2;
            if (next == lower.length() || Character.isWhitespace(lower.charAt(next)) || lower.charAt(next) == '>') return p;
            p = next;
        }
        return -1;
    }
    private static int tagEnd(String text, int from) {
        char quote = 0;
        for (int i = from; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quote != 0) { if (c == quote) quote = 0; }
            else if (c == '\'' || c == '"') quote = c;
            else if (c == '>') return i;
        }
        return -1;
    }
    private static Map<String, String> attributes(String text) {
        Map<String, String> attrs = new HashMap<>();
        int i = 0;
        while (i < text.length()) {
            while (i < text.length() && (Character.isWhitespace(text.charAt(i)) || text.charAt(i) == '/')) i++;
            int start = i;
            while (i < text.length() && !Character.isWhitespace(text.charAt(i)) && text.charAt(i) != '=') i++;
            String key = text.substring(start, i).toLowerCase(Locale.ROOT), value = "";
            while (i < text.length() && Character.isWhitespace(text.charAt(i))) i++;
            if (i < text.length() && text.charAt(i) == '=') {
                i++; while (i < text.length() && Character.isWhitespace(text.charAt(i))) i++;
                if (i < text.length() && (text.charAt(i) == '\'' || text.charAt(i) == '"')) {
                    char q = text.charAt(i++); start = i;
                    while (i < text.length() && text.charAt(i) != q) i++;
                    value = text.substring(start, i); if (i < text.length()) i++;
                } else {
                    start = i; while (i < text.length() && !Character.isWhitespace(text.charAt(i))) i++;
                    value = text.substring(start, i);
                }
            }
            if (!key.isEmpty() && !attrs.containsKey(key)) attrs.put(key, entities(value));
        }
        return attrs;
    }
    private static Style style(String tag, String css, Style parent) {
        float size = parent.size; int color = parent.color;
        boolean bold = parent.bold || set("b strong h1 h2 h3 h4 h5 h6 th").contains(tag);
        boolean italic = parent.italic || tag.equals("i") || tag.equals("em");
        if (tag.equals("h1")) size = 32; else if (tag.equals("h2")) size = 25; else if (tag.equals("h3")) size = 21;
        if (css != null) for (String declaration : css.split(";")) {
            String[] parts = declaration.split(":", 2); if (parts.length != 2) continue;
            String k = parts[0].trim().toLowerCase(Locale.ROOT), v = parts[1].trim().toLowerCase(Locale.ROOT);
            try {
                if (k.equals("color") && v.matches("#[0-9a-f]{6}")) color = 0xff000000 | Integer.parseInt(v.substring(1), 16);
                if (k.equals("font-size") && v.matches("[0-9]{1,2}px")) size = Math.max(10, Math.min(48, Float.parseFloat(v.substring(0, v.length() - 2))));
                if (k.equals("font-weight") && (v.equals("bold") || v.equals("700"))) bold = true;
                if (k.equals("font-style") && v.equals("italic")) italic = true;
            } catch (NumberFormatException ignored) { /* Unsupported declarations retain inherited values. */ }
        }
        return new Style(size, color, bold, italic, parent.pre || tag.equals("pre"));
    }
    private static void add(List<Run> runs, String text, Frame frame) {
        if (text.isEmpty()) return;
        if (!frame.style.pre) text = text.replaceAll("[\\t\\n\\r\\f ]+", " ");
        if (runs.size() >= MAX_RUNS) throw new IllegalArgumentException("Page has too many text runs.");
        runs.add(new Run(text, frame.style, frame.link, false));
    }
    private static void newline(List<Run> runs, Frame frame) {
        if (!runs.isEmpty() && !runs.get(runs.size() - 1).newline) {
            if (runs.size() >= MAX_RUNS) throw new IllegalArgumentException("Page has too many text runs.");
            runs.add(new Run("", frame.style, null, true));
        }
    }
    public static String entities(String text) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i); int end = -1;
            if (c == '&') for (int j = i + 1; j < Math.min(text.length(), i + 16); j++) if (text.charAt(j) == ';') { end = j; break; }
            if (end > i && end - i < 16) {
                String name = text.substring(i + 1, end); Integer cp = null;
                switch (name) {
                    case "amp": cp = 38; break; case "lt": cp = 60; break; case "gt": cp = 62; break;
                    case "quot": cp = 34; break; case "apos": cp = 39; break; case "nbsp": cp = 160; break;
                    default:
                        try { if (name.startsWith("#x") || name.startsWith("#X")) cp = Integer.parseInt(name.substring(2), 16);
                              else if (name.startsWith("#")) cp = Integer.parseInt(name.substring(1)); }
                        catch (NumberFormatException ignored) { }
                }
                if (cp != null) {
                    if (cp <= 0 || cp > 0x10ffff || cp >= 0xd800 && cp <= 0xdfff) cp = 0xfffd;
                    out.appendCodePoint(cp); i = end; continue;
                }
            }
            out.append(c);
        }
        return out.toString();
    }
    public interface Measure { float width(String text, Style style); }
    public static final class Draw {
        public final String text; public final Style style; public final URI link;
        public final float x, y, width, height;
        Draw(String text, Style style, URI link, float x, float y, float width) {
            this.text = text; this.style = style; this.link = link; this.x = x; this.y = y;
            this.width = width; this.height = style.size * 1.45f;
        }
    }
    public static final class Layout {
        public final List<Draw> items; public final float height;
        Layout(List<Draw> items, float height) { this.items = Collections.unmodifiableList(items); this.height = height; }
        public URI hit(float x, float y) {
            for (Draw d : items) if (d.link != null && x >= d.x && x <= d.x + d.width && y >= d.y && y <= d.y + d.height) return d.link;
            return null;
        }
    }
    public static Layout layout(Document doc, float viewportWidth, Measure measure) {
        float right = Math.max(100, Math.min(10_000, viewportWidth)) - 24, x = 24, y = 24, line = 25;
        List<Draw> items = new ArrayList<>();
        boolean pendingSpace = false;
        for (Run run : doc.runs) {
            if (run.newline) { y += line + 10; x = 24; line = 25; pendingSpace = false; continue; }
            String text = run.text; int at = 0;
            while (at < text.length()) {
                if (items.size() >= 100_000) throw new IllegalArgumentException("Page layout exceeds the preview limit.");
                int cp = text.codePointAt(at);
                if (Character.isWhitespace(cp)) {
                    at += Character.charCount(cp);
                    if (run.style.pre && cp == '\n') { y += line; x = 24; line = 25; pendingSpace = false; }
                    else if (run.style.pre) x += measure.width(cp == '\t' ? "    " : " ", run.style);
                    else pendingSpace = true;
                    continue;
                }
                int end = at;
                while (end < text.length() && !Character.isWhitespace(text.codePointAt(end))) end += Character.charCount(text.codePointAt(end));
                String word = text.substring(at, end);
                float width = measure.width(word, run.style);
                float space = pendingSpace && x > 24 ? measure.width(" ", run.style) : 0;
                if (x > 24 && x + space + width > right) { y += line; x = 24; line = 25; space = 0; }
                x += space; pendingSpace = false;
                // Split oversized words on code-point boundaries without quadratic prefix measurements.
                if (width > right - 24) {
                    for (int i = 0; i < word.length();) {
                        int n = Character.charCount(word.codePointAt(i)); String unit = word.substring(i, i + n); i += n;
                        float w = measure.width(unit, run.style);
                        if (x > 24 && x + w > right) { y += line; x = 24; line = 25; }
                        if (items.size() >= 100_000) throw new IllegalArgumentException("Page layout exceeds the preview limit.");
                        items.add(new Draw(unit, run.style, run.link, x, y, w)); x += w; line = Math.max(line, run.style.size * 1.45f);
                    }
                } else {
                    items.add(new Draw(word, run.style, run.link, x, y, width)); x += width; line = Math.max(line, run.style.size * 1.45f);
                }
                at = end;
            }
        }
        return new Layout(items, y + line + 24);
    }
    private Engine() { }
}
