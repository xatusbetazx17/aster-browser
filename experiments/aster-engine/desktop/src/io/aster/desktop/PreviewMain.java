package io.aster.desktop;

import io.aster.engine.*;
import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.net.URI;
import java.util.*;
import java.util.concurrent.*;
import java.util.prefs.Preferences;
import javax.imageio.ImageIO;

/** Native desktop window, with Aster display lists painted by Java2D (no HTML widget). */
public final class PreviewMain {
    private final JFrame window = new JFrame("Aster · Original engine preview");
    private final JTabbedPane tabs = new JTabbedPane();
    private final JTextField address = new JTextField();
    private final JLabel status = new JLabel("Engine preview · Basic text websites only");
    private final Preferences preferences = Preferences.userRoot().node("io/aster/engine-preview");
    private final ExecutorService network = Executors.newFixedThreadPool(2, r -> { Thread t = new Thread(r, "aster-navigation"); t.setDaemon(true); return t; });
    private final JButton back = button("←", "Back", () -> move(-1));
    private final JButton forward = button("→", "Forward", () -> move(1));

    private PreviewMain() {
        window.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        window.addWindowListener(new WindowAdapter() { public void windowClosed(WindowEvent event) { network.shutdownNow(); } });
        JPanel top = new JPanel(new BorderLayout(8, 8));
        top.setBorder(BorderFactory.createEmptyBorder(12, 14, 10, 14));
        top.setBackground(new Color(0xeaf3f1));
        JPanel navigation = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0)); navigation.setOpaque(false);
        navigation.add(back); navigation.add(forward);
        navigation.add(button("Home", "Aster home", () -> load(current(), PageLoader.HOME, -1)));
        top.add(navigation, BorderLayout.WEST);
        address.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 16)); address.setToolTipText("Enter an HTTP or HTTPS website address");
        address.getAccessibleContext().setAccessibleName("Website address");
        address.addActionListener(event -> { try { load(current(), PageLoader.address(address.getText()), -1); }
            catch (IllegalArgumentException e) { status.setText(e.getMessage()); } });
        top.add(address, BorderLayout.CENTER);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0)); actions.setOpaque(false);
        actions.add(button("+", "New tab", this::newTab));
        actions.add(button("Close", "Close current tab", this::closeTab));
        actions.add(button("★", "Bookmarks", this::bookmarks));
        top.add(actions, BorderLayout.EAST);
        window.add(top, BorderLayout.NORTH); window.add(tabs, BorderLayout.CENTER);
        status.setBorder(BorderFactory.createEmptyBorder(9, 18, 9, 18)); window.add(status, BorderLayout.SOUTH);
        tabs.addChangeListener(event -> sync());
        bind("control L", () -> { address.requestFocusInWindow(); address.selectAll(); });
        bind("control T", this::newTab); bind("control W", this::closeTab);
        bind("alt LEFT", () -> move(-1)); bind("alt RIGHT", () -> move(1));
        bind("F11", () -> window.setExtendedState(window.getExtendedState() == JFrame.MAXIMIZED_BOTH ? JFrame.NORMAL : JFrame.MAXIMIZED_BOTH));
        window.setSize(1100, 780); window.setMinimumSize(new Dimension(720, 420)); window.setLocationByPlatform(true);
        newTab();
    }
    private JButton button(String label, String name, Runnable action) {
        JButton button = new JButton(label); button.setToolTipText(name); button.getAccessibleContext().setAccessibleName(name);
        button.addActionListener(event -> action.run()); return button;
    }
    private void bind(String stroke, Runnable action) {
        window.getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(stroke), stroke);
        window.getRootPane().getActionMap().put(stroke, new AbstractAction() { public void actionPerformed(ActionEvent event) { action.run(); } });
    }
    private final class Tab extends JScrollPane {
        final PageCanvas canvas = new PageCanvas(); final java.util.List<URI> history = new ArrayList<>();
        int index = -1, generation; Future<?> pending; boolean closed; String message = "";
        Tab() { setViewportView(canvas); getVerticalScrollBar().setUnitIncrement(28); canvas.navigate = uri -> load(this, uri, -1); }
    }
    private Tab current() { return (Tab) tabs.getSelectedComponent(); }
    private void newTab() {
        if (tabs.getTabCount() >= 20) { status.setText("The preview supports up to 20 tabs."); return; }
        Tab tab = new Tab(); tabs.addTab("New tab", tab); tabs.setSelectedComponent(tab); load(tab, PageLoader.HOME, -1);
    }
    private void closeTab() {
        Tab tab = current(); if (tab == null) return;
        tab.closed = true; tab.generation++; if (tab.pending != null) tab.pending.cancel(true); tabs.remove(tab);
        if (tabs.getTabCount() == 0) newTab();
    }
    private void move(int delta) {
        Tab tab = current(); if (tab == null) return;
        int next = tab.index + delta;
        if (next >= 0 && next < tab.history.size()) load(tab, tab.history.get(next), next);
    }
    private void load(Tab tab, URI uri, int historyIndex) {
        if (tab == null || tab.closed) return;
        if (tab.pending != null) tab.pending.cancel(true);
        int generation = ++tab.generation; tab.message = "Opening " + uri + "…"; sync();
        tab.pending = network.submit(() -> {
            try {
                Engine.Document document = PageLoader.load(uri);
                SwingUtilities.invokeLater(() -> {
                    if (tab.closed || tab.generation != generation || !window.isDisplayable()) return;
                    tab.canvas.setDocument(document);
                    if (historyIndex >= 0) tab.index = historyIndex;
                    else {
                        while (tab.history.size() > tab.index + 1) tab.history.remove(tab.history.size() - 1);
                        tab.history.add(document.uri);
                        if (tab.history.size() > 100) tab.history.remove(0);
                        tab.index = tab.history.size() - 1;
                    }
                    tabs.setTitleAt(tabs.indexOfComponent(tab), document.title.length() > 26 ? document.title.substring(0, 26) + "…" : document.title);
                    tab.message = "Basic HTML/text · No JavaScript, video or DRM"; sync();
                    tab.getVerticalScrollBar().setValue(0);
                });
            } catch (Exception e) { SwingUtilities.invokeLater(() -> {
                if (!tab.closed && tab.generation == generation) { tab.message = "Could not open page: " + e.getMessage(); sync(); }
            }); }
        });
    }
    private void sync() {
        Tab tab = current(); if (tab == null) return;
        if (tab.canvas.document != null) address.setText(tab.canvas.document.uri.toString());
        status.setText(tab.message); back.setEnabled(tab.index > 0); forward.setEnabled(tab.index + 1 < tab.history.size());
    }
    private void bookmarks() {
        JPopupMenu menu = new JPopupMenu(); JMenuItem save = new JMenuItem("Bookmark this page");
        save.addActionListener(event -> {
            Tab tab = current(); if (tab == null || tab.canvas.document == null) return;
            String url = tab.canvas.document.uri.toString();
            int count = preferences.getInt("count", 0);
            for (int i = 0; i < count; i++) if (preferences.get("url" + i, "").equals(url)) return;
            if (count >= 30) { status.setText("The preview supports up to 30 bookmarks."); return; }
            preferences.put("url" + count, url); preferences.put("title" + count, tab.canvas.document.title); preferences.putInt("count", count + 1);
        }); menu.add(save); menu.addSeparator();
        for (int i = 0; i < Math.min(30, preferences.getInt("count", 0)); i++) {
            final String url = preferences.get("url" + i, "");
            JMenuItem item = new JMenuItem(preferences.get("title" + i, url)); item.setToolTipText(url);
            item.addActionListener(event -> { try { load(current(), PageLoader.address(url), -1); } catch (IllegalArgumentException e) { status.setText(e.getMessage()); } });
            menu.add(item);
        }
        menu.addSeparator(); JMenuItem clear = new JMenuItem("Clear preview bookmarks");
        clear.addActionListener(event -> {
            if (JOptionPane.showConfirmDialog(window, "Remove the preview's saved bookmarks?", "Clear bookmarks", JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION) {
                try { preferences.clear(); } catch (Exception e) { status.setText("Could not clear bookmarks: " + e.getMessage()); }
            }
        }); menu.add(clear); menu.show(window.getRootPane(), window.getWidth() - 310, 65);
    }
    static final class PageCanvas extends JPanel implements Scrollable {
        Engine.Document document; Engine.Layout layout; int layoutWidth = -1;
        java.util.function.Consumer<URI> navigate = uri -> { };
        PageCanvas() {
            setBackground(Color.WHITE); setFocusable(true);
            getAccessibleContext().setAccessibleName("Aster page");
            addMouseListener(new MouseAdapter() { public void mouseClicked(MouseEvent e) {
                if (layout != null) { URI uri = layout.hit(e.getX(), e.getY()); if (uri != null) navigate.accept(uri); }
            }});
            addMouseMotionListener(new MouseMotionAdapter() { public void mouseMoved(MouseEvent e) {
                URI uri = layout == null ? null : layout.hit(e.getX(), e.getY());
                setCursor(Cursor.getPredefinedCursor(uri == null ? Cursor.DEFAULT_CURSOR : Cursor.HAND_CURSOR));
                setToolTipText(uri == null ? null : uri.toString());
            }});
        }
        void setDocument(Engine.Document doc) { document = doc; layoutWidth = -1; getAccessibleContext().setAccessibleDescription(doc.text()); revalidate(); repaint(); }
        static Font font(Engine.Style s) { return new Font(s.pre ? Font.MONOSPACED : Font.SANS_SERIF, (s.bold ? Font.BOLD : 0) | (s.italic ? Font.ITALIC : 0), Math.round(s.size)); }
        void ensureLayout() {
            if (document != null && (layout == null || layoutWidth != getWidth())) {
                layoutWidth = getWidth();
                try { layout = Engine.layout(document, getWidth(), (text, style) -> getFontMetrics(font(style)).stringWidth(text)); }
                catch (IllegalArgumentException e) { document = Engine.parse(PageLoader.HOME, "<h1>Page is too complex</h1><p>" + PageLoader.escape(e.getMessage()) + "</p>"); layout = Engine.layout(document, getWidth(), (t, s) -> getFontMetrics(font(s)).stringWidth(t)); }
                revalidate();
            }
        }
        protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics); ensureLayout(); if (layout == null) return;
            Graphics2D g = (Graphics2D) graphics.create(); g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            Rectangle clip = g.getClipBounds();
            for (Engine.Draw draw : layout.items) {
                if (clip != null && (draw.y + draw.height < clip.y || draw.y > clip.y + clip.height)) continue;
                g.setFont(font(draw.style)); g.setColor(new Color(draw.style.color, true));
                g.drawString(draw.text, draw.x, draw.y + draw.style.size);
                if (draw.link != null) g.drawLine((int) draw.x, (int) (draw.y + draw.style.size + 2), (int) (draw.x + draw.width), (int) (draw.y + draw.style.size + 2));
            }
            g.dispose();
        }
        public Dimension getPreferredSize() { return new Dimension(800, layout == null ? 650 : (int) Math.ceil(layout.height)); }
        public Dimension getPreferredScrollableViewportSize() { return new Dimension(800, 650); }
        public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) { return 28; }
        public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) { return Math.max(28, visible.height - 30); }
        public boolean getScrollableTracksViewportWidth() { return true; }
        public boolean getScrollableTracksViewportHeight() { return false; }
    }
    private static void renderTest(String output) throws Exception {
        PageCanvas canvas = new PageCanvas(); canvas.setSize(1000, 720); canvas.setDocument(Engine.parse(PageLoader.HOME, PageLoader.WELCOME));
        BufferedImage image = new BufferedImage(1000, 720, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics(); canvas.paint(graphics); graphics.dispose();
        if (canvas.layout == null || canvas.layout.items.size() < 60 || canvas.layout.items.stream().noneMatch(d -> d.link != null)) throw new AssertionError("Page did not render");
        int ink = 0; for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++) if ((image.getRGB(x, y) & 0xffffff) != 0xffffff) ink++;
        if (ink < 2000) throw new AssertionError("Page has no painted text");
        ImageIO.write(image, "png", new File(output)); System.out.println("Aster Java2D render passed; " + canvas.layout.items.size() + " draws, " + ink + " painted pixels.");
    }
    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("--render-test")) { renderTest(args.length > 1 ? args[1] : "aster-engine.png"); return; }
        SwingUtilities.invokeLater(() -> {
            PreviewMain app = new PreviewMain(); app.window.setVisible(true);
            if (args.length > 0 && args[0].equals("--smoke")) {
                javax.swing.Timer timer = new javax.swing.Timer(1800, event -> {
                    try {
                        if (app.current().canvas.layout == null) throw new AssertionError("No page layout in desktop window");
                        BufferedImage screenshot = new Robot().createScreenCapture(app.window.getBounds());
                        ImageIO.write(screenshot, "png", new File(args[1]));
                        System.out.println("Aster native desktop window opened and rendered successfully."); app.window.dispose();
                    } catch (Throwable e) { e.printStackTrace(); app.window.dispose(); System.exit(1); }
                }); timer.setRepeats(false); timer.start();
            }
        });
    }
}
