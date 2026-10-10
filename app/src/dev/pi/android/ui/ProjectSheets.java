package dev.pi.android.ui;

import android.app.Activity;
import android.content.Context;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

import dev.pi.android.R;
import dev.pi.android.model.PickerItem;

/** Pocket Files / Changes / @-mention / branch pickers over bridge-local git + file RPCs. */
public final class ProjectSheets {
    private ProjectSheets() {}

    public interface Rpc {
        String rpc(String command, @Nullable JSONObject params);
    }

    public interface FilePick {
        void onPick(@NonNull String relativePath);
    }

    public interface BranchPick {
        void onPick(@NonNull String branch);
    }

    public interface DiffOpen {
        void onOpen(@NonNull String path, @NonNull String diff);
    }

    /** @ mention / Files tile: list_project_files */
    public static void showFilePicker(@NonNull Activity activity, @NonNull List<PickerItem> items,
                                      @NonNull FilePick pick) {
        if (items.isEmpty()) {
            Toast.makeText(activity, R.string.files_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        PickerSheet sheet = new PickerSheet(activity, R.string.files_title, true, item -> {
            if (item.payload instanceof String) pick.onPick((String) item.payload);
        });
        sheet.setItems(items);
        sheet.show();
    }

    public static List<PickerItem> filesFromResponse(@Nullable JSONObject data) {
        List<PickerItem> out = new ArrayList<>();
        if (data == null) return out;
        JSONArray files = data.optJSONArray("files");
        if (files == null) return out;
        for (int i = 0; i < files.length(); i++) {
            JSONObject f = files.optJSONObject(i);
            if (f == null) continue;
            String path = f.optString("path", "");
            if (path.isEmpty()) continue;
            String name = f.optString("name", path);
            long bytes = f.optLong("bytes", 0);
            String sub = bytes > 0 ? formatBytes(bytes) : null;
            out.add(PickerItem.row(name, sub == null ? path : path + " · " + sub, path, false));
        }
        return out;
    }

    public static void showBranches(@NonNull Activity activity, @NonNull List<PickerItem> items,
                                    @NonNull BranchPick pick) {
        if (items.isEmpty()) {
            Toast.makeText(activity, R.string.git_no_branches, Toast.LENGTH_SHORT).show();
            return;
        }
        PickerSheet sheet = new PickerSheet(activity, R.string.git_branches_title, false, item -> {
            if (item.payload instanceof String) pick.onPick((String) item.payload);
        });
        sheet.setItems(items);
        sheet.show();
    }

    public static List<PickerItem> branchesFromResponse(@Nullable JSONObject data) {
        List<PickerItem> out = new ArrayList<>();
        if (data == null) return out;
        String current = data.optString("current", "");
        JSONArray branches = data.optJSONArray("branches");
        if (branches == null) return out;
        for (int i = 0; i < branches.length(); i++) {
            String b = branches.optString(i, "");
            if (b.isEmpty()) continue;
            out.add(PickerItem.row(b, null, b, b.equals(current)));
        }
        return out;
    }

    public static void showChanges(@NonNull Activity activity, @NonNull List<PickerItem> items,
                                   @NonNull FilePick pick) {
        if (items.isEmpty()) {
            Toast.makeText(activity, R.string.changes_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        PickerSheet sheet = new PickerSheet(activity, R.string.changes_title, false, item -> {
            if (item.payload instanceof String) pick.onPick((String) item.payload);
        });
        sheet.setItems(items);
        sheet.show();
    }

    public static List<PickerItem> changesFromResponse(@Nullable JSONObject data) {
        List<PickerItem> out = new ArrayList<>();
        if (data == null) return out;
        JSONArray files = data.optJSONArray("files");
        if (files == null) return out;
        for (int i = 0; i < files.length(); i++) {
            JSONObject f = files.optJSONObject(i);
            if (f == null) continue;
            String path = f.optString("path", "");
            if (path.isEmpty()) continue;
            String st = f.optString("status", "");
            out.add(PickerItem.row(path, st.isEmpty() ? null : st, path, false));
        }
        return out;
    }

    /**
     * Working-directory browser from list_cwd_entries.
     * First row is “输入路径…”; remaining rows are directories (incl. ..).
     * Footer “使用此目录” applies {@code browseCwd}.
     */
    public static void showCwdPicker(@NonNull Activity activity, @NonNull String browseCwd,
                                     @NonNull List<PickerItem> entries,
                                     @NonNull FilePick browse, @NonNull FilePick usePath,
                                     @NonNull Runnable typePath) {
        List<PickerItem> items = new ArrayList<>();
        items.add(PickerItem.row(activity.getString(R.string.cwd_enter_path),
                activity.getString(R.string.cwd_enter_hint), "__type__", false));
        if (!browseCwd.isEmpty()) {
            items.add(PickerItem.header(browseCwd));
        }
        items.addAll(entries);
        PickerSheet sheet = new PickerSheet(activity, R.string.cwd_title, true, item -> {
            if (!(item.payload instanceof String)) return;
            String path = (String) item.payload;
            if ("__type__".equals(path)) {
                typePath.run();
                return;
            }
            browse.onPick(path);
        });
        if (!browseCwd.isEmpty()) {
            sheet.enableFooter(activity.getString(R.string.cwd_use_here), () -> {
                sheet.dismiss();
                usePath.onPick(browseCwd);
            });
        }
        sheet.setItems(items);
        sheet.show();
    }

    public static List<PickerItem> cwdEntriesFromResponse(@NonNull Context ctx,
                                                          @Nullable JSONObject data) {
        List<PickerItem> out = new ArrayList<>();
        if (data == null) return out;
        JSONArray entries = data.optJSONArray("entries");
        if (entries == null) return out;
        String parentLabel = ctx.getString(R.string.cwd_parent);
        for (int i = 0; i < entries.length(); i++) {
            JSONObject e = entries.optJSONObject(i);
            if (e == null) continue;
            String name = e.optString("name", "");
            String path = e.optString("path", "");
            if (path.isEmpty()) continue;
            if ("..".equals(name)) {
                out.add(PickerItem.row(parentLabel, path, path, false));
            } else {
                out.add(PickerItem.row(name.isEmpty() ? path : name, path, path, false));
            }
        }
        return out;
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(java.util.Locale.US, "%.1f KB", bytes / 1024.0);
        return String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0 * 1024));
    }
}
