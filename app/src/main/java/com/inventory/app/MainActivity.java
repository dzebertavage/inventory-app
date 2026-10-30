package com.inventory.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.UriPermission;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.inventory.app.xlsx.XlsxBook;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    // ---- palette
    static final int C_BG = 0xFFF3F0EA;
    static final int C_CARD = 0xFFFFFFFF;
    static final int C_INK = 0xFF1B1B1B;
    static final int C_INK2 = 0xFF4A4A4A;
    static final int C_MUTED = 0xFF8A8580;
    static final int C_LINE = 0xFFDAD4CB;
    static final int C_BRAND = 0xFF1F4E3D;
    static final int C_BRAND_DARK = 0xFF153629;
    static final int C_SELECT = 0xFFF2A900;
    static final int C_SELECT_BG = 0xFFFFF6DC;
    static final int C_DONE = 0xFF2E7D4F;
    static final int C_WARN = 0xFFC62828;
    static final int C_WARN_BG = 0xFFFDECEA;
    static final int C_KEY = 0xFFFFFFFF;
    static final int C_KEYPAD_BG = 0xFFE6E1D8;

    private static final int REQ_OPEN = 1;
    private static final int REQ_FOLDER = 2;
    private static final String MIME_XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private InventoryState state;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;

    // views
    private FrameLayout root;
    private TextView titleView, subtitleView;
    private LinearLayout tabStrip;
    private HorizontalScrollView tabScroll;
    private ScrollView listScroll;
    private LinearLayout list;
    private TextView padItem, padField, padDisplay;
    private final List<ItemViews> itemViews = new ArrayList<>();

    /** Digits typed for the selected field but not yet committed. */
    private String buffer = "";
    private boolean pendingExport;

    private static final class ItemViews {
        XlsxBook.Item item;
        LinearLayout card;
        View stripe;
        TextView parBadge;
        final List<LinearLayout> fieldBoxes = new ArrayList<>();
        final List<TextView> fieldValues = new ArrayList<>();
        final List<TextView> fieldNotes = new ArrayList<>();
    }

    // =================================================================== lifecycle

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        prefs = getSharedPreferences("settings", MODE_PRIVATE);
        state = new InventoryState(getFilesDir());
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        root = new FrameLayout(this);
        root.setBackgroundColor(C_BG);
        setContentView(root);
        setupInsets();
        showLoading("Loading…");

        io.execute(() -> {
            boolean restored = false;
            String err = null;
            try { restored = state.restore(); } catch (IOException e) { err = e.getMessage(); }
            final boolean ok = restored;
            final String error = err;
            main.post(() -> {
                if (ok) buildInventoryScreen(); else showWelcome();
                if (error != null) toast("Couldn't reopen the last inventory: " + error);
                handleIncomingIntent(getIntent());
            });
        });
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIncomingIntent(intent);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (state.book != null) { commitBuffer(); state.save(); }
    }

    private void setupInsets() {
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                // light icons on the dark header, dark icons on the light keypad at the bottom
                c.setSystemBarsAppearance(WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
                        WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
            }
            root.setOnApplyWindowInsetsListener((v, insets) -> {
                android.graphics.Insets b = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                v.setPadding(b.left, 0, b.right, b.bottom);
                View header = v.findViewWithTag("header");
                if (header != null) header.setPadding(header.getPaddingLeft(), b.top + dp(8), header.getPaddingRight(), header.getPaddingBottom());
                v.setTag(b.top);
                return WindowInsets.CONSUMED;
            });
        }
    }

    private int statusInset() { return root.getTag() instanceof Integer ? (Integer) root.getTag() : 0; }

    // =================================================================== import

    private void handleIncomingIntent(Intent intent) {
        if (intent == null) return;
        Uri uri = null;
        if (Intent.ACTION_VIEW.equals(intent.getAction())) uri = intent.getData();
        else if (Intent.ACTION_SEND.equals(intent.getAction())) {
            uri = Build.VERSION.SDK_INT >= 33
                    ? intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri.class)
                    : intent.getParcelableExtra(Intent.EXTRA_STREAM);
        }
        if (uri == null) return;
        intent.setAction(null);   // don't re-import on rotation/recreate
        confirmImport(uri);
    }

    private void openPicker() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{MIME_XLSX, "application/octet-stream", "application/vnd.ms-excel"});
        startActivityForResult(i, REQ_OPEN);
    }

    private void confirmImport(Uri uri) {
        if (state.book != null && !state.entries.isEmpty()) {
            int n = state.entries.size();
            new AlertDialog.Builder(this)
                    .setTitle("Start a new inventory?")
                    .setMessage("You have " + n + " " + (n == 1 ? "entry" : "entries") + " in the current inventory"
                            + (state.fileName != null ? " (" + state.fileName + ")" : "")
                            + ". Opening this file will replace them.\n\nExport first if you haven't already.")
                    .setPositiveButton("Replace", (d, w) -> importFile(uri))
                    .setNegativeButton("Cancel", null)
                    .show();
        } else {
            importFile(uri);
        }
    }

    private void importFile(Uri uri) {
        showLoading("Reading spreadsheet…");
        io.execute(() -> {
            String err = null;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                if (in == null) throw new IOException("Couldn't open the file.");
                byte[] data = XlsxBook.readAll(in);
                state.startNew(data, displayName(uri));
            } catch (Exception e) {
                err = e.getMessage() == null ? e.toString() : e.getMessage();
            }
            final String error = err;
            main.post(() -> {
                if (error != null) {
                    new AlertDialog.Builder(this).setTitle("Couldn't open that file")
                            .setMessage(error).setPositiveButton("OK", null).show();
                    if (state.book != null) buildInventoryScreen(); else showWelcome();
                } else {
                    buffer = "";
                    buildInventoryScreen();
                    int[] p = state.totalProgress();
                    toast("Loaded " + state.book.tabs.size() + " tabs, " + p[1] + " counts to take");
                }
            });
        });
    }

    private String displayName(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (Exception ignored) { }
        return uri.getLastPathSegment();
    }

    // =================================================================== screens

    private void showLoading(String msg) {
        root.removeAllViews();
        LinearLayout box = vbox();
        box.setGravity(Gravity.CENTER);
        ProgressBar pb = new ProgressBar(this);
        pb.setIndeterminateTintList(ColorStateList.valueOf(C_BRAND));
        box.addView(pb);
        TextView t = text(msg, 18, C_INK2, false);
        t.setPadding(0, dp(16), 0, 0);
        box.addView(t);
        root.addView(box, new FrameLayout.LayoutParams(-1, -1));
    }

    private void showWelcome() {
        root.removeAllViews();
        LinearLayout box = vbox();
        box.setGravity(Gravity.CENTER);
        box.setPadding(dp(40), dp(40), dp(40), dp(40));
        TextView h = text("Monthly Inventory", 32, C_INK, true);
        h.setGravity(Gravity.CENTER);
        box.addView(h);
        TextView s = text("Open the inventory spreadsheet you downloaded from Gmail (it's usually in Downloads). "
                + "You can also tap the attachment in Gmail and choose this app.", 18, C_INK2, false);
        s.setGravity(Gravity.CENTER);
        s.setPadding(0, dp(16), 0, dp(32));
        box.addView(s);
        TextView btn = button("Open spreadsheet", C_BRAND, Color.WHITE, 22);
        btn.setOnClickListener(v -> openPicker());
        box.addView(btn, new LinearLayout.LayoutParams(dp(360), dp(72)));
        root.addView(box, new FrameLayout.LayoutParams(-1, -1));
    }

    private void buildInventoryScreen() {
        root.removeAllViews();
        LinearLayout page = vbox();
        root.addView(page, new FrameLayout.LayoutParams(-1, -1));

        // ---- header
        LinearLayout header = new LinearLayout(this);
        header.setTag("header");
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setBackgroundColor(C_BRAND);
        header.setPadding(dp(20), statusInset() + dp(8), dp(8), dp(10));
        LinearLayout titles = vbox();
        titleView = text("Monthly Inventory", 22, Color.WHITE, true);
        subtitleView = text("", 15, 0xFFCFE3D9, false);
        titleView.setSingleLine(true);
        subtitleView.setSingleLine(true);
        subtitleView.setEllipsize(TextUtils.TruncateAt.END);
        titles.addView(titleView);
        titles.addView(subtitleView);
        header.addView(titles, new LinearLayout.LayoutParams(0, -2, 1));

        TextView next = button("Next empty", 0x33FFFFFF, Color.WHITE, 16);
        next.setOnClickListener(v -> {
            commitBuffer();
            if (state.gotoNextUnanswered()) { buffer = ""; showTab(true); }
            else toast("Everything has been counted or marked Blank.");
        });
        header.addView(next, lp(-2, dp(52), dp(8)));
        TextView export = button("Export", C_SELECT, C_INK, 18);
        export.setOnClickListener(v -> startExport());
        header.addView(export, lp(dp(110), dp(52), dp(8)));
        TextView more = button("⋮", 0x00000000, Color.WHITE, 28);
        more.setOnClickListener(this::showMenu);
        header.addView(more, lp(dp(52), dp(52), 0));
        page.addView(header);

        // ---- tab strip
        tabScroll = new HorizontalScrollView(this);
        tabScroll.setHorizontalScrollBarEnabled(false);
        tabScroll.setBackgroundColor(C_BRAND_DARK);
        tabStrip = new LinearLayout(this);
        tabStrip.setPadding(dp(8), dp(6), dp(8), dp(6));
        tabScroll.addView(tabStrip);
        page.addView(tabScroll);

        // ---- item list
        listScroll = new ScrollView(this);
        listScroll.setFillViewport(true);
        list = vbox();
        list.setPadding(dp(12), dp(4), dp(12), dp(24));
        listScroll.addView(list);
        page.addView(listScroll, new LinearLayout.LayoutParams(-1, 0, 1));

        // ---- keypad
        page.addView(buildKeypad());

        showTab(true);
    }

    private void showMenu(View anchor) {
        PopupMenu m = new PopupMenu(this, anchor);
        m.getMenu().add(0, 1, 0, "Open a different spreadsheet…");
        m.getMenu().add(0, 2, 0, "Change export folder…");
        m.getMenu().add(0, 3, 0, "About this inventory");
        m.setOnMenuItemClickListener(mi -> {
            switch (mi.getItemId()) {
                case 1: openPicker(); return true;
                case 2: pickFolder(); return true;
                case 3: showAbout(); return true;
            }
            return false;
        });
        m.show();
    }

    private void showAbout() {
        int[] p = state.totalProgress();
        String folder = prefs.getString("treeName", null);
        new AlertDialog.Builder(this)
                .setTitle("This inventory")
                .setMessage("File: " + (state.fileName == null ? "—" : state.fileName)
                        + "\nCounted: " + p[0] + " of " + p[1]
                        + "\nExport folder: " + (folder == null ? "not chosen yet" : folder)
                        + "\n\nProgress is saved automatically after every entry.")
                .setPositiveButton("OK", null).show();
    }

    // =================================================================== tabs & list

    private void refreshHeader() {
        int[] p = state.totalProgress();
        String name = state.fileName == null ? "" : state.fileName.replaceAll("(?i)\\.xlsx$", "");
        subtitleView.setText(p[0] + " of " + p[1] + " counted" + (name.isEmpty() ? "" : "  ·  " + name));
    }

    private void rebuildTabStrip() {
        tabStrip.removeAllViews();
        for (int i = 0; i < state.book.tabs.size(); i++) {
            XlsxBook.Tab t = state.book.tabs.get(i);
            int[] p = state.tabProgress(t);
            boolean sel = i == state.tab, done = p[0] == p[1];
            LinearLayout chip = vbox();
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(dp(16), dp(4), dp(16), dp(4));
            chip.setBackground(ripple(rounded(sel ? Color.WHITE : 0x22FFFFFF, dp(10), 0, 0)));
            TextView n = text(t.name, 17, sel ? C_BRAND : Color.WHITE, true);
            n.setSingleLine(true);
            TextView c = text(done ? "✓ done" : p[0] + " / " + p[1], 13, sel ? (done ? C_DONE : C_INK2) : (done ? 0xFF9BE0B8 : 0xFFCFE3D9), false);
            chip.addView(n);
            chip.addView(c);
            final int idx = i;
            chip.setOnClickListener(v -> {
                commitBuffer();
                state.tab = idx; state.item = 0; state.field = 0;
                buffer = "";
                showTab(true);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(54));
            lp.rightMargin = dp(8);
            tabStrip.addView(chip, lp);
        }
        main.post(() -> {
            View sel = tabStrip.getChildAt(state.tab);
            if (sel != null) tabScroll.smoothScrollTo(Math.max(0, sel.getLeft() - dp(40)), 0);
        });
    }

    private void showTab(boolean scrollToSelection) {
        state.clampPosition();
        rebuildTabStrip();
        refreshHeader();
        list.removeAllViews();
        itemViews.clear();
        XlsxBook.Tab tab = state.currentTab();
        String lastSection = null;
        for (int i = 0; i < tab.items.size(); i++) {
            XlsxBook.Item it = tab.items.get(i);
            if (it.section != null && !it.section.equals(lastSection)) {
                TextView sh = text(it.section.toUpperCase(Locale.US), 15, C_BRAND, true);
                sh.setLetterSpacing(0.08f);
                sh.setPadding(dp(4), dp(18), 0, dp(4));
                list.addView(sh);
            }
            lastSection = it.section;
            ItemViews iv = buildCard(it, i);
            itemViews.add(iv);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.topMargin = dp(10);
            list.addView(iv.card, lp);
            refreshCard(i);
        }
        TextView end = text("End of " + tab.name, 14, C_MUTED, false);
        end.setGravity(Gravity.CENTER);
        end.setPadding(0, dp(20), 0, dp(8));
        list.addView(end);
        refreshKeypad();
        if (scrollToSelection) main.post(() -> scrollToCurrent(false));
    }

    private ItemViews buildCard(XlsxBook.Item it, int index) {
        ItemViews iv = new ItemViews();
        iv.item = it;
        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.HORIZONTAL);
        iv.card = outer;

        iv.stripe = new View(this);
        outer.addView(iv.stripe, new LinearLayout.LayoutParams(dp(8), -1));

        LinearLayout body = vbox();
        body.setPadding(dp(16), dp(12), dp(16), dp(14));
        outer.addView(body, new LinearLayout.LayoutParams(0, -2, 1));

        // Description (most prominent)
        TextView desc = text(it.description, 23, C_INK, true);
        body.addView(desc);
        // Vendor (second) — keep the line even when there is no vendor
        TextView vendor = text(it.vendor == null ? " " : it.vendor.replace('\n', ' '), 18, C_INK2, false);
        vendor.setPadding(0, dp(2), 0, 0);
        body.addView(vendor);

        // Unit · Par · Item ID
        LinearLayout meta = new LinearLayout(this);
        meta.setGravity(Gravity.CENTER_VERTICAL);
        meta.setPadding(0, dp(8), 0, 0);
        if (it.unit != null) {
            meta.addView(chip("Unit  " + it.unit, 0xFFEFEBE4, C_INK, false), lp(-2, -2, dp(8)));
        }
        if (it.par != null) {
            TextView par = chip("Par  " + it.par, 0xFFFFF1CC, 0xFF6B4E00, true);
            meta.addView(par, lp(-2, -2, dp(8)));
        }
        iv.parBadge = chip("AT / BELOW PAR", C_WARN, Color.WHITE, true);
        iv.parBadge.setVisibility(View.GONE);
        meta.addView(iv.parBadge, lp(-2, -2, dp(8)));
        View spacer = new View(this);
        meta.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1));
        if (it.itemId != null) {
            TextView id = text(it.itemId, 13, C_MUTED, false);
            id.setMaxWidth(dp(200));
            id.setGravity(Gravity.END);
            meta.addView(id);
        }
        body.addView(meta);

        // Entry fields
        LinearLayout fields = new LinearLayout(this);
        fields.setPadding(0, dp(12), 0, 0);
        for (int f = 0; f < it.fields.size(); f++) {
            LinearLayout box = vbox();
            box.setPadding(dp(12), dp(8), dp(12), dp(8));
            TextView label = text(it.fields.get(f).label, 14, C_INK2, true);
            label.setSingleLine(true);
            label.setEllipsize(TextUtils.TruncateAt.END);
            box.addView(label);
            TextView val = text("", 26, C_INK, true);
            box.addView(val);
            TextView note = text("", 12, C_MUTED, false);
            box.addView(note);
            final int fi = f;
            box.setOnClickListener(v -> selectField(index, fi));
            box.setOnLongClickListener(v -> { offerReset(index, fi); return true; });
            LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(0, -2, 1);
            if (f < it.fields.size() - 1) flp.rightMargin = dp(10);
            fields.addView(box, flp);
            iv.fieldBoxes.add(box);
            iv.fieldValues.add(val);
            iv.fieldNotes.add(note);
        }
        body.addView(fields);
        outer.setOnClickListener(v -> selectField(index, 0));
        return iv;
    }

    private void refreshCard(int i) {
        if (i < 0 || i >= itemViews.size()) return;
        ItemViews iv = itemViews.get(i);
        XlsxBook.Item it = iv.item;
        boolean selectedItem = i == state.item;
        boolean allDone = true;
        boolean belowPar = false;
        BigDecimal par = parse(it.par);

        for (int f = 0; f < it.fields.size(); f++) {
            XlsxBook.Field fld = it.fields.get(f);
            boolean sel = selectedItem && f == state.field;
            String v = state.effectiveValue(it, fld);
            TextView val = iv.fieldValues.get(f), note = iv.fieldNotes.get(f);
            if (sel && !buffer.isEmpty()) {
                val.setText(buffer);
                val.setTextColor(C_INK);
                note.setText("typing…  tap Next to save");
            } else if (v == null) {
                val.setText("—");
                val.setTextColor(0xFFBDB6AC);
                note.setText("not counted");
            } else if (InventoryState.BLANK.equals(v)) {
                val.setText("Blank");
                val.setTextColor(C_MUTED);
                note.setText("cell left empty");
            } else {
                val.setText(v);
                val.setTextColor(C_INK);
                note.setText(state.isFromSheet(it, fld) ? "already in sheet" : " ");
            }
            if (!state.isAnswered(it, fld)) allDone = false;

            boolean isQty = fld.label != null && fld.label.trim().equalsIgnoreCase("qty on hand");
            BigDecimal num = parse(sel && !buffer.isEmpty() ? buffer : v);
            boolean low = isQty && par != null && num != null && num.compareTo(par) <= 0;
            if (low) belowPar = true;

            int fill = sel ? C_SELECT_BG : (low ? C_WARN_BG : 0xFFF8F6F2);
            int stroke = sel ? C_SELECT : (low ? C_WARN : C_LINE);
            iv.fieldBoxes.get(f).setBackground(ripple(rounded(fill, dp(10), sel ? dp(3) : dp(1), stroke)));
        }
        iv.parBadge.setVisibility(belowPar ? View.VISIBLE : View.GONE);
        iv.stripe.setBackgroundColor(belowPar ? C_WARN : (allDone ? C_DONE : (selectedItem ? C_SELECT : 0x00000000)));
        GradientDrawable bg = rounded(C_CARD, dp(14), selectedItem ? dp(2) : 0, selectedItem ? C_SELECT : 0);
        iv.card.setBackground(bg);
        iv.card.setClipToOutline(true);
        iv.card.setElevation(selectedItem ? dp(4) : dp(1));
    }

    private void scrollToCurrent(boolean smooth) {
        if (state.item >= itemViews.size()) return;
        View card = itemViews.get(state.item).card;
        int viewH = listScroll.getHeight();
        int top = card.getTop() - dp(40);   // leave room for a section heading
        int bottom = card.getBottom() + dp(12);
        int cur = listScroll.getScrollY();
        int target = cur;
        if (top < cur) target = top;
        else if (bottom > cur + viewH) target = Math.min(top, bottom - viewH);
        if (target != cur) {
            if (smooth) listScroll.smoothScrollTo(0, Math.max(0, target));
            else listScroll.scrollTo(0, Math.max(0, target));
        }
    }

    // =================================================================== selection & entry

    private void selectField(int itemIndex, int fieldIndex) {
        if (itemIndex == state.item && fieldIndex == state.field) return;
        commitBuffer();
        int old = state.item;
        state.item = itemIndex;
        state.field = fieldIndex;
        state.save();
        refreshCard(old);
        refreshCard(itemIndex);
        refreshKeypad();
        scrollToCurrent(true);
    }

    /** Saves typed digits into the selected field. */
    private void commitBuffer() {
        if (buffer.isEmpty() || state.book == null) return;
        String n = XlsxBook.normalizeNumber(buffer);
        buffer = "";
        if (n == null) return;
        XlsxBook.Item it = state.currentItem();
        XlsxBook.Field f = state.currentField();
        state.entries.put(it.key(f), n);
        state.save();
    }

    private void setBlank() {
        buffer = "";
        XlsxBook.Item it = state.currentItem();
        state.entries.put(it.key(state.currentField()), InventoryState.BLANK);
        state.save();
        advance(1);
    }

    private void offerReset(int itemIndex, int fieldIndex) {
        XlsxBook.Item it = state.currentTab().items.get(itemIndex);
        XlsxBook.Field f = it.fields.get(fieldIndex);
        if (!state.entries.containsKey(it.key(f))) return;
        new AlertDialog.Builder(this)
                .setTitle("Undo this entry?")
                .setMessage(it.description + " — " + f.label + "\n\nThe cell will go back to "
                        + (f.original == null ? "not counted" : "what was in the sheet (" + f.original + ")") + ".")
                .setPositiveButton("Undo entry", (d, w) -> {
                    state.entries.remove(it.key(f));
                    if (itemIndex == state.item && fieldIndex == state.field) buffer = "";
                    state.save();
                    refreshCard(itemIndex);
                    refreshKeypad();
                    rebuildTabStrip();
                    refreshHeader();
                })
                .setNegativeButton("Cancel", null).show();
    }

    /** Moves to the next (+1) or previous (-1) field, crossing items and tabs. */
    private void advance(int dir) {
        commitBuffer();
        int oldTab = state.tab, oldItem = state.item;
        XlsxBook.Tab tab = state.currentTab();
        int f = state.field + dir, i = state.item, t = state.tab;
        if (f >= tab.items.get(i).fields.size()) { f = 0; i++; }
        else if (f < 0) { i--; f = Integer.MAX_VALUE; }
        if (i >= tab.items.size()) {
            if (t + 1 < state.book.tabs.size()) { t++; i = 0; f = 0; }
            else {
                i = tab.items.size() - 1; f = tab.items.get(i).fields.size() - 1;
                toast("That's the last item. Tap Export when you're ready.");
            }
        } else if (i < 0) {
            if (t > 0) { t--; i = Integer.MAX_VALUE; }
            else { i = 0; f = 0; }
        }
        state.tab = t;
        XlsxBook.Tab nt = state.currentTab();
        if (i == Integer.MAX_VALUE) i = nt.items.size() - 1;
        state.item = i;
        if (f == Integer.MAX_VALUE) f = nt.items.get(i).fields.size() - 1;
        state.field = f;
        state.save();
        if (t != oldTab) {
            showTab(true);
            toast(nt.name);
        } else {
            refreshCard(oldItem);
            refreshCard(state.item);
            refreshKeypad();
            rebuildTabStripProgressOnly();
            refreshHeader();
            scrollToCurrent(true);
        }
    }

    private void rebuildTabStripProgressOnly() {
        // cheap enough to rebuild; keeps counts on the chips current
        int x = tabScroll.getScrollX();
        rebuildTabStrip();
        tabScroll.scrollTo(x, 0);
    }

    private void typeKey(String k) {
        if (k.equals(".") && buffer.contains(".")) return;
        if (buffer.replace(".", "").length() >= 8) return;
        if (buffer.equals("0") && !k.equals(".")) buffer = "";
        buffer += k;
        refreshCard(state.item);
        refreshKeypad();
    }

    private void backspace() {
        if (!buffer.isEmpty()) buffer = buffer.substring(0, buffer.length() - 1);
        refreshCard(state.item);
        refreshKeypad();
    }

    // =================================================================== keypad

    private View buildKeypad() {
        LinearLayout pad = vbox();
        pad.setBackgroundColor(C_KEYPAD_BG);
        pad.setPadding(dp(12), dp(10), dp(12), dp(12));
        pad.setElevation(dp(12));

        LinearLayout info = new LinearLayout(this);
        info.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout infoText = vbox();
        padItem = text("", 17, C_INK, true);
        padItem.setSingleLine(true);
        padItem.setEllipsize(TextUtils.TruncateAt.END);
        padField = text("", 14, C_INK2, false);
        infoText.addView(padItem);
        infoText.addView(padField);
        info.addView(infoText, new LinearLayout.LayoutParams(0, -2, 1));
        padDisplay = text("", 30, C_INK, true);
        padDisplay.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        padDisplay.setPadding(dp(16), 0, dp(16), 0);
        padDisplay.setBackground(rounded(Color.WHITE, dp(10), dp(2), C_SELECT));
        info.addView(padDisplay, new LinearLayout.LayoutParams(dp(190), dp(52)));
        pad.addView(info);

        String[][] rows = {
                {"7", "8", "9", "⌫"},
                {"4", "5", "6", "BLANK"},
                {"1", "2", "3", "◀  Prev"},
                {".", "0", "", "Next  ▶"},
        };
        for (String[] r : rows) {
            LinearLayout row = new LinearLayout(this);
            row.setPadding(0, dp(8), 0, 0);
            for (int c = 0; c < r.length; c++) {
                String k = r[c];
                if (k.isEmpty()) continue;
                TextView key;
                float weight = 1;
                if (k.equals("0")) weight = 2.06f;   // wide zero spans the empty slot
                switch (k) {
                    case "⌫":
                        key = button(k, 0xFFD5CFC4, C_INK, 28);
                        key.setOnClickListener(v -> { haptic(v); backspace(); });
                        key.setOnLongClickListener(v -> { buffer = ""; backspace(); return true; });
                        break;
                    case "BLANK":
                        key = button("Blank", 0xFF6E6862, Color.WHITE, 22);
                        key.setOnClickListener(v -> { haptic(v); setBlank(); });
                        break;
                    case "◀  Prev":
                        key = button(k, 0xFFD5CFC4, C_INK, 20);
                        key.setOnClickListener(v -> { haptic(v); advance(-1); });
                        break;
                    case "Next  ▶":
                        key = button(k, C_BRAND, Color.WHITE, 22);
                        key.setOnClickListener(v -> { haptic(v); advance(1); });
                        break;
                    default:
                        key = button(k, C_KEY, C_INK, 30);
                        key.setOnClickListener(v -> { haptic(v); typeKey(k); });
                }
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(58), weight);
                if (c < r.length - 1) lp.rightMargin = dp(8);
                row.addView(key, lp);
            }
            pad.addView(row);
        }
        return pad;
    }

    private void refreshKeypad() {
        if (padItem == null || state.book == null) return;
        XlsxBook.Item it = state.currentItem();
        XlsxBook.Field f = state.currentField();
        padItem.setText(it.description);
        padField.setText(f.label + (it.vendor != null ? "  ·  " + it.vendor.replace('\n', ' ') : ""));
        if (!buffer.isEmpty()) {
            padDisplay.setText(buffer);
            padDisplay.setTextColor(C_INK);
        } else {
            String v = state.effectiveValue(it, f);
            padDisplay.setText(v == null ? "" : InventoryState.BLANK.equals(v) ? "Blank" : v);
            padDisplay.setTextColor(0xFFA9A299);
        }
    }

    private void haptic(View v) { v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); }

    // =================================================================== export

    private void startExport() {
        commitBuffer();
        state.save();
        Map<String, Integer> gaps = state.unanswered();
        if (!gaps.isEmpty()) {
            int total = 0;
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, Integer> e : gaps.entrySet()) {
                total += e.getValue();
                sb.append("\n•  ").append(e.getKey()).append(": ").append(e.getValue());
            }
            new AlertDialog.Builder(this)
                    .setTitle(total + (total == 1 ? " count is" : " counts are") + " missing")
                    .setMessage("These haven't been given a number or marked Blank:\n" + sb
                            + "\n\nIf you export anyway, those cells are left exactly as they were in the original sheet.")
                    .setPositiveButton("Go to first", (d, w) -> {
                        state.tab = 0; state.item = 0; state.field = 0;
                        state.gotoNextUnanswered();
                        buffer = "";
                        showTab(true);
                    })
                    .setNeutralButton("Export anyway", (d, w) -> ensureFolderThenExport())
                    .setNegativeButton("Cancel", null)
                    .show();
        } else {
            ensureFolderThenExport();
        }
    }

    private Uri exportTree() {
        String s = prefs.getString("tree", null);
        if (s == null) return null;
        Uri u = Uri.parse(s);
        for (UriPermission p : getContentResolver().getPersistedUriPermissions()) {
            if (p.getUri().equals(u) && p.isWritePermission()) return u;
        }
        return null;
    }

    private void ensureFolderThenExport() {
        if (exportTree() != null) { doExport(); return; }
        pendingExport = true;
        new AlertDialog.Builder(this)
                .setTitle("Choose the Inventories folder")
                .setMessage("On the next screen, open Internal storage → Inventories, then tap \"Use this folder\" and Allow.\n\n"
                        + "You only need to do this once. Year folders (" + new SimpleDateFormat("yyyy", Locale.US).format(new Date())
                        + ", …) are created inside it automatically.")
                .setPositiveButton("Choose folder", (d, w) -> pickFolder())
                .setNegativeButton("Cancel", (d, w) -> pendingExport = false)
                .show();
    }

    private void pickFolder() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        try {
            Uri initial = DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Inventories");
            i.putExtra(DocumentsContract.EXTRA_INITIAL_URI, initial);
        } catch (Exception ignored) { }
        startActivityForResult(i, REQ_FOLDER);
    }

    private void doExport() {
        final Uri tree = exportTree();
        if (tree == null) { ensureFolderThenExport(); return; }
        showBusy("Saving inventory…");
        final Map<String, String> edits = state.exportEdits();
        io.execute(() -> {
            String err = null, savedPath = null;
            Uri fileUri = null;
            try {
                ContentResolver cr = getContentResolver();
                Uri parentDoc = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
                String year = new SimpleDateFormat("yyyy", Locale.US).format(new Date());
                Uri yearDoc = findChild(cr, tree, parentDoc, year, true);
                if (yearDoc == null) {
                    yearDoc = DocumentsContract.createDocument(cr, parentDoc, DocumentsContract.Document.MIME_TYPE_DIR, year);
                    if (yearDoc == null) throw new IOException("Couldn't create the " + year + " folder.");
                }
                String base = "Monthly Inventory " + new SimpleDateFormat("MM-dd-yy", Locale.US).format(new Date());
                Set<String> existing = childNames(cr, tree, yearDoc);
                String name = base + ".xlsx";
                for (int n = 2; existing.contains(name.toLowerCase(Locale.US)); n++) name = base + " (" + n + ").xlsx";
                fileUri = DocumentsContract.createDocument(cr, yearDoc, MIME_XLSX, name);
                if (fileUri == null) throw new IOException("Couldn't create the file.");
                try (OutputStream out = cr.openOutputStream(fileUri, "wt")) {
                    if (out == null) throw new IOException("Couldn't write the file.");
                    state.book.write(edits, out);
                }
                savedPath = prefs.getString("treeName", "Inventories") + " / " + year + " / " + name;
            } catch (Exception e) {
                err = e.getMessage() == null ? e.toString() : e.getMessage();
                if (fileUri != null) {
                    try { DocumentsContract.deleteDocument(getContentResolver(), fileUri); } catch (Exception ignored) { }
                }
            }
            final String error = err, path = savedPath;
            final Uri result = fileUri;
            main.post(() -> {
                hideBusy();
                if (error != null) {
                    new AlertDialog.Builder(this).setTitle("Export failed").setMessage(error
                                    + "\n\nIf the folder was moved or renamed, choose it again from the ⋮ menu.")
                            .setPositiveButton("OK", null).show();
                    return;
                }
                new AlertDialog.Builder(this)
                        .setTitle("Inventory exported")
                        .setMessage("Saved to:\n" + path)
                        .setPositiveButton("Done", null)
                        .setNeutralButton("Share / email", (d, w) -> share(result))
                        .show();
            });
        });
    }

    private void share(Uri file) {
        Intent s = new Intent(Intent.ACTION_SEND);
        s.setType(MIME_XLSX);
        s.putExtra(Intent.EXTRA_STREAM, file);
        s.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(s, "Send inventory"));
    }

    private static Uri findChild(ContentResolver cr, Uri tree, Uri parentDoc, String name, boolean dir) {
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(parentDoc));
        try (Cursor c = cr.query(children, new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE}, null, null, null)) {
            while (c != null && c.moveToNext()) {
                boolean isDir = DocumentsContract.Document.MIME_TYPE_DIR.equals(c.getString(2));
                if (name.equals(c.getString(1)) && isDir == dir) {
                    return DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0));
                }
            }
        }
        return null;
    }

    private static Set<String> childNames(ContentResolver cr, Uri tree, Uri parentDoc) {
        Set<String> out = new HashSet<>();
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(parentDoc));
        try (Cursor c = cr.query(children, new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
            while (c != null && c.moveToNext()) out.add(c.getString(0).toLowerCase(Locale.US));
        }
        return out;
    }

    private View busy;

    private void showBusy(String msg) {
        LinearLayout b = vbox();
        b.setGravity(Gravity.CENTER);
        b.setBackgroundColor(0x99000000);
        b.setClickable(true);
        ProgressBar pb = new ProgressBar(this);
        pb.setIndeterminateTintList(ColorStateList.valueOf(Color.WHITE));
        b.addView(pb);
        TextView t = text(msg, 18, Color.WHITE, true);
        t.setPadding(0, dp(12), 0, 0);
        b.addView(t);
        busy = b;
        root.addView(b, new FrameLayout.LayoutParams(-1, -1));
    }

    private void hideBusy() { if (busy != null) { root.removeView(busy); busy = null; } }

    // =================================================================== results

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_OPEN && res == RESULT_OK && data != null && data.getData() != null) {
            confirmImport(data.getData());
        } else if (req == REQ_FOLDER) {
            if (res == RESULT_OK && data != null && data.getData() != null) {
                Uri tree = data.getData();
                try {
                    getContentResolver().takePersistableUriPermission(tree,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                } catch (SecurityException e) {
                    toast("Couldn't get permission for that folder.");
                    pendingExport = false;
                    return;
                }
                String id = DocumentsContract.getTreeDocumentId(tree);
                String label = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
                if (label.isEmpty()) label = "Internal storage";
                prefs.edit().putString("tree", tree.toString()).putString("treeName", label).apply();
                String leaf = label.contains("/") ? label.substring(label.lastIndexOf('/') + 1) : label;
                if (!leaf.equalsIgnoreCase("Inventories")) {
                    toast("Exports will go to \"" + label + "\"");
                }
                if (pendingExport) { pendingExport = false; doExport(); }
            } else {
                pendingExport = false;
            }
        }
    }

    // =================================================================== view helpers

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    private LinearLayout vbox() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    private LinearLayout.LayoutParams lp(int w, int h, int rightMargin) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.rightMargin = rightMargin;
        return p;
    }

    private TextView text(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return t;
    }

    private TextView chip(String s, int bg, int fg, boolean bold) {
        TextView t = text(s, 15, fg, bold);
        t.setPadding(dp(10), dp(4), dp(10), dp(4));
        t.setBackground(rounded(bg, dp(8), 0, 0));
        t.setSingleLine(true);
        return t;
    }

    private TextView button(String s, int bg, int fg, float sp) {
        TextView b = text(s, sp, fg, true);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(14), 0, dp(14), 0);
        b.setBackground(ripple(rounded(bg, dp(12), 0, 0)));
        b.setClickable(true);
        b.setFocusable(true);
        return b;
    }

    private static GradientDrawable rounded(int fill, int radius, int strokeW, int strokeColor) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(radius);
        if (strokeW > 0) g.setStroke(strokeW, strokeColor);
        return g;
    }

    private static Drawable ripple(Drawable content) {
        return new RippleDrawable(ColorStateList.valueOf(0x33000000), content, null);
    }

    private static BigDecimal parse(String s) {
        if (s == null || InventoryState.BLANK.equals(s)) return null;
        try { return new BigDecimal(s.trim()); } catch (NumberFormatException e) { return null; }
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
}
