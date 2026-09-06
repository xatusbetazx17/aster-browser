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

/** Android widgets and Canvas only. There is deliberately no android.webkit dependency. */
public final class MainActivity extends Activity {
    private EditText address;
    private TextView status;
    private PageView page;
    private ScrollView scroll;
    private final List<URI> history = new ArrayList<>();
    private int index = -1, generation;
    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private Future<?> pending;
    private SharedPreferences preferences;
    private Engine.Document document;

    public void onCreate(Bundle savedState) {
        super.onCreate(savedState);
        preferences = getSharedPreferences("aster-engine-preview", MODE_PRIVATE);
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xffeaf3f1); root.setFitsSystemWindows(true);
        LinearLayout nav = new LinearLayout(this); nav.setGravity(Gravity.CENTER_VERTICAL); nav.setPadding(dp(8), dp(8), dp(8), dp(8));
        Button home = new Button(this); home.setText("A"); home.setContentDescription("Aster home"); home.setMinWidth(0); home.setMinimumWidth(0);
        home.setOnClickListener(v -> load(PageLoader.HOME, -1)); nav.addView(home, new LinearLayout.LayoutParams(dp(48), dp(48)));
        address = new EditText(this); address.setSingleLine(true); address.setTextSize(16); address.setHint("Website address");
        address.setContentDescription("Website address"); address.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        address.setImeOptions(EditorInfo.IME_ACTION_GO);
        address.setOnEditorActionListener((v, action, event) -> {
            if (action == EditorInfo.IME_ACTION_GO || event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER && event.getAction() == KeyEvent.ACTION_UP) {
                try {
                    load(PageLoader.address(address.getText().toString()), -1);
                    ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(address.getWindowToken(), 0);
                } catch (IllegalArgumentException e) { status.setText(e.getMessage()); }
                return true;
            }
            return false;
        }); nav.addView(address, new LinearLayout.LayoutParams(0, dp(52), 1));
        Button menu = new Button(this); menu.setText("⋮"); menu.setContentDescription("Aster menu"); menu.setMinWidth(0); menu.setMinimumWidth(0);
        menu.setOnClickListener(this::menu); nav.addView(menu, new LinearLayout.LayoutParams(dp(48), dp(48)));
        root.addView(nav);
        status = new TextView(this); status.setTextColor(0xff344b55); status.setTextSize(12); status.setPadding(dp(16), dp(4), dp(16), dp(8));
        root.addView(status);
        scroll = new ScrollView(this); scroll.setFillViewport(true); page = new PageView(); scroll.addView(page);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1)); setContentView(root);
        URI start = PageLoader.HOME;
        if (savedState != null) try { start = PageLoader.address(savedState.getString("address", PageLoader.HOME.toString())); } catch (IllegalArgumentException ignored) { }
        load(start, -1);
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private void menu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add("Back").setEnabled(index > 0).setOnMenuItemClickListener(item -> { move(-1); return true; });
        menu.getMenu().add("Forward").setEnabled(index + 1 < history.size()).setOnMenuItemClickListener(item -> { move(1); return true; });
        menu.getMenu().add("Bookmark this page").setOnMenuItemClickListener(item -> { bookmark(); return true; });
        menu.getMenu().add("Open bookmark").setOnMenuItemClickListener(item -> { openBookmarks(); return true; });
        menu.getMenu().add("Streaming support").setOnMenuItemClickListener(item -> {
            new AlertDialog.Builder(this).setTitle("Aster streaming support").setMessage(DrmProbe.report()).setPositiveButton("OK", null).show(); return true;
        });
        menu.getMenu().add("About this preview").setOnMenuItemClickListener(item -> {
            new AlertDialog.Builder(this).setTitle("Aster Engine Preview 0.1").setMessage("An original basic HTML/text renderer. Native Android interface; no WebView or other browser engine. JavaScript, images, forms, video and the full Aster companion are not supported in this preview.").setPositiveButton("OK", null).show(); return true;
        }); menu.show();
    }
    private void move(int delta) { int next = index + delta; if (next >= 0 && next < history.size()) load(history.get(next), next); }
    private void load(URI uri, int historyIndex) {
        if (pending != null) pending.cancel(true);
        final int request = ++generation; status.setText("Opening " + uri + "…");
        pending = network.submit(() -> {
            try {
                Engine.Document result = PageLoader.load(uri);
                runOnUiThread(() -> {
                    if (isDestroyed() || request != generation) return;
                    document = result;
                    if (historyIndex >= 0) index = historyIndex;
                    else {
                        while (history.size() > index + 1) history.remove(history.size() - 1);
                        history.add(result.uri); if (history.size() > 100) history.remove(0); index = history.size() - 1;
                    }
                    address.setText(result.uri.toString()); status.setText("Engine preview · Basic text pages only"); setTitle(result.title);
                    page.reset(); scroll.scrollTo(0, 0);
                });
            } catch (Exception e) { runOnUiThread(() -> { if (!isDestroyed() && request == generation) status.setText("Could not open page: " + e.getMessage()); }); }
        });
    }
    private void bookmark() {
        if (document == null) return;
        int count = preferences.getInt("count", 0);
        for (int i = 0; i < count; i++) if (preferences.getString("url" + i, "").equals(document.uri.toString())) return;
        if (count >= 30) { status.setText("The preview supports up to 30 bookmarks."); return; }
        preferences.edit().putString("url" + count, document.uri.toString()).putString("title" + count, document.title).putInt("count", count + 1).apply();
        status.setText("Bookmark saved on this device.");
    }
    private void openBookmarks() {
        int count = Math.min(30, preferences.getInt("count", 0)); String[] titles = new String[count];
        for (int i = 0; i < count; i++) titles[i] = preferences.getString("title" + i, "Bookmark");
        new AlertDialog.Builder(this).setTitle(count == 0 ? "No bookmarks yet" : "Bookmarks").setItems(titles, (dialog, which) -> {
            try { load(PageLoader.address(preferences.getString("url" + which, "")), -1); } catch (IllegalArgumentException e) { status.setText(e.getMessage()); }
        }).setNegativeButton("Close", null).setNeutralButton("Clear…", (dialog, which) -> {
            new AlertDialog.Builder(this).setMessage("Remove the preview's saved bookmarks?").setNegativeButton("Cancel", null)
                .setPositiveButton("Clear", (d, w) -> preferences.edit().clear().apply()).show();
        }).show();
    }
    public void onBackPressed() { if (index > 0) move(-1); else super.onBackPressed(); }
    protected void onSaveInstanceState(Bundle out) { if (document != null) out.putString("address", document.uri.toString()); super.onSaveInstanceState(out); }
    protected void onDestroy() { generation++; if (pending != null) pending.cancel(true); network.shutdownNow(); super.onDestroy(); }

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
                catch (IllegalArgumentException e) { layout = null; status.setText("Page is too complex for this preview."); }
            }
            setMeasuredDimension(width, Math.max(MeasureSpec.getSize(h), layout == null ? dp(200) : (int) Math.ceil(layout.height * scale)));
        }
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas); if (layout == null) return;
            canvas.save(); canvas.scale(scale, scale); Rect clip = canvas.getClipBounds();
            for (Engine.Draw draw : layout.items) {
                if (draw.y + draw.height < clip.top || draw.y > clip.bottom) continue;
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
