package dev.pi.android.ui;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.IBinder;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import dev.pi.android.BuildInfo;
import dev.pi.android.R;
import dev.pi.android.bridge.BridgeService;
import dev.pi.android.ui.ExtensionUi;

/**
 * 独立设置页：连接器、模型/思考、主题、关于。
 */
public class SettingsActivity extends AppCompatActivity implements BridgeService.UiHost {

    private static final String PREF = "pi_bridge";
    private static final String K_HOST = "host";
    private static final String K_PORT = "port";
    private static final String K_TOKEN = "token";
    private static final String K_THEME = "theme_mode"; // system | light | dark

    private EditText hostEdit;
    private EditText portEdit;
    private EditText tokenEdit;
    private TextView modelValue;
    private TextView thinkingValue;
    private TextView themeValue;

    private BridgeService service;
    private boolean serviceBound;
    private final List<CharSequence> modelLabels = new ArrayList<>();
    private final List<String[]> modelRefs = new ArrayList<>();
    private final List<CharSequence> thinkingLevels = new ArrayList<>();
    private String currentModelLabel = "";
    private String currentThinking = "";

    private final android.content.ServiceConnection svcConn = new android.content.ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((BridgeService.LocalBinder) binder).get();
            serviceBound = true;
            service.setHost(SettingsActivity.this);
            if (service.isConnected()) {
                service.rpc("get_state", null);
                service.rpc("get_available_models", null);
                service.rpc("get_available_thinking_levels", null);
            }
        }

        @Override public void onServiceDisconnected(ComponentName name) {
            serviceBound = false;
            service = null;
        }
    };

    public static String host(Context ctx) {
        return prefs(ctx).getString(K_HOST, "127.0.0.1");
    }

    public static int port(Context ctx) {
        return prefs(ctx).getInt(K_PORT, 8945);
    }

    public static String token(Context ctx) {
        return prefs(ctx).getString(K_TOKEN, "");
    }

    public static boolean hasToken(Context ctx) {
        String t = token(ctx);
        return t != null && !t.trim().isEmpty();
    }

    public static void setToken(Context ctx, String value) {
        prefs(ctx).edit().putString(K_TOKEN, value).apply();
    }

    public static void setPort(Context ctx, int value) {
        prefs(ctx).edit().putInt(K_PORT, value).apply();
    }

    public static String configSignature(Context ctx) {
        return host(ctx) + ":" + port(ctx) + ":" + token(ctx);
    }

    public static String themeMode(Context ctx) {
        return prefs(ctx).getString(K_THEME, "system");
    }

    public static void applySavedTheme(Context ctx) {
        String mode = themeMode(ctx);
        int night;
        switch (mode) {
            case "light":
                night = AppCompatDelegate.MODE_NIGHT_NO;
                break;
            case "dark":
                night = AppCompatDelegate.MODE_NIGHT_YES;
                break;
            default:
                night = AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
                break;
        }
        AppCompatDelegate.setDefaultNightMode(night);
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREF, MODE_PRIVATE);
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        applySavedTheme(this);
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_settings);

        View top = findViewById(R.id.settings_top);
        View root = findViewById(R.id.settings_root);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            if (top != null) top.setPadding(0, bars.top, 0, 0);
            v.setPadding(0, 0, 0, bars.bottom);
            return insets;
        });

        findViewById(R.id.settings_back).setOnClickListener(v -> finish());

        hostEdit = findViewById(R.id.host);
        portEdit = findViewById(R.id.port);
        tokenEdit = findViewById(R.id.token);
        modelValue = findViewById(R.id.pref_model_value);
        thinkingValue = findViewById(R.id.pref_thinking_value);
        themeValue = findViewById(R.id.pref_theme_value);
        TextView aboutVersion = findViewById(R.id.about_version);

        hostEdit.setText(host(this));
        portEdit.setText(String.valueOf(port(this)));
        tokenEdit.setText(token(this));
        refreshThemeLabel();
        if (aboutVersion != null) {
            aboutVersion.setText(getString(R.string.settings_version_fmt, BuildInfo.HEAD, BuildInfo.BUILD_TIME));
        }

        findViewById(R.id.pref_model).setOnClickListener(v -> showModelPicker());
        findViewById(R.id.pref_thinking).setOnClickListener(v -> showThinkingPicker());
        findViewById(R.id.pref_theme).setOnClickListener(v -> showThemePicker());

        Intent svc = new Intent(this, BridgeService.class);
        bindService(svc, svcConn, Context.BIND_AUTO_CREATE);
    }

    @Override
    protected void onPause() {
        super.onPause();
        saveConnectorFields();
    }

    @Override
    protected void onDestroy() {
        if (serviceBound) {
            if (service != null) service.setHost(null);
            unbindService(svcConn);
            serviceBound = false;
        }
        super.onDestroy();
    }

    private void saveConnectorFields() {
        int portVal;
        try {
            portVal = requirePort(portEdit.getText().toString());
        } catch (IllegalArgumentException e) {
            Toast.makeText(this, e.getMessage(), Toast.LENGTH_SHORT).show();
            return;
        }
        prefs(this).edit()
                .putString(K_HOST, hostEdit.getText().toString().trim())
                .putInt(K_PORT, portVal)
                .putString(K_TOKEN, tokenEdit.getText().toString().trim())
                .apply();
    }

    private void refreshThemeLabel() {
        if (themeValue == null) return;
        switch (themeMode(this)) {
            case "light":
                themeValue.setText(R.string.settings_theme_light);
                break;
            case "dark":
                themeValue.setText(R.string.settings_theme_dark);
                break;
            default:
                themeValue.setText(R.string.settings_theme_system);
                break;
        }
    }

    private void showThemePicker() {
        Pickers.showTheme(this, themeMode(this), mode -> {
            prefs(this).edit().putString(K_THEME, mode).apply();
            applySavedTheme(this);
            refreshThemeLabel();
        });
    }

    private void showModelPicker() {
        if (service == null || !service.isConnected()) {
            Toast.makeText(this, R.string.settings_need_connector, Toast.LENGTH_SHORT).show();
            return;
        }
        if (modelLabels.isEmpty()) {
            service.rpc("get_available_models", null);
            Toast.makeText(this, R.string.models_not_ready, Toast.LENGTH_SHORT).show();
            return;
        }
        String curProv = "";
        String curId = "";
        if (currentModelLabel != null && currentModelLabel.contains(" / ")) {
            String[] parts = currentModelLabel.split(" / ", 2);
            curProv = parts[0];
            curId = parts[1];
        }
        Pickers.showModel(this, modelLabels, modelRefs, curProv, curId, (provider, modelId) -> {
            JSONObject params = new JSONObject();
            try {
                params.put("provider", provider);
                params.put("modelId", modelId);
            } catch (Exception e) {
                throw new IllegalStateException("set_model params", e);
            }
            service.rpc("set_model", params);
            currentModelLabel = provider + " / " + modelId;
            if (modelValue != null) modelValue.setText(currentModelLabel);
        });
    }

    private void showThinkingPicker() {
        if (service == null || !service.isConnected()) {
            Toast.makeText(this, R.string.settings_need_connector, Toast.LENGTH_SHORT).show();
            return;
        }
        if (thinkingLevels.isEmpty()) {
            service.rpc("get_available_thinking_levels", null);
            Toast.makeText(this, R.string.thinking_not_ready, Toast.LENGTH_SHORT).show();
            return;
        }
        Pickers.showThinking(this, thinkingLevels, currentThinking, level -> {
            JSONObject params = new JSONObject();
            try {
                params.put("level", level);
            } catch (Exception e) {
                throw new IllegalStateException("set_thinking_level params", e);
            }
            service.rpc("set_thinking_level", params);
            currentThinking = level;
            if (thinkingValue != null) thinkingValue.setText(currentThinking);
        });
    }

    private static int requirePort(String s) {
        try {
            int p = Integer.parseInt(s.trim());
            if (p < 1 || p > 65535) {
                throw new IllegalArgumentException("端口超出范围: " + p);
            }
            return p;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("端口无效: " + s, e);
        }
    }

    // ---------- BridgeService.UiHost ----------

    @Override public void onStatus(String state, @Nullable String error) {
        if ("online".equals(state) && service != null) {
            service.rpc("get_state", null);
            service.rpc("get_available_models", null);
            service.rpc("get_available_thinking_levels", null);
        }
    }

    @Override public void onEvent(JSONObject ev) { /* ignore on settings */ }

    @Override
    public void onResponse(String id, boolean ok, String command, @Nullable JSONObject data, @Nullable String error) {
        if (!ok || data == null) return;
        if ("get_available_models".equals(command)) {
            modelLabels.clear();
            modelRefs.clear();
            JSONArray models = data.optJSONArray("models");
            if (models == null) models = data.optJSONArray("availableModels");
            if (models != null) {
                for (int i = 0; i < models.length(); i++) {
                    JSONObject m = models.optJSONObject(i);
                    if (m == null) continue;
                    String provider = m.optString("provider", m.optString("providerName", ""));
                    String mid = m.optString("id", m.optString("modelId", ""));
                    String label = m.optString("name", mid);
                    if (!provider.isEmpty()) label = provider + " / " + label;
                    modelLabels.add(label);
                    modelRefs.add(new String[]{provider, mid});
                }
            }
            return;
        }
        if ("get_available_thinking_levels".equals(command)) {
            thinkingLevels.clear();
            JSONArray levels = data.optJSONArray("levels");
            if (levels == null) levels = data.optJSONArray("thinkingLevels");
            if (levels != null) {
                for (int i = 0; i < levels.length(); i++) thinkingLevels.add(levels.optString(i));
            }
            return;
        }
        if ("get_state".equals(command)) {
            JSONObject model = data.optJSONObject("model");
            if (model != null) {
                currentModelLabel = model.optString("name", model.optString("id", ""));
                if (modelValue != null && !currentModelLabel.isEmpty()) modelValue.setText(currentModelLabel);
            }
            currentThinking = data.optString("thinkingLevel", "");
            if (thinkingValue != null && !currentThinking.isEmpty()) thinkingValue.setText(currentThinking);
        }
    }

    @Override public void onFailure(@Nullable Throwable t) { /* ignore */ }

    @Override public void onExtStatus(Map<String, String> statuses) { /* ignore */ }

    @Override public void onExtTitle(String title) { /* ignore */ }

    @Override public boolean isVisible() { return true; }

    @Override
    public boolean showChoice(String id, String kind, String title, String hint, List<String> options,
                              String prefill, ExtensionUi.AnswerSink sink) {
        return false; // 设置页不处理扩展提问，留给 MainActivity / 通知
    }
}
