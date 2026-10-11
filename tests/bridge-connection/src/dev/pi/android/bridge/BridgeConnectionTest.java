package dev.pi.android.bridge;

import android.os.Looper;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Tests the production class with real OkHttp/JSON types but no network transport. */
public final class BridgeConnectionTest {
    private static int passed;

    public static void main(String[] args) throws Exception {
        test("open/initial offline cannot flush before auth ack", BridgeConnectionTest::authGate);
        test("starting acknowledges auth; sent commands never replay", BridgeConnectionTest::startingAck);
        test("Pi offline keeps auth and recovery RPC is sent immediately", BridgeConnectionTest::piOfflineRecovery);
        test("all stale socket callbacks leave new connection untouched", BridgeConnectionTest::staleSockets);
        test("queued status/response/event recheck generation on main", BridgeConnectionTest::staleMainQueue);
        test("close invalidates queued online and notifies offline once", BridgeConnectionTest::closeNotifies);
        test("failure invalidates queued events and notifies failure/offline", BridgeConnectionTest::failureNotifies);
        test("queued terminal notifications cannot affect reconnected socket", BridgeConnectionTest::staleTerminalQueue);
        test("disconnect invalidates callbacks before synchronous cancel", BridgeConnectionTest::disconnectInvalidates);
        test("failed sends retain FIFO but never cross generations", BridgeConnectionTest::failedSendFifo);
        test("pre-open status/failed auth send cannot grant auth", BridgeConnectionTest::ackRequiresAuthSend);
        test("bad token never grants auth or flushes prompt", BridgeConnectionTest::badToken);
        test("listener removal before main drain is safe", BridgeConnectionTest::listenerRemoved);
        test("failure listener reconnect suppresses old offline", BridgeConnectionTest::reentrantReconnect);
        test("background callback and deterministic main queue", BridgeConnectionTest::backgroundCallback);
        check(passed > 0, "test filter matched no cases");
        System.out.println("PASS " + passed + " offline regression tests (no socket/service/APK)");
    }

    private interface Test { void run() throws Exception; }
    private static void test(String name, Test test) throws Exception {
        if (!name.contains(System.getProperty("test.filter", ""))) return;
        Looper.getMainLooper().clear();
        try { test.run(); }
        catch (Throwable e) { throw new AssertionError("FAIL: " + name, e); }
        passed++;
        System.out.println("OK " + passed + " " + name);
        Looper.getMainLooper().clear();
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    private static void equal(Object expected, Object actual, String message) {
        if (!expected.equals(actual)) throw new AssertionError(message + ": expected=" + expected + " actual=" + actual);
    }
    private static void drain() { Looper.getMainLooper().drain(); }

    private static final class Recording implements BridgeConnection.Listener {
        final List<String> calls = new ArrayList<>();
        @Override public void onStatus(String state, String error) { calls.add("status:" + state); }
        @Override public void onResponse(String id, boolean ok, String command, JSONObject data, String error) {
            calls.add("response:" + id + ":" + command + ":" + ok);
        }
        @Override public void onEvent(JSONObject event) { calls.add("event:" + event.optString("type")); }
        @Override public void onFailure(Throwable error) { calls.add("failure"); }
    }

    private static final class FakeClient extends OkHttpClient {
        final List<Socket> sockets = new ArrayList<>();
        @Override public WebSocket newWebSocket(Request request, WebSocketListener listener) {
            Socket socket = new Socket(request, listener);
            sockets.add(socket);
            return socket;
        }
    }

    private static final class Socket implements WebSocket {
        final Request request;
        final WebSocketListener listener;
        final List<String> sent = new ArrayList<>();
        boolean accept = true;
        boolean failOnCancel;
        int cancellations;
        int closes;
        Socket(Request request, WebSocketListener listener) { this.request = request; this.listener = listener; }
        @Override public Request request() { return request; }
        @Override public long queueSize() { return 0; }
        @Override public boolean send(String text) {
            if (accept) sent.add(text);
            return accept;
        }
        @Override public boolean send(ByteString bytes) { throw new AssertionError("unexpected binary send"); }
        @Override public boolean close(int code, String reason) { closes++; return true; }
        @Override public void cancel() {
            cancellations++;
            if (failOnCancel) fail();
        }
        void open() {
            Response response = new Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                    .code(101).message("Switching Protocols").build();
            listener.onOpen(this, response);
        }
        void message(String text) { listener.onMessage(this, text); }
        void status(String state) { message("{\"kind\":\"status\",\"state\":\"" + state + "\"}"); }
        void response() { message("{\"kind\":\"response\",\"id\":\"r1\",\"ok\":true,\"command\":\"get_state\",\"data\":{}}"); }
        void event() { message("{\"kind\":\"event\",\"event\":{\"type\":\"agent_end\"}}"); }
        void fail() { listener.onFailure(this, new IOException("test failure"), null); }
        void closing() { listener.onClosing(this, 1000, "test close"); }
        void closed() { listener.onClosed(this, 1000, "test close"); }
        List<String> rpcNames() {
            List<String> names = new ArrayList<>();
            for (String line : sent) {
                JSONObject obj = new JSONObject(line);
                if (obj.has("rpc")) names.add(obj.optString("rpc"));
            }
            return names;
        }
        List<String> rpcIds() {
            List<String> ids = new ArrayList<>();
            for (String line : sent) {
                JSONObject obj = new JSONObject(line);
                if (obj.has("rpc")) ids.add(obj.optString("id"));
            }
            return ids;
        }
    }

    private static final class Fixture {
        final FakeClient http = new FakeClient();
        final BridgeConnection bridge = new BridgeConnection(http);
        final Recording recording = new Recording();
        Fixture() {
            bridge.configure("127.0.0.1", 8945, "offline-test-placeholder");
            bridge.setListener(recording);
        }
        Socket connect() { bridge.connect(); return http.sockets.get(http.sockets.size() - 1); }
        Socket ready() {
            Socket socket = connect();
            socket.open(); socket.status("offline"); socket.status("online");
            drain(); recording.calls.clear();
            return socket;
        }
    }

    private static void authGate() {
        Fixture f = new Fixture(); Socket s = f.connect();
        String first = f.bridge.rpc("prompt", new JSONObject().put("message", "test"));
        check(s.sent.isEmpty(), "sent before open");
        s.open();
        equal(1, s.sent.size(), "open sends only auth");
        check(new JSONObject(s.sent.get(0)).has("auth"), "missing auth frame");
        check(!f.bridge.isConnected(), "open is not authenticated");
        s.status("offline"); s.status("unknown");
        String second = f.bridge.rpc("get_state", null);
        check(s.rpcNames().isEmpty(), "initial offline flushed RPC");
        s.status("online");
        check(f.bridge.isConnected(), "online ack did not authenticate");
        equal(Arrays.asList(first, second), s.rpcIds(), "FIFO/id preserved after ack");
        check(f.recording.calls.isEmpty(), "Listener called before main drain");
        drain();
        // Greeting offline / unknown must not reach UI; only auth ack (online).
        equal(Arrays.asList("status:online"), f.recording.calls, "status delivery");
    }

    private static void startingAck() {
        Fixture f = new Fixture(); Socket s = f.connect(); s.open();
        f.bridge.rpc("prompt", null); s.status("starting");
        check(f.bridge.isConnected(), "starting not acknowledged");
        s.status("online"); s.status("starting");
        equal(Arrays.asList("prompt"), s.rpcNames(), "prompt replayed on duplicate status");
    }

    private static void piOfflineRecovery() {
        Fixture f = new Fixture(); Socket s = f.ready();
        s.status("offline"); drain();
        check(f.bridge.isConnected(), "Pi offline revoked socket auth");
        String id = f.bridge.rpc("get_state", null);
        equal(Arrays.asList(id), s.rpcIds(), "recovery RPC queued forever");
        s.status("starting"); s.status("online");
        equal(Arrays.asList("get_state"), s.rpcNames(), "recovery command duplicated");
        drain(); equal(Arrays.asList("status:offline", "status:starting", "status:online"), f.recording.calls, "Pi status");
    }

    private static void staleSockets() {
        Fixture f = new Fixture(); Socket old = f.ready(); Socket current = f.ready();
        f.bridge.rpc("get_state", null);
        old.open(); old.status("offline"); old.status("online"); old.response(); old.event();
        old.closing(); old.closed(); old.fail(); drain();
        check(f.bridge.isConnected(), "old callback disconnected new socket");
        check(f.recording.calls.isEmpty(), "stale socket reached Listener");
        equal(1, old.sent.size(), "stale open sent auth again");
        equal(0, old.closes, "stale closing touched socket");
        equal(Arrays.asList("get_state"), current.rpcNames(), "new commands affected");
    }

    private static void staleMainQueue() {
        Fixture f = new Fixture(); Socket old = f.ready();
        old.status("online"); old.response(); old.event();
        equal(3, Looper.getMainLooper().pending(), "main queue not populated");
        Socket current = f.connect(); current.open(); current.status("starting");
        drain(); equal(Arrays.asList("status:starting"), f.recording.calls, "stale queued message delivered");
        current.response(); current.event(); drain();
        equal(Arrays.asList("status:starting", "response:r1:get_state:true", "event:agent_end"), f.recording.calls, "fresh messages lost");
    }

    private static void closeNotifies() {
        Fixture f = new Fixture(); Socket s = f.ready();
        s.status("online"); s.response(); s.event(); s.closed();
        check(!f.bridge.isConnected(), "closed socket still connected");
        s.status("online"); s.fail(); s.closed(); drain();
        equal(Arrays.asList("status:offline"), f.recording.calls, "close notification or invalidation");
        equal(1, f.http.sockets.size(), "automatic reconnect occurred");
        // Exercise the normal OkHttp onClosing -> onClosed sequence separately.
        Socket next = f.ready(); next.closing(); next.closed(); drain();
        equal(1, next.closes, "closing handshake not completed");
        equal(Arrays.asList("status:offline"), f.recording.calls, "duplicate close offline");
    }

    private static void failureNotifies() {
        Fixture f = new Fixture(); Socket s = f.ready();
        s.event(); s.fail(); s.closed(); drain();
        check(!f.bridge.isConnected(), "failed socket still connected");
        equal(Arrays.asList("failure", "status:offline"), f.recording.calls, "failure notifications");
        equal(1, f.http.sockets.size(), "failure auto-reconnected");
    }

    private static void staleTerminalQueue() {
        Fixture f = new Fixture(); Socket old = f.ready(); old.fail();
        Socket fresh = f.connect(); fresh.open(); fresh.status("online"); drain();
        equal(Arrays.asList("status:online"), f.recording.calls, "queued failure/offline reached new generation");
        f.recording.calls.clear(); fresh.closed();
        Socket next = f.connect(); next.open(); next.status("starting"); drain();
        equal(Arrays.asList("status:starting"), f.recording.calls, "queued close/offline reached new generation");
    }

    private static void disconnectInvalidates() {
        Fixture f = new Fixture(); Socket s = f.ready();
        s.failOnCancel = true; s.response(); s.event();
        f.bridge.disconnect(); drain();
        equal(1, s.cancellations, "disconnect did not cancel");
        equal(Arrays.asList("status:offline"), f.recording.calls, "cancel leaked failure or queued messages");
        check(!f.bridge.isConnected(), "disconnect left auth enabled");
    }

    private static void failedSendFifo() {
        Fixture f = new Fixture(); Socket s = f.ready(); s.accept = false;
        f.bridge.rpc("prompt", null); f.bridge.rpc("get_state", null);
        check(s.rpcNames().isEmpty(), "failed send reported accepted");
        s.accept = true; f.bridge.rpc("get_models", null);
        equal(Arrays.asList("prompt", "get_state", "get_models"), s.rpcNames(), "failed send reordered FIFO");
        s.accept = false; f.bridge.rpc("prompt", null); s.fail();
        Socket fresh = f.connect(); fresh.open(); fresh.status("online");
        check(fresh.rpcNames().isEmpty(), "old prompt replayed after failure");
        f.bridge.rpc("get_state", null);
        equal(Arrays.asList("get_state"), fresh.rpcNames(), "fresh RPC not sent");
        // Also discard a command queued before auth when explicitly replacing a live socket.
        Socket pending = f.connect(); pending.open(); f.bridge.rpc("prompt", null);
        Socket replacement = f.connect(); replacement.open(); replacement.status("online");
        check(replacement.rpcNames().isEmpty(), "pre-auth prompt crossed generations");
    }

    private static void ackRequiresAuthSend() {
        Fixture f = new Fixture(); Socket s = f.connect();
        s.status("online"); check(!f.bridge.isConnected(), "pre-open status authenticated");
        s.accept = false; s.open(); s.accept = true;
        f.bridge.rpc("prompt", null); s.status("online"); s.status("starting");
        check(!f.bridge.isConnected(), "ack authenticated despite rejected auth send");
        check(s.rpcNames().isEmpty(), "RPC flushed after failed auth send");
    }

    private static void badToken() {
        Fixture f = new Fixture(); Socket s = f.connect(); s.open(); f.bridge.rpc("prompt", null);
        s.message("{\"kind\":\"status\",\"state\":\"offline\",\"error\":\"bad token\"}");
        check(!f.bridge.isConnected(), "bad token authenticated");
        check(s.rpcNames().isEmpty(), "bad token flushed prompt");
        s.closing(); s.closed(); drain();
        equal(Arrays.asList("status:offline"), f.recording.calls, "bad token close not delivered");
    }

    private static void listenerRemoved() {
        Fixture f = new Fixture(); Socket s = f.ready(); s.status("online"); s.response(); s.event(); s.fail();
        f.bridge.setListener(null); drain();
        check(f.recording.calls.isEmpty(), "removed Listener invoked");
    }

    private static void reentrantReconnect() {
        Fixture f = new Fixture(); Socket s = f.ready();
        f.bridge.setListener(new BridgeConnection.Listener() {
            @Override public void onStatus(String state, String error) { f.recording.onStatus(state, error); }
            @Override public void onResponse(String id, boolean ok, String cmd, JSONObject data, String error) {}
            @Override public void onEvent(JSONObject event) {}
            @Override public void onFailure(Throwable error) {
                f.recording.onFailure(error);
                Socket fresh = f.connect(); fresh.open(); fresh.status("online");
            }
        });
        s.fail(); drain();
        check(f.bridge.isConnected(), "reentrant reconnect failed");
        equal(Arrays.asList("failure", "status:online"), f.recording.calls, "old offline after reentrant reconnect");
    }

    private static void backgroundCallback() throws Exception {
        Fixture f = new Fixture(); Socket s = f.connect(); f.bridge.rpc("get_state", null);
        Thread background = new Thread(() -> { s.open(); s.status("offline"); s.status("online"); s.response(); }, "fake-okhttp");
        background.start(); background.join();
        check(f.bridge.isConnected(), "background auth not visible");
        check(f.recording.calls.isEmpty(), "background invoked Listener directly");
        equal(Arrays.asList("get_state"), s.rpcNames(), "background ack failed to flush");
        f.bridge.disconnect(); drain();
        equal(Arrays.asList("status:offline"), f.recording.calls, "queued background callback survived disconnect");
    }
}
