package dev.pi.android.model;

import androidx.annotation.Nullable;

/** 会话列表一行（或分组头）。 */
public final class SessionEntry {
    public enum Kind { HEADER, SESSION }

    public final Kind kind;
    @Nullable public final String headerLabel;
    @Nullable public final String path;
    @Nullable public final String title;
    @Nullable public final String preview;
    public final long mtimeMs;
    public final boolean current;
    /** Optional model label from list_sessions; empty → hide. */
    @Nullable public final String model;
    /** Optional message count; &lt;0 → hide. */
    public final int messageCount;
    public final boolean archived;

    private SessionEntry(Kind kind, @Nullable String headerLabel, @Nullable String path,
                         @Nullable String title, @Nullable String preview, long mtimeMs,
                         boolean current, @Nullable String model, int messageCount, boolean archived) {
        this.kind = kind;
        this.headerLabel = headerLabel;
        this.path = path;
        this.title = title;
        this.preview = preview;
        this.mtimeMs = mtimeMs;
        this.current = current;
        this.model = model;
        this.messageCount = messageCount;
        this.archived = archived;
    }

    public static SessionEntry header(String label) {
        return new SessionEntry(Kind.HEADER, label, null, null, null, 0L, false, null, -1, false);
    }

    public static SessionEntry session(String path, String title, String preview, long mtimeMs, boolean current) {
        return session(path, title, preview, mtimeMs, current, null, -1, false);
    }

    public static SessionEntry session(String path, String title, String preview, long mtimeMs,
                                       boolean current, @Nullable String model, int messageCount,
                                       boolean archived) {
        return new SessionEntry(Kind.SESSION, null, path, title, preview, mtimeMs, current,
                model, messageCount, archived);
    }
}
