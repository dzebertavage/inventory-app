package com.inventory.app.xlsx;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Reads an .xlsx inventory workbook into a simple item model, and writes entered
 * counts back by changing ONLY the value of the target cells. Every other byte of
 * every other part of the file (styles, conditional formatting, formulas, other
 * tabs, Google Sheets metadata) is copied through untouched.
 *
 * Pure JDK so it can be tested off-device.
 */
public final class XlsxBook {

    /** Tabs that are never shown and never modified. */
    public static final Set<String> IGNORED_TABS = new HashSet<>(Arrays.asList(
            "inventory - green beans",
            "roaster & mail order shelves",
            "equipment",
            "ba equipment",
            "totals",
            "sheet5"));

    /** Column headers the user fills in. */
    public static final List<String> FILL_HEADERS = Arrays.asList(
            "qty on hand", "5 lb bags", "lbs on mail order shelf", "lbs in buckets");

    // ---------------------------------------------------------------- model

    public static final class Field {
        public final String label;      // header text as shown in the sheet
        public final String cellRef;    // e.g. "E13"
        public final String original;   // numeric/text value already in the cell, or null
        Field(String label, String cellRef, String original) {
            this.label = label; this.cellRef = cellRef; this.original = original;
        }
    }

    public static final class Item {
        public final String sheetPath;
        public final int row;
        public String section;      // nearest section label above, may be null
        public String description;
        public String vendor;       // may be null
        public String unit;         // may be null
        public String par;          // may be null
        public String itemId;       // may be null
        public final List<Field> fields = new ArrayList<>();
        Item(String sheetPath, int row) { this.sheetPath = sheetPath; this.row = row; }
        public String key(Field f) { return sheetPath + "!" + f.cellRef; }
    }

    public static final class Tab {
        public final String name;
        public final String path;   // zip entry, e.g. xl/worksheets/sheet2.xml
        public final List<Item> items = new ArrayList<>();
        Tab(String name, String path) { this.name = name; this.path = path; }
    }

    public final List<Tab> tabs = new ArrayList<>();
    private final byte[] source;

    private XlsxBook(byte[] source) { this.source = source; }

    // ---------------------------------------------------------------- reading

    public static XlsxBook load(byte[] xlsx) throws IOException {
        XlsxBook book = new XlsxBook(xlsx);
        Map<String, byte[]> parts = readParts(xlsx, null);
        String workbook = str(parts.get("xl/workbook.xml"));
        String rels = str(parts.get("xl/_rels/workbook.xml.rels"));
        if (workbook == null || rels == null) throw new IOException("This file doesn't look like an Excel workbook.");

        Map<String, String> relTargets = new HashMap<>();
        Matcher rm = Pattern.compile("<Relationship\\b[^>]*>").matcher(rels);
        while (rm.find()) {
            String id = attr(rm.group(), "Id"), target = attr(rm.group(), "Target");
            if (id != null && target != null) relTargets.put(id, resolveTarget(target));
        }
        List<String> shared = parseSharedStrings(str(parts.get("xl/sharedStrings.xml")));
        boolean[] boldStyles = parseBoldStyles(str(parts.get("xl/styles.xml")));

        Matcher sm = Pattern.compile("<sheet\\b[^>]*>").matcher(workbook);
        while (sm.find()) {
            String name = unescape(attr(sm.group(), "name"));
            String rid = attr(sm.group(), "r:id");
            String path = relTargets.get(rid);
            if (name == null || path == null) continue;
            if (IGNORED_TABS.contains(name.trim().toLowerCase(Locale.US))) continue;
            byte[] xml = parts.get(path);
            if (xml == null) continue;
            Tab tab = new Tab(name, path);
            parseSheet(tab, str(xml), shared, boldStyles);
            if (!tab.items.isEmpty()) book.tabs.add(tab);
        }
        return book;
    }

    private static String resolveTarget(String target) {
        if (target.startsWith("/")) return target.substring(1);
        String t = "xl/" + target;
        // normalise "xl/../" style paths
        while (t.contains("/../")) t = t.replaceFirst("[^/]+/\\.\\./", "");
        return t;
    }

    private static final Pattern ROW = Pattern.compile("<row\\b([^>]*?)(/>|>(.*?)</row>)", Pattern.DOTALL);
    private static final Pattern CELL = Pattern.compile("<c\\b([^>]*?)(/>|>(.*?)</c>)", Pattern.DOTALL);

    private static final class RawCell {
        String text;        // display text, null if empty
        boolean formula;
        boolean bold;
    }

    private static void parseSheet(Tab tab, String xml, List<String> shared, boolean[] boldStyles) {
        int sd = xml.indexOf("<sheetData");
        if (sd < 0) return;
        int sdEnd = xml.indexOf("</sheetData>", sd);
        if (sdEnd < 0) return;
        String data = xml.substring(sd, sdEnd);

        Map<String, String> headerByCol = null;   // column letter -> normalised header
        Map<String, String> headerLabel = null;   // column letter -> header as written
        String section = null;

        Matcher rowM = ROW.matcher(data);
        while (rowM.find()) {
            String rAttr = attr("<row" + rowM.group(1) + ">", "r");
            if (rAttr == null || rowM.group(3) == null) continue;
            int rowNum = Integer.parseInt(rAttr);
            Map<String, RawCell> cells = new LinkedHashMap<>();
            Matcher cm = CELL.matcher(rowM.group(3));
            while (cm.find()) {
                String head = "<c" + cm.group(1) + ">";
                String ref = attr(head, "r");
                if (ref == null) continue;
                RawCell rc = new RawCell();
                String body = cm.group(3) == null ? "" : cm.group(3);
                rc.formula = body.contains("<f");
                rc.text = cellText(attr(head, "t"), body, shared);
                String st = attr(head, "s");
                try {
                    int si = st == null ? 0 : Integer.parseInt(st);
                    rc.bold = si < boldStyles.length && boldStyles[si];
                } catch (NumberFormatException ignored) { }
                cells.put(col(ref), rc);
            }

            // Header row?
            boolean isHeader = false;
            for (RawCell rc : cells.values()) {
                if (rc.text != null && norm(rc.text).equals("item description")) { isHeader = true; break; }
            }
            if (isHeader) {
                headerByCol = new HashMap<>();
                headerLabel = new HashMap<>();
                for (Map.Entry<String, RawCell> e : cells.entrySet()) {
                    if (e.getValue().text == null) continue;
                    headerByCol.put(e.getKey(), norm(e.getValue().text));
                    headerLabel.put(e.getKey(), e.getValue().text.trim().replaceAll("\\s+", " "));
                }
                section = null;
                continue;
            }
            if (headerByCol == null) continue;

            String descCol = null, vendorCol = null, idCol = null, unitCol = null, parCol = null;
            List<String> fillCols = new ArrayList<>();
            for (Map.Entry<String, String> h : headerByCol.entrySet()) {
                String v = h.getValue();
                if (v.equals("item description")) descCol = h.getKey();
                else if (v.contains("vendor")) vendorCol = h.getKey();      // incl. "Item ID/Vendor"
                else if (v.equals("item id")) idCol = h.getKey();
                else if (v.equals("unit")) unitCol = h.getKey();
                else if (v.equals("par")) parCol = h.getKey();
                else if (FILL_HEADERS.contains(v)) fillCols.add(h.getKey());
            }
            fillCols.sort((a, b) -> colIndex(a) - colIndex(b));
            if (descCol == null || fillCols.isEmpty()) continue;

            String desc = textOf(cells, descCol);
            if (desc == null) continue;   // totals rows, spacer rows

            // Does anything besides the description identify this row as an item?
            boolean other = false;
            for (Map.Entry<String, RawCell> e : cells.entrySet()) {
                if (e.getKey().equals(descCol)) continue;
                if (e.getValue().text != null || e.getValue().formula) { other = true; break; }
            }
            // A row with only a bold description is a section heading ("Shelf 1", "Milk").
            // A non-bold description on its own is still an item to count.
            if (!other && cells.get(descCol).bold) { section = desc; continue; }

            Item item = new Item(tab.path, rowNum);
            item.section = section;
            item.description = desc;
            item.vendor = textOf(cells, vendorCol);
            item.itemId = textOf(cells, idCol);
            item.unit = textOf(cells, unitCol);
            item.par = textOf(cells, parCol);
            for (String c : fillCols) {
                RawCell rc = cells.get(c);
                if (rc != null && rc.formula) continue;   // never overwrite a formula
                item.fields.add(new Field(headerLabel.get(c), c + rowNum, rc == null ? null : rc.text));
            }
            if (!item.fields.isEmpty()) tab.items.add(item);
        }
    }

    private static String textOf(Map<String, RawCell> cells, String col) {
        if (col == null) return null;
        RawCell rc = cells.get(col);
        return rc == null ? null : rc.text;
    }

    private static String cellText(String type, String body, List<String> shared) {
        String v = between(body, "<v>", "</v>");
        String out;
        if ("s".equals(type)) {
            if (v == null) return null;
            try { out = shared.get(Integer.parseInt(v.trim())); } catch (RuntimeException e) { return null; }
        } else if ("inlineStr".equals(type)) {
            out = joinT(body);
        } else {
            out = v == null ? null : unescape(v);
            if (out != null && type == null) out = prettyNumber(out);
        }
        if (out == null) return null;
        out = out.trim();
        return out.isEmpty() ? null : out;
    }

    /** "3.0" -> "3", "1.25" -> "1.25"; non-numbers unchanged. */
    public static String prettyNumber(String s) {
        try {
            BigDecimal d = new BigDecimal(s.trim()).stripTrailingZeros();
            if (d.scale() < 0) d = d.setScale(0);
            return d.toPlainString();
        } catch (NumberFormatException e) { return s; }
    }

    /** For each cellXfs index, whether its font is bold. */
    private static boolean[] parseBoldStyles(String xml) {
        if (xml == null) return new boolean[0];
        List<Boolean> fontBold = new ArrayList<>();
        String fonts = between(xml, "<fonts", "</fonts>");
        if (fonts != null) {
            Matcher fm = Pattern.compile("<font\\b[^>]*?(/>|>(.*?)</font>)", Pattern.DOTALL).matcher(fonts);
            while (fm.find()) {
                String body = fm.group(2) == null ? "" : fm.group(2);
                Matcher b = Pattern.compile("<b\\b([^>]*)/?>").matcher(body);
                boolean bold = false;
                if (b.find()) {
                    String v = attr("<b" + b.group(1) + ">", "val");
                    bold = v == null || !(v.equals("0") || v.equalsIgnoreCase("false"));
                }
                fontBold.add(bold);
            }
        }
        String xfs = between(xml, "<cellXfs", "</cellXfs>");
        if (xfs == null) return new boolean[0];
        List<Boolean> out = new ArrayList<>();
        Matcher xm = Pattern.compile("<xf\\b[^>]*>").matcher(xfs);
        while (xm.find()) {
            String fid = attr(xm.group(), "fontId");
            int f = fid == null ? 0 : Integer.parseInt(fid);
            out.add(f < fontBold.size() && fontBold.get(f));
        }
        boolean[] arr = new boolean[out.size()];
        for (int i = 0; i < arr.length; i++) arr[i] = out.get(i);
        return arr;
    }

    private static List<String> parseSharedStrings(String xml) {
        List<String> out = new ArrayList<>();
        if (xml == null) return out;
        Matcher m = Pattern.compile("<si>(.*?)</si>|<si/>", Pattern.DOTALL).matcher(xml);
        while (m.find()) out.add(m.group(1) == null ? "" : joinT(m.group(1)));
        return out;
    }

    private static String joinT(String xml) {
        // ignore phonetic runs
        xml = xml.replaceAll("(?s)<rPh\\b.*?</rPh>", "");
        StringBuilder sb = new StringBuilder();
        Matcher m = Pattern.compile("<t\\b[^>]*>(.*?)</t>", Pattern.DOTALL).matcher(xml);
        while (m.find()) sb.append(unescape(m.group(1)));
        return sb.toString();
    }

    // ---------------------------------------------------------------- writing

    /**
     * Writes a copy of the original workbook with the given cell changes.
     * @param edits key = sheetPath + "!" + cellRef; value = number text, or null to leave the cell empty.
     *              Cells not present in the map are not touched at all.
     */
    public void write(Map<String, String> edits, OutputStream out) throws IOException {
        Map<String, Map<String, String>> bySheet = new HashMap<>();
        for (Map.Entry<String, String> e : edits.entrySet()) {
            int bang = e.getKey().lastIndexOf('!');
            bySheet.computeIfAbsent(e.getKey().substring(0, bang), k -> new LinkedHashMap<>())
                    .put(e.getKey().substring(bang + 1), e.getValue());
        }
        try (ZipInputStream zin = new ZipInputStream(new java.io.ByteArrayInputStream(source));
             ZipOutputStream zout = new ZipOutputStream(out)) {
            ZipEntry in;
            byte[] buf = new byte[65536];
            while ((in = zin.getNextEntry()) != null) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                int n;
                while ((n = zin.read(buf)) > 0) bos.write(buf, 0, n);
                byte[] data = bos.toByteArray();
                Map<String, String> sheetEdits = bySheet.get(in.getName());
                if (sheetEdits != null && !sheetEdits.isEmpty()) {
                    data = applyEdits(str(data), sheetEdits).getBytes(StandardCharsets.UTF_8);
                }
                ZipEntry outE = new ZipEntry(in.getName());
                if (in.getTime() != -1) outE.setTime(in.getTime());
                if (in.getMethod() == ZipEntry.STORED) {
                    outE.setMethod(ZipEntry.STORED);
                    outE.setSize(data.length);
                    CRC32 crc = new CRC32(); crc.update(data); outE.setCrc(crc.getValue());
                }
                zout.putNextEntry(outE);
                zout.write(data);
                zout.closeEntry();
            }
        }
    }

    static String applyEdits(String xml, Map<String, String> edits) {
        StringBuilder sb = new StringBuilder(xml);
        // Process in sheet order so row/cell insertion positions stay valid.
        List<String> refs = new ArrayList<>(edits.keySet());
        refs.sort((a, b) -> {
            int r = rowOf(a) - rowOf(b);
            return r != 0 ? r : colIndex(col(a)) - colIndex(col(b));
        });
        for (String ref : refs) setCell(sb, ref, edits.get(ref));
        return sb.toString();
    }

    private static void setCell(StringBuilder sb, String ref, String value) {
        int sdStart = sb.indexOf("<sheetData");
        if (sdStart < 0) throw new IllegalStateException("No sheetData");
        int sdOpenEnd = sb.indexOf(">", sdStart);
        boolean sdSelfClosing = sb.charAt(sdOpenEnd - 1) == '/';
        if (sdSelfClosing) {
            sb.replace(sdStart, sdOpenEnd + 1, "<sheetData></sheetData>");
            sdOpenEnd = sdStart + "<sheetData>".length() - 1;
        }
        int sdEnd = sb.indexOf("</sheetData>", sdOpenEnd);
        int rowNum = rowOf(ref);
        String newValueXml = value == null ? null : "<v>" + value + "</v>";

        // Locate the row.
        int pos = sdOpenEnd + 1;
        while (true) {
            int rs = sb.indexOf("<row", pos);
            if (rs < 0 || rs >= sdEnd) {   // append new row at end
                sb.insert(sdEnd, newRow(rowNum, ref, newValueXml));
                return;
            }
            int re = sb.indexOf(">", rs);
            String rowHead = sb.substring(rs, re + 1);
            int r = Integer.parseInt(attr(rowHead, "r"));
            if (r < rowNum) {
                pos = rowHead.endsWith("/>") ? re + 1 : sb.indexOf("</row>", re) + 6;
                continue;
            }
            if (r > rowNum) {
                sb.insert(rs, newRow(rowNum, ref, newValueXml));
                return;
            }
            // r == rowNum
            if (rowHead.endsWith("/>")) {
                if (newValueXml == null) return;
                String open = rowHead.substring(0, rowHead.length() - 2) + ">";
                sb.replace(rs, re + 1, open + "<c r=\"" + ref + "\">" + newValueXml + "</c></row>");
                return;
            }
            int rowEnd = sb.indexOf("</row>", re);
            int targetCol = colIndex(col(ref));
            int cpos = re + 1;
            while (true) {
                int cs = sb.indexOf("<c", cpos);
                // make sure it's a <c ...> element and inside this row
                while (cs >= 0 && cs < rowEnd && !(sb.charAt(cs + 2) == ' ' || sb.charAt(cs + 2) == '>' || sb.charAt(cs + 2) == '/')) {
                    cs = sb.indexOf("<c", cs + 2);
                }
                if (cs < 0 || cs >= rowEnd) {
                    if (newValueXml != null) sb.insert(rowEnd, "<c r=\"" + ref + "\">" + newValueXml + "</c>");
                    return;
                }
                int ce = sb.indexOf(">", cs);
                String cellHead = sb.substring(cs, ce + 1);
                boolean selfClosing = cellHead.endsWith("/>");
                int cellEnd = selfClosing ? ce + 1 : sb.indexOf("</c>", ce) + 4;
                String cref = attr(cellHead, "r");
                int c = cref == null ? -1 : colIndex(col(cref));
                if (cref != null && c == targetCol) {
                    String attrs = selfClosing ? cellHead.substring(2, cellHead.length() - 2) : cellHead.substring(2, cellHead.length() - 1);
                    attrs = removeAttr(attrs, "t").replaceAll("\\s+$", "");
                    String repl = newValueXml == null ? "<c" + attrs + "/>" : "<c" + attrs + ">" + newValueXml + "</c>";
                    sb.replace(cs, cellEnd, repl);
                    return;
                }
                if (cref != null && c > targetCol) {
                    if (newValueXml != null) sb.insert(cs, "<c r=\"" + ref + "\">" + newValueXml + "</c>");
                    return;
                }
                cpos = cellEnd;
            }
        }
    }

    private static String newRow(int rowNum, String ref, String valueXml) {
        if (valueXml == null) return "";
        return "<row r=\"" + rowNum + "\"><c r=\"" + ref + "\">" + valueXml + "</c></row>";
    }

    /** Normalises a typed number for storage in a cell. Returns null if not a number. */
    public static String normalizeNumber(String typed) {
        if (typed == null) return null;
        String t = typed.trim();
        if (t.isEmpty() || t.equals(".")) return null;
        try {
            BigDecimal d = new BigDecimal(t.startsWith(".") ? "0" + t : t).stripTrailingZeros();
            if (d.scale() < 0) d = d.setScale(0);
            return d.toPlainString();
        } catch (NumberFormatException e) { return null; }
    }

    // ---------------------------------------------------------------- helpers

    private static Map<String, byte[]> readParts(byte[] xlsx, Set<String> only) throws IOException {
        Map<String, byte[]> parts = new LinkedHashMap<>();
        try (ZipInputStream zin = new ZipInputStream(new java.io.ByteArrayInputStream(xlsx))) {
            ZipEntry e;
            byte[] buf = new byte[65536];
            while ((e = zin.getNextEntry()) != null) {
                if (only != null && !only.contains(e.getName())) continue;
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                int n;
                while ((n = zin.read(buf)) > 0) bos.write(buf, 0, n);
                parts.put(e.getName(), bos.toByteArray());
            }
        } catch (java.util.zip.ZipException ze) {
            throw new IOException("This file isn't a valid .xlsx spreadsheet.", ze);
        }
        return parts;
    }

    public static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        return bos.toByteArray();
    }

    private static String str(byte[] b) { return b == null ? null : new String(b, StandardCharsets.UTF_8); }

    static String attr(String tag, String name) {
        Matcher m = Pattern.compile("[\\s<]" + Pattern.quote(name) + "\\s*=\\s*\"([^\"]*)\"").matcher(tag);
        return m.find() ? m.group(1) : null;
    }

    private static String removeAttr(String attrs, String name) {
        return attrs.replaceAll("\\s" + Pattern.quote(name) + "\\s*=\\s*\"[^\"]*\"", "");
    }

    private static String between(String s, String a, String b) {
        int i = s.indexOf(a);
        if (i < 0) return null;
        int j = s.indexOf(b, i + a.length());
        return j < 0 ? null : s.substring(i + a.length(), j);
    }

    static String unescape(String s) {
        if (s == null || s.indexOf('&') < 0) return s;
        Matcher m = Pattern.compile("&(#x[0-9a-fA-F]+|#\\d+|amp|lt|gt|quot|apos);").matcher(s);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String g = m.group(1), r;
            switch (g) {
                case "amp": r = "&"; break;
                case "lt": r = "<"; break;
                case "gt": r = ">"; break;
                case "quot": r = "\""; break;
                case "apos": r = "'"; break;
                default:
                    int cp = g.startsWith("#x") ? Integer.parseInt(g.substring(2), 16) : Integer.parseInt(g.substring(1));
                    r = new String(Character.toChars(cp));
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(r));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String norm(String s) { return s.trim().replaceAll("\\s+", " ").toLowerCase(Locale.US); }

    static String col(String ref) {
        int i = 0;
        while (i < ref.length() && Character.isLetter(ref.charAt(i))) i++;
        return ref.substring(0, i).toUpperCase(Locale.US);
    }

    static int rowOf(String ref) { return Integer.parseInt(ref.substring(col(ref).length())); }

    static int colIndex(String col) {
        int n = 0;
        for (int i = 0; i < col.length(); i++) n = n * 26 + (col.charAt(i) - 'A' + 1);
        return n;
    }
}
