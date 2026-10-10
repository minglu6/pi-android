package dev.pi.android.model;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;

import java.util.Arrays;
import java.util.List;

import dev.pi.android.R;

/**
 * 作曲框 "/" 提示：与 Pi TUI / 连接器能力对齐的本地命令表。
 * UI 动作在 App 内处理；RPC 动作走连接器白名单。
 */
public final class SlashCommand {
    public enum Kind {
        /** 打开 App UI（设置 / 会话 / 模型选择等） */
        UI,
        /** 发给连接器的 RPC */
        RPC
    }

    public final String name;
    @StringRes public final int descRes;
    public final Kind kind;
    /** UI 动作键，或 RPC 命令名 */
    public final String action;

    public SlashCommand(String name, @StringRes int descRes, Kind kind, String action) {
        this.name = name;
        this.descRes = descRes;
        this.kind = kind;
        this.action = action;
    }

    /** 当前 App + 连接器实际支持的斜杠命令（按常用度排序）。 */
    public static List<SlashCommand> catalog() {
        return Arrays.asList(
                new SlashCommand("/model", R.string.slash_model, Kind.UI, "model"),
                new SlashCommand("/thinking", R.string.slash_thinking, Kind.UI, "thinking"),
                new SlashCommand("/new", R.string.slash_new, Kind.UI, "new"),
                new SlashCommand("/sessions", R.string.slash_sessions, Kind.UI, "sessions"),
                new SlashCommand("/settings", R.string.slash_settings, Kind.UI, "settings"),
                new SlashCommand("/compact", R.string.slash_compact, Kind.RPC, "compact"),
                new SlashCommand("/clear", R.string.slash_clear, Kind.UI, "clear"),
                new SlashCommand("/stop", R.string.slash_stop, Kind.RPC, "abort"),
                new SlashCommand("/reload", R.string.slash_reload, Kind.UI, "reload")
        );
    }

    public boolean matches(@Nullable String typed) {
        if (typed == null || typed.isEmpty()) return true;
        String q = typed.toLowerCase();
        if (!q.startsWith("/")) q = "/" + q;
        return name.startsWith(q);
    }

    @NonNull
    @Override
    public String toString() {
        return name;
    }
}
