package dev.pi.android.ui;

import android.app.Activity;
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

    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(java.util.Locale.US, "%.1f KB", bytes / 1024.0);
        return String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0 * 1024));
    }
}
