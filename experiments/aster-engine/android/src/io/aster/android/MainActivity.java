package io.aster.android;

import android.app.*;
import android.os.Bundle;
import android.content.*;
import android.graphics.*;
import android.view.*;
import android.view.inputmethod.*;
import android.widget.*;
import android.text.InputType;
import io.aster.engine.*;
import java.net.URI;
import java.util.*;
import java.util.concurrent.*;
import java.io.*;

/** Android widgets and Canvas only. There is deliberately no android.webkit dependency. */
public final class MainActivity extends Activity {
    private EditText address;
    private TextView status;
    private PageView page;
    private ScrollView scroll;
    private ScrollView homePage;
    private Button navBack,navForward,tabButton;
    private static final int INK=0xffe8eef5,ACCENT=0xff64dfce,PAPER=0xff121922,CHROME=0xff0b1017;
    private final List<URI> history = new ArrayList<>();
    private int index = -1, generation;
    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private Future<?> pending;
    private SharedPreferences preferences;
    private static SiteData profileData;
    private SiteData siteData;
    private Engine.Document document;
    private ReadingTools reading;
    private final ExecutorService assets=Executors.newSingleThreadExecutor();private Future<?> images;
    private final Map<URI,Bitmap> bitmaps=new HashMap<>();
    private static final class MobileTab {URI uri=PageLoader.HOME;String title="New tab";int scroll,index=-1;final List<URI> history=new ArrayList<>();MobileTab copy(){MobileTab t=new MobileTab();t.uri=uri;t.title=title;t.scroll=scroll;t.index=index;t.history.addAll(history);return t;}}
    private final List<MobileTab> tabs=new ArrayList<>(),previousTabs=new ArrayList<>();private int selectedTab,restoreScroll;
    private URI downloadSource;private Future<?> download;
    private final ExecutorService transfers=Executors.newSingleThreadExecutor();

    public void onCreate(Bundle savedState) {
        super.onCreate(savedState);
        preferences = getSharedPreferences("aster-engine-preview", MODE_PRIVATE);
        String dataWarning="";
        if(profileData==null)try{profileData=new SiteData(new File(getFilesDir(),"website-data/site-data.bin").toPath());}catch(Exception e){android.util.Log.w("AsterSiteData","Saved profile could not be read",e);profileData=new SiteData();dataWarning="Saved website data could not be opened. This session keeps it in memory only.";}
        siteData=profileData;
        reading=new ReadingTools(this,preferences);tabs.add(new MobileTab());
        for(int i=0;i<Math.min(12,preferences.getInt("session-count",0));i++)try{MobileTab t=new MobileTab();t.uri=PageLoader.address(preferences.getString("session-url-"+i,""));t.title=preferences.getString("session-title-"+i,"Saved tab");t.scroll=Math.max(0,preferences.getInt("session-scroll-"+i,0));previousTabs.add(t);}catch(IllegalArgumentException ignored){}
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(CHROME); root.setFitsSystemWindows(true);getWindow().setStatusBarColor(CHROME);getWindow().setNavigationBarColor(CHROME);
        LinearLayout nav = new LinearLayout(this); nav.setGravity(Gravity.CENTER_VERTICAL); nav.setPadding(dp(8), dp(8), dp(8), dp(8));
        Button home = new Button(this); home.setText("✦");home.setTextColor(ACCENT); home.setContentDescription("Aster home"); home.setMinWidth(0); home.setMinimumWidth(0);
        home.setOnClickListener(v -> load(PageLoader.HOME, -1)); nav.addView(home, new LinearLayout.LayoutParams(dp(48), dp(48)));
        address = new EditText(this); address.setSingleLine(true); address.setSelectAllOnFocus(true); address.setTextSize(16); address.setHint("Search or website address");
        address.setTextColor(INK);address.setHintTextColor(0xffa5b4c6);address.setBackground(round(0xff1b2633));address.setPadding(dp(12),0,dp(8),0);
        address.setContentDescription("Website address"); address.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        address.setImeOptions(EditorInfo.IME_ACTION_GO);
        address.setOnEditorActionListener((v, action, event) -> {
            if (action == EditorInfo.IME_ACTION_GO || event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER) {
                // Consume the down event so TextView does not move focus before key-up.
                if (event != null && event.getAction() == KeyEvent.ACTION_UP) return true;
                try {
                    load(PageLoader.searchOrAddress(address.getText().toString()), -1);
                    ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(address.getWindowToken(), 0);
                } catch (IllegalArgumentException e) { say(e.getMessage()); }
                return true;
            }
            return false;
        }); nav.addView(address, new LinearLayout.LayoutParams(0, dp(52), 1));
        Button menu = new Button(this); menu.setText("⋮");menu.setTextColor(INK); menu.setContentDescription("Aster menu"); menu.setMinWidth(0); menu.setMinimumWidth(0);
        menu.setOnClickListener(this::menu); nav.addView(menu, new LinearLayout.LayoutParams(dp(48), dp(48)));
        root.addView(nav);
        status = new TextView(this); status.setTextColor(INK); status.setTextSize(13);status.setVisibility(View.GONE); status.setPadding(dp(16), dp(4), dp(16), dp(8));
        root.addView(status);
        scroll = new ScrollView(this); scroll.setFillViewport(true); page = new PageView(); scroll.addView(page);
        FrameLayout pages=new FrameLayout(this);pages.addView(scroll);homePage=buildHome();pages.addView(homePage);root.addView(pages,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout bottom=new LinearLayout(this);bottom.setPadding(dp(4),dp(3),dp(4),dp(3));
        navBack=control("←","Back",()->move(-1));navForward=control("→","Forward",()->move(1));tabButton=control("Tabs · 1","Show tabs",this::tabMenu);
        for(Button b:new Button[]{navBack,navForward,tabButton,control("Read","Read page and notes",()->{if(document!=null)reading.reader(document.title,document.text(),document.uri.toString());})})bottom.addView(b,new LinearLayout.LayoutParams(0,dp(52),1));
        root.addView(bottom);setContentView(root);
        root.setFocusableInTouchMode(true); root.requestFocus();
        URI start = PageLoader.HOME;
        if (savedState != null) try { start = PageLoader.address(savedState.getString("address", PageLoader.HOME.toString())); } catch (IllegalArgumentException ignored) { }
        load(start, -1);if(!dataWarning.isEmpty())say(dataWarning);
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private android.graphics.drawable.GradientDrawable round(int color){android.graphics.drawable.GradientDrawable shape=new android.graphics.drawable.GradientDrawable();shape.setColor(color);shape.setCornerRadius(dp(12));return shape;}
    private Button control(String text,String description,Runnable action){Button b=new Button(this);b.setText(text);b.setTextSize(14);b.setTextColor(INK);b.setAllCaps(false);b.setMinWidth(0);b.setMinimumWidth(0);b.setContentDescription(description);b.setOnClickListener(v->action.run());return b;}
    private void say(String text){status.setText(text);status.setVisibility(text.isEmpty()?View.GONE:View.VISIBLE);}
    private TextView homeText(String text,int size,int color){TextView view=new TextView(this);view.setText(text);view.setTextSize(size);view.setTextColor(color);view.setPadding(0,dp(8),0,dp(12));return view;}
    private ScrollView buildHome(){ScrollView holder=new ScrollView(this);holder.setFillViewport(true);holder.setBackgroundColor(PAPER);LinearLayout body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);body.setPadding(dp(24),dp(20),dp(24),dp(24));
        body.addView(homeText("✦  A S T E R",16,ACCENT));body.addView(homeText("Your space to explore.",28,INK));body.addView(homeText("Browse, read and keep your ideas together.",16,0xffa5b4c6));
        String[] titles={"Search with DuckDuckGo","Example website","Your bookmarks","Open document","Restore saved tabs"};Runnable[] actions={()->load(URI.create("https://html.duckduckgo.com/html/"),-1),()->load(URI.create("https://example.com"),-1),this::openBookmarks,this::openDocument,this::restoreTabs};
        for(int i=0;i<titles.length;i++){Button b=control(titles[i],titles[i],actions[i]);b.setBackground(round(0xff1b2633));LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,dp(52));params.bottomMargin=dp(10);body.addView(b,params);}
        body.addView(homeText("Independent engine · 0.4 preview",15,ACCENT));body.addView(homeText("Simple pages and reading are ready to try. Netflix, Prime Video and cloud gaming remain unsupported. Streaming support in the menu explains the remaining work.",15,0xffa5b4c6));holder.addView(body);return holder;}
    private void updateNavigation(){navBack.setEnabled(index>0);navForward.setEnabled(index+1<history.size());tabButton.setText("Tabs · "+tabs.size());tabButton.setContentDescription("Show tabs");}
    private void openDocument(){Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT);intent.addCategory(Intent.CATEGORY_OPENABLE);intent.setType("*/*");startActivityForResult(intent,41);}
    private void stopLoading(){if(pending==null)return;generation++;pending.cancel(true);pending=null;if(images!=null)images.cancel(true);say("Navigation stopped.");}
    private void menu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add("Back").setEnabled(index > 0).setOnMenuItemClickListener(item -> { move(-1); return true; });
        menu.getMenu().add("Forward").setEnabled(index + 1 < history.size()).setOnMenuItemClickListener(item -> { move(1); return true; });
        menu.getMenu().add("Reload").setOnMenuItemClickListener(item->{if(document!=null)load(document.uri,index);return true;});
        menu.getMenu().add("Stop loading").setEnabled(pending!=null).setOnMenuItemClickListener(item->{stopLoading();return true;});
        menu.getMenu().add("Tabs").setOnMenuItemClickListener(item->{tabMenu();return true;});
        menu.getMenu().add("Read page / Find").setOnMenuItemClickListener(item->{if(document!=null)reading.reader(document.title,document.text(),document.uri.toString());return true;});
        menu.getMenu().add("Open document").setOnMenuItemClickListener(item->{openDocument();return true;});
        menu.getMenu().add("Page forms").setOnMenuItemClickListener(item->{if(document!=null)reading.forms(document.uri,document.source,r->load(r.uri,-1,r.body));return true;});
        menu.getMenu().add("Cancel download").setEnabled(download!=null&&!download.isDone()).setOnMenuItemClickListener(item->{if(download!=null)download.cancel(true);return true;});
        menu.getMenu().add("Bookmark this page").setOnMenuItemClickListener(item -> { bookmark(); return true; });
        menu.getMenu().add("Open bookmark").setOnMenuItemClickListener(item -> { openBookmarks(); return true; });
        menu.getMenu().add("Streaming support").setOnMenuItemClickListener(item -> {
            new AlertDialog.Builder(this).setTitle("Aster streaming support").setMessage(DrmProbe.report()).setPositiveButton("OK", null).show(); return true;
        });
        menu.getMenu().add("Clear website data").setOnMenuItemClickListener(item->{new AlertDialog.Builder(this).setTitle("Clear website data").setMessage("Sign out of websites and clear their cookies? Bookmarks and reader notes stay saved.").setNegativeButton("Cancel",null).setPositiveButton("Clear",(d,w)->{stopLoading();if(download!=null)download.cancel(true);siteData.clear();try{siteData.flush();}catch(Exception e){android.util.Log.w("AsterSiteData","Website data clear failed",e);new AlertDialog.Builder(this).setTitle("Website data").setMessage("Website data was cleared in memory, but the saved file could not be updated.").setPositiveButton("OK",null).show();}load(PageLoader.HOME,-1);}).show();return true;});
        menu.getMenu().add("About this preview").setOnMenuItemClickListener(item -> {
            new AlertDialog.Builder(this).setTitle("Aster 0.4 · Independent preview").setMessage("Aster's own renderer with images, a small CSS subset, native forms, tabs, document reading and offline system speech. Android 8 or later. Full web layouts, advanced login flows, Android JavaScript/video, PDF and the AI companion remain unfinished.").setPositiveButton("OK", null).show(); return true;
        }); menu.show();
    }
    private void move(int delta) { int next = index + delta; if (next >= 0 && next < history.size()) load(history.get(next), next); }
    private void load(URI uri, int historyIndex) {
        load(uri,historyIndex,null);
    }
    private void load(URI uri,int historyIndex,byte[] formBody) {
        if (pending != null) pending.cancel(true);
        if(images!=null)images.cancel(true);bitmaps.clear();
        final int request = ++generation; say("Opening " + uri + "…");
        SiteData.Request siteRequest=siteData.request(document==null?null:document.uri,true,formBody==null?"GET":"POST");
        pending = network.submit(() -> {
            try {
                Engine.Document result = PageLoader.load(uri,formBody,siteData,siteRequest);
                String warning="";try{siteData.flush();}catch(Exception e){android.util.Log.w("AsterSiteData","Website data flush failed",e);warning="Website data could not be saved to this device.";}final String storageWarning=warning;
                runOnUiThread(() -> {
                    if (isDestroyed() || request != generation) return;
                    pending=null;document = result;
                    if (historyIndex >= 0) index = historyIndex;
                    else {
                        while (history.size() > index + 1) history.remove(history.size() - 1);
                        history.add(result.uri); if (history.size() > 100) history.remove(0); index = history.size() - 1;
                    }
                    address.setText(result.uri.toString());say(storageWarning);setTitle(result.title);updateNavigation();homePage.setVisibility(result.uri.equals(PageLoader.HOME)?View.VISIBLE:View.GONE);scroll.setVisibility(result.uri.equals(PageLoader.HOME)?View.GONE:View.VISIBLE);
                    MobileTab tab=tabs.get(selectedTab);tab.uri=result.uri;tab.title=result.title;
                    page.reset();int offset=restoreScroll;restoreScroll=0;scroll.post(()->{if(request==generation){scroll.scrollTo(0,offset);saveTabs();}});loadImages(result,request);
                });
            } catch(PageLoader.DownloadRequired file){runOnUiThread(()->{if(isDestroyed()||request!=generation)return;pending=null;say("");
                new AlertDialog.Builder(this).setTitle("Save file").setMessage("Save this file from "+file.uri.getHost()+"? It will not open automatically.")
                    .setNegativeButton("Cancel",null).setPositiveButton("Save as…",(d,w)->{
                        if(download!=null&&!download.isDone()){say("Finish or cancel the current download first.");return;}
                        downloadSource=file.uri;Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/octet-stream");
                        String path=file.uri.getPath(),name=path==null?"download":path.substring(path.lastIndexOf('/')+1);name=name.replaceAll("[^a-zA-Z0-9._ -]","_");
                        if(name.isEmpty()||name.equals(".")||name.equals(".."))name="download";if(name.length()>120)name=name.substring(0,120);intent.putExtra(Intent.EXTRA_TITLE,name);startActivityForResult(intent,42);
                    }).show();});
            } catch (Exception e) { runOnUiThread(() -> { if (!isDestroyed() && request == generation){pending=null;say("Could not open page: " + e.getMessage());} }); }
        });
    }
    private void loadImages(Engine.Document doc,int request){
        if(doc.scriptsBlocked)return;List<URI> sources=new ArrayList<>();
        for(Engine.Run r:doc.runs)if(r.image!=null&&PageAssets.sameOrigin(doc.uri,r.image)&&!sources.contains(r.image)&&sources.size()<8)sources.add(r.image);
        images=assets.submit(()->{long total=0;for(URI source:sources){if(Thread.currentThread().isInterrupted())return;
            try{byte[] bytes=PageAssets.fetch(doc.uri,source,true,siteData.request(doc.uri,false,"GET"));BitmapFactory.Options options=new BitmapFactory.Options();options.inJustDecodeBounds=true;BitmapFactory.decodeByteArray(bytes,0,bytes.length,options);
                if(options.outWidth<1||options.outHeight<1||(long)options.outWidth*options.outHeight>2_000_000)continue;
                long size=(long)options.outWidth*options.outHeight*4;if(total+size>8*1024*1024)break;total+=size;
                Bitmap image=BitmapFactory.decodeByteArray(bytes,0,bytes.length);if(image==null)continue;
                runOnUiThread(()->{if(!isDestroyed()&&generation==request){bitmaps.put(source,image);page.invalidate();}});
            }catch(Exception ignored){/* Keep alternative text on failure. */}
        }});
    }
    private void stashTab(){if(document!=null){MobileTab t=tabs.get(selectedTab);t.uri=document.uri;t.title=document.title;t.scroll=scroll.getScrollY();t.history.clear();t.history.addAll(history);t.index=index;}}
    private void selectTab(int selected){if(selected<0||selected>=tabs.size())return;stashTab();selectedTab=selected;MobileTab t=tabs.get(selected);restoreScroll=t.scroll;history.clear();history.addAll(t.history);index=t.index;document=null;page.reset();updateNavigation();load(t.uri,index);}
    private void restoreTabs(){if(previousTabs.isEmpty()){say("No saved session yet.");return;}stashTab();tabs.clear();for(MobileTab t:previousTabs)tabs.add(t.copy());selectedTab=0;document=null;selectTab(0);}
    private void tabMenu(){
        String[] titles=new String[tabs.size()];for(int i=0;i<titles.length;i++)titles[i]=(i==selectedTab?"● ":"◌ ")+tabs.get(i).title;
        new AlertDialog.Builder(this).setTitle("Aster tabs").setItems(titles,(d,w)->{if(w!=selectedTab)selectTab(w);}).setPositiveButton("New tab",(d,w)->{if(tabs.size()>=12){say("Up to 12 tabs in this preview.");return;}tabs.add(new MobileTab());selectTab(tabs.size()-1);})
            .setNegativeButton("Close tab",(d,w)->{if(tabs.size()==1){tabs.set(0,new MobileTab());document=null;history.clear();index=-1;load(PageLoader.HOME,-1);}else{tabs.remove(selectedTab);selectedTab=Math.min(selectedTab,tabs.size()-1);document=null;selectTab(selectedTab);}})
            .setNeutralButton("Restore saved tabs",(d,w)->restoreTabs()).show();
    }
    private void saveTabs(){if(document==null)return;stashTab();boolean any=false;for(MobileTab t:tabs)if(!t.uri.equals(PageLoader.HOME))any=true;if(!any)return;
        SharedPreferences.Editor edit=preferences.edit().putInt("session-count",tabs.size());for(int i=0;i<tabs.size();i++){MobileTab t=tabs.get(i);edit.putString("session-url-"+i,t.uri.toString()).putString("session-title-"+i,t.title).putInt("session-scroll-"+i,t.scroll);}edit.apply();}
    protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);
        if(request==42&&result==RESULT_OK&&data!=null&&data.getData()!=null&&downloadSource!=null){saveDownload(downloadSource,data.getData());downloadSource=null;return;}
        if(request!=41||result!=RESULT_OK||data==null||data.getData()==null)return;android.net.Uri uri=data.getData();
        String name="document";try(android.database.Cursor cursor=getContentResolver().query(uri,new String[]{android.provider.OpenableColumns.DISPLAY_NAME},null,null,null)){if(cursor!=null&&cursor.moveToFirst())name=cursor.getString(0);}catch(Exception ignored){}
        final String title=name;network.submit(()->{try(InputStream input=getContentResolver().openInputStream(uri)){String text=DocumentReader.read(title,input);runOnUiThread(()->{if(!isDestroyed())reading.reader(title,text,uri.toString());});}
            catch(Exception e){runOnUiThread(()->{if(!isDestroyed())say("Could not read document: "+e.getMessage());});}});
    }
    private void saveDownload(URI source,android.net.Uri destination){
        download=transfers.submit(()->{File staged=null;boolean complete=false;
            try{staged=File.createTempFile("aster-download-",".part",getCacheDir());
                try(OutputStream output=new FileOutputStream(staged)){FileTransfer.save(source,output,(received,total)->runOnUiThread(()->{if(!isDestroyed())say("Downloading · "+(received/1024)+" KiB"+(total>0?" / "+(total/1024)+" KiB":""));}),siteData.request(source,false,"GET"));}
                if(Thread.currentThread().isInterrupted())throw new IOException("Download cancelled.");
                try(InputStream input=new FileInputStream(staged);OutputStream output=getContentResolver().openOutputStream(destination,"w")){
                    if(output==null)throw new IOException("Destination is unavailable.");byte[] buffer=new byte[32768];int n;
                    while((n=input.read(buffer))!=-1){if(Thread.currentThread().isInterrupted())throw new IOException("Download cancelled.");output.write(buffer,0,n);}}
                complete=true;runOnUiThread(()->{if(!isDestroyed())say("File saved to the destination you selected.");});
            }catch(Exception e){runOnUiThread(()->{if(!isDestroyed())say("Download failed: "+e.getMessage());});}
            finally{if(staged!=null)staged.delete();if(!complete)try{android.provider.DocumentsContract.deleteDocument(getContentResolver(),destination);}catch(Exception ignored){}}
        });
    }
    private void bookmark() {
        if (document == null) return;
        int count = preferences.getInt("count", 0);
        for (int i = 0; i < count; i++) if (preferences.getString("url" + i, "").equals(document.uri.toString())) return;
        if (count >= 30) { say("The preview supports up to 30 bookmarks."); return; }
        preferences.edit().putString("url" + count, document.uri.toString()).putString("title" + count, document.title).putInt("count", count + 1).apply();
        say("Bookmark saved on this device.");
    }
    private void openBookmarks() {
        int count = Math.min(30, preferences.getInt("count", 0)); String[] titles = new String[count];
        for (int i = 0; i < count; i++) titles[i] = preferences.getString("title" + i, "Bookmark");
        new AlertDialog.Builder(this).setTitle(count == 0 ? "No bookmarks yet" : "Bookmarks").setItems(titles, (dialog, which) -> {
            try { load(PageLoader.address(preferences.getString("url" + which, "")), -1); } catch (IllegalArgumentException e) { say(e.getMessage()); }
        }).setNegativeButton("Close", null).setNeutralButton("Clear…", (dialog, which) -> {
            new AlertDialog.Builder(this).setMessage("Remove the preview's saved bookmarks?").setNegativeButton("Cancel", null)
                .setPositiveButton("Clear", (d, w) -> {SharedPreferences.Editor edit=preferences.edit().remove("count");for(int i=0;i<30;i++)edit.remove("url"+i).remove("title"+i);edit.apply();}).show();
        }).show();
    }
    public void onBackPressed() { if (index > 0) move(-1); else super.onBackPressed(); }
    protected void onSaveInstanceState(Bundle out) { if (document != null) out.putString("address", document.uri.toString()); super.onSaveInstanceState(out); }
    protected void onPause(){saveTabs();super.onPause();}
    protected void onStop(){try{siteData.flush();}catch(Exception e){android.util.Log.w("AsterSiteData","Website data flush failed on stop",e);say("Website data could not be saved.");}super.onStop();}
    protected void onDestroy() { generation++; if (pending != null) pending.cancel(true);if(images!=null)images.cancel(true);if(download!=null)download.cancel(true);transfers.shutdownNow();assets.shutdownNow();reading.close(); network.shutdownNow(); super.onDestroy(); }

    private final class PageView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private Engine.Layout layout; private int lastWidth = -1;
        private final float scale = getResources().getDisplayMetrics().scaledDensity;
        private float downX, downY;
        PageView() { super(MainActivity.this); setBackgroundColor(Color.WHITE); setFocusable(true); setContentDescription("Aster page"); }
        void reset() { lastWidth = -1; layout = null; setContentDescription(document == null ? "Aster page" : document.text()); requestLayout(); invalidate(); }
        private void style(Engine.Style s) {
            paint.setTypeface(Typeface.create(s.pre ? "monospace" : "sans-serif", (s.bold ? Typeface.BOLD : 0) | (s.italic ? Typeface.ITALIC : 0)));
            paint.setTextSize(s.size); paint.setColor(s.color);
        }
        protected void onMeasure(int w, int h) {
            int width = MeasureSpec.getSize(w);
            if (document != null && (layout == null || width != lastWidth)) {
                lastWidth = width;
                try { layout = Engine.layout(document, width / scale, (text, s) -> { style(s); return paint.measureText(text); }); }
                catch (IllegalArgumentException e) { layout = null; say("Page is too complex for this preview."); }
            }
            setMeasuredDimension(width, Math.max(MeasureSpec.getSize(h), layout == null ? dp(200) : (int) Math.ceil(layout.height * scale)));
        }
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas); if (layout == null) return;
            canvas.save(); canvas.scale(scale, scale); Rect clip = canvas.getClipBounds();
            for (int i=layout.firstVisible(clip.top);i<layout.items.size();i++) {Engine.Draw draw=layout.items.get(i);if(draw.y>clip.bottom)break;
                if (draw.y + draw.height < clip.top || draw.y > clip.bottom) continue;
                if(draw.image!=null){Bitmap bitmap=bitmaps.get(draw.image);if(bitmap!=null){float fit=Math.min(draw.width/bitmap.getWidth(),draw.height/bitmap.getHeight());canvas.drawBitmap(bitmap,null,new RectF(draw.x,draw.y,draw.x+bitmap.getWidth()*fit,draw.y+bitmap.getHeight()*fit),paint);continue;}
                    paint.setColor(0xffe8eeec);canvas.drawRect(draw.x,draw.y,draw.x+draw.width,draw.y+draw.height,paint);}
                style(draw.style); canvas.drawText(draw.text, draw.x, draw.y + draw.style.size, paint);
                if (draw.link != null) canvas.drawLine(draw.x, draw.y + draw.style.size + 2, draw.x + draw.width, draw.y + draw.style.size + 2, paint);
            }
            canvas.restore();
        }
        public boolean onTouchEvent(android.view.MotionEvent event) {
            if (event.getAction() == MotionEvent.ACTION_DOWN) { downX = event.getX(); downY = event.getY(); return true; }
            if (event.getAction() == MotionEvent.ACTION_UP) {
                float slop = ViewConfiguration.get(MainActivity.this).getScaledTouchSlop();
                if (Math.abs(event.getX() - downX) < slop && Math.abs(event.getY() - downY) < slop && layout != null) {
                    URI target = layout.hit(event.getX() / scale, event.getY() / scale); if (target != null) load(target, -1); performClick();
                }
                return true;
            }
            return true;
        }
        public boolean performClick() { super.performClick(); return true; }
    }
}
