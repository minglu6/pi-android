package dev.pi.android.bridge;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.text.TextUtils;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.pi.android.R;
import dev.pi.android.ui.ExtensionUi;
import dev.pi.android.ui.SettingsActivity;

/**
 * 桥连接的所有者：**不在 Activity 里**。
 *
 * <p>为什么必须挪出来：select / confirm / input / editor 这类扩展请求是**阻塞式**的 —— Pi 会一直等
 * `extension_ui_response`。连接挂在 Activity 上时，App 一进后台（或被 ColorOS 清理）就没有人回答，
 * Pi 停在工具调用上，之后每条 prompt 都报 "Agent is already processing"。
 * 服务持有连接后，提问可以落到通知栏：不管用户在哪个界面都能点一下答掉。
 *
 * <p>前台服务而不是后台服务：本机后台进程会被系统清理（见 AGENTS.md），前台服务带常驻通知，
 * 存活率高得多。代价是常驻一条通知，所以通知里放了「停止桥」。
 */
public class BridgeService extends Service {

    public static final String ACTION_ANSWER = "dev.pi.android.ANSWER";
    public static final String ACTION_STOP = "dev.pi.android.STOP";

    public static final String EXTRA_REQ_ID = "reqId";
    public static final String EXTRA_KIND = "kind";
    public static final String EXTRA_VALUE = "value";
    public static final String EXTRA_NOTIFY_ID = "notifyId";

    private static final String CHANNEL = "pi_bridge";
    private static final int ONGOING_ID = 1;
    private static final int ASK_BASE_ID = 1000;

    /** Activity 实现：服务转发事件给它渲染；没有 Activity 时事件只影响状态与通知。 */
    public interface UiHost {
        void onStatus(String state, @Nullable String error);

        void onEvent(JSONObject ev);

        void onResponse(String id, boolean ok, String command, @Nullable JSONObject data, @Nullable String error);

        void onFailure(@Nullable Throwable t);

        void onExtStatus(Map<String, String> statuses);

        /** 扩展 setTitle：反映到顶栏品牌标题 */
        void onExtTitle(String title);

        /** Activity 是否在前台可见（不能叫 isResumed：Activity 有个 final 的同名方法） */
        boolean isVisible();

        /** 内联渲染提问；返回 false 表示它不管，服务改发通知 */
        boolean showChoice(String id, String kind, String title, String hint, List<String> options,
                           String prefill, ExtensionUi.AnswerSink sink);
    }

    public class LocalBinder extends Binder {
        public BridgeService get() {
            return BridgeService.this;
        }
    }

    /**
     * 取证通道（默认关闭）：本机 App 的 logcat 收不到（连 Log.i 都没有，见 docs/build-pipeline.md P12），
     * 所以把 trace 追加到自己的 files/debug.log，再从 su 侧 `cat` 出来。
     * 开关：应用数据目录下存在 debug.trace 文件时才写 —— 平时零开销，出问题时
     * `su -c 'touch /data/data/dev.pi.android/files/debug.trace'` 打开。
     */
    private volatile boolean tracing;

    private void dbg(String msg) {
        if (!tracing) return;
        try {
            java.io.FileOutputStream fos = openFileOutput("debug.log", MODE_APPEND);
            fos.write((new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
                    .format(new java.util.Date()) + " " + msg + "\n").getBytes("UTF-8"));
            fos.close();
        } catch (Exception ignored) {
        }
    }

    private final LocalBinder binder = new LocalBinder();
    private final Map<String, String> extStatuses = new LinkedHashMap<>();
    private final Map<String, ExtensionUi.AnswerSink> asks = new LinkedHashMap<>();
    private Ask pendingAsk;

    private BridgeConnection bridge;
    private ExtensionUi extUi;
    private UiHost host;
    private int askSeq;

    @Override
    public void onCreate() {
        super.onCreate();
        tracing = new java.io.File(getFilesDir(), "debug.trace").exists();
        dbg("service onCreate");
        ensureChannel();
        startForeground(ONGOING_ID, ongoingNotification("正在连接…"));

        bridge = new BridgeConnection();
        bridge.setTrace(msg -> dbg("conn: " + msg));
        bridge.setListener(new BridgeConnection.Listener() {
            @Override
            public void onStatus(String state, @Nullable String error) {
                dbg("status " + state);
                if ("online".equals(state)) bridge.rpc("get_state", null);
                notifyOngoing(state);
                if (host != null) host.onStatus(state, error);
            }

            @Override
            public void onResponse(String id, boolean ok, String command, @Nullable JSONObject data, @Nullable String error) {
                dbg("resp " + command + " ok=" + ok);
                if (host != null) host.onResponse(id, ok, command, data, error);
            }

            @Override
            public void onEvent(JSONObject ev) {
                // 扩展 UI 全部由服务处理：它要在没有界面的情况下也能作答
                if ("extension_ui_request".equals(ev.optString("type"))) {
                    String m = ev.optString("method");
                    dbg("got extui " + m + " host=" + (host != null)
                            + " visible=" + (host != null && host.isVisible()));
                    extUi.handle(ev);
                    return;
                }
                dbg("event " + ev.optString("type"));
                if (host != null) host.onEvent(ev);
            }

            @Override
            public void onFailure(@Nullable Throwable t) {
                dbg("failure " + (t == null ? "?" : t.getMessage()));
                if (host != null) host.onFailure(t);
            }
        });

        extUi = new ExtensionUi(
                msg -> Toast.makeText(this, msg, Toast.LENGTH_SHORT).show(),
                r -> bridge.rpc("extension_ui_response", r),
                new ExtensionUi.StatusSink() {
                    @Override
                    public void onStatus(String key, @Nullable String text) {
                        if (text == null) extStatuses.remove(key);
                        else extStatuses.put(key, text);
                        publishStatuses();
                    }

                    @Override
                    public void onTitle(String title) {
                        if (host != null) host.onExtTitle(title);
                    }

                    @Override
                    public void onNotify(String message, String type) {
                        JSONObject ev = new JSONObject();
                        try {
                            ev.put("type", "extension_notify");
                            ev.put("message", message);
                            ev.put("notifyType", type);
                        } catch (Exception ignored) {
                        }
                        if (host != null) host.onEvent(ev);
                    }
                },
                (id, kind, title, hint, options, prefill, sink) -> {
                    boolean inline = host != null && host.isVisible()
                            && host.showChoice(id, kind, title, hint, options, prefill, sink);
                    dbg("decide inline=" + inline + " host=" + (host != null)
                            + " visible=" + (host != null && host.isVisible()));
                    if (inline) return;
                    dbg("notify path for " + kind + " id=" + id);
                    askViaNotification(id, kind, title, hint, options, prefill, sink);
                });
        extUi.setTrace(msg -> dbg("extui: " + msg));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        dbg("onStartCommand action=" + action);
        if (ACTION_STOP.equals(action)) {
            extUi.cancelPending();
            bridge.disconnect();
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_ANSWER.equals(action) && intent != null) {
            cancelAsk(intent.getIntExtra(EXTRA_NOTIFY_ID, 0));
            pendingAsk = null;
            answerFromIntent(intent.getStringExtra(EXTRA_REQ_ID),
                    intent.getStringExtra(EXTRA_KIND),
                    intent.getStringExtra(EXTRA_VALUE));
            return START_STICKY;
        }
        if (!bridge.isConnected()) connectWithSaved();
        return START_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    // ---------- 给 Activity 用的 API ----------

    public void setHost(@Nullable UiHost h) {
        this.host = h;
        dbg("setHost " + (h != null));
        if (h != null) {
            publishStatuses();
            if (!bridge.isConnected()) connectWithSaved();
        }
    }

    public String rpc(String command, @Nullable JSONObject params) {
        return bridge.rpc(command, params);
    }

    public boolean isConnected() {
        return bridge != null && bridge.isConnected();
    }

    public void reconnect() {
        extUi.cancelPending();
        connectWithSaved();
    }

    private void connectWithSaved() {
        bridge.configure(SettingsActivity.host(this), SettingsActivity.port(this), SettingsActivity.token(this));
        bridge.connect();
    }

    /** Activity 回到前台：把还在等的提问从通知拽回来，改成内联渲染 */
    public boolean takePendingAsk() {
        dbg("takePendingAsk pending=" + (pendingAsk != null) + " host=" + (host != null));
        if (pendingAsk == null || host == null) return false;
        cancelAsk(pendingAsk.notifyId);
        Ask ask = pendingAsk;
        pendingAsk = null;
        asks.remove(ask.reqId); // 改走内联 sink，通知按钮路径作废
        return host.showChoice(ask.reqId, ask.kind, ask.title, ask.hint, ask.options, ask.prefill, ask.sink);
    }

    private void publishStatuses() {
        if (host != null) host.onExtStatus(new LinkedHashMap<>(extStatuses));
    }

    // ---------- 通知 ----------

    private void ensureChannel() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null || nm.getNotificationChannel(CHANNEL) != null) return;
        NotificationChannel c = new NotificationChannel(CHANNEL, "Pi 连接器", NotificationManager.IMPORTANCE_LOW);
        c.setDescription("Pi 与 Termux 连接器的状态，以及扩展提问");
        nm.createNotificationChannel(c);
    }

    private void notifyOngoing(String state) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(ONGOING_ID, ongoingNotification(state));
    }

    private Notification ongoingNotification(String state) {
        Intent open = new Intent(this, dev.pi.android.ui.MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 1, open,
                PendingIntent.FLAG_UPDATE_CURRENT | immutableFlag());
        Intent stop = new Intent(this, BridgeService.class).setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 2, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | immutableFlag());
        return new NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_pi_logo)
                .setContentTitle("Pi 连接器 · " + state)
                .setContentText(isConnected() ? "已连接（点一下打开对话）" : "未连接")
                .setContentIntent(pi)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止连接器", stopPi)
                .build();
    }

    /**
     * 后台时的提问：高优先级通知。
     * select/confirm 可直接点通知按钮作答；input/editor 需要文本，通知里无法可靠输入，
     * 只提供「打开 App」与「取消」，回前台后由 {@link #takePendingAsk()} 还原内联输入行。
     */
    private void askViaNotification(String reqId, String kind, String title, String hint,
                                    List<String> options, String prefill,
                                    ExtensionUi.AnswerSink sink) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) {
            dbg("askViaNotification: no NotificationManager -> cancelled");
            sink.cancelled();
            return;
        }
        ensureChannel();
        int id = ASK_BASE_ID + (++askSeq);
        asks.put(reqId, sink);
        dbg("askViaNotification notifyId=" + id + " reqId=" + reqId + " kind=" + kind
                + " options=" + options.size());

        boolean needsText = "input".equals(kind) || "editor".equals(kind);
        String body = needsText
                ? "点开 App 在输入行里回答"
                : TextUtils.join(" / ", options);

        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_pi_logo)
                .setContentTitle("Pi 需要你回答：" + title)
                .setContentText(body)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setAutoCancel(true)
                .setContentIntent(PendingIntent.getActivity(this, id,
                        new Intent(this, dev.pi.android.ui.MainActivity.class),
                        PendingIntent.FLAG_UPDATE_CURRENT | immutableFlag()));

        if (!needsText) {
            int shown = Math.min(options.size(), 3);
            for (int i = 0; i < shown; i++) {
                b.addAction(android.R.drawable.ic_menu_send, options.get(i),
                        answerIntent(reqId, kind, options.get(i), id));
            }
        }
        b.addAction(android.R.drawable.ic_menu_close_clear_cancel, "取消",
                answerIntent(reqId, kind, "__cancel__", id));
        nm.notify(id, b.build());
        dbg("notification posted id=" + id);
        pendingAsk = new Ask(id, reqId, kind, title,
                hint == null ? "" : hint, options,
                prefill == null ? "" : prefill, sink);
    }

    private PendingIntent answerIntent(String reqId, String kind, String value, int notifyId) {
        Intent i = new Intent(this, BridgeService.class)
                .setAction(ACTION_ANSWER)
                .putExtra(EXTRA_REQ_ID, reqId)
                .putExtra(EXTRA_KIND, kind)
                .putExtra(EXTRA_VALUE, value)
                .putExtra(EXTRA_NOTIFY_ID, notifyId);
        return PendingIntent.getService(this, (reqId + value).hashCode(), i,
                PendingIntent.FLAG_UPDATE_CURRENT | immutableFlag());
    }

    private void cancelAsk(int notifyId) {
        if (notifyId <= 0) return;
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(notifyId);
    }

    /** 通知按钮被点：把答案交回 ExtensionUi 的 sink，由它推进队列并回复 Pi */
    private void answerFromIntent(String id, String kind, String value) {
        dbg("answerFromIntent id=" + id + " kind=" + kind + " value=" + value);
        ExtensionUi.AnswerSink sink = asks.remove(id);
        if (sink == null) {
            dbg("no sink for " + id);
            return;
        }
        if ("__cancel__".equals(value)) {
            sink.cancelled();
        } else if ("confirm".equals(kind)) {
            // 选项文案是「确定/取消」，不是 true/false；Boolean.parseBoolean("确定") 恒为 false
            sink.confirmed("确定".equals(value));
        } else {
            sink.value(value == null ? "" : value);
        }
    }

    private static int immutableFlag() {
        return Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0;
    }

    private static final class Ask {
        final int notifyId;
        final String reqId;
        final String kind;
        final String title;
        final String hint;
        final List<String> options;
        final String prefill;
        final ExtensionUi.AnswerSink sink;

        Ask(int notifyId, String reqId, String kind, String title, String hint, List<String> options,
            String prefill, ExtensionUi.AnswerSink sink) {
            this.notifyId = notifyId;
            this.reqId = reqId;
            this.kind = kind;
            this.title = title;
            this.hint = hint;
            this.options = options;
            this.prefill = prefill;
            this.sink = sink;
        }
    }
}
