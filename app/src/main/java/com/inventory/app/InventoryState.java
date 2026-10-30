package com.inventory.app;

import com.inventory.app.xlsx.XlsxBook;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The inventory in progress: the imported workbook plus everything entered so far.
 * Saved to app-private storage after every change so nothing is lost if the app closes.
 */
final class InventoryState {

    static final String BLANK = "\u0000blank";

    XlsxBook book;
    String fileName;
    /** key (sheet!cell) -> number text, or BLANK. Missing = not touched yet. */
    final Map<String, String> entries = new HashMap<>();
    int tab, item, field;

    private final File dir;

    InventoryState(File dir) { this.dir = dir; }

    private File sourceFile() { return new File(dir, "source.xlsx"); }
    private File progressFile() { return new File(dir, "progress.json"); }

    boolean hasSaved() { return sourceFile().exists(); }

    /** Restores the last session. Returns false if there is none. */
    boolean restore() throws IOException {
        if (!sourceFile().exists()) return false;
        book = XlsxBook.load(Files.readAllBytes(sourceFile().toPath()));
        entries.clear();
        tab = item = field = 0;
        fileName = null;
        if (progressFile().exists()) {
            try {
                JSONObject o = new JSONObject(new String(Files.readAllBytes(progressFile().toPath()), StandardCharsets.UTF_8));
                fileName = o.optString("fileName", null);
                JSONObject e = o.optJSONObject("entries");
                if (e != null) {
                    for (Iterator<String> it = e.keys(); it.hasNext(); ) {
                        String k = it.next();
                        String v = e.getString(k);
                        entries.put(k, v.equals("b") ? BLANK : v.substring(2));
                    }
                }
                tab = o.optInt("tab", 0);
                item = o.optInt("item", 0);
                field = o.optInt("field", 0);
            } catch (JSONException ignored) { }
        }
        clampPosition();
        return true;
    }

    /** Starts a new inventory from a freshly imported file (replacing any previous one). */
    void startNew(byte[] xlsx, String name) throws IOException {
        XlsxBook b = XlsxBook.load(xlsx);
        if (b.tabs.isEmpty()) throw new IOException("No inventory tabs were found in this file.");
        writeAtomic(sourceFile(), xlsx);
        book = b;
        fileName = name;
        entries.clear();
        tab = item = field = 0;
        save();
    }

    void save() {
        try {
            JSONObject o = new JSONObject();
            o.put("fileName", fileName);
            JSONObject e = new JSONObject();
            for (Map.Entry<String, String> en : entries.entrySet()) {
                e.put(en.getKey(), BLANK.equals(en.getValue()) ? "b" : "v:" + en.getValue());
            }
            o.put("entries", e);
            o.put("tab", tab);
            o.put("item", item);
            o.put("field", field);
            writeAtomic(progressFile(), o.toString().getBytes(StandardCharsets.UTF_8));
        } catch (JSONException | IOException ignored) { }
    }

    private static void writeAtomic(File f, byte[] data) throws IOException {
        File tmp = new File(f.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(data);
            out.getFD().sync();
        }
        if (!tmp.renameTo(f)) {
            f.delete();
            if (!tmp.renameTo(f)) throw new IOException("Could not save progress");
        }
    }

    // ------------------------------------------------------------ queries

    XlsxBook.Tab currentTab() { return book.tabs.get(tab); }
    XlsxBook.Item currentItem() { return currentTab().items.get(item); }
    XlsxBook.Field currentField() { return currentItem().fields.get(field); }

    void clampPosition() {
        if (book == null || book.tabs.isEmpty()) { tab = item = field = 0; return; }
        tab = Math.max(0, Math.min(tab, book.tabs.size() - 1));
        item = Math.max(0, Math.min(item, currentTab().items.size() - 1));
        field = Math.max(0, Math.min(field, currentItem().fields.size() - 1));
    }

    /** What the cell will contain: a number, BLANK, or null if nothing at all. */
    String effectiveValue(XlsxBook.Item it, XlsxBook.Field f) {
        String e = entries.get(it.key(f));
        if (e != null) return e;
        return f.original;
    }

    boolean isFromSheet(XlsxBook.Item it, XlsxBook.Field f) {
        return !entries.containsKey(it.key(f)) && f.original != null;
    }

    /** A field is "answered" once it has a number (entered or already in the sheet) or was marked Blank. */
    boolean isAnswered(XlsxBook.Item it, XlsxBook.Field f) {
        return entries.containsKey(it.key(f)) || f.original != null;
    }

    int[] tabProgress(XlsxBook.Tab t) {
        int done = 0, total = 0;
        for (XlsxBook.Item it : t.items) for (XlsxBook.Field f : it.fields) {
            total++;
            if (isAnswered(it, f)) done++;
        }
        return new int[]{done, total};
    }

    int[] totalProgress() {
        int done = 0, total = 0;
        for (XlsxBook.Tab t : book.tabs) { int[] p = tabProgress(t); done += p[0]; total += p[1]; }
        return new int[]{done, total};
    }

    /** Unanswered count per tab name, in tab order (only tabs with gaps). */
    LinkedHashMap<String, Integer> unanswered() {
        LinkedHashMap<String, Integer> out = new LinkedHashMap<>();
        for (XlsxBook.Tab t : book.tabs) {
            int[] p = tabProgress(t);
            if (p[1] - p[0] > 0) out.put(t.name, p[1] - p[0]);
        }
        return out;
    }

    /** Moves to the first unanswered field at or after the current position (wrapping). */
    boolean gotoNextUnanswered() {
        int nt = book.tabs.size();
        for (int pass = 0; pass <= nt; pass++) {
            int ti = (tab + pass) % nt;
            XlsxBook.Tab t = book.tabs.get(ti);
            for (int ii = 0; ii < t.items.size(); ii++) {
                XlsxBook.Item it = t.items.get(ii);
                for (int fi = 0; fi < it.fields.size(); fi++) {
                    if (pass == 0 && (ii < item || (ii == item && fi < field))) continue;
                    if (!isAnswered(it, it.fields.get(fi))) { tab = ti; item = ii; field = fi; return true; }
                }
            }
        }
        return false;
    }

    /** Builds the cell edits for export. Untouched fields are left exactly as they are in the sheet. */
    Map<String, String> exportEdits() {
        Map<String, String> edits = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : entries.entrySet()) {
            edits.put(e.getKey(), BLANK.equals(e.getValue()) ? null : e.getValue());
        }
        return edits;
    }
}
