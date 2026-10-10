package dev.pi.android.ui;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.IBinder;
import android.text.InputType;
import android.text.method.PasswordTransformationMethod;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import dev.pi.android.BuildInfo;
import dev.pi.android.R;
import dev.pi.android.bridge.AgentState;
import dev.pi.android.bridge.BridgeService;
import dev.pi.android.model.PickerItem;
import dev.pi.android.ui.ExtensionUi;
import dev.pi.android.util.AppFonts;

/**
 * 独立设置页：连接器、模型/思考（只读状态）、主题、关于。
 * 模型与思考档位切换只在聊天页 composer 模型芯片 / Pocket picker。
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
    private ImageButton tokenToggle;
    private boolean tokenVisible;
    private TextView modelValue;
    private TextView thinkingValue;
    private TextView themeValue;
    private TextView termFontValue;
    private TextView termSizeValue;

    private BridgeService service;
    private boolean serviceBound;
    private boolean visible;

    private final AgentState.Listener agentListener = this::applyAgentStateToUi;

    private final android.content.ServiceConnection svcConn = new android.content.ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((BridgeService.LocalBinder) binder).get();
            serviceBound = true;
            service.setHost(SettingsActivity.this);
            applyAgentStateToUi();
            refreshFromService();
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

        AgentState.get().loadCache(this);

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
        tokenToggle = findViewById(R.id.token_toggle);
        modelValue = findViewById(R.id.pref_model_value);
        thinkingValue = findViewById(R.id.pref_thinking_value);
        themeValue = findViewById(R.id.pref_theme_value);
        TextView aboutVersion = findViewById(R.id.about_version);

        hostEdit.setText(host(this));
        portEdit.setText(String.valueOf(port(this)));
        // 默认掩码；绝不把 token 打到日志
        tokenEdit.setText(token(this));
        applyTokenVisibility(false);
        if (tokenToggle != null) {
            tokenToggle.setOnClickListener(v -> applyTokenVisibility(!tokenVisible));
        }

        refreshThemeLabel();
        applyAgentStateToUi();
        if (aboutVersion != null) {
            aboutVersion.setText(getString(R.string.settings_version_fmt, BuildInfo.HEAD, BuildInfo.BUILD_TIME));
        }

        // pref_model / pref_thinking are read-only status; switch via composer chip.
        findViewById(R.id.pref_theme).setOnClickListener(v -> showThemePicker());

        termFontValue = findViewById(R.id.pref_term_font_value);
        termSizeValue = findViewById(R.id.pref_term_size_value);
        refreshTermFontLabels();
        View prefTermFont = findViewById(R.id.pref_term_font);
        if (prefTermFont != null) {
            prefTermFont.setOnClickListener(v -> showTermFontPicker());
        }
        View prefTermSize = findViewById(R.id.pref_term_size);
        if (prefTermSize != null) {
            prefTermSize.setOnClickListener(v -> showTermSizePicker());
        }

        AgentState.get().addListener(agentListener);

        Intent svc = new Intent(this, BridgeService.class);
        bindService(svc, svcConn, Context.BIND_AUTO_CREATE);
    }

    @Override
    protected void onResume() {
        super.onResume();
        visible = true;
        applyAgentStateToUi();
        if (serviceBound && service != null) {
            service.setHost(this);
            refreshFromService();
        }
    }

    @Override
    protected void onPause() {
        visible = false;
        super.onPause();
        saveConnectorFields();
    }

    @Override
    protected void onDestroy() {
        AgentState.get().removeListener(agentListener);
        if (serviceBound) {
            if (service != null) service.clearHostIf(this);
            unbindService(svcConn);
            serviceBound = false;
        }
        super.onDestroy();
    }

    private void applyTokenVisibility(boolean visible) {
        tokenVisible = visible;
        if (tokenEdit == null) return;
        int sel = tokenEdit.getSelectionEnd();
        if (visible) {
            tokenEdit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
            tokenEdit.setTransformationMethod(null);
            if (tokenToggle != null) {
                tokenToggle.setImageResource(R.drawable.ic_visibility_off);
                tokenToggle.setContentDescription(getString(R.string.settings_token_hide));
            }
        } else {
            tokenEdit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            tokenEdit.setTransformationMethod(PasswordTransformationMethod.getInstance());
            if (tokenToggle != null) {
                tokenToggle.setImageResource(R.drawable.ic_visibility);
                tokenToggle.setContentDescription(getString(R.string.settings_token_show));
            }
        }
        if (sel >= 0) {
            try {
                tokenEdit.setSelection(Math.min(sel, tokenEdit.getText().length()));
            } catch (IndexOutOfBoundsException ignored) {
            }
        }
    }

    private void applyAgentStateToUi() {
        AgentState st = AgentState.get();
        String model = st.modelLabel();
        String thinking = st.thinking();
        if (modelValue != null) modelValue.setText(model.isEmpty() ? "—" : model);
        if (thinkingValue != null) thinkingValue.setText(thinking.isEmpty() ? "—" : thinking);
    }

    private void refreshFromService() {
        if (service == null || !service.isConnected()) return;
        // Only need current identity for read-only display; lists live in MainActivity picker.
        service.rpc("get_state", null);
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

    private void refreshTermFontLabels() {
        if (termFontValue != null) {
            termFontValue.setText(AppFonts.FAMILY_SYSTEM.equals(AppFonts.family(this))
                    ? R.string.settings_term_font_system
                    : R.string.settings_term_font_nerd);
        }
        if (termSizeValue != null) {
            termSizeValue.setText(getString(R.string.settings_term_size_fmt, AppFonts.sizeSp(this)));
        }
    }

    private void showTermFontPicker() {
        List<PickerItem> items = new ArrayList<>();
        String cur = AppFonts.family(this);
        items.add(PickerItem.row(
                getString(R.string.settings_term_font_nerd),
                getString(R.string.settings_term_font_desc_nerd),
                AppFonts.FAMILY_NERD,
                AppFonts.FAMILY_NERD.equals(cur)));
        items.add(PickerItem.row(
                getString(R.string.settings_term_font_system),
                getString(R.string.settings_term_font_desc_system),
                AppFonts.FAMILY_SYSTEM,
                AppFonts.FAMILY_SYSTEM.equals(cur)));
        PickerSheet sheet = new PickerSheet(this, R.string.settings_term_font, false, item -> {
            if (item.payload instanceof String) {
                AppFonts.setFamily(this, (String) item.payload);
                refreshTermFontLabels();
            }
        });
        sheet.setItems(items);
        sheet.show();
    }

    private void showTermSizePicker() {
        final float[] choices = {10f, 12f, 13f, 14f, 16f, 18f, 20f, 24f};
        List<PickerItem> items = new ArrayList<>();
        float cur = AppFonts.sizeSp(this);
        for (float sp : choices) {
            items.add(PickerItem.row(
                    String.format(java.util.Locale.US, "%.0f sp", sp), null, sp,
                    Math.abs(sp - cur) < 0.1f));
        }
        PickerSheet sheet = new PickerSheet(this, R.string.settings_term_size, false, item -> {
            if (item.payload instanceof Float) {
                AppFonts.setSizeSp(this, (Float) item.payload);
                refreshTermFontLabels();
            }
        });
        sheet.setItems(items);
        sheet.show();
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
        // starting ≡ running: connector is up — refresh read-only model/thinking status.
        if ("online".equals(state) || "starting".equals(state)) refreshFromService();
    }

    @Override public void onEvent(JSONObject ev) {
        if ("thinking_level_changed".equals(ev.optString("type"))) applyAgentStateToUi();
    }

    @Override
    public void onResponse(String id, boolean ok, String command, @Nullable JSONObject data, @Nullable String error) {
        if (!ok) {
            if (visible && "get_state".equals(command)) {
                String detail = error != null && !error.isEmpty() ? error : getString(R.string.rpc_failed);
                Toast.makeText(this, detail, Toast.LENGTH_LONG).show();
            }
            return;
        }
        // set_model / set_thinking_level happen from MainActivity; AgentState listener refreshes UI.
        if ("set_model".equals(command) || "set_thinking_level".equals(command)
                || "get_state".equals(command)) {
            applyAgentStateToUi();
        }
    }

    @Override public void onFailure(@Nullable Throwable t) { /* ignore */ }

    @Override public void onExtStatus(Map<String, String> statuses) { /* ignore */ }

    @Override public void onExtTitle(String title) { /* ignore */ }

    @Override public boolean isVisible() { return visible; }

    @Override
    public boolean showChoice(String id, String kind, String title, String hint, List<String> options,
                              String prefill, ExtensionUi.AnswerSink sink) {
        return false; // 设置页不处理扩展提问，留给 MainActivity / 通知
    }
}
