package dev.pi.android.bridge;

import androidx.annotation.Nullable;

import java.util.concurrent.ConcurrentHashMap;

import dev.pi.android.ssh.SshSession;

/** 进程内 SSH 会话表：TerminalActivity 销毁后仍可由前台服务进程保活。 */
public final class SshSessionHolder {
    private static final ConcurrentHashMap<String, SshSession> SESSIONS = new ConcurrentHashMap<>();

    private SshSessionHolder() {}

    public static void put(String nodeId, SshSession session) {
        SshSession old = SESSIONS.put(nodeId, session);
        if (old != null && old != session) old.close();
    }

    @Nullable
    public static SshSession get(String nodeId) {
        return SESSIONS.get(nodeId);
    }

    public static void remove(String nodeId) {
        SshSession s = SESSIONS.remove(nodeId);
        if (s != null) s.close();
    }

    public static void closeAll() {
        for (String id : SESSIONS.keySet()) remove(id);
    }
}
