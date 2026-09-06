package io.aster.tests;

import io.aster.engine.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class EngineTests {
    private static int passed;
    private static final URI BASE = URI.create("https://example.org/notes/page");
    private interface Checked { void run() throws Exception; }
    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    private static void test(String name, Checked check) throws Exception { check.run(); passed++; System.out.println("PASS " + name); }
    private static void rejects(Checked action) throws Exception {
        boolean rejected = false;
        try { action.run(); } catch (IllegalArgumentException | java.io.IOException expected) { rejected = true; }
        check(rejected, "Expected an explicit refusal");
    }
    private static Engine.Document doc(String text) { return Engine.parse(BASE, text); }
    public static void main(String[] args) throws Exception {
        test("HTML title, paragraphs and table text", () -> {
            Engine.Document page = doc("<html><head><title>Marcelo &amp; Aster</title><script>hide()</script></head><body><h1>Hello</h1><p>One <b>two</b>.</p><table><tr><td>Three</td></tr></table></body></html>");
            check(page.title.equals("Marcelo & Aster"), "title"); check(page.text().contains("Hello\nOne two."), page.text()); check(!page.text().contains("hide()"), "head leaked");
        });
        test("Raw script/style and Unicode offsets", () -> {
            Engine.Document page = doc("İ<script>'<b>hidden</b></scriptx>still hidden'</script><style>.x{}</style><p>Visible</p>");
            check(!page.text().contains("hidden") && !page.text().contains(".x") && page.text().contains("Visible"), page.text());
        });
        test("Quoted markup, entities and safe relative links", () -> {
            Engine.Document page = doc("<a title='a > b' href='../next?a=1&amp;b=2'>Go &#x1F680;</a><a href='javascript:alert(1)'>Blocked</a>");
            check(page.runs.get(0).link.toString().equals("https://example.org/next?a=1&b=2"), "relative link");
            check(page.text().contains("🚀") && page.runs.get(1).link == null, "entity or scheme");
        });
        test("Inline typography inherits and restores", () -> {
            Engine.Document page = doc("<p style='color:#246810;font-size:22px'>A<b>B</b>C</p><p>D</p>");
            check(page.runs.get(1).style.bold && !page.runs.get(2).style.bold, "bold leaked");
            check(page.runs.get(0).style.color == 0xff246810 && page.runs.get(0).style.size == 22, "style");
            check(page.runs.get(page.runs.size() - 2).style.size == 17, "paragraph style leaked");
        });
        test("Images remain descriptions without subresource requests", () -> {
            check(doc("<img src='http://private.invalid' alt='Aster logo'>").text().equals("[Image: Aster logo]"), "alt text");
        });
        test("Line wrap and clickable text bounds", () -> {
            Engine.Layout layout = Engine.layout(doc("<a href='/game'>alpha beta gamma delta</a>"), 125, (t, s) -> t.codePointCount(0, t.length()) * 9);
            check(layout.items.get(2).y > layout.items.get(0).y, "no wrap");
            Engine.Draw draw = layout.items.get(0); check(layout.hit(draw.x + 1, draw.y + 1).equals(BASE.resolve("/game")), "hit target");
            check(layout.hit(1, 1) == null, "outside hit");
        });
        test("Oversized words and preformatted Unicode", () -> {
            Engine.Layout layout = Engine.layout(doc("<pre>🚀🚀🚀🚀🚀🚀🚀🚀🚀🚀\nnext</pre>"), 100, (t, s) -> t.codePointCount(0, t.length()) * 15);
            for (Engine.Draw item : layout.items) check(!item.text.equals("\ud83d") && item.x + item.width <= 76, "split surrogate or horizontal overflow");
            check(layout.items.get(layout.items.size() - 1).y > layout.items.get(0).y, "pre newline");
        });
        test("Parser size, depth and entity work bounds", () -> {
            rejects(() -> doc(String.join("", Collections.nCopies(70, "<div>"))));
            rejects(() -> doc(new String(new char[Engine.MAX_SOURCE + 1])));
            check(Engine.entities("&#0; &#xD800; &amp;").equals("� � &"), "invalid scalar");
            String ampersands = String.join("", Collections.nCopies(100_000, "&"));
            check(Engine.entities(ampersands).equals(ampersands), "bounded malformed entities");
        });
        test("Malformed HTML corpus terminates without parser crashes", () -> {
            Random random = new Random(94071); String alphabet = "abc<>/!?'\"= &;\n";
            for (int n = 0; n < 500; n++) {
                StringBuilder text = new StringBuilder(); for (int i = 0; i < 350; i++) text.append(alphabet.charAt(random.nextInt(alphabet.length())));
                Engine.layout(doc(text.toString()), 320, (t, s) -> t.length() * 8);
            }
        });
        test("Address validation refuses executable/local/credential URLs", () -> {
            for (String address : new String[]{"javascript:alert(1)", "data:text/html,x", "file:///tmp/test", "ftp://example.com", "https://user:password@example.org", "https://example.com:99999", "a b"}) rejects(() -> PageLoader.address(address));
            check(PageLoader.address("example.org/path").equals(URI.create("https://example.org/path")), "HTTPS default");
            check(PageLoader.address("localhost:8080/a").toString().equals("https://localhost:8080/a"), "host port");
        });
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/redirect") || path.equals("/loop") || path.equals("/bad")) {
                exchange.getResponseHeaders().set("Location", path.equals("/redirect") ? "/page" : path.equals("/bad") ? "file:///etc/passwd" : "/loop");
                exchange.sendResponseHeaders(302, -1); exchange.close(); return;
            }
            String content = path.equals("/plain") ? "<script>literal text</script>" : "<title>Network fixture</title><p>Loaded over HTTP</p>";
            String type = path.equals("/binary") ? "application/octet-stream" : path.equals("/plain") ? "text/plain" : "text/html; charset=UTF-8";
            byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", type); exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes); exchange.close();
        }); server.start();
        URI local = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        try {
            test("Actual HTTP navigation, title and redirect address", () -> {
                Engine.Document page = PageLoader.load(local.resolve("/redirect")); check(page.uri.equals(local.resolve("/page")) && page.title.equals("Network fixture"), "navigation result");
            });
            test("HTTP plaintext is not interpreted as HTML", () -> check(PageLoader.load(local.resolve("/plain")).text().equals("<script>literal text</script>"), "plaintext executed as markup"));
            test("Redirect loops, local schemes and binary downloads refused", () -> {
                rejects(() -> PageLoader.load(local.resolve("/loop"))); rejects(() -> PageLoader.load(local.resolve("/bad"))); rejects(() -> PageLoader.load(local.resolve("/binary")));
            });
        } finally { server.stop(0); }
        System.out.println(passed + " original-engine tests passed.");
    }
}
