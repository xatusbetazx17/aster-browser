package io.aster.desktop;

import io.aster.engine.*;
import javax.swing.*;
import javax.swing.plaf.basic.BasicTabbedPaneUI;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.*;
import java.net.URI;
import java.util.*;
import java.util.concurrent.*;
import java.util.prefs.Preferences;
import javax.imageio.ImageIO;

/** Aster's native desktop shell; web pages use only the Aster Java2D renderer. */
public final class PreviewMain {
    private static final Color INK = new Color(0x173d38), ACCENT = new Color(0x176b59), PAPER = new Color(0xf7faf8);
    private final JFrame window = GraphicsEnvironment.isHeadless() ? null : new JFrame("Aster · Original engine preview");
    final JPanel surface = new JPanel(new BorderLayout());
    final JTabbedPane tabs = new JTabbedPane();
    final JPanel strip = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
    final JTextField address = new JTextField();
    final JLabel status = new JLabel();
    private final Preferences preferences;
    final java.util.List<Visit> visits = new ArrayList<>();
    private final ExecutorService network = Executors.newFixedThreadPool(2, r -> { Thread t = new Thread(r, "aster-navigation"); t.setDaemon(true); return t; });
    private final JButton back = button("←", "Back", () -> move(-1));
    private final JButton forward = button("→", "Forward", () -> move(1));
    private final JButton add = button("+", "New tab (Ctrl+T)", this::newTab);
    private boolean disposed;
    final DownloadManager downloads = new DownloadManager(transfer -> SwingUtilities.invokeLater(this::updateDownloads));
    java.util.function.Function<String, Path> destinationChooser = this::chooseDestination;
    boolean reducedMotion;
    double pageScale;
    private static final class Visit {
        final URI uri; final String title;
        Visit(URI uri, String title) { this.uri = uri; this.title = title; }
    }
    private PreviewMain() { this(Preferences.userRoot().node("io/aster/engine-preview")); }
    PreviewMain(Preferences prefs) {
        preferences = prefs;
        reducedMotion = prefs.getBoolean("reducedMotion", false);
        int percent = prefs.getInt("pageScale", 100);
        pageScale = (percent == 125 || percent == 150 || percent == 200 ? percent : 100) / 100.0;
        surface.setBackground(PAPER);
        if (window != null) {
            window.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
            window.addWindowListener(new WindowAdapter() { public void windowClosing(WindowEvent e) {
                if(downloads.activeCount() == 0 || JOptionPane.showConfirmDialog(surface,
                        "Cancel active downloads and close Aster?", "Downloads in progress", JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION)
                    window.dispose();
            } });
            window.addWindowListener(new WindowAdapter() { public void windowClosed(WindowEvent e) { dispose(); } });
            window.setContentPane(surface);
        }
        tabs.setUI(new BasicTabbedPaneUI() {
            protected int calculateTabAreaHeight(int placement, int runs, int height) { return 0; }
            protected void paintTabArea(Graphics g, int placement, int selected) { }
            protected void paintContentBorder(Graphics g, int placement, int selected) { }
            protected Insets getContentBorderInsets(int placement) { return new Insets(0,0,0,0); }
        });
        tabs.setBorder(BorderFactory.createEmptyBorder()); tabs.setFocusable(false);
        tabs.addChangeListener(event -> { refreshInternal(current()); sync(); });
        JPanel header = new JPanel(new BorderLayout()); header.setBackground(PAPER);
        strip.setBackground(new Color(0xe6efeb)); strip.setBorder(BorderFactory.createEmptyBorder(5, 8, 0, 8));
        add.setText(""); add.setBorder(BorderFactory.createEmptyBorder(4,4,4,4));
        add.setIcon(new Icon() { public int getIconWidth(){return 12;} public int getIconHeight(){return 12;} public void paintIcon(Component c, Graphics g, int x, int y){ g.setColor(INK); g.drawLine(x+1,y+6,x+11,y+6); g.drawLine(x+6,y+1,x+6,y+11); } });
        add.setPreferredSize(new Dimension(30, 30)); strip.add(add);
        JScrollPane tabScroll = new JScrollPane(strip, JScrollPane.VERTICAL_SCROLLBAR_NEVER, JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        tabScroll.setBorder(BorderFactory.createEmptyBorder()); tabScroll.setPreferredSize(new Dimension(600, 58));
        tabScroll.getHorizontalScrollBar().setUnitIncrement(80);
        header.add(tabScroll, BorderLayout.NORTH);
        JPanel toolbar = new JPanel(new BorderLayout(8, 0)); toolbar.setOpaque(false);
        toolbar.setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 12));
        JPanel navigation = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0)); navigation.setOpaque(false);
        navigation.add(back); navigation.add(forward);
        navigation.add(button("⌂", "Home", () -> load(current(), PageLoader.HOME, -1)));
        toolbar.add(navigation, BorderLayout.WEST);
        address.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 16));
        address.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(new Color(0xc6d6cd)), BorderFactory.createEmptyBorder(5, 10, 5, 10)));
        address.setToolTipText("Website address or aster:settings"); address.getAccessibleContext().setAccessibleName("Website address");
        address.addActionListener(e -> { try { load(current(), target(address.getText()), -1); } catch (IllegalArgumentException ex) { message(ex.getMessage()); } });
        toolbar.add(address, BorderLayout.CENTER);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0)); actions.setOpaque(false);
        actions.add(button("☆", "Bookmark this page (Ctrl+D)", this::saveBookmark));
        actions.add(button("Menu", "Aster menu", this::menu)); toolbar.add(actions, BorderLayout.EAST);
        header.add(toolbar, BorderLayout.CENTER);
        status.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 13)); status.setForeground(INK);
        status.setBorder(BorderFactory.createEmptyBorder(0, 18, 6, 18)); status.setVisible(false);
        // Only pending navigation and errors occupy space; there is no permanent bottom bar.
        header.add(status, BorderLayout.SOUTH);
        surface.add(header, BorderLayout.NORTH); surface.add(tabs, BorderLayout.CENTER);
        bind("control L", () -> { address.requestFocusInWindow(); address.selectAll(); });
        bind("control S", this::saveCurrentPage); bind("control J", () -> load(current(), URI.create("aster:downloads"), -1));
        bind("control T", this::newTab); bind("control W", this::closeTab); bind("control D", this::saveBookmark);
        bind("alt LEFT", () -> move(-1)); bind("alt RIGHT", () -> move(1));
        bind("control TAB", () -> cycle(1)); bind("control shift TAB", () -> cycle(-1));
        bind("F11", () -> { if (window != null) window.setExtendedState(window.getExtendedState() == JFrame.MAXIMIZED_BOTH ? JFrame.NORMAL : JFrame.MAXIMIZED_BOTH); });
        if (window != null) { window.setSize(1100, 780); window.setMinimumSize(new Dimension(720, 420)); window.setLocationByPlatform(true); }
        // Construct and lay out the native welcome page before showing the window once.
        newTab();
    }
    void dispose() { disposed = true; downloads.close(); network.shutdownNow(); for (Component c : strip.getComponents()) if (c instanceof TabChip) ((TabChip)c).stop(); }
    private static final class RoundButton extends JButton {
        RoundButton(String label) { super(label); setContentAreaFilled(false); setOpaque(false); setBorder(BorderFactory.createEmptyBorder(6, 10, 6, 10)); setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 14)); setForeground(INK); }
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create(); g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(getModel().isPressed() ? new Color(0xc0dbce) : getModel().isRollover() ? new Color(0xdcece3) : Color.WHITE);
            g.fillRoundRect(0, 0, getWidth()-1, getHeight()-1, 16, 16); g.setColor(hasFocus() ? ACCENT : new Color(0xccdcd2));
            g.drawRoundRect(0, 0, getWidth()-1, getHeight()-1, 16, 16); g.dispose(); super.paintComponent(graphics);
        }
    }
    private JButton button(String label, String name, Runnable action) {
        JButton b = new RoundButton(label); b.setToolTipText(name); b.getAccessibleContext().setAccessibleName(name);
        b.addActionListener(e -> action.run()); return b;
    }
    private void bind(String stroke, Runnable action) {
        surface.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(stroke), stroke);
        surface.getActionMap().put(stroke, new AbstractAction() { public void actionPerformed(ActionEvent event) { action.run(); } });
    }
    final class Tab extends JScrollPane {
        final PageCanvas canvas = new PageCanvas(); final java.util.List<URI> history = new ArrayList<>();
        final TabChip chip = new TabChip(this);
        final java.util.List<DownloadRow> downloadRows = new ArrayList<>();
        int index = -1, generation; Future<?> pending; boolean closed; String message = "";
        Tab() { setBorder(BorderFactory.createEmptyBorder()); setViewportView(canvas); getVerticalScrollBar().setUnitIncrement(28); canvas.scale = pageScale; canvas.navigate = uri -> load(this, uri, -1); canvas.save = uri -> chooseDownload(uri, DownloadManager.filename(uri, null)); }
    }
    final class TabChip extends JPanel {
        final Tab tab; final JButton select; final JButton close;
        float alpha; boolean hover, closing; int width = 190;
        javax.swing.Timer animation, shrink;
        TabChip(Tab tab) {
            this.tab = tab; setLayout(new BorderLayout()); setOpaque(true);
            select = new JButton("New tab"); select.setHorizontalAlignment(SwingConstants.LEFT); select.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 14));
            select.setBorder(BorderFactory.createEmptyBorder(4, 10, 4, 2)); select.setContentAreaFilled(false); select.addActionListener(e -> { if (!closing) tabs.setSelectedComponent(tab); });
            close = new JButton() {
                protected void paintComponent(Graphics graphics) {
                    Graphics2D g = (Graphics2D)graphics.create(); g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    g.setComposite(AlphaComposite.SrcOver.derive(hasFocus() ? 1f : alpha));
                    if (getModel().isRollover() || hasFocus()) { g.setColor(new Color(0xc7ddd2)); g.fillOval(2, 4, 26, 26); }
                    g.setColor(INK); g.setStroke(new BasicStroke(1.6f)); int x = getWidth()/2, y = getHeight()/2;
                    g.drawLine(x-4,y-4,x+4,y+4); g.drawLine(x+4,y-4,x-4,y+4); g.dispose();
                }
            };
            close.setPreferredSize(new Dimension(30, 34)); close.setBorderPainted(false); close.setContentAreaFilled(false);
            close.setToolTipText("Close tab"); close.getAccessibleContext().setAccessibleName("Close tab"); close.addActionListener(e -> closeTab(tab));
            close.addFocusListener(new FocusAdapter() { public void focusGained(FocusEvent e) { close.repaint(); } public void focusLost(FocusEvent e) { close.repaint(); } });
            MouseAdapter tracker = new MouseAdapter() {
                public void mouseEntered(MouseEvent e) { hover = true; fade(); }
                public void mouseExited(MouseEvent e) { Point p = SwingUtilities.convertPoint(e.getComponent(), e.getPoint(), TabChip.this); hover = contains(p); fade(); }
                public void mouseClicked(MouseEvent e) { if (SwingUtilities.isMiddleMouseButton(e)) closeTab(tab); }
            };
            for (Component c : new Component[]{this, select, close}) c.addMouseListener(tracker);
            add(select, BorderLayout.CENTER); add(close, BorderLayout.EAST); update();
        }
        public Dimension getPreferredSize() { return new Dimension(width, 36); }
        void title(String title) { select.setText(plainLabel(title.length() > 20 ? title.substring(0,20) + "…" : title)); select.setToolTipText(plainLabel(title)); select.getAccessibleContext().setAccessibleName(title); close.getAccessibleContext().setAccessibleName("Close tab: " + title); }
        void update() { boolean selected = current() == tab; select.setSelected(selected); setBackground(selected ? PAPER : new Color(0xe6efeb)); setBorder(BorderFactory.createMatteBorder(0,0,2,1,selected ? ACCENT : new Color(0xcbd9d1))); fade(); }
        void fade() {
            if (animation != null) animation.stop(); final float end = current() == tab || hover ? 1f : 0f;
            if (reducedMotion || !isShowing()) { alpha = end; close.repaint(); return; }
            final float start = alpha; final long began = System.nanoTime();
            animation = new javax.swing.Timer(15, e -> { float t = Math.min(1f, (System.nanoTime()-began)/120_000_000f); float eased = 1-(1-t)*(1-t)*(1-t); alpha = start+(end-start)*eased; close.repaint(); if(t>=1) ((javax.swing.Timer)e.getSource()).stop(); }); animation.start();
        }
        void stop() { if(animation != null) animation.stop(); if(shrink != null) shrink.stop(); }
    }
    Tab current() { return (Tab) tabs.getSelectedComponent(); }
    private void cycle(int delta) { int count = tabs.getTabCount(); if(count > 0) tabs.setSelectedIndex((tabs.getSelectedIndex()+delta+count)%count); }
    void newTab() {
        if (tabs.getTabCount() >= 20) { message("The preview supports up to 20 tabs."); return; }
        Tab tab = new Tab(); tabs.addTab("New tab", tab); strip.add(tab.chip, strip.getComponentCount()-1); tabs.setSelectedComponent(tab); load(tab, PageLoader.HOME, -1);
        strip.revalidate(); strip.repaint(); SwingUtilities.invokeLater(() -> tab.chip.scrollRectToVisible(new Rectangle(0,0,190,36)));
    }
    private void closeTab() { closeTab(current()); }
    private void closeTab(Tab tab) {
        if(tab == null || tab.closed) return;
        tab.closed = true; tab.generation++; tab.chip.closing = true; tab.chip.select.setEnabled(false); tab.chip.close.setEnabled(false);
        if(tab.pending != null) tab.pending.cancel(true);
        tabs.remove(tab); if(tabs.getTabCount()==0) newTab(); refreshInternal(current());
        Runnable finish = () -> { tab.chip.stop(); strip.remove(tab.chip); strip.revalidate(); strip.repaint(); };
        if(reducedMotion || !strip.isShowing()) { finish.run(); return; }
        final long began = System.nanoTime();
        tab.chip.shrink = new javax.swing.Timer(15, e -> { float t = Math.min(1f,(System.nanoTime()-began)/160_000_000f); tab.chip.width = Math.round(190*(1-t)*(1-t)); strip.revalidate(); strip.repaint(); if(t>=1) finish.run(); }); tab.chip.shrink.start();
    }
    static String internal(URI uri) {
        String s = uri.toString(); if(s.equals(PageLoader.HOME.toString()) || s.equals("aster:newtab") || s.equals("aster:home")) return "home";
        for(String page : new String[]{"settings","bookmarks","history","downloads"}) if(s.equals("aster:"+page)) return page;
        return null;
    }
    static URI target(String input) { URI uri; try { uri = URI.create(input.trim()); } catch(IllegalArgumentException e) { return PageLoader.address(input); } return internal(uri) != null ? uri : PageLoader.address(input); }
    void move(int delta) { Tab tab = current(); if(tab == null) return; int next = tab.index+delta; if(next>=0 && next<tab.history.size()) load(tab,tab.history.get(next),next); }
    void load(Tab tab, URI uri, int historyIndex) {
        if(tab == null || tab.closed) return; if(tab.pending != null) tab.pending.cancel(true);
        int generation = ++tab.generation; tab.downloadRows.clear();
        if(internal(uri) != null) {
            String page = internal(uri); Engine.Document doc = Engine.parse(uri,"<title>Aster · "+page+"</title><h1>"+page+"</h1>");
            tab.canvas.setDocument(doc); tab.setViewportView(internalPage(tab,page)); completed(tab,doc,historyIndex); return;
        }
        tab.message = "Opening " + uri + "…"; sync();
        tab.pending = network.submit(() -> { try {
            Engine.Document document = PageLoader.load(uri);
            SwingUtilities.invokeLater(() -> { if(tab.closed || tab.generation != generation || disposed) return;
                tab.canvas.setDocument(document); tab.setViewportView(tab.canvas);
                visits.add(0,new Visit(document.uri,document.title)); if(visits.size()>200) visits.remove(visits.size()-1);
                completed(tab,document,historyIndex); refreshInternal(current());
            });
        } catch(PageLoader.DownloadRequired file) { SwingUtilities.invokeLater(() -> {
            if(!tab.closed && tab.generation == generation && !disposed) showDownloadOffer(tab, file, historyIndex);
        }); } catch(Exception e) { SwingUtilities.invokeLater(() -> { if(!tab.closed && tab.generation==generation && !disposed) { tab.message="Could not open page: "+e.getMessage(); sync(); } }); } });
    }
    private void saveCurrentPage() {
        Tab tab=current();
        if(tab==null || tab.canvas.document==null || internal(tab.canvas.document.uri)!=null) { message("Open a website first, or paste a direct link in Downloads."); return; }
        chooseDownload(tab.canvas.document.uri,DownloadManager.filename(tab.canvas.document.uri,null));
    }
    private Path chooseDestination(String filename) {
        JFileChooser chooser=new JFileChooser(); chooser.setDialogTitle("Save file as"); chooser.setSelectedFile(new File(filename));
        return chooser.showSaveDialog(surface)==JFileChooser.APPROVE_OPTION ? chooser.getSelectedFile().toPath() : null;
    }
    void chooseDownload(URI uri,String filename) {
        try {
            PageLoader.validate(uri);
            Path selected=destinationChooser.apply(filename); if(selected==null) return;
            downloads.start(uri,selected);
            load(current(),URI.create("aster:downloads"),-1); message("Download started. You can keep browsing.");
        } catch(Exception e) { message("Could not start download: "+e.getMessage()); }
    }
    private void showDownloadOffer(Tab tab,PageLoader.DownloadRequired file,int historyIndex) {
        String filename=DownloadManager.filename(file.uri,file.disposition);
        Engine.Document doc=Engine.parse(file.uri,"<title>Download · "+PageLoader.escape(filename)+"</title>");
        tab.canvas.setDocument(doc);
        JPanel panel=body("Save this file",filename);
        paragraph(panel,"From "+file.uri.getHost()+" · "+(file.length<0 ? "Size not provided" : DownloadManager.bytes(file.length)));
        paragraph(panel,"Choose where to save this file. Aster will not open it automatically.");
        panel.add(button("Save as…","Choose destination for "+filename,()->chooseDownload(file.uri,filename)));
        panel.add(Box.createVerticalGlue()); tab.setViewportView(panel); completed(tab,doc,historyIndex);
    }
    private void updateDownloads() {
        if(disposed) return;
        for(int i=0;i<tabs.getTabCount();i++) for(DownloadRow row:((Tab)tabs.getComponentAt(i)).downloadRows) row.refresh();
        if(downloads.activeCount()==0 && current()!=null && current().message.equals("Download started. You can keep browsing.")) message("");
    }
    private final class DownloadRow extends JPanel {
        final DownloadManager.Transfer transfer;
        final JLabel detail=new JLabel(); final JProgressBar progress=new JProgressBar(0,100);
        final JButton action;
        DownloadRow(DownloadManager.Transfer transfer) {
            this.transfer=transfer; setOpaque(false); setLayout(new BoxLayout(this,BoxLayout.Y_AXIS)); setAlignmentX(Component.LEFT_ALIGNMENT);
            setMaximumSize(new Dimension(Integer.MAX_VALUE,170));
            JLabel name=new JLabel(plainLabel(transfer.target.getFileName().toString())); name.setFont(new Font(Font.SANS_SERIF,Font.BOLD,15)); name.setForeground(INK); add(name);
            JTextArea path=new JTextArea(transfer.target.toString()); path.setEditable(false); path.setLineWrap(true); path.setWrapStyleWord(true); path.setOpaque(false); path.setFont(new Font(Font.SANS_SERIF,Font.PLAIN,13)); path.setMaximumSize(new Dimension(Integer.MAX_VALUE,36)); path.setAlignmentX(Component.LEFT_ALIGNMENT); add(path);
            detail.setFont(new Font(Font.SANS_SERIF,Font.PLAIN,13)); detail.setAlignmentX(Component.LEFT_ALIGNMENT); add(detail); add(Box.createVerticalStrut(6));
            progress.setAlignmentX(Component.LEFT_ALIGNMENT); progress.setMaximumSize(new Dimension(Integer.MAX_VALUE,10)); add(progress); add(Box.createVerticalStrut(6));
            action=button("Cancel","Cancel this download",()->{ if(transfer.finished()) chooseDownload(transfer.source,transfer.target.getFileName().toString()); else transfer.cancel(); }); action.setAlignmentX(Component.LEFT_ALIGNMENT); add(action); refresh();
        }
        void refresh() {
            String state=transfer.state.toString().toLowerCase(Locale.ROOT);
            String info=capitalize(state)+" · "+DownloadManager.bytes(transfer.received)+(transfer.total<0 ? "" : " / "+DownloadManager.bytes(transfer.total));
            if(!transfer.error.isEmpty()) info += " · "+transfer.error;
            detail.setText(plainLabel(info)); detail.setToolTipText(plainLabel(info));
            progress.setIndeterminate(transfer.total<0 && !transfer.finished());
            progress.setValue(transfer.state==DownloadManager.State.COMPLETE ? 100 : transfer.total>0 ? (int)Math.min(100,100.0*transfer.received/transfer.total) : 0);
            action.setVisible(transfer.state!=DownloadManager.State.COMPLETE);
            action.setEnabled(transfer.state!=DownloadManager.State.CANCELLING);
            action.setText(transfer.finished() ? "Try again…" : "Cancel");
        }
    }
    private void refreshInternal(Tab tab) {
        if(tab != null && tab.canvas.document != null) { String page=internal(tab.canvas.document.uri); if(page != null) tab.setViewportView(internalPage(tab,page)); }
    }
    private void completed(Tab tab, Engine.Document document, int historyIndex) {
        if(historyIndex>=0) tab.index=historyIndex;
        else { while(tab.history.size()>tab.index+1) tab.history.remove(tab.history.size()-1); tab.history.add(document.uri); if(tab.history.size()>100) tab.history.remove(0); tab.index=tab.history.size()-1; }
        tabs.setTitleAt(tabs.indexOfComponent(tab),plainLabel(document.title)); tab.chip.title(document.title); tab.message=""; sync();
        tab.getVerticalScrollBar().setValue(0);
    }
    private void message(String text) { if(current()!=null) current().message=text; status.setText(plainLabel(text)); status.setVisible(!text.isEmpty()); }
    private void sync() {
        Tab tab = current(); if(tab == null) return;
        if(tab.canvas.document != null) address.setText(tab.canvas.document.uri.toString());
        message(tab.message); back.setEnabled(tab.index>0); forward.setEnabled(tab.index+1<tab.history.size());
        for(Component c:strip.getComponents()) if(c instanceof TabChip) ((TabChip)c).update();
    }
    private int bookmarkCount() { return Math.max(0, Math.min(30,preferences.getInt("count",0))); }
    void saveBookmark() {
        Tab tab=current(); if(tab==null || tab.canvas.document==null) return;
        String url=tab.canvas.document.uri.toString(); int count=bookmarkCount();
        for(int i=0;i<count;i++) if(preferences.get("url"+i,"").equals(url)) { message("This page is already bookmarked."); return; }
        if(count>=30) { message("The preview supports up to 30 bookmarks."); return; }
        preferences.put("url"+count,url); preferences.put("title"+count,tab.canvas.document.title); preferences.putInt("count",count+1); refreshInternal(tab); message("Bookmark saved.");
    }
    private void menu() {
        JPopupMenu popup=new JPopupMenu(); JPanel grid=new JPanel(new GridLayout(2,2,8,8)); grid.setBorder(BorderFactory.createEmptyBorder(10,10,10,10));
        for(String page:new String[]{"bookmarks","history","downloads","settings"}) { JButton b=button(capitalize(page),"Open "+page,()->{ popup.setVisible(false); load(current(),URI.create("aster:"+page),-1); }); b.setPreferredSize(new Dimension(112,76)); grid.add(b); }
        popup.add(grid); popup.show(surface,Math.max(0,surface.getWidth()-260),88);
    }
    private static String capitalize(String s) { return s.substring(0,1).toUpperCase(Locale.ROOT)+s.substring(1); }
    private static final class PagePanel extends JPanel implements Scrollable {
        public Dimension getPreferredScrollableViewportSize() { return new Dimension(800,600); }
        public int getScrollableUnitIncrement(Rectangle r,int axis,int direction) { return 28; }
        public int getScrollableBlockIncrement(Rectangle r,int axis,int direction) { return Math.max(28,r.height-28); }
        public boolean getScrollableTracksViewportWidth() { return true; }
        public boolean getScrollableTracksViewportHeight() { return getParent()!=null && getPreferredSize().height<getParent().getHeight(); }
    }
    private JPanel body(String title, String description) {
        JPanel panel = new PagePanel(); panel.setLayout(new BoxLayout(panel,BoxLayout.Y_AXIS)); panel.setBackground(PAPER); panel.setBorder(BorderFactory.createEmptyBorder(28,32,28,32));
        JLabel brand=new JLabel("A S T E R"); brand.setForeground(ACCENT); brand.setFont(new Font(Font.SANS_SERIF,Font.BOLD,14)); brand.setAlignmentX(Component.LEFT_ALIGNMENT); panel.add(brand); panel.add(Box.createVerticalStrut(18));
        JLabel heading=new JLabel(title); heading.setForeground(INK); heading.setFont(new Font(Font.SANS_SERIF,Font.BOLD,28)); heading.setAlignmentX(Component.LEFT_ALIGNMENT); panel.add(heading); panel.add(Box.createVerticalStrut(10));
        paragraph(panel,description); return panel;
    }
    private void paragraph(JPanel panel,String text) { JTextArea label=new JTextArea(text,2,36); label.setLineWrap(true); label.setWrapStyleWord(true); label.setEditable(false); label.setFocusable(false); label.setOpaque(false); label.setForeground(INK); label.setFont(new Font(Font.SANS_SERIF,Font.PLAIN,15)); label.setAlignmentX(Component.LEFT_ALIGNMENT); label.setMaximumSize(new Dimension(Integer.MAX_VALUE,70)); panel.add(label); panel.add(Box.createVerticalStrut(16)); }
    private JPanel internalPage(Tab tab,String page) {
        JPanel panel=body(page.equals("home") ? "Your space to explore." : capitalize(page), page.equals("home") ? "A first look at Aster's own engine. Start with a simple text website." : "Aster tools · on this computer");
        if(page.equals("home")) {
            JPanel links=new JPanel(new GridLayout(2,3,8,8)); links.setOpaque(false); links.setAlignmentX(Component.LEFT_ALIGNMENT); links.setMaximumSize(new Dimension(Integer.MAX_VALUE,130));
            String[][] targets={{"Example website","https://example.com"},{"Bookmarks","aster:bookmarks"},{"History","aster:history"},{"Settings","aster:settings"},{"Downloads","aster:downloads"},{"Aster project","https://github.com/xatusbetazx17/aster-browser"}};
            for(String[] item:targets) links.add(button(item[0],item[0],()->load(tab,target(item[1]),-1))); panel.add(links); panel.add(Box.createVerticalStrut(24));
            JPanel awareness=new JPanel(new FlowLayout(FlowLayout.LEFT,16,0)); awareness.setOpaque(false); awareness.setAlignmentX(Component.LEFT_ALIGNMENT); awareness.setMaximumSize(new Dimension(Integer.MAX_VALUE,120));
            final int count=tabs.getTabCount(); JComponent ring=new JComponent() { protected void paintComponent(Graphics graphics) { Graphics2D g=(Graphics2D)graphics.create(); g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON); g.setStroke(new BasicStroke(8)); g.setColor(new Color(0xdbe7df)); g.drawOval(8,8,94,94); g.setColor(ACCENT); g.drawArc(8,8,94,94,90,-Math.round(360f*count/20)); g.setFont(new Font(Font.SANS_SERIF,Font.BOLD,20)); String value=count+" / 20"; g.drawString(value,(110-g.getFontMetrics().stringWidth(value))/2,61); g.dispose(); } };
            ring.setPreferredSize(new Dimension(110,110)); ring.setToolTipText("Open tabs: "+count+" of 20"); awareness.add(ring); awareness.add(new JLabel("Open tabs · "+bookmarkCount()+" bookmarks · "+visits.size()+" recent visits")); panel.add(awareness); panel.add(Box.createVerticalStrut(22));
            paragraph(panel,"Preview limits: basic HTML and text only. JavaScript, images, video, Prime Video and cloud gaming are not supported yet.");
        } else if(page.equals("bookmarks")) {
            if(bookmarkCount()==0) paragraph(panel,"No bookmarks yet. Open a page and press Ctrl+D or the star button.");
            for(int i=0;i<bookmarkCount();i++) { final String url=preferences.get("url"+i,""); JButton item=button(plainLabel(preferences.get("title"+i,url)),url,()->{ try { load(tab,target(url),-1); } catch(IllegalArgumentException e) { message(e.getMessage()); } }); item.setAlignmentX(Component.LEFT_ALIGNMENT); item.setMaximumSize(new Dimension(1200,38)); panel.add(item); panel.add(Box.createVerticalStrut(6)); }
            panel.add(button("Clear bookmarks","Clear saved bookmarks",()->{ if(JOptionPane.showConfirmDialog(surface,"Remove saved bookmarks?","Clear bookmarks",JOptionPane.OK_CANCEL_OPTION)==JOptionPane.OK_OPTION) { for(int i=0;i<30;i++){ preferences.remove("url"+i);preferences.remove("title"+i); } preferences.putInt("count",0); tab.setViewportView(internalPage(tab,page)); } }));
        } else if(page.equals("history")) {
            paragraph(panel,"The latest 200 successful web visits in this session. This list is cleared when Aster closes.");
            if(visits.isEmpty()) paragraph(panel,"No websites visited in this session.");
            for(Visit visit:new ArrayList<>(visits)) { JButton item=button(plainLabel(visit.title),visit.uri.toString(),()->load(tab,visit.uri,-1)); item.setAlignmentX(Component.LEFT_ALIGNMENT); item.setMaximumSize(new Dimension(1200,38)); panel.add(item); panel.add(Box.createVerticalStrut(6)); }
            panel.add(button("Clear session history","Clear session history",()->{ visits.clear(); tab.setViewportView(internalPage(tab,page)); }));
        } else if(page.equals("downloads")) {
            paragraph(panel,"Save files from direct HTTP or HTTPS links. Choose a destination for each file. Up to two transfers at once, 2 GiB per file.");
            JPanel entry = new JPanel(new BorderLayout(8, 0)); entry.setOpaque(false); entry.setAlignmentX(Component.LEFT_ALIGNMENT); entry.setMaximumSize(new Dimension(Integer.MAX_VALUE,36));
            JTextField url = new JTextField(); url.setFont(new Font(Font.SANS_SERIF,Font.PLAIN,15)); url.getAccessibleContext().setAccessibleName("File download URL"); url.setToolTipText("Paste a direct file URL"); entry.add(url, BorderLayout.CENTER);
            Runnable submit = () -> { try { URI uri=PageLoader.address(url.getText()); chooseDownload(uri,DownloadManager.filename(uri,null)); } catch(IllegalArgumentException e) { message(e.getMessage()); } };
            url.addActionListener(e -> submit.run()); entry.add(button("Save link…","Choose where to save this link",submit),BorderLayout.EAST); panel.add(entry); panel.add(Box.createVerticalStrut(18));
            java.util.List<DownloadManager.Transfer> items = downloads.snapshot();
            if(items.isEmpty()) paragraph(panel,"No downloads in this session. Paste a file link above, or right-click a website link and choose Save link as.");
            tab.downloadRows.clear();
            for(DownloadManager.Transfer transfer : items) { DownloadRow row=new DownloadRow(transfer); tab.downloadRows.add(row); panel.add(row); panel.add(Box.createVerticalStrut(12)); }
            panel.add(button("Clear finished list","Clear finished entries; keep downloaded files",()->{ downloads.clearFinished(); tab.setViewportView(internalPage(tab,page)); }));
        } else if(page.equals("settings")) {
            paragraph(panel,"Reading size changes website text. Settings are saved without changing your bookmarks.");
            JComboBox<String> zoom = new JComboBox<String>(new String[]{"100%","125%","150%","200%"}) {
                protected void processMouseWheelEvent(MouseWheelEvent e) { if(isPopupVisible()) super.processMouseWheelEvent(e); else { e.consume(); tab.dispatchEvent(SwingUtilities.convertMouseEvent(this,e,tab)); } }
            };
            zoom.setFont(new Font(Font.SANS_SERIF,Font.PLAIN,15)); zoom.setSelectedItem(Math.round(pageScale*100)+"%"); zoom.getAccessibleContext().setAccessibleName("Website reading size"); zoom.setMaximumSize(new Dimension(180,34)); zoom.setAlignmentX(Component.LEFT_ALIGNMENT);
            zoom.addActionListener(e->{ pageScale=Integer.parseInt(zoom.getSelectedItem().toString().replace("%",""))/100.0; preferences.putInt("pageScale",(int)Math.round(pageScale*100)); for(int i=0;i<tabs.getTabCount();i++){ PageCanvas canvas=((Tab)tabs.getComponentAt(i)).canvas; canvas.scale=pageScale; canvas.layoutWidth=-1; canvas.revalidate();canvas.repaint(); } }); panel.add(zoom); panel.add(Box.createVerticalStrut(18));
            JCheckBox motion=new JCheckBox("Reduce motion",reducedMotion); motion.setOpaque(false); motion.setFont(new Font(Font.SANS_SERIF,Font.PLAIN,15)); motion.addActionListener(e->{ reducedMotion=motion.isSelected(); preferences.putBoolean("reducedMotion",reducedMotion); sync(); }); panel.add(motion); panel.add(Box.createVerticalStrut(18));
            paragraph(panel,"Tab close buttons appear when selected, hovered, or focused with the keyboard. Ctrl+Tab switches tabs; Ctrl+W closes the current tab.");
        }
        panel.add(Box.createVerticalGlue()); return panel;
    }
    private static String plainLabel(String text) { return "\u200b"+text; }
    static final class PageCanvas extends JPanel implements Scrollable {
        Engine.Document document; Engine.Layout layout; int layoutWidth = -1; double scale = 1;
        java.util.function.Consumer<URI> navigate = uri -> { };
        java.util.function.Consumer<URI> save = uri -> { };
        PageCanvas() {
            setBackground(Color.WHITE); setFocusable(true);
            getAccessibleContext().setAccessibleName("Aster page");
            addMouseListener(new MouseAdapter() {
                public void mousePressed(MouseEvent e) { popup(e); }
                public void mouseReleased(MouseEvent e) { popup(e); }
                private void popup(MouseEvent e) {
                    if(!e.isPopupTrigger() || layout==null) return;
                    URI uri=layout.hit((float)(e.getX()/scale),(float)(e.getY()/scale)); if(uri==null)return;
                    JPopupMenu menu=new JPopupMenu(); JMenuItem open=new JMenuItem("Open link"); open.addActionListener(event->navigate.accept(uri)); menu.add(open);
                    JMenuItem download=new JMenuItem("Save link as…"); download.addActionListener(event->save.accept(uri)); menu.add(download); menu.show(PageCanvas.this,e.getX(),e.getY());
                }
                public void mouseClicked(MouseEvent e) {
                    if (SwingUtilities.isLeftMouseButton(e) && layout != null) { URI uri = layout.hit((float)(e.getX()/scale), (float)(e.getY()/scale)); if (uri != null) navigate.accept(uri); }
                }
            });
            addMouseMotionListener(new MouseMotionAdapter() { public void mouseMoved(MouseEvent e) {
                URI uri = layout == null ? null : layout.hit((float)(e.getX()/scale), (float)(e.getY()/scale));
                setCursor(Cursor.getPredefinedCursor(uri == null ? Cursor.DEFAULT_CURSOR : Cursor.HAND_CURSOR));
                setToolTipText(uri == null ? null : uri.toString());
            }});
        }
        void setDocument(Engine.Document doc) { document = doc; layoutWidth = -1; getAccessibleContext().setAccessibleDescription(doc.text()); revalidate(); repaint(); }
        static Font font(Engine.Style s) { return new Font(s.pre ? Font.MONOSPACED : Font.SANS_SERIF, (s.bold ? Font.BOLD : 0) | (s.italic ? Font.ITALIC : 0), Math.round(s.size)); }
        void ensureLayout() {
            if (document != null && (layout == null || layoutWidth != getWidth())) {
                layoutWidth = getWidth();
                try { layout = Engine.layout(document, (float)(getWidth()/scale), (text, style) -> getFontMetrics(font(style)).stringWidth(text)); }
                catch (IllegalArgumentException e) { document = Engine.parse(PageLoader.HOME, "<h1>Page is too complex</h1><p>" + PageLoader.escape(e.getMessage()) + "</p>"); layout = Engine.layout(document, (float)(getWidth()/scale), (t, s) -> getFontMetrics(font(s)).stringWidth(t)); }
                revalidate();
            }
        }
        protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics); ensureLayout(); if (layout == null) return;
            Graphics2D g = (Graphics2D) graphics.create(); g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.scale(scale, scale); Rectangle clip = g.getClipBounds();
            for (Engine.Draw draw : layout.items) {
                if (clip != null && (draw.y + draw.height < clip.y || draw.y > clip.y + clip.height)) continue;
                g.setFont(font(draw.style)); g.setColor(new Color(draw.style.color, true));
                g.drawString(draw.text, draw.x, draw.y + draw.style.size);
                if (draw.link != null) g.drawLine((int) draw.x, (int) (draw.y + draw.style.size + 2), (int) (draw.x + draw.width), (int) (draw.y + draw.style.size + 2));
            }
            g.dispose();
        }
        public Dimension getPreferredSize() { return new Dimension(800, layout == null ? 650 : (int) Math.ceil(layout.height*scale)); }
        public Dimension getPreferredScrollableViewportSize() { return new Dimension(800, 650); }
        public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) { return 28; }
        public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) { return Math.max(28, visible.height - 30); }
        public boolean getScrollableTracksViewportWidth() { return true; }
        public boolean getScrollableTracksViewportHeight() { return false; }
    }
    private static void renderTest(String output) throws Exception {
        JMenuItem hostileTitle = new JMenuItem(plainLabel("<html><img src='https://example.invalid/track'>"));
        if (hostileTitle.getClientProperty("html") != null) throw new AssertionError("Page title became a Swing HTML document");
        PageCanvas canvas = new PageCanvas(); canvas.setSize(1000, 720); canvas.setDocument(Engine.parse(PageLoader.HOME, PageLoader.WELCOME));
        BufferedImage image = new BufferedImage(1000, 720, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics(); canvas.paint(graphics); graphics.dispose();
        if (canvas.layout == null || canvas.layout.items.size() < 60 || canvas.layout.items.stream().noneMatch(d -> d.link != null)) throw new AssertionError("Page did not render");
        int ink = 0; for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++) if ((image.getRGB(x, y) & 0xffffff) != 0xffffff) ink++;
        if (ink < 2000) throw new AssertionError("Page has no painted text");
        ImageIO.write(image, "png", new File(output)); System.out.println("Aster Java2D render passed; " + canvas.layout.items.size() + " draws, " + ink + " painted pixels.");
    }
    private void later(int delay, Runnable action) {
        javax.swing.Timer timer = new javax.swing.Timer(delay, event -> {
            try { action.run(); } catch (Throwable e) { e.printStackTrace(); window.dispose(); System.exit(1); }
        }); timer.setRepeats(false); timer.start();
    }
    private void nativeSmoke(String output) {
        later(1800, () -> {
            if (current().getViewport().getView().getWidth() < 600 || current().canvas.document == null)
                throw new AssertionError("No native home page in desktop window");
            Tab background = current(); newTab(); Tab selected = current();
            later(230, () -> {
                if (background.chip.alpha > 0.1f) throw new AssertionError("Inactive close button did not fade out");
                background.chip.select.dispatchEvent(new MouseEvent(background.chip.select, MouseEvent.MOUSE_ENTERED,
                    System.currentTimeMillis(), 0, 10, 10, 0, false));
                later(230, () -> {
                    if (background.chip.alpha < 0.9f) throw new AssertionError("Hover did not reveal close button");
                    background.chip.close.doClick();
                    if (current() != selected || tabs.getTabCount() != 1) throw new AssertionError("Background close changed active tab");
                    later(250, () -> {
                        if (background.chip.getParent() != null || strip.getComponentCount() != 2)
                            throw new AssertionError("Animated closed tab was not removed");
                        try {
                            BufferedImage screenshot = new Robot().createScreenCapture(window.getBounds());
                            ImageIO.write(screenshot, "png", new File(output)); preferences.removeNode();
                        } catch (Exception e) { throw new RuntimeException(e); }
                        System.out.println("Aster native desktop passed: welcome, hover fade, background close, shrink cleanup and window rendering.");
                        window.dispose();
                    });
                });
            });
        });
    }
    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("--render-test")) { renderTest(args.length > 1 ? args[1] : "aster-engine.png"); return; }
        SwingUtilities.invokeLater(() -> {
            boolean smoke = args.length > 0 && args[0].equals("--smoke");
            PreviewMain app = smoke ? new PreviewMain(Preferences.userRoot().node("io/aster/ui-smoke-" + UUID.randomUUID())) : new PreviewMain();
            app.window.setVisible(true);
            if (smoke) app.nativeSmoke(args[1]);
        });
    }
}
