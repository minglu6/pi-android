package dev.pi.android.ui;

import android.app.Activity;
import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import dev.pi.android.R;
import dev.pi.android.model.PickerItem;
import dev.pi.android.util.RecentModels;

/** 各类选择器入口：模型 / 思考档 / 主题 / Fork / 会话操作。 */
public final class Pickers {
    private Pickers() {}

    public interface ModelListener {
        void onModel(String provider, String modelId);
    }

    public interface ThinkingListener {
        void onThinking(String level);
    }

    public interface ThemeListener {
        void onTheme(String mode);
    }

    public interface ForkListener {
        void onFork(String entryId);
    }

    public interface ActionListener {
        void onOpen();
        void onRename();
        void onDelete();
        /** Optional: pin/unpin session in local list. Default no-op. */
        default void onTogglePin() {}
    }

    public static void showModel(@NonNull Activity activity,
                                 @NonNull List<CharSequence> labels,
                                 @NonNull List<String[]> refs,
                                 @Nullable String currentProvider,
                                 @Nullable String currentModelId,
                                 @NonNull ModelListener listener) {
        showModelThinking(activity, labels, refs, null, currentProvider, currentModelId, null,
                listener, null, null);
    }

    /**
     * Pocket-style combined sheet: 模型 (searchable, grouped) + 思考 segment,
     * optional 设为默认 footer.
     */
    public static void showModelThinking(@NonNull Activity activity,
                                         @NonNull List<CharSequence> labels,
                                         @NonNull List<String[]> refs,
                                         @Nullable List<CharSequence> thinkingLevels,
                                         @Nullable String currentProvider,
                                         @Nullable String currentModelId,
                                         @Nullable String currentThinking,
                                         @NonNull ModelListener modelListener,
                                         @Nullable ThinkingListener thinkingListener,
                                         @Nullable Runnable onSetDefault) {
        final List<PickerItem> modelItems = buildModelItems(activity, labels, refs,
                currentProvider, currentModelId);
        final List<PickerItem> thinkItems = new ArrayList<>();
        if (thinkingLevels != null) {
            for (CharSequence level : thinkingLevels) {
                String lv = String.valueOf(level);
                thinkItems.add(PickerItem.row(lv, thinkingDesc(activity, lv), lv,
                        currentThinking != null && currentThinking.equalsIgnoreCase(lv)));
            }
        }
        final String[] lastModel = {currentProvider == null ? "" : currentProvider,
                currentModelId == null ? "" : currentModelId};
        PickerSheet sheet = new PickerSheet(activity, R.string.action_model, true, item -> {
            Object p = item.payload;
            if (p instanceof String[]) {
                String[] ref = (String[]) p;
                if (ref.length >= 2 && ref[0] != null && ref[1] != null
                        && !ref[0].isEmpty() && !ref[1].isEmpty()) {
                    lastModel[0] = ref[0];
                    lastModel[1] = ref[1];
                    modelListener.onModel(ref[0], ref[1]);
                }
            } else if (p instanceof String && thinkingListener != null) {
                thinkingListener.onThinking((String) p);
            }
        });
        if (thinkingLevels != null && !thinkingLevels.isEmpty() && thinkingListener != null) {
            sheet.enableSegments(
                    activity.getString(R.string.picker_tab_models),
                    activity.getString(R.string.picker_tab_thinking),
                    index -> sheet.setItems(index == 0 ? modelItems : thinkItems));
        }
        if (onSetDefault != null) {
            sheet.enableFooter(activity.getString(R.string.picker_set_default), () -> {
                if (!lastModel[0].isEmpty() && !lastModel[1].isEmpty()) {
                    dev.pi.android.util.DefaultModel.save(activity, lastModel[0], lastModel[1]);
                }
                onSetDefault.run();
            });
        }
        sheet.setItems(modelItems);
        sheet.show();
    }

    @NonNull
    private static List<PickerItem> buildModelItems(Context ctx,
                                                    List<CharSequence> labels,
                                                    List<String[]> refs,
                                                    @Nullable String currentProvider,
                                                    @Nullable String currentModelId) {
        List<PickerItem> out = new ArrayList<>();
        // 最近使用
        List<String[]> recent = RecentModels.list(ctx);
        List<PickerItem> recentRows = new ArrayList<>();
        for (String[] r : recent) {
            int idx = indexOf(refs, r[0], r[1]);
            if (idx < 0) continue;
            boolean sel = equals(r[0], currentProvider) && equals(r[1], currentModelId);
            recentRows.add(PickerItem.row(r[1], r[0], r, sel));
        }
        if (!recentRows.isEmpty()) {
            out.add(PickerItem.header(ctx.getString(R.string.picker_recent)));
            out.addAll(recentRows);
        }
        // 按 provider 分组
        Map<String, List<Integer>> groups = new LinkedHashMap<>();
        for (int i = 0; i < refs.size(); i++) {
            String[] ref = refs.get(i);
            String prov = ref[0] == null || ref[0].isEmpty() ? ctx.getString(R.string.picker_other_provider) : ref[0];
            List<Integer> list = groups.get(prov);
            if (list == null) {
                list = new ArrayList<>();
                groups.put(prov, list);
            }
            list.add(i);
        }
        for (Map.Entry<String, List<Integer>> e : groups.entrySet()) {
            out.add(PickerItem.header(e.getKey()));
            for (int idx : e.getValue()) {
                String[] ref = refs.get(idx);
                boolean sel = equals(ref[0], currentProvider) && equals(ref[1], currentModelId);
                out.add(PickerItem.row(ref[1], ref[0], ref, sel));
            }
        }
        return out;
    }

    public static void showThinking(@NonNull Activity activity,
                                    @NonNull List<CharSequence> levels,
                                    @Nullable String current,
                                    @NonNull ThinkingListener listener) {
        List<PickerItem> items = new ArrayList<>();
        for (CharSequence level : levels) {
            String lv = String.valueOf(level);
            items.add(PickerItem.row(lv, thinkingDesc(activity, lv), lv,
                    current != null && current.equalsIgnoreCase(lv)));
        }
        PickerSheet sheet = new PickerSheet(activity, R.string.action_thinking, false, item -> {
            if (item.payload instanceof String) listener.onThinking((String) item.payload);
        });
        sheet.setItems(items);
        sheet.show();
    }

    public static void showTheme(@NonNull Activity activity, @NonNull String currentMode,
                                 @NonNull ThemeListener listener) {
        String[] modes = {"system", "light", "dark"};
        int[] labels = {
                R.string.settings_theme_system,
                R.string.settings_theme_light,
                R.string.settings_theme_dark
        };
        int[] descs = {
                R.string.settings_theme_system_desc,
                R.string.settings_theme_light_desc,
                R.string.settings_theme_dark_desc
        };
        List<PickerItem> items = new ArrayList<>();
        for (int i = 0; i < modes.length; i++) {
            items.add(PickerItem.row(activity.getString(labels[i]),
                    activity.getString(descs[i]), modes[i],
                    modes[i].equals(currentMode)));
        }
        PickerSheet sheet = new PickerSheet(activity, R.string.settings_theme, false, item -> {
            if (item.payload instanceof String) listener.onTheme((String) item.payload);
        });
        sheet.setItems(items);
        sheet.show();
    }

    public static void showFork(@NonNull Activity activity,
                                @NonNull List<CharSequence> labels,
                                @NonNull List<String> ids,
                                @NonNull ForkListener listener) {
        List<PickerItem> items = new ArrayList<>();
        for (int i = 0; i < labels.size(); i++) {
            items.add(PickerItem.row(String.valueOf(labels.get(i)), null, ids.get(i), false));
        }
        PickerSheet sheet = new PickerSheet(activity, R.string.action_fork, true, item -> {
            if (item.payload instanceof String) listener.onFork((String) item.payload);
        });
        sheet.setItems(items);
        sheet.show();
    }

    public static void showSessionActions(@NonNull Activity activity, boolean canRename, boolean canDelete,
                                          @NonNull ActionListener listener) {
        showSessionActions(activity, canRename, canDelete, false, listener);
    }

    public static void showSessionActions(@NonNull Activity activity, boolean canRename, boolean canDelete,
                                          boolean pinned, @NonNull ActionListener listener) {
        List<PickerItem> items = new ArrayList<>();
        items.add(PickerItem.row(activity.getString(R.string.session_open), null, "open", false));
        items.add(PickerItem.row(
                activity.getString(pinned ? R.string.session_unpin : R.string.session_pin),
                null, "pin", false));
        if (canRename) {
            items.add(PickerItem.row(activity.getString(R.string.session_rename), null, "rename", false));
        }
        if (canDelete) {
            items.add(PickerItem.row(activity.getString(R.string.session_delete),
                    activity.getString(R.string.session_delete_hint), "delete", false));
        }
        PickerSheet sheet = new PickerSheet(activity, R.string.sessions_actions_title, false, item -> {
            Object p = item.payload;
            if ("open".equals(p)) listener.onOpen();
            else if ("pin".equals(p)) listener.onTogglePin();
            else if ("rename".equals(p)) listener.onRename();
            else if ("delete".equals(p)) listener.onDelete();
        });
        sheet.setItems(items);
        sheet.show();
    }

    @Nullable
    private static String thinkingDesc(Context ctx, String level) {
        String key = level.toLowerCase(Locale.ROOT);
        switch (key) {
            case "off":
            case "none":
                return ctx.getString(R.string.thinking_desc_off);
            case "minimal":
                return ctx.getString(R.string.thinking_desc_minimal);
            case "low":
                return ctx.getString(R.string.thinking_desc_low);
            case "medium":
                return ctx.getString(R.string.thinking_desc_medium);
            case "high":
                return ctx.getString(R.string.thinking_desc_high);
            case "xhigh":
                return ctx.getString(R.string.thinking_desc_xhigh);
            case "max":
                return ctx.getString(R.string.thinking_desc_max);
            default:
                return ctx.getString(R.string.thinking_desc_generic);
        }
    }

    private static int indexOf(List<String[]> refs, String provider, String modelId) {
        for (int i = 0; i < refs.size(); i++) {
            String[] r = refs.get(i);
            if (equals(r[0], provider) && equals(r[1], modelId)) return i;
        }
        return -1;
    }

    private static boolean equals(@Nullable String a, @Nullable String b) {
        if (a == null) return b == null || b.isEmpty();
        if (b == null) return a.isEmpty();
        return a.equals(b);
    }
}
