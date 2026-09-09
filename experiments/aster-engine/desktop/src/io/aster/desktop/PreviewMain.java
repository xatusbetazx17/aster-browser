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
    private static final Color INK=WorkspaceTheme.TEXT, ACCENT=WorkspaceTheme.ACCENT, PAPER=WorkspaceTheme.PAGE;
    private final JFrame window = GraphicsEnvironment.isHeadless() ? null : new JFrame("Aster · Independent browser preview");
    final JPanel surface = new JPanel(new BorderLayout());
    final JTabbedPane tabs = new JTabbedPane();
    final JPanel strip = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
    final JTextField address = new JTextField();
    final JLabel status = new JLabel();
    final JSplitPane workspace=new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
    final ReaderDock reader;
    private final JLabel tabCount=new JLabel("1 live",SwingConstants.CENTER);
    private final JButton reloadButton=button("↻","Reload (Ctrl+R)",this::reload);
    private final JPanel findBar=new JPanel(new BorderLayout(8,0));
    final JTextField findQuery=new JTextField(18);
    final JLabel findCount=new JLabel();
    boolean autoPark;
    private int liveTabLimit;
    private final Preferences preferences;
    final SiteData siteData;
    private final ScheduledExecutorService siteWriter=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"aster-site-data");t.setDaemon(true);return t;});
    private final ExecutorService assets=Executors.newFixedThreadPool(2,r->{Thread t=new Thread(r,"aster-images");t.setDaemon(true);return t;});
    private final ArrayDeque<URI> closedTabs=new ArrayDeque<>();
    private boolean restoring;
    final java.util.List<Visit> visits = new ArrayList<>();
    private final ExecutorService network = Executors.newFixedThreadPool(2, r -> { Thread t = new Thread(r, "aster-navigation"); t.setDaemon(true); return t; });
    private final JButton back = button("←", "Back", () -> move(-1));
    private final JButton forward = button("→", "Forward", () -> move(1));
    private final JButton add = button("+", "New tab (Ctrl+T)", this::newTab);
    private boolean disposed;
    private final ExecutorService scripts = Executors.newFixedThreadPool(2,r->{Thread t=new Thread(r,"aster-page-scripts");t.setDaemon(true);return t;});
    final DownloadManager downloads;
    java.util.function.Function<String, Path> destinationChooser = this::chooseDestination;
    boolean reducedMotion;
    double pageScale;
    private static final class Visit {
        final URI uri; final String title;
        Visit(URI uri, String title) { this.uri = uri; this.title = title; }
    }
    private PreviewMain() { this(Preferences.userRoot().node("io/aster/engine-preview"),openSiteData()); }
    private static SiteData openSiteData(){
        try{return new SiteData(Paths.get(System.getProperty("user.home"),".aster-engine-preview","site-data.bin"));}
        catch(Exception e){SwingUtilities.invokeLater(()->JOptionPane.showMessageDialog(null,"Saved website data could not be opened. This window will keep website data only until it closes.","Website data",JOptionPane.WARNING_MESSAGE));return new SiteData();}
    }
    PreviewMain(Preferences prefs) {this(prefs,new SiteData());}
    PreviewMain(Preferences prefs,SiteData data) {
        preferences = prefs;siteData=data;downloads=new DownloadManager(transfer->SwingUtilities.invokeLater(this::updateDownloads),DownloadManager.MAX_BYTES,data);
        siteWriter.scheduleWithFixedDelay(()->{try{siteData.flush();}catch(Exception e){SwingUtilities.invokeLater(()->{if(!disposed)message("Website data could not be saved. Check your profile folder and available disk space.");});}},2,2,TimeUnit.SECONDS);
        autoPark=prefs.getBoolean("autoPark",false);liveTabLimit=Math.max(2,Math.min(8,prefs.getInt("liveTabLimit",4)));
        reader=new ReaderDock(prefs,this::closeReader);
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
        tabs.addChangeListener(event -> { for(int i=0;i<tabs.getTabCount();i++){ Tab t=(Tab)tabs.getComponentAt(i); if(t!=current()&&t.controllerAllowed)stopScripts(t); }
            Tab selected=current();if(selected!=null){selected.lastUsed=System.nanoTime();if(selected.parked&&!restoring)resume(selected);}
            refreshInternal(selected);sync();if(!restoring)rebalanceTabs(); });
        JPanel header = new JPanel(new BorderLayout()); header.setBackground(PAPER);
        strip.setBackground(WorkspaceTheme.CHROME); strip.setBorder(BorderFactory.createEmptyBorder(7, 8, 0, 8));
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
        navigation.add(reloadButton);
        toolbar.add(navigation, BorderLayout.WEST);
        address.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 16));
        address.setBackground(WorkspaceTheme.RAISED);address.setForeground(INK);address.setCaretColor(INK);address.setSelectionColor(WorkspaceTheme.LINE);
        address.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(WorkspaceTheme.LINE), BorderFactory.createEmptyBorder(7, 12, 7, 12)));
        address.setToolTipText("Search with DuckDuckGo or enter a website address"); address.getAccessibleContext().setAccessibleName("Search or website address");
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
        workspace.setLeftComponent(tabs);workspace.setRightComponent(null);workspace.setBorder(BorderFactory.createEmptyBorder());workspace.setDividerSize(0);workspace.setResizeWeight(.64);
        tabs.setMinimumSize(new Dimension(280,180));
        JPanel center=new JPanel(new BorderLayout());center.add(workspace);buildFindBar();center.add(findBar,BorderLayout.NORTH);
        surface.add(header, BorderLayout.NORTH);surface.add(sidebar(),BorderLayout.WEST);surface.add(center, BorderLayout.CENTER);
        surface.addComponentListener(new ComponentAdapter(){public void componentResized(ComponentEvent e){fitReader();}});
        bind("control L", () -> { address.requestFocusInWindow(); address.selectAll(); });
        bind("control S", this::saveCurrentPage); bind("control J", () -> load(current(), URI.create("aster:downloads"), -1));
        bind("control R",this::reload);bind("F5",this::reload);bind("control F",this::findPage);
        bind("ESCAPE",()->{if(findBar.isVisible()){findBar.setVisible(false);if(current()!=null){current().canvas.findText="";current().canvas.repaint();}}else stopNavigation();});
        bind("F3",()->findNext(1));bind("shift F3",()->findNext(-1));bind("control shift R",this::readPage);
        bind("control shift T",this::reopenTab);bind("control O",this::openDocument);
        bind("control T", this::newTab); bind("control W", this::closeTab); bind("control D", this::saveBookmark);
        bind("alt LEFT", () -> move(-1)); bind("alt RIGHT", () -> move(1));
        bind("control TAB", () -> cycle(1)); bind("control shift TAB", () -> cycle(-1));
        bind("F11", () -> { if(window!=null){GraphicsDevice screen=window.getGraphicsConfiguration().getDevice();if(screen.getFullScreenWindow()==window)screen.setFullScreenWindow(null);else screen.setFullScreenWindow(window);} });
        if (window != null) { window.setSize(1100, 780); window.setMinimumSize(new Dimension(720, 420)); window.setLocationByPlatform(true); }
        // Construct and lay out the native welcome page before showing the window once.
        newTab();
    }
    void dispose() { if(disposed)return;reader.close();saveSession();disposed = true;siteWriter.shutdownNow();try{siteData.flush();}catch(Exception e){System.err.println("Website data could not be saved on exit.");}ReadingTools.stopSpeech(); downloads.close(); network.shutdownNow();assets.shutdownNow(); for(int i=0;i<tabs.getTabCount();i++){Tab t=(Tab)tabs.getComponentAt(i);stopScripts(t);if(t.media!=null)t.media.close();} scripts.shutdownNow(); for (Component c : strip.getComponents()) if (c instanceof TabChip) ((TabChip)c).stop();MediaPanel.shutdown(); }
    private static final class RoundButton extends JButton {
        RoundButton(String label) { super(label); setContentAreaFilled(false); setOpaque(false); setBorder(BorderFactory.createEmptyBorder(6, 10, 6, 10)); setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 14)); setForeground(INK); }
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create(); g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(getModel().isPressed() ? WorkspaceTheme.LINE : getModel().isRollover() ? new Color(0x263747) : WorkspaceTheme.RAISED);
            g.fillRoundRect(0, 0, getWidth()-1, getHeight()-1, 14, 14); g.setColor(hasFocus() ? ACCENT : WorkspaceTheme.LINE);
            g.drawRoundRect(0, 0, getWidth()-1, getHeight()-1, 14, 14); g.dispose(); super.paintComponent(graphics);
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
        URI location=PageLoader.HOME;String title="New tab";boolean parked,postPage;long lastUsed=System.nanoTime();
        Future<?> imageTask;int restoreScroll=-1;
        final SiteData.Storage sessionStorage=new SiteData.Storage();
        volatile ScriptSession script;
        Future<?> scriptTask;
        javax.swing.Timer scriptTimer; boolean scriptBusy,controllerAllowed,scriptStarting;
        final ArrayDeque<String> inputCommands=new ArrayDeque<>();
        JToggleButton controllerButton; JButton runScripts; Engine.Document original;
        MediaPanel media;
        int mediaId;String mediaSource="";
        final java.util.concurrent.ConcurrentLinkedQueue<String> mediaEvents=new java.util.concurrent.ConcurrentLinkedQueue<>();
        final java.util.concurrent.atomic.AtomicReference<String> mediaTime=new java.util.concurrent.atomic.AtomicReference<>();
        Tab() { setBorder(BorderFactory.createEmptyBorder()); setViewportView(canvas); getVerticalScrollBar().setUnitIncrement(28); canvas.scale = pageScale; canvas.navigate = uri -> load(this, uri, -1); canvas.save = uri -> chooseDownload(uri, DownloadManager.filename(uri, null));
            canvas.openTab=uri->openLinkTab(uri,false);
            canvas.action=id->scriptCommand(this,"__aster.click("+id+")",true);
            canvas.keys=(type,key)->scriptCommand(this,"__aster.key("+Json.quote(type)+","+Json.quote(key)+","+Json.quote(key)+",false)",false);
        }
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
                    if (getModel().isRollover() || hasFocus()) { g.setColor(WorkspaceTheme.LINE); g.fillOval(2, 4, 26, 26); }
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
                public void mousePressed(MouseEvent e){popup(e);}public void mouseReleased(MouseEvent e){popup(e);}
                private void popup(MouseEvent e){if(!e.isPopupTrigger())return;JPopupMenu menu=new JPopupMenu();JMenuItem parkItem=new JMenuItem(tab.parked?"Resume tab":"Park tab to release memory");parkItem.addActionListener(a->{if(tab.parked)resume(tab);else if(!park(tab))message("Finish loading and close page scripts, forms or media before parking this tab.");});menu.add(parkItem);menu.show(e.getComponent(),e.getX(),e.getY());}
            };
            for (Component c : new Component[]{this, select, close}) c.addMouseListener(tracker);
            add(select, BorderLayout.CENTER); add(close, BorderLayout.EAST); update();
        }
        public Dimension getPreferredSize() { return new Dimension(width, 36); }
        void title(String title) { select.setText(plainLabel(title.length() > 20 ? title.substring(0,20) + "…" : title)); select.setToolTipText(plainLabel(title)); select.getAccessibleContext().setAccessibleName(title); close.getAccessibleContext().setAccessibleName("Close tab: " + title); }
        void update() { boolean selected = current() == tab; select.setSelected(selected);select.setForeground(tab.parked?WorkspaceTheme.MUTED:INK);title((tab.parked?"◌ ":"")+tab.title);setBackground(selected ? PAPER : WorkspaceTheme.CHROME); setBorder(BorderFactory.createMatteBorder(0,0,2,1,selected ? ACCENT : WorkspaceTheme.LINE)); fade(); }
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
        closedTabs.addFirst(tab.location);while(closedTabs.size()>20)closedTabs.removeLast();
        if(tab.imageTask!=null)tab.imageTask.cancel(true);
        stopScripts(tab);
        if(tab.media!=null){tab.media.close();tab.media=null;}
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
        for(String page : new String[]{"settings","bookmarks","history","downloads","playground","compatibility"}) if(s.equals("aster:"+page)) return page;
        return null;
    }
    static URI target(String input) { URI uri; try { uri = URI.create(input.trim()); } catch(IllegalArgumentException e) { return PageLoader.searchOrAddress(input); } return internal(uri) != null ? uri : PageLoader.searchOrAddress(input); }
    void move(int delta) { Tab tab = current(); if(tab == null) return; int next = tab.index+delta; if(next>=0 && next<tab.history.size()) load(tab,tab.history.get(next),next); }
    void load(Tab tab, URI uri, int historyIndex) {
        load(tab,uri,historyIndex,null);
    }
    void load(Tab tab,URI uri,int historyIndex,byte[] formBody) {
        if(tab == null || tab.closed) return; if(tab.pending != null) tab.pending.cancel(true);
        tab.pending=null;tab.parked=false;tab.postPage=formBody!=null;tab.canvas.findText="";
        if(tab.canvas.document==null)tab.canvas.setDocument(Engine.parse(tab.location,"<p>Opening page…</p>"));
        if(tab.imageTask!=null)tab.imageTask.cancel(true);tab.canvas.images.clear();
        stopScripts(tab); tab.setColumnHeaderView(null); tab.original=null;
        if(tab.media!=null){tab.media.close();tab.media=null;}
        int generation = ++tab.generation; tab.downloadRows.clear();
        if(internal(uri) != null) {
            if("playground".equals(internal(uri))) { Engine.Document doc=Engine.parse(uri,resourceText("/playground.html")); tab.canvas.setDocument(doc);tab.setViewportView(tab.canvas);completed(tab,doc,historyIndex);scriptControls(tab,doc);return; }
            String page = internal(uri); Engine.Document doc = Engine.parse(uri,"<title>Aster · "+page+"</title><h1>"+page+"</h1>");
            tab.canvas.setDocument(doc); tab.setViewportView(internalPage(tab,page)); completed(tab,doc,historyIndex); return;
        }
        tab.message = "Opening " + uri + "…"; sync();
        SiteData.Request siteRequest=siteData.request(tab.location,true,formBody==null?"GET":"POST");
        tab.pending = network.submit(() -> { try {
            Engine.Document document = PageLoader.load(uri,formBody,siteData,siteRequest);
            SwingUtilities.invokeLater(() -> { if(tab.closed || tab.generation != generation || disposed) return;
                tab.canvas.setDocument(document); tab.setViewportView(tab.canvas);
                visits.add(0,new Visit(document.uri,document.title)); if(visits.size()>200) visits.remove(visits.size()-1);
                completed(tab,document,historyIndex); refreshInternal(current());
                scriptControls(tab,document);
                loadImages(tab,document,generation);
            });
        } catch(PageLoader.DownloadRequired file) { SwingUtilities.invokeLater(() -> {
            if(!tab.closed && tab.generation == generation && !disposed) showDownloadOffer(tab, file, historyIndex);
        }); } catch(Exception e) { SwingUtilities.invokeLater(() -> { if(!tab.closed && tab.generation==generation && !disposed) { tab.pending=null;tab.message="Could not open page: "+e.getMessage(); sync(); } }); } });sync();
    }
    private void saveCurrentPage() {
        Tab tab=current();
        if(tab==null || tab.canvas.document==null || internal(tab.canvas.document.uri)!=null) { message("Open a website first, or paste a direct link in Downloads."); return; }
        chooseDownload(tab.canvas.document.uri,DownloadManager.filename(tab.canvas.document.uri,null));
    }
    private void reload(){Tab t=current();if(t==null)return;if(t.pending!=null){stopNavigation();return;}if(t.parked)resume(t);else load(t,t.location,t.index);}
    void stopNavigation(){Tab t=current();if(t==null||t.pending==null)return;t.generation++;t.pending.cancel(true);t.pending=null;
        if(t.imageTask!=null)t.imageTask.cancel(true);t.message="Navigation stopped.";sync();}
    void reopenTab(){if(closedTabs.isEmpty()||tabs.getTabCount()>=20)return;openLinkTab(closedTabs.removeFirst(),true);}
    void openLinkTab(URI uri,boolean selected){if(tabs.getTabCount()>=20){message("Close a tab before opening another (20 tabs maximum).");return;}
        Tab tab=new Tab();tabs.addTab("New tab",tab);strip.add(tab.chip,strip.getComponentCount()-1);if(selected)tabs.setSelectedComponent(tab);load(tab,uri,-1);strip.revalidate();strip.repaint();}
    private void buildFindBar(){findBar.setBorder(BorderFactory.createEmptyBorder(7,14,7,14));findQuery.getAccessibleContext().setAccessibleName("Find on page");findBar.add(findQuery);
        JPanel actions=new JPanel(new FlowLayout(FlowLayout.RIGHT,5,0));actions.add(findCount);actions.add(button("↑","Previous match (Shift+F3)",()->findNext(-1)));actions.add(button("↓","Next match (F3)",()->findNext(1)));
        actions.add(button("Close","Close page search",()->{findBar.setVisible(false);if(current()!=null){current().canvas.findText="";current().canvas.repaint();}}));findBar.add(actions,BorderLayout.EAST);
        findQuery.addActionListener(e->findNext(1));findQuery.getDocument().addDocumentListener(new javax.swing.event.DocumentListener(){public void insertUpdate(javax.swing.event.DocumentEvent e){search();}public void removeUpdate(javax.swing.event.DocumentEvent e){search();}public void changedUpdate(javax.swing.event.DocumentEvent e){search();}private void search(){if(current()!=null){current().canvas.findIndex=-1;findNext(1);}}});
        WorkspaceTheme.apply(findBar);findBar.setVisible(false);
    }
    private void findPage(){Tab t=current();if(t==null)return;if(t.parked){resume(t);return;}if(internal(t.location)!=null){message("Open a website to search its page text.");return;}findBar.setVisible(true);findQuery.requestFocusInWindow();findQuery.selectAll();}
    void findNext(int direction){Tab t=current();if(t==null||t.canvas.document==null)return;PageCanvas canvas=t.canvas;canvas.findText=findQuery.getText();canvas.ensureLayout();canvas.search();
        int count=canvas.findRects.size();if(count==0){findCount.setText(canvas.findText.isEmpty()?"":"No match");canvas.repaint();return;}
        canvas.findIndex=Math.floorMod(canvas.findIndex+direction,count);Rectangle r=canvas.findRects.get(canvas.findIndex);canvas.scrollRectToVisible(new Rectangle(0,Math.max(0,(int)(r.y*canvas.scale)-24),20,Math.max(32,(int)(r.height*canvas.scale))));findCount.setText((canvas.findIndex+1)+" / "+count);canvas.repaint();}
    void readPage(){Tab t=current();if(t!=null&&t.canvas.document!=null)showReader(t.title,t.canvas.document.text(),t.location.toString());}
    void showReader(String title,String text,String identity){reader.showDocument(title,text,identity);workspace.setRightComponent(reader);workspace.setDividerSize(6);fitReader();SwingUtilities.invokeLater(()->workspace.setDividerLocation(.62));}
    void closeReader(){reader.close();workspace.setRightComponent(null);workspace.setDividerSize(0);workspace.revalidate();}
    void fitReader(){if(workspace.getRightComponent()==null)return;int orientation=surface.getWidth()>=1050?JSplitPane.HORIZONTAL_SPLIT:JSplitPane.VERTICAL_SPLIT;
        if(workspace.getOrientation()!=orientation){workspace.setOrientation(orientation);SwingUtilities.invokeLater(()->workspace.setDividerLocation(.52));}}
    private JPanel sidebar(){JPanel side=new JPanel();side.setLayout(new BoxLayout(side,BoxLayout.Y_AXIS));side.setBackground(WorkspaceTheme.CHROME);side.setBorder(BorderFactory.createEmptyBorder(14,8,12,8));side.setPreferredSize(new Dimension(96,600));
        JLabel star=new JLabel("✦",SwingConstants.CENTER);star.setFont(new Font(Font.SANS_SERIF,Font.PLAIN,36));star.setForeground(ACCENT);star.setAlignmentX(.5f);star.getAccessibleContext().setAccessibleName("Aster");side.add(star);
        JLabel name=new JLabel("ASTER");name.setForeground(INK);name.setFont(new Font(Font.SANS_SERIF,Font.BOLD,13));name.setAlignmentX(.5f);side.add(name);side.add(Box.createVerticalStrut(22));
        String[] labels={"Home","Read","Files","Saved","Downloads","Park"};Runnable[] actions={()->load(current(),PageLoader.HOME,-1),this::readPage,this::openDocument,()->load(current(),URI.create("aster:bookmarks"),-1),()->load(current(),URI.create("aster:downloads"),-1),()->{if(!park(current()))message("Only fully loaded pages without forms, scripts or media can be parked.");}};
        for(int i=0;i<labels.length;i++){JButton b=button(labels[i],labels[i].equals("Park")?"Park current tab to release memory":labels[i],actions[i]);b.setFont(new Font(Font.SANS_SERIF,Font.PLAIN,13));b.setBorder(BorderFactory.createEmptyBorder(8,2,8,2));b.setAlignmentX(.5f);b.setMaximumSize(new Dimension(80,42));side.add(b);side.add(Box.createVerticalStrut(8));}
        side.add(Box.createVerticalGlue());tabCount.setFont(new Font(Font.SANS_SERIF,Font.PLAIN,13));tabCount.setForeground(WorkspaceTheme.MUTED);tabCount.setAlignmentX(.5f);side.add(tabCount);side.add(Box.createVerticalStrut(12));
        JButton settings=button("Settings","Aster settings",()->load(current(),URI.create("aster:settings"),-1));settings.setAlignmentX(.5f);settings.setBorder(BorderFactory.createEmptyBorder(8,3,8,3));side.add(settings);return side;}
    boolean park(Tab tab){if(tab==null||tab.parked||tab.closed||internal(tab.location)!=null||tab.canvas.document==null||tab.pending!=null||tab.postPage||tab.script!=null||tab.scriptStarting||tab.media!=null)return false;
        if(!PageForms.parse(tab.location,tab.canvas.document.source).isEmpty())return false;
        tab.restoreScroll=tab.getVerticalScrollBar().getValue();tab.generation++;if(tab.imageTask!=null){tab.imageTask.cancel(true);tab.imageTask=null;}tab.original=null;tab.canvas.release();tab.setColumnHeaderView(null);tab.parked=true;tab.message="";showParked(tab);sync();return true;}
    private void showParked(Tab tab){JPanel panel=body("Ready when you are.","This tab is parked. Its page and images have been released from memory.");paragraph(panel,tab.title);paragraph(panel,tab.location.toString());panel.add(button("Resume tab","Reload this parked page",()->resume(tab)));panel.add(Box.createVerticalGlue());tab.setViewportView(panel);}
    void resume(Tab tab){if(tab==null||!tab.parked)return;load(tab,tab.location,tab.index);}
    void rebalanceTabs(){if(!autoPark||restoring)return;java.util.List<Tab> live=new ArrayList<>();for(int i=0;i<tabs.getTabCount();i++){Tab t=(Tab)tabs.getComponentAt(i);if(!t.parked&&internal(t.location)==null)live.add(t);}
        live.sort(Comparator.comparingLong(t->t.lastUsed));int remaining=live.size();for(Tab t:live){if(remaining<=liveTabLimit)break;if(t!=current()&&park(t))remaining--;}}
    private void openDocument(){JFileChooser chooser=new JFileChooser();chooser.setDialogTitle("Open a Word, text or Markdown document");
        if(chooser.showOpenDialog(surface)!=JFileChooser.APPROVE_OPTION)return;File file=chooser.getSelectedFile();
        network.submit(()->{try(java.io.InputStream in=Files.newInputStream(file.toPath())){String text=DocumentReader.read(file.getName(),in);
            SwingUtilities.invokeLater(()->{if(!disposed)showReader(file.getName(),text,file.toURI().toString());});
        }catch(Exception e){SwingUtilities.invokeLater(()->message("Could not read document: "+e.getMessage()));}});
    }
    void saveSession(){if(restoring)return;int count=0,selected=0;
        for(int i=0;i<tabs.getTabCount();i++){Tab t=(Tab)tabs.getComponentAt(i);if(internal(t.location)!=null)continue;String url=t.location.toString();if(url.length()>7000)continue;
            preferences.put("session-url-"+count,url);preferences.put("session-title-"+count,t.title.length()>200?t.title.substring(0,200):t.title);preferences.putInt("session-scroll-"+count,t.parked?Math.max(0,t.restoreScroll):t.getVerticalScrollBar().getValue());
            if(t==current())selected=count;count++;}
        // An empty startup window does not erase the previous session before Restore is offered.
        if(count>0){preferences.putInt("session-count",count);preferences.putInt("session-selected",selected);}
    }
    void restoreSession(){int count=Math.max(0,Math.min(20,preferences.getInt("session-count",0)));
        java.util.List<URI> urls=new ArrayList<>();java.util.List<Integer> offsets=new ArrayList<>();java.util.List<String> titles=new ArrayList<>();
        for(int i=0;i<count;i++)try{urls.add(PageLoader.address(preferences.get("session-url-"+i,"")));offsets.add(Math.max(0,preferences.getInt("session-scroll-"+i,0)));titles.add(preferences.get("session-title-"+i,"Saved tab"));}catch(IllegalArgumentException ignored){}
        if(urls.isEmpty()){message("No saved browsing session yet.");return;}
        boolean reuse=tabs.getTabCount()==1&&"home".equals(internal(current().location));
        if(tabs.getTabCount()+urls.size()-(reuse?1:0)>20){message("Close some tabs before restoring this session.");return;}
        int selected=preferences.getInt("session-selected",0);restoring=true;
        try{for(int i=0;i<urls.size();i++){if(i!=0||!reuse)newTab();Tab t=current();t.canvas.release();t.location=urls.get(i);t.title=titles.get(i);t.restoreScroll=offsets.get(i);t.history.clear();t.history.add(t.location);t.index=0;t.parked=true;showParked(t);}
            int base=tabs.getTabCount()-urls.size();tabs.setSelectedIndex(Math.max(base,Math.min(tabs.getTabCount()-1,base+selected)));}
        finally{restoring=false;}resume(current());sync();
    }
    private void loadImages(Tab tab,Engine.Document document,int generation){
        if(document.scriptsBlocked)return;
        java.util.List<URI> sources=new ArrayList<>();for(Engine.Run r:document.runs)if(r.image!=null&&PageAssets.sameOrigin(document.uri,r.image)&&!sources.contains(r.image)&&sources.size()<8)sources.add(r.image);
        tab.imageTask=assets.submit(()->{long bytes=0;for(URI source:sources){if(Thread.currentThread().isInterrupted())return;
            try{BufferedImage decoded=decodeImage(PageAssets.fetch(document.uri,source,true,siteData.request(document.uri,false,"GET")));long size=(long)decoded.getWidth()*decoded.getHeight()*4;
                if(bytes+size>8*1024*1024)break;bytes+=size;
                SwingUtilities.invokeLater(()->{if(!disposed&&!tab.closed&&tab.generation==generation){tab.canvas.images.put(source,decoded);tab.canvas.repaint();}});
            }catch(Exception ignored){/* Keep the image description when a bounded decode fails. */}
        }});
    }
    static BufferedImage decodeImage(byte[] bytes)throws java.io.IOException{
        try(javax.imageio.stream.ImageInputStream input=new javax.imageio.stream.MemoryCacheImageInputStream(new java.io.ByteArrayInputStream(bytes))){
            Iterator<javax.imageio.ImageReader> readers=ImageIO.getImageReaders(input);if(!readers.hasNext())throw new java.io.IOException("Unknown image format");
            javax.imageio.ImageReader reader=readers.next();try{reader.setInput(input,true,true);int w=reader.getWidth(0),h=reader.getHeight(0);
                if(w<1||h<1||(long)w*h>2_000_000)throw new java.io.IOException("Image dimensions exceed limit");return reader.read(0);
            }finally{reader.dispose();}}
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
        if(MediaRelay.supported(file.uri))panel.add(button("Play in Aster","Play this unencrypted media inside Aster",()->openMedia(tab,()->MediaResource.remote(file.uri,file.uri,siteData))));
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
        if(tab != null && tab.canvas.document != null) { String page=internal(tab.canvas.document.uri); if(page != null && !page.equals("playground")) tab.setViewportView(internalPage(tab,page)); }
    }
    static String resourceText(String name) {
        try(java.io.InputStream input=PreviewMain.class.getResourceAsStream(name); java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream()) {
            if(input==null)throw new java.io.IOException("Missing bundled page"); byte[] buffer=new byte[8192];int n;while((n=input.read(buffer))!=-1)out.write(buffer,0,n);return new String(out.toByteArray(),java.nio.charset.StandardCharsets.UTF_8);
        } catch(Exception e) { throw new IllegalStateException(e); }
    }
    private void scriptControls(Tab tab,Engine.Document document) {
        tab.original=document;
        JPanel bar=new WrapPanel();bar.setBackground(PAPER);
        tab.runScripts=button("Run JavaScript","Run this page's scripts for this visit",()->{if(tab.script==null)startScripts(tab);else{stopScripts(tab);tab.canvas.setDocument(tab.original);tab.runScripts.setText("Run JavaScript");}});
        tab.runScripts.setEnabled(!document.scriptsBlocked);bar.add(tab.runScripts);
        tab.controllerButton=new JToggleButton("Enable controller");tab.controllerButton.setEnabled(false);tab.controllerButton.addActionListener(e->{tab.controllerAllowed=tab.controllerButton.isSelected();tab.canvas.requestFocusInWindow();});bar.add(tab.controllerButton);
        tab.controllerButton.setFont(new Font(Font.SANS_SERIF,Font.PLAIN,14));tab.controllerButton.setBackground(PAPER);tab.controllerButton.setFocusPainted(false);
        if("playground".equals(internal(document.uri)))bar.add(button("Play sample","Play Aster's bundled sample video",()->openMedia(tab,MediaPanel::sample)));
        else if(!document.media.isEmpty())bar.add(button("Play media","Play this page's first direct media source",()->openMedia(tab,()->MediaResource.remote(document.uri,document.media.get(0),siteData))));
        bar.add(button("Read page","Read, select text, make notes or read aloud",this::readPage));
        java.util.List<PageForms.Form> forms=PageForms.parse(document.uri,document.source);
        if(!forms.isEmpty())bar.add(button("Forms","Fill this page's native forms",()->{
            stopScripts(tab);PageForms.Form form=forms.get(0);
            if(forms.size()>1){Object choice=JOptionPane.showInputDialog(surface,"Choose a form","Page forms",JOptionPane.PLAIN_MESSAGE,null,forms.stream().map(f->f.title).toArray(),forms.get(0).title);if(choice==null)return;for(PageForms.Form f:forms)if(f.title.equals(choice)){form=f;break;}}
            java.util.List<String> values=ReadingTools.form(surface,form);if(values==null)return;
            try{PageForms.Submission request=form.submit(values);load(tab,request.uri,-1,request.body);}catch(Exception e){message(e.getMessage());}
        }));
        tab.runScripts.setToolTipText(document.scriptsBlocked?"This page requires a security policy Aster cannot yet enforce. Scripts are disabled.":"Allow this page to run scripts for this visit. Permission resets when you navigate.");
        WorkspaceTheme.apply(bar);
        tab.setColumnHeaderView(bar);
    }
    private void openMedia(Tab tab,MediaPanel.Source source) {
        stopScripts(tab);if(tab.runScripts!=null)tab.runScripts.setText("Run JavaScript");
        if(tab.media!=null)tab.media.close();
        try{tab.media=new MediaPanel(source,()->{tab.media=null;tab.setViewportView(tab.canvas);});tab.setViewportView(tab.media);}
        catch(Throwable e){tab.message="Media could not start: "+e.getMessage();sync();}
    }
    void startScripts(Tab tab) {
        if(tab.original==null || tab.script!=null)return;
        int running=0;for(int i=0;i<tabs.getTabCount();i++)if(((Tab)tabs.getComponentAt(i)).script!=null||((Tab)tabs.getComponentAt(i)).scriptStarting)running++;
        if(running>=4){message("Up to four script pages can run at once. Stop one first.");return;}
        final int generation=tab.generation;final Engine.Document original=tab.original;tab.scriptStarting=true;tab.runScripts.setEnabled(false);tab.message="Starting page scripts…";sync();
        tab.scriptTask=scripts.submit(()->{
            ScriptSession session=null;
            try {
                session=new ScriptSession(siteData,tab.sessionStorage);final ScriptSession created=session;
                SwingUtilities.invokeAndWait(()->{if(disposed||tab.closed||tab.generation!=generation||!tab.scriptStarting)created.close();else tab.script=created;});
                if(!session.alive())return;
                Map<String,Object> snapshot=session.start(original); final ScriptSession started=session;
                SwingUtilities.invokeLater(()->{if(disposed||tab.closed||tab.generation!=generation||tab.script!=started){started.close();return;}
                    tab.scriptTask=null;tab.scriptStarting=false;applySnapshot(tab,snapshot,false);tab.runScripts.setEnabled(true);tab.runScripts.setText("Stop JavaScript");tab.controllerButton.setEnabled(true);tab.canvas.requestFocusInWindow();
                    final long began=System.nanoTime(); tab.scriptTimer=new javax.swing.Timer(50,e->{if(current()==tab&&!tab.scriptBusy)scriptCommand(tab,"__aster.tick("+((System.nanoTime()-began)/1_000_000L)+")",false);});tab.scriptTimer.start();
                });
            }catch(Exception e){if(session!=null)session.close();SwingUtilities.invokeLater(()->{if(!tab.closed&&tab.generation==generation){stopScripts(tab);tab.runScripts.setEnabled(true);tab.message="Could not run scripts: "+e.getMessage();sync();}});}
        });
    }
    private void stopScripts(Tab tab) {
        if(tab.scriptTask!=null){tab.scriptTask.cancel(true);tab.scriptTask=null;}
        if(tab.scriptTimer!=null){tab.scriptTimer.stop();tab.scriptTimer=null;}
        ScriptSession session=tab.script;tab.script=null;if(session!=null)session.close();tab.scriptBusy=false;tab.controllerAllowed=false;tab.scriptStarting=false;tab.inputCommands.clear();
        if(tab.mediaId!=0){if(tab.media!=null)tab.media.close();tab.media=null;tab.mediaId=0;tab.mediaSource="";tab.setViewportView(tab.canvas);}tab.mediaEvents.clear();tab.mediaTime.set(null);
        if(tab.controllerButton!=null){tab.controllerButton.setSelected(false);tab.controllerButton.setEnabled(false);}
        if(tab.runScripts!=null){tab.runScripts.setText("Run JavaScript");tab.runScripts.setEnabled(tab.original!=null&&!tab.original.scriptsBlocked);}
        if(session!=null&&tab.canvas.document!=null)tab.canvas.setDocument(Engine.parse(tab.canvas.document.uri,tab.canvas.document.source));
    }
    @SuppressWarnings("unchecked") private void scriptCommand(Tab tab,String command,boolean click) {
        ScriptSession session=tab.script;if(session==null||current()!=tab)return;
        if(tab.scriptBusy){if(!command.startsWith("__aster.tick(")&&tab.inputCommands.size()<32)tab.inputCommands.addLast(command);return;}
        tab.scriptBusy=true;int generation=tab.generation;boolean allow=tab.controllerAllowed;
        scripts.submit(()->{try{
            session.controller(allow);session.pump();String mediaEvent;int count=0;while(count++<64&&(mediaEvent=tab.mediaEvents.poll())!=null)session.eval(mediaEvent);
            String mediaTime=tab.mediaTime.getAndSet(null);if(mediaTime!=null)session.eval(mediaTime);
            Map<String,Object> snapshot=(Map<String,Object>)session.eval(command);
            SwingUtilities.invokeLater(()->{if(!tab.closed&&tab.generation==generation&&tab.script==session){tab.scriptBusy=false;applySnapshot(tab,snapshot,click);if(!tab.inputCommands.isEmpty()){String next=tab.inputCommands.removeFirst();scriptCommand(tab,next,next.startsWith("__aster.click("));}}});
        }catch(Exception e){SwingUtilities.invokeLater(()->{if(!tab.closed&&tab.generation==generation&&tab.script==session){stopScripts(tab);tab.runScripts.setText("Run JavaScript");tab.message="JavaScript stopped: "+e.getMessage();sync();}});}});
    }
    private void applySnapshot(Tab tab,Map<String,Object> snapshot,boolean click) {
        try {
            Object update=snapshot.get("html");if(update!=null&&!(update instanceof String))throw new IllegalArgumentException("Invalid page snapshot");
            if(update instanceof String){String html=(String)update;if(html.length()>Engine.MAX_SOURCE)throw new IllegalArgumentException("Page snapshot exceeds limit");
                if(!html.equals(tab.canvas.document.source))tab.canvas.setDocument(Engine.parseInteractive(tab.original.uri,html,tab.original.css));}
            tab.chip.title(tab.canvas.document.title);tabs.setTitleAt(tabs.indexOfComponent(tab),plainLabel(tab.canvas.document.title));
            Object errors=snapshot.get("errors");tab.message=errors instanceof java.util.List&&!((java.util.List<?>)errors).isEmpty()?"Page script: "+((java.util.List<?>)errors).get(0):"";
            applyMediaCommands(tab,snapshot.get("media"),click);
            if(click && snapshot.get("navigation") instanceof String) { URI next=PageLoader.link(tab.original.uri,(String)snapshot.get("navigation"));if(next!=null){load(tab,next,-1);return;} }
            sync();
        }catch(Exception e){stopScripts(tab);tab.message="Could not display script changes: "+e.getMessage();sync();}
    }
    @SuppressWarnings("unchecked") private void applyMediaCommands(Tab tab,Object value,boolean gesture)throws Exception{
        if(!(value instanceof java.util.List))return;java.util.List<?> commands=(java.util.List<?>)value;if(commands.size()>32)throw new IllegalArgumentException("Media command limit");
        for(Object raw:commands){
            if(!(raw instanceof Map))throw new IllegalArgumentException("Invalid media command");Map<String,Object> c=(Map<String,Object>)raw;
            int id=PageNetwork.integer(c,"id");String kind=PageNetwork.string(c,"kind");if(id<=0||id>10001)throw new IllegalArgumentException("Invalid media element");
            if(kind.equals("play")){
                String src=PageNetwork.string(c,"src");int request=PageNetwork.integer(c,"request");
                boolean sample="playground".equals(internal(tab.original.uri))&&src.equals("aster-sample.mp4");
                URI uri=sample?null:PageLoader.link(tab.original.uri,src);
                if(!sample&&(uri==null||!MediaRelay.supported(uri))){mediaReply(tab,id,"error",Map.of(),"Unsupported media source",request);continue;}
                String canonical=sample?"aster-sample.mp4":uri.toString();
                if(tab.mediaId==id&&tab.media!=null&&tab.media.usable()&&tab.mediaSource.equals(canonical)){tab.media.control("play",null);continue;}
                if(!gesture){mediaReply(tab,id,"denied",Map.of(),"Click this page's Play button to allow playback",request);continue;}
                if(tab.media!=null){int old=tab.mediaId;tab.media.close();tab.mediaTime.set(null);if(old>0&&old!=id)mediaReply(tab,old,"emptied",Map.of("paused",true,"readyState",0),null,0);}
                tab.mediaId=id;tab.mediaSource=canonical;ScriptSession owner=tab.script;final URI source=uri;
                try{
                    tab.media=new MediaPanel(sample?MediaPanel::sample:()->MediaResource.remote(tab.original.uri,source,siteData),()->{
                        if(tab.script==owner){mediaReply(tab,id,"emptied",Map.of("paused",true,"readyState",0),null,0);tab.media=null;tab.mediaId=0;tab.mediaTime.set(null);tab.mediaSource="";tab.setViewportView(tab.canvas);}
                    },(event,state)->{
                        if(!owner.alive())return;Map<String,Object> copy=new LinkedHashMap<>(state);copy.put("src",canonical);
                        String code="__aster.mediaUpdate("+id+","+Json.stringify(copy)+","+Json.quote(event)+","+(event.equals("error")?Json.quote(String.valueOf(state.get("message"))):"null")+",0);void 0";
                        if(event.equals("timeupdate"))tab.mediaTime.set(code);else {tab.mediaTime.set(null);if(tab.mediaEvents.size()<64)tab.mediaEvents.add(code);}
                    });
                    tab.media.setPreferredSize(new Dimension(800,340));JPanel content=new JPanel(new BorderLayout());content.setBackground(PAPER);content.add(tab.media,BorderLayout.NORTH);content.add(tab.canvas,BorderLayout.CENTER);tab.setViewportView(content);
                    for(String property:Arrays.asList("volume","muted")){Object v=c.get(property);if(property.equals("volume"))validMediaNumber(v,0,1);else if(!(v instanceof Boolean))throw new IllegalArgumentException("Invalid mute value");tab.media.control(property,v);}
                    Object time=c.get("time");validMediaNumber(time,0,86400*365);tab.media.control("seek",time);
                }catch(Throwable e){if(tab.media!=null)tab.media.close();tab.media=null;tab.mediaId=0;tab.mediaSource="";tab.setViewportView(tab.canvas);mediaReply(tab,id,"error",Map.of(),"Media could not start: "+e.getMessage(),request);}
            }else if(tab.mediaId==id&&tab.media!=null){
                if(kind.equals("unload")){tab.media.close();tab.media=null;tab.mediaId=0;tab.mediaTime.set(null);tab.mediaSource="";tab.setViewportView(tab.canvas);mediaReply(tab,id,"emptied",Map.of("paused",true,"readyState",0,"currentTime",0),null,0);}
                else if(kind.equals("pause"))tab.media.control(kind,null);
                else if(kind.equals("seek")||kind.equals("volume")){validMediaNumber(c.get("value"),0,kind.equals("volume")?1:86400*365);tab.media.control(kind,c.get("value"));}
                else if(kind.equals("muted")){if(!(c.get("value") instanceof Boolean))throw new IllegalArgumentException("Invalid mute value");tab.media.control(kind,c.get("value"));}
                else throw new IllegalArgumentException("Unsupported media command");
            }
        }
    }
    private static void validMediaNumber(Object value,double min,double max){if(!(value instanceof Number)||!Double.isFinite(((Number)value).doubleValue())||((Number)value).doubleValue()<min||((Number)value).doubleValue()>max)throw new IllegalArgumentException("Invalid media control value");}
    private void mediaReply(Tab tab,int id,String event,Map<String,Object> state,String error,int request){
        if(tab.mediaEvents.size()<64)tab.mediaEvents.add("__aster.mediaUpdate("+id+","+Json.stringify(state)+","+Json.quote(event)+","+(error==null?"null":Json.quote(error))+","+request+");void 0");
    }
    private void completed(Tab tab, Engine.Document document, int historyIndex) {
        tab.pending=null;tab.location=document.uri;tab.title=document.title;tab.parked=false;
        if(historyIndex>=0) tab.index=historyIndex;
        else { while(tab.history.size()>tab.index+1) tab.history.remove(tab.history.size()-1); tab.history.add(document.uri); if(tab.history.size()>100) tab.history.remove(0); tab.index=tab.history.size()-1; }
        tabs.setTitleAt(tabs.indexOfComponent(tab),plainLabel(document.title)); tab.chip.title(document.title); tab.message=""; sync();
        tab.getVerticalScrollBar().setValue(0);
        if(tab.restoreScroll>=0){int offset=tab.restoreScroll;tab.restoreScroll=-1;SwingUtilities.invokeLater(()->{tab.canvas.ensureLayout();tab.canvas.revalidate();SwingUtilities.invokeLater(()->tab.getVerticalScrollBar().setValue(offset));});}
        if(!restoring){saveSession();SwingUtilities.invokeLater(this::rebalanceTabs);}
    }
    private void message(String text) { if(current()!=null) current().message=text; status.setText(plainLabel(text)); status.setVisible(!text.isEmpty()); }
    private void sync() {
        Tab tab = current(); if(tab == null) return;
        if(!address.hasFocus())address.setText(tab.location.toString());
        reloadButton.setText(tab.pending==null?"↻":"×");reloadButton.getAccessibleContext().setAccessibleName(tab.pending==null?"Reload (Ctrl+R)":"Stop loading (Escape)");
        int parked=0;for(int i=0;i<tabs.getTabCount();i++)if(((Tab)tabs.getComponentAt(i)).parked)parked++;tabCount.setText((tabs.getTabCount()-parked)+" live");tabCount.setToolTipText(parked+" parked · "+tabs.getTabCount()+" total tabs");
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
        JPopupMenu popup=new JPopupMenu(); JPanel grid=new JPanel(new GridLayout(0,2,8,8)); grid.setBorder(BorderFactory.createEmptyBorder(10,10,10,10));
        for(String page:new String[]{"bookmarks","history","downloads","settings","playground"}) { JButton b=button(capitalize(page),"Open "+page,()->{ popup.setVisible(false); load(current(),URI.create("aster:"+page),-1); }); b.setPreferredSize(new Dimension(112,76)); grid.add(b); }
        String[] labels={"Read page","Open document","Restore session","Reopen tab","Site support"};Runnable[] commands={this::readPage,this::openDocument,this::restoreSession,this::reopenTab,()->load(current(),URI.create("aster:compatibility"),-1)};
        for(int i=0;i<labels.length;i++){Runnable command=commands[i];JButton b=button(labels[i],labels[i],()->{popup.setVisible(false);command.run();});b.setPreferredSize(new Dimension(112,76));grid.add(b);}
        WorkspaceTheme.apply(grid);popup.add(grid); popup.show(surface,Math.max(0,surface.getWidth()-260),88);
    }
    private static String capitalize(String s) { return s.substring(0,1).toUpperCase(Locale.ROOT)+s.substring(1); }
    private static final class WrapPanel extends JPanel {
        WrapPanel(){super(new FlowLayout(FlowLayout.LEFT,8,4));}
        public Dimension getPreferredSize(){int width=getParent()==null?600:Math.max(240,getParent().getWidth());int x=8,height=8,row=0;
            for(Component c:getComponents())if(c.isVisible()){Dimension d=c.getPreferredSize();if(x>8&&x+d.width+8>width){height+=row+4;x=8;row=0;}x+=d.width+8;row=Math.max(row,d.height);}return new Dimension(width,height+row);}
    }
    private static final class PagePanel extends JPanel implements Scrollable {
        public Dimension getPreferredScrollableViewportSize() { return new Dimension(800,600); }
        public int getScrollableUnitIncrement(Rectangle r,int axis,int direction) { return 28; }
        public int getScrollableBlockIncrement(Rectangle r,int axis,int direction) { return Math.max(28,r.height-28); }
        public boolean getScrollableTracksViewportWidth() { return true; }
        public boolean getScrollableTracksViewportHeight() { return getParent()!=null && getPreferredSize().height<getParent().getHeight(); }
    }
    private JPanel body(String title, String description) {
        JPanel panel = new PagePanel(); panel.setLayout(new BoxLayout(panel,BoxLayout.Y_AXIS)); panel.setBackground(PAPER); panel.setBorder(BorderFactory.createEmptyBorder(28,32,28,32));
        JLabel brand=new JLabel("A S T E R   /   YOUR WORKSPACE"); brand.putClientProperty("aster.accent",Boolean.TRUE);brand.setForeground(ACCENT); brand.setFont(new Font(Font.SANS_SERIF,Font.BOLD,14)); brand.setAlignmentX(Component.LEFT_ALIGNMENT); panel.add(brand); panel.add(Box.createVerticalStrut(18));
        JLabel heading=new JLabel(title); heading.setForeground(INK); heading.setFont(new Font(Font.SANS_SERIF,Font.BOLD,28)); heading.setAlignmentX(Component.LEFT_ALIGNMENT); panel.add(heading); panel.add(Box.createVerticalStrut(10));
        paragraph(panel,description); return panel;
    }
    private void paragraph(JPanel panel,String text) { JTextArea label=new JTextArea(text,2,36); label.setLineWrap(true); label.setWrapStyleWord(true); label.setEditable(false); label.setFocusable(false); label.setOpaque(false); label.setForeground(INK); label.setFont(new Font(Font.SANS_SERIF,Font.PLAIN,15)); label.setAlignmentX(Component.LEFT_ALIGNMENT); label.setMaximumSize(new Dimension(Integer.MAX_VALUE,70)); panel.add(label); panel.add(Box.createVerticalStrut(16)); }
    private JPanel internalPage(Tab tab,String page) {
        JPanel panel=body(page.equals("home") ? "Your space to explore." : capitalize(page), page.equals("home") ? "Browse, read and keep your ideas together." : "Aster tools · on this computer");
        if(page.equals("home")) {
            JPanel links=new JPanel(new GridLayout(3,2,10,10)); links.setOpaque(false); links.setAlignmentX(Component.LEFT_ALIGNMENT); links.setMaximumSize(new Dimension(Integer.MAX_VALUE,180));links.setPreferredSize(new Dimension(560,180));
            String[][] targets={{"DuckDuckGo search","https://html.duckduckgo.com/html/"},{"Example website","https://example.com"},{"Your bookmarks","aster:bookmarks"},{"Recent pages","aster:history"},{"Try the playground","aster:playground"},{"Website compatibility","aster:compatibility"}};
            for(String[] item:targets) links.add(button(item[0],item[0],()->load(tab,target(item[1]),-1))); panel.add(links); panel.add(Box.createVerticalStrut(24));
            JPanel reading=new JPanel(new FlowLayout(FlowLayout.LEFT));reading.setOpaque(false);reading.setAlignmentX(Component.LEFT_ALIGNMENT);reading.setMaximumSize(new Dimension(Integer.MAX_VALUE,48));
            reading.add(button("Open document","Open Word, text or Markdown",this::openDocument));reading.add(button("Restore tabs","Restore saved tabs and reading positions",this::restoreSession));panel.add(reading);
            JPanel awareness=new JPanel(new FlowLayout(FlowLayout.LEFT,16,0)); awareness.setOpaque(false); awareness.setAlignmentX(Component.LEFT_ALIGNMENT); awareness.setMaximumSize(new Dimension(Integer.MAX_VALUE,120));
            final int count=tabs.getTabCount(); JComponent ring=new JComponent() { protected void paintComponent(Graphics graphics) { Graphics2D g=(Graphics2D)graphics.create(); g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON); g.setStroke(new BasicStroke(8)); g.setColor(WorkspaceTheme.LINE); g.drawOval(8,8,94,94); g.setColor(ACCENT); g.drawArc(8,8,94,94,90,-Math.round(360f*count/20)); g.setFont(new Font(Font.SANS_SERIF,Font.BOLD,20)); String value=count+" / 20"; g.drawString(value,(110-g.getFontMetrics().stringWidth(value))/2,61); g.dispose(); } };
            int parked=0;for(int i=0;i<tabs.getTabCount();i++)if(((Tab)tabs.getComponentAt(i)).parked)parked++;
            ring.setPreferredSize(new Dimension(110,110)); ring.setToolTipText("Open tabs: "+count+" of 20"); awareness.add(ring); awareness.add(new JLabel((count-parked)+" live · "+parked+" parked · "+bookmarkCount()+" saved")); panel.add(awareness); panel.add(Box.createVerticalStrut(22));
            paragraph(panel,"Aster 0.4 preview · Simple websites and reading are ready to try. Netflix, Prime Video and cloud gaming still need engine work. Check Website compatibility before testing a service.");
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
            JCheckBox parking=new JCheckBox("Park older background tabs",autoPark);parking.setOpaque(false);parking.setFont(new Font(Font.SANS_SERIF,Font.PLAIN,15));parking.addActionListener(e->{autoPark=parking.isSelected();preferences.putBoolean("autoPark",autoPark);rebalanceTabs();});panel.add(parking);
            JComboBox<Integer> limit=new JComboBox<>(new Integer[]{2,3,4,5,6,7,8});limit.setSelectedItem(liveTabLimit);limit.getAccessibleContext().setAccessibleName("Maximum live website tabs before parking");limit.setMaximumSize(new Dimension(180,34));limit.setAlignmentX(Component.LEFT_ALIGNMENT);limit.addActionListener(e->{liveTabLimit=(Integer)limit.getSelectedItem();preferences.putInt("liveTabLimit",liveTabLimit);rebalanceTabs();});panel.add(limit);panel.add(Box.createVerticalStrut(12));
            paragraph(panel,"Parking releases a page and its images, then reloads it when selected. Active tabs and pages with forms, running scripts or media stay live. Restore tabs opens only the selected saved website.");
            panel.add(button("Clear website data","Sign out of websites and clear their saved data",()->{if(JOptionPane.showConfirmDialog(surface,"Clear cookies and website storage, and return open tabs to Home? Bookmarks, reader notes and downloaded files stay saved.","Clear website data",JOptionPane.OK_CANCEL_OPTION)==JOptionPane.OK_OPTION)clearWebsiteData();}));
        } else if(page.equals("compatibility")) {
            paragraph(panel,"This preview uses Aster's own page renderer. A familiar interface does not yet mean full modern-web compatibility.");
            paragraph(panel,"Available to test: simple HTML and CSS, images, same-site basic forms, downloads, bookmarks, reading and notes. Desktop also has a limited JavaScript runtime and unencrypted MP4/HLS playback.");
            paragraph(panel,"Netflix and Prime Video: unsupported. Aster now has basic website sessions but still needs a standards-complete DOM, media streaming APIs and an approved DRM module. A hardware DRM indicator alone does not enable playback.");
            paragraph(panel,"Cloud gaming: unsupported. WebRTC transport, real-time audio/video, complete graphics and input APIs, and service acceptance must work together. The playground only demonstrates local controller input.");
            paragraph(panel,"Check the release notes for tested platforms and known issues. An unsupported page may load incompletely, even when some text is visible.");
            panel.add(button("Engine roadmap","Open the implementation and compatibility roadmap",()->load(tab,URI.create("https://github.com/xatusbetazx17/aster-browser/blob/codex/aster-webkit-desktop/experiments/aster-engine/RELEASE_0.4.md"),-1)));
        }
        panel.add(Box.createVerticalGlue());WorkspaceTheme.apply(panel); return panel;
    }
    void clearWebsiteData(){
        for(DownloadManager.Transfer transfer:downloads.snapshot())if(!transfer.finished())transfer.cancel();
        for(int i=0;i<tabs.getTabCount();i++){Tab tab=(Tab)tabs.getComponentAt(i);stopScripts(tab);tab.sessionStorage.clear();load(tab,PageLoader.HOME,-1);}
        siteData.clear();try{siteData.flush();message("Website data cleared. Bookmarks and notes are preserved.");}catch(Exception e){message("Website data cleared in memory, but its saved file could not be updated.");}
    }
    private static String plainLabel(String text) { return "\u200b"+text; }
    static final class PageCanvas extends JPanel implements Scrollable {
        final Map<URI,BufferedImage> images=new HashMap<>();String findText="";
        int findIndex=-1;String searched="";Engine.Layout searchedLayout;
        final java.util.List<Rectangle> findRects=new ArrayList<>();final Set<Engine.Draw> foundDraws=new HashSet<>();
        private final Map<String,Font> fonts=new HashMap<>();
        private Font cachedFont(Engine.Style style){String key=style.size+":"+style.bold+":"+style.italic+":"+style.pre;return fonts.computeIfAbsent(key,k->font(style));}
        Engine.Document document; Engine.Layout layout; int layoutWidth = -1; double scale = 1;
        java.util.function.Consumer<URI> navigate = uri -> { };
        java.util.function.Consumer<URI> openTab = uri -> { };
        java.util.function.Consumer<URI> save = uri -> { };
        java.util.function.IntConsumer action = id -> { };
        java.util.function.BiConsumer<String,String> keys = (type,key) -> { };
        PageCanvas() {
            setBackground(Color.WHITE); setFocusable(true);
            getAccessibleContext().setAccessibleName("Aster page");
            addKeyListener(new KeyAdapter(){public void keyPressed(KeyEvent e){keys.accept("keydown",key(e));}public void keyReleased(KeyEvent e){keys.accept("keyup",key(e));}private String key(KeyEvent e){switch(e.getKeyCode()){case KeyEvent.VK_LEFT:return "ArrowLeft";case KeyEvent.VK_RIGHT:return "ArrowRight";case KeyEvent.VK_UP:return "ArrowUp";case KeyEvent.VK_DOWN:return "ArrowDown";case KeyEvent.VK_ENTER:return "Enter";case KeyEvent.VK_ESCAPE:return "Escape";default:return e.getKeyChar()==KeyEvent.CHAR_UNDEFINED?KeyEvent.getKeyText(e.getKeyCode()):String.valueOf(e.getKeyChar());}}});
            addMouseListener(new MouseAdapter() {
                public void mousePressed(MouseEvent e) { popup(e); }
                public void mouseReleased(MouseEvent e) { popup(e); }
                private void popup(MouseEvent e) {
                    if(!e.isPopupTrigger() || layout==null) return;
                    URI uri=layout.hit((float)(e.getX()/scale),(float)(e.getY()/scale)); if(uri==null)return;
                    JPopupMenu menu=new JPopupMenu(); JMenuItem open=new JMenuItem("Open link"); open.addActionListener(event->navigate.accept(uri)); menu.add(open);
                    JMenuItem background=new JMenuItem("Open link in new tab");background.addActionListener(event->openTab.accept(uri));menu.add(background);
                    JMenuItem download=new JMenuItem("Save link as…"); download.addActionListener(event->save.accept(uri)); menu.add(download); menu.show(PageCanvas.this,e.getX(),e.getY());
                }
                public void mouseClicked(MouseEvent e) {
                    if(layout==null)return;URI uri=layout.hit((float)(e.getX()/scale),(float)(e.getY()/scale));
                    if(uri!=null&&(SwingUtilities.isMiddleMouseButton(e)||SwingUtilities.isLeftMouseButton(e)&&(e.isControlDown()||e.isMetaDown()))){openTab.accept(uri);return;}
                    if (SwingUtilities.isLeftMouseButton(e)) { requestFocusInWindow();int id=layout.actionAt((float)(e.getX()/scale),(float)(e.getY()/scale));if(id>0){action.accept(id);return;}if(uri!=null)navigate.accept(uri); }
                }
            });
            addMouseMotionListener(new MouseMotionAdapter() { public void mouseMoved(MouseEvent e) {
                URI uri = layout == null ? null : layout.hit((float)(e.getX()/scale), (float)(e.getY()/scale));
                setCursor(Cursor.getPredefinedCursor(uri == null ? Cursor.DEFAULT_CURSOR : Cursor.HAND_CURSOR));
                setToolTipText(uri == null ? null : uri.toString());
            }});
        }
        void setDocument(Engine.Document doc) { document = doc; layout = null; layoutWidth = -1;findIndex=-1; getAccessibleContext().setAccessibleDescription(doc.text()); revalidate(); repaint(); }
        void release(){document=null;layout=null;layoutWidth=-1;images.clear();fonts.clear();findRects.clear();foundDraws.clear();searchedLayout=null;getAccessibleContext().setAccessibleDescription("Parked page");}
        void search(){if(layout==searchedLayout&&findText.equals(searched))return;searchedLayout=layout;searched=findText;findIndex=-1;findRects.clear();foundDraws.clear();if(layout==null||findText.trim().isEmpty())return;
            StringBuilder text=new StringBuilder();int[] starts=new int[layout.items.size()];for(int i=0;i<starts.length;i++){starts[i]=text.length();text.append(layout.items.get(i).text).append(' ');}
            java.util.regex.Matcher matches=java.util.regex.Pattern.compile(java.util.regex.Pattern.quote(findText.trim().replaceAll("\\s+"," ")),java.util.regex.Pattern.CASE_INSENSITIVE|java.util.regex.Pattern.UNICODE_CASE).matcher(text);
            while(matches.find()&&findRects.size()<1000){int at=Arrays.binarySearch(starts,matches.start());if(at<0)at=Math.max(0,-at-2);Rectangle rect=null;
                for(int i=at;i<starts.length&&starts[i]<matches.end();i++){Engine.Draw d=layout.items.get(i);foundDraws.add(d);Rectangle r=new Rectangle((int)d.x,(int)d.y,(int)Math.ceil(d.width),(int)Math.ceil(d.height));rect=rect==null?r:rect.union(r);}if(rect!=null)findRects.add(rect);}
        }
        static Font font(Engine.Style s) { return new Font(s.pre ? Font.MONOSPACED : Font.SANS_SERIF, (s.bold ? Font.BOLD : 0) | (s.italic ? Font.ITALIC : 0), Math.round(s.size)); }
        void ensureLayout() {
            if (document != null && (layout == null || layoutWidth != getWidth())) {
                layoutWidth = getWidth();
                try { layout = Engine.layout(document, (float)(getWidth()/scale), (text, style) -> getFontMetrics(cachedFont(style)).stringWidth(text)); }
                catch (IllegalArgumentException e) { document = Engine.parse(PageLoader.HOME, "<h1>Page is too complex</h1><p>" + PageLoader.escape(e.getMessage()) + "</p>"); layout = Engine.layout(document, (float)(getWidth()/scale), (t, s) -> getFontMetrics(font(s)).stringWidth(t)); }
                revalidate();
            }
        }
        protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics); ensureLayout(); if (layout == null) return;search();
            Graphics2D g = (Graphics2D) graphics.create(); g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.scale(scale, scale); Rectangle clip = g.getClipBounds();
            for (int i=layout.firstVisible(clip==null?0:clip.y);i<layout.items.size();i++) {Engine.Draw draw=layout.items.get(i);
                if(clip!=null&&draw.y>clip.y+clip.height)break;
                if (clip != null && (draw.y + draw.height < clip.y || draw.y > clip.y + clip.height)) continue;
                if(draw.image!=null){BufferedImage bitmap=images.get(draw.image);
                    if(bitmap!=null){double fit=Math.min(draw.width/bitmap.getWidth(),draw.height/bitmap.getHeight());g.drawImage(bitmap,(int)draw.x,(int)draw.y,(int)(bitmap.getWidth()*fit),(int)(bitmap.getHeight()*fit),null);continue;}
                    g.setColor(new Color(0xe8eeec));g.fillRect((int)draw.x,(int)draw.y,(int)draw.width,(int)draw.height);}
                if(foundDraws.contains(draw)){g.setColor(new Color(0xffe38a));g.fillRect((int)draw.x,(int)draw.y,(int)draw.width,(int)draw.height);}
                g.setFont(cachedFont(draw.style)); g.setColor(new Color(draw.style.color, true));
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
        try { Class.forName("jdk.swing.interop.SwingInterOpUtils"); }
        catch(ClassNotFoundException e){throw new IllegalStateException("Bundled runtime lacks jdk.unsupported.desktop",e);}
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
                            ImageIO.write(screenshot, "png", new File(output));
                        } catch (Exception e) { throw new RuntimeException(e); }
                        System.out.println("Aster native desktop passed: welcome, hover fade, background close, shrink cleanup and window rendering.");
                        finishSmoke();
                    });
                });
            });
        });
    }
    public static void main(String[] args) throws Exception {
        if(args.length>0&&args[0].equals("--benchmark")){EngineBenchmark.run(Paths.get(args.length>1?args[1]:"aster-benchmark.json"));return;}
        if (args.length > 0 && args[0].equals("--render-test")) { renderTest(args.length > 1 ? args[1] : "aster-engine.png"); return; }
        if(args.length>0&&(args[0].equals("--media-smoke")||args[0].equals("--stream-smoke"))){
            if(args.length<2)throw new IllegalArgumentException("--media-smoke requires an output image path");
            Thread.setDefaultUncaughtExceptionHandler((thread,error)->{mediaFailure(args[1],error.toString());error.printStackTrace();System.exit(1);});
        }
        SwingUtilities.invokeLater(() -> {
            boolean mediaSmoke = args.length > 0 && args[0].equals("--media-smoke");
            boolean streamSmoke=args.length>0&&args[0].equals("--stream-smoke");
            boolean smoke = args.length > 0 && (args[0].equals("--smoke")||mediaSmoke||streamSmoke);
            PreviewMain app = smoke ? new PreviewMain(Preferences.userRoot().node("io/aster/ui-smoke-" + UUID.randomUUID())) : new PreviewMain();
            app.window.setVisible(true);
            if(streamSmoke){StreamSmoke.run(app,args[1]);}
            else if(mediaSmoke){app.load(app.current(),URI.create("aster:playground"),-1);app.openMedia(app.current(),MediaPanel::sample);
                if(app.current().media==null){app.window.dispose();System.exit(1);return;}
                final javax.swing.Timer deadline=new javax.swing.Timer(25000,e->{mediaFailure(args[1],"Media smoke timed out");app.window.dispose();System.exit(1);});deadline.setRepeats(false);deadline.start();
                app.current().media.evidence(Paths.get(args[1]),()->{deadline.stop();app.finishSmoke();},error->{deadline.stop();mediaFailure(args[1],error);app.window.dispose();System.exit(1);});
            } else if (smoke) app.nativeSmoke(args[1]);
        });
    }
    private static void preferencesRemove(Preferences preferences) throws Exception { preferences.removeNode(); }
    void finishSmoke(){dispose();try{preferencesRemove(preferences);}catch(Exception ignored){}window.dispose();}
    private static void mediaFailure(String output,String error){System.err.println(error);try{Files.write(Paths.get(output+".log"),error.getBytes(java.nio.charset.StandardCharsets.UTF_8));}catch(Exception ignored){}}
}
