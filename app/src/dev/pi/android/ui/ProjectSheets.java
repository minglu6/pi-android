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
import dev.pi.android.model.SlashCommand;

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

    public interface McpPick {
        void onPick(@NonNull JSONObject server);
    }

    public interface SkillPick {
        void onPick(@NonNull SlashCommand skill);
    }

    /** MCP 管理 sheet：list_mcp_servers → rows; footer runs /mcp. */
    public static void showMcpServers(@NonNull Activity activity, @Nullable JSONObject data,
                                      @NonNull McpPick pick, @NonNull Runnable runMcp) {
        List<PickerItem> items = mcpItemsFromResponse(activity, data);
        if (items.isEmpty()) {
            Toast.makeText(activity, R.string.mcp_empty, Toast.LENGTH_SHORT).show();
            // Still offer /mcp so user can inspect via Pi.
            MaterialDialogs.confirm(activity, R.string.menu_mcp, R.string.mcp_empty_run,
                    R.string.mcp_run_command, () -> runMcp.run());
            return;
        }
        PickerSheet sheet = new PickerSheet(activity, R.string.menu_mcp, true, item -> {
            if (item.payload instanceof JSONObject) pick.onPick((JSONObject) item.payload);
        });
        sheet.enableFooter(activity.getString(R.string.mcp_run_command), () -> {
            sheet.dismiss();
            runMcp.run();
        });
        sheet.setItems(items);
        sheet.show();
    }

    public static List<PickerItem> mcpItemsFromResponse(@NonNull Context ctx,
                                                        @Nullable JSONObject data) {
        List<PickerItem> out = new ArrayList<>();
        if (data == null) return out;
        Boolean trusted = data.has("projectTrusted") && !data.isNull("projectTrusted")
                ? data.optBoolean("projectTrusted") : null;
        if (trusted != null) {
            out.add(PickerItem.header(trusted
                    ? ctx.getString(R.string.mcp_trust_yes)
                    : ctx.getString(R.string.mcp_trust_no)));
        } else if (data.has("trustDecision") && !data.isNull("trustDecision")
                && !data.optString("trustDecision", "").isEmpty()) {
            out.add(PickerItem.header(ctx.getString(R.string.mcp_trust_unknown)));
        }
        JSONArray servers = data.optJSONArray("servers");
        if (servers == null) return out;
        for (int i = 0; i < servers.length(); i++) {
            JSONObject s = servers.optJSONObject(i);
            if (s == null) continue;
            String name = s.optString("name", "");
            if (name.isEmpty()) continue;
            String status = mcpStatusLabel(ctx, s.optString("status", "configured"));
            String source = "project".equals(s.optString("source"))
                    ? ctx.getString(R.string.mcp_source_project)
                    : ctx.getString(R.string.mcp_source_user);
            String transport = s.optString("transport", "");
            String desc = s.optString("description", "");
            StringBuilder sub = new StringBuilder(status).append(" · ").append(source);
            if (!transport.isEmpty() && !"unknown".equals(transport)) {
                sub.append(" · ").append(transport);
            }
            if (!desc.isEmpty()) sub.append(" · ").append(desc);
            out.add(PickerItem.row(name, sub.toString(), s, false));
        }
        return out;
    }

    public static String mcpStatusLabel(@NonNull Context ctx, @Nullable String status) {
        String s = status == null ? "" : status.toLowerCase(java.util.Locale.US);
        switch (s) {
            case "connected":
            case "cached":
                return ctx.getString(R.string.mcp_status_connected);
            case "failed":
            case "error":
                return ctx.getString(R.string.mcp_status_failed);
            case "disabled":
                return ctx.getString(R.string.mcp_status_disabled);
            case "connecting":
            case "idle":
                return ctx.getString(R.string.mcp_status_connecting);
            case "needs-auth":
                return ctx.getString(R.string.mcp_status_needs_auth);
            case "disconnected":
                return ctx.getString(R.string.mcp_status_disconnected);
            case "configured":
            default:
                return ctx.getString(R.string.mcp_status_configured);
        }
    }

    /** Skill 管理：get_commands source=skill；tap inserts /skill:name into composer. */
    public static void showSkills(@NonNull Activity activity,
                                  @NonNull List<SlashCommand> skills,
                                  @Nullable Boolean projectTrusted,
                                  @NonNull SkillPick pick) {
        if (skills.isEmpty()) {
            Toast.makeText(activity, R.string.skills_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        List<PickerItem> items = new ArrayList<>();
        if (projectTrusted != null) {
            items.add(PickerItem.header(projectTrusted
                    ? activity.getString(R.string.mcp_trust_yes)
                    : activity.getString(R.string.mcp_trust_no)));
        }
        List<SlashCommand> project = new ArrayList<>();
        List<SlashCommand> other = new ArrayList<>();
        for (SlashCommand s : skills) {
            if ("project".equals(s.location)) project.add(s);
            else other.add(s);
        }
        if (!project.isEmpty()) {
            items.add(PickerItem.header(activity.getString(R.string.skills_section_project)));
            for (SlashCommand s : project) {
                items.add(PickerItem.row(s.skillLabel(), s.description, s, false));
            }
        }
        if (!other.isEmpty()) {
            items.add(PickerItem.header(activity.getString(R.string.skills_section_user)));
            for (SlashCommand s : other) {
                String loc = s.location == null || s.location.isEmpty() ? null : s.location;
                String sub = s.description;
                if (loc != null) {
                    sub = sub == null || sub.isEmpty() ? loc : loc + " · " + sub;
                }
                items.add(PickerItem.row(s.skillLabel(), sub, s, false));
            }
        }
        PickerSheet sheet = new PickerSheet(activity, R.string.menu_skills, true, item -> {
            if (item.payload instanceof SlashCommand) pick.onPick((SlashCommand) item.payload);
        });
        sheet.setItems(items);
        sheet.show();
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(java.util.Locale.US, "%.1f KB", bytes / 1024.0);
        return String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0 * 1024));
    }
}
