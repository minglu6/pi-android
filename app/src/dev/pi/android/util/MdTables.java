package dev.pi.android.util;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * GFM pipe-table detection for assistant prose. Rows are split so callers can
 * put tables in a {@link android.widget.HorizontalScrollView}.
 */
public final class MdTables {
    private MdTables() {}

    public static final class Block {
        public final boolean table;
        @Nullable public final String[][] rows; // table cells [row][col]
        @Nullable public final String prose;    // non-table text

        private Block(boolean table, @Nullable String[][] rows, @Nullable String prose) {
            this.table = table;
            this.rows = rows;
            this.prose = prose;
        }

        public static Block table(@NonNull String[][] rows) {
            return new Block(true, rows, null);
        }

        public static Block prose(@NonNull String text) {
            return new Block(false, null, text);
        }
    }

    @NonNull
    public static List<Block> split(@Nullable String raw) {
        List<Block> out = new ArrayList<>();
        if (raw == null || raw.isEmpty()) return out;
        String[] lines = raw.split("\n", -1);
        StringBuilder prose = new StringBuilder();
        int i = 0;
        while (i < lines.length) {
            if (isTableHeader(lines, i)) {
                if (prose.length() > 0) {
                    out.add(Block.prose(prose.toString()));
                    prose.setLength(0);
                }
                List<String[]> rows = new ArrayList<>();
                rows.add(splitCells(lines[i]));
                i += 2; // skip header + separator
                while (i < lines.length && isTableRow(lines[i])) {
                    rows.add(splitCells(lines[i]));
                    i++;
                }
                out.add(Block.table(rows.toArray(new String[0][])));
                continue;
            }
            if (prose.length() > 0) prose.append('\n');
            prose.append(lines[i]);
            i++;
        }
        if (prose.length() > 0) out.add(Block.prose(prose.toString()));
        return out;
    }

    private static boolean isTableHeader(String[] lines, int i) {
        if (i + 1 >= lines.length) return false;
        if (!isTableRow(lines[i])) return false;
        return isSeparator(lines[i + 1]);
    }

    private static boolean isTableRow(String line) {
        String t = line.trim();
        return t.startsWith("|") && t.indexOf('|', 1) >= 0;
    }

    private static boolean isSeparator(String line) {
        String t = line.trim();
        if (!t.startsWith("|")) return false;
        // | --- | :---: | ---: |
        return t.matches("^\\|?\\s*:?-{3,}:?\\s*(\\|\\s*:?-{3,}:?\\s*)+\\|?\\s*$");
    }

    @NonNull
    private static String[] splitCells(String line) {
        String t = line.trim();
        if (t.startsWith("|")) t = t.substring(1);
        if (t.endsWith("|")) t = t.substring(0, t.length() - 1);
        String[] parts = t.split("\\|", -1);
        for (int i = 0; i < parts.length; i++) parts[i] = parts[i].trim();
        return parts;
    }
}
