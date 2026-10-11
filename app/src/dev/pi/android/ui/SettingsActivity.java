package dev.pi.android.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.InputType;
import android.text.method.PasswordTransformationMethod;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dev.pi.android.BuildInfo;
import dev.pi.android.R;
import dev.pi.android.model.PickerItem;
import dev.pi.android.termux.ConnectorController;
import dev.pi.android.termux.TermuxRunCommand;
import dev.pi.android.util.AppFonts;
import dev.pi.android.util.ConnectionProfile;
import dev.pi.android.util.ConnectionProfiles;
import dev.pi.android.util.SecureTokenStore;

/**
 * 独立设置页：连接器、外观、关于。
 * 模型与思考档位只在聊天页顶栏 / composer 模型芯片切换。
 */
public class SettingsActivity extends AppCompatActivity {

    private static final String PREF = "pi_bridge";
    private static final String K_HOST = "host";
    private static final String K_PORT = "port";
    private static final String K_THEME = "theme_mode"; // system | light | dark
    private static final String K_AUTO_START = "auto_start_connector"; // default true
    private static final Pattern VERSION_LINE = Pattern.compile("VERSION\\s+(\\S+)\\s+(\\S+)");

    private EditText hostEdit;
    private EditText portEdit;
    private EditText tokenEdit;
    private ImageButton tokenToggle;
    private boolean tokenVisible;
    private TextView themeValue;
    private TextView termFontValue;
    private TextView termSizeValue;
    private TextView autoStartValue;
    private SwitchCompat autoStartSwitch;
    private TextView connectorVersionView;
    @Nullable private ConnectorProgressSheet updateSheet;
    @Nullable private TermuxSetupSheet setupSheet;
    private final ConnectorController.Listener connectorListener = new ConnectorController.Listener() {
        @Override public void onAutoStartBegan() {}
        @Override public void onAutoStartFinished(boolean ok, @Nullable String detail) {}
        @Override public void onUpdateProgress(@NonNull String line) {
            if (updateSheet != null) updateSheet.append(line);
        }
        @Override
        public void onUpdateFinished(boolean ok, int exitCode, @Nullable String stdout,
                                     @Nullable String stderr, @Nullable String errmsg) {
            runOnUiThread(() -> finishUpdateSheet(ok, exitCode, stdout, stderr, errmsg));
        }
        @Override public void requestReconnect(@NonNull String reason) {}
    };

    public static String host(Context ctx) {
        return ConnectionProfiles.active(ctx).host;
    }

    public static int port(Context ctx) {
        return ConnectionProfiles.active(ctx).port;
    }

    /** Connector auth token — Keystore AES-GCM at rest (migrates legacy plaintext). Never log. */
    public static String token(Context ctx) {
        return SecureTokenStore.get(ctx);
    }

    public static boolean hasToken(Context ctx) {
        String t = token(ctx);
        return t != null && !t.trim().isEmpty();
    }

    public static void setToken(Context ctx, String value) {
        SecureTokenStore.set(ctx, value);
    }

    public static void setPort(Context ctx, int value) {
        ConnectionProfile p = ConnectionProfiles.active(ctx);
        p.port = value;
        ConnectionProfiles.save(ctx, p);
        prefs(ctx).edit().putInt(K_PORT, value).apply(); // keep legacy mirror
    }

    public static String configSignature(Context ctx) {
        ConnectionProfile p = ConnectionProfiles.active(ctx);
        return p.id + "|" + p.host + ":" + p.port + ":" + token(ctx);
    }

    public static String themeMode(Context ctx) {
        return prefs(ctx).getString(K_THEME, "system");
    }

    /** Default ON: auto-start Termux connector when WebSocket is refused/timeout. */
    public static boolean autoStartConnector(Context ctx) {
        return prefs(ctx).getBoolean(K_AUTO_START, true);
    }

    public static void setAutoStartConnector(Context ctx, boolean on) {
        prefs(ctx).edit().putBoolean(K_AUTO_START, on).apply();
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
        AppFonts.beforeContentView(this);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_settings);
        AppFonts.afterContentView(this);

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
        if (aboutVersion != null) {
            aboutVersion.setText(getString(R.string.settings_version_fmt, BuildInfo.HEAD, BuildInfo.BUILD_TIME));
        }

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

        autoStartValue = findViewById(R.id.pref_auto_start_value);
        autoStartSwitch = findViewById(R.id.pref_auto_start_switch);
        connectorVersionView = findViewById(R.id.pref_connector_version);
        refreshAutoStartLabel();
        refreshConnectorVersionLabel();
        if (autoStartSwitch != null) {
            autoStartSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
                setAutoStartConnector(this, isChecked);
                refreshAutoStartLabel();
            });
        }
        View prefAutoStart = findViewById(R.id.pref_auto_start);
        if (prefAutoStart != null) {
            prefAutoStart.setOnClickListener(v -> {
                if (autoStartSwitch != null) autoStartSwitch.toggle();
            });
        }
        View prefUpdate = findViewById(R.id.pref_update_connector);
        if (prefUpdate != null) {
            prefUpdate.setOnClickListener(v -> startConnectorUpdate());
        }
        View prefSetup = findViewById(R.id.pref_termux_setup);
        if (prefSetup != null) {
            prefSetup.setOnClickListener(v -> {
                if (setupSheet == null) setupSheet = new TermuxSetupSheet(this);
                setupSheet.show();
            });
        }
        TextView profilesValue = findViewById(R.id.pref_profiles_value);
        refreshProfilesLabel(profilesValue);
        View prefProfiles = findViewById(R.id.pref_profiles);
        if (prefProfiles != null) {
            prefProfiles.setOnClickListener(v ->
                    new ProfilesSheet(this, new ProfilesSheet.Host() {
                        @Override
                        public void onProfileSwitched(@NonNull ConnectionProfile profile) {
                            hostEdit.setText(profile.host);
                            portEdit.setText(String.valueOf(profile.port));
                            tokenEdit.setText(SecureTokenStore.getForProfile(
                                    SettingsActivity.this, profile.id));
                            refreshProfilesLabel(profilesValue);
                        }

                        @Override
                        @Nullable
                        public String activeConnectionState() {
                            return null; // Settings has no live socket — probe only
                        }
                    }).show());
        }
        ConnectorController.addListener(connectorListener);
    }

    private void refreshProfilesLabel(@Nullable TextView profilesValue) {
        if (profilesValue == null) return;
        ConnectionProfile active = ConnectionProfiles.active(this);
        profilesValue.setText(getString(R.string.settings_profiles_active_fmt,
                active.name, active.host, active.port));
    }

    @Override
    protected void onDestroy() {
        ConnectorController.removeListener(connectorListener);
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == TermuxSetupSheet.REQ_RUN_COMMAND && setupSheet != null) {
            setupSheet.refresh();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        saveConnectorFields();
    }

    private void refreshAutoStartLabel() {
        boolean on = autoStartConnector(this);
        if (autoStartSwitch != null) autoStartSwitch.setChecked(on);
        if (autoStartValue != null) {
            autoStartValue.setText(on ? R.string.settings_auto_start_on : R.string.settings_auto_start_off);
        }
    }

    private void refreshConnectorVersionLabel() {
        if (connectorVersionView == null) return;
        connectorVersionView.setText(getString(R.string.settings_connector_version,
                ConnectorController.connectorVersionLabel(this)));
    }

    private void startConnectorUpdate() {
        if (TermuxSetupSheet.needsSetup(this)
                || !TermuxRunCommand.hasRunCommandPermission(this)) {
            if (setupSheet == null) setupSheet = new TermuxSetupSheet(this);
            setupSheet.show();
            return;
        }
        updateSheet = new ConnectorProgressSheet(this, getString(R.string.connector_update_title));
        updateSheet.setSubtitle(getString(R.string.settings_connector_version,
                ConnectorController.connectorVersionLabel(this)));
        updateSheet.append(getString(R.string.connector_update_running));
        updateSheet.show();
        if (!ConnectorController.beginUpdate(this)) {
            updateSheet.append(getString(R.string.connector_autostart_fail, "无法启动 Termux 命令"));
        }
    }

    private void finishUpdateSheet(boolean ok, int exitCode, @Nullable String stdout,
                                   @Nullable String stderr, @Nullable String errmsg) {
        if (updateSheet == null) {
            updateSheet = new ConnectorProgressSheet(this, getString(R.string.connector_update_title));
            updateSheet.show();
        }
        StringBuilder sb = new StringBuilder();
        if (stdout != null && !stdout.isEmpty()) sb.append(stdout.trim()).append('\n');
        if (stderr != null && !stderr.isEmpty()) sb.append(stderr.trim()).append('\n');
        if (errmsg != null && !errmsg.isEmpty()) sb.append(errmsg.trim()).append('\n');
        if (ok) {
            Matcher m = VERSION_LINE.matcher(stdout == null ? "" : stdout);
            if (m.find()) {
                ConnectorController.saveConnectorVersion(this, m.group(1), m.group(2));
            }
            refreshConnectorVersionLabel();
            sb.append(getString(R.string.connector_update_ok,
                    ConnectorController.connectorVersionLabel(this)));
        } else {
            sb.append(getString(R.string.connector_update_fail, exitCode));
            if (errmsg != null && errmsg.contains("allow-external-apps")) {
                if (setupSheet == null) setupSheet = new TermuxSetupSheet(this);
                setupSheet.show();
            }
        }
        updateSheet.setBody(sb.toString().trim());
        updateSheet.setSubtitle(ok
                ? getString(R.string.connector_update_ok, ConnectorController.connectorVersionLabel(this))
                : getString(R.string.connector_update_fail, exitCode));
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

    private void saveConnectorFields() {
        int portVal;
        try {
            portVal = requirePort(portEdit.getText().toString());
        } catch (IllegalArgumentException e) {
            Toast.makeText(this, e.getMessage(), Toast.LENGTH_SHORT).show();
            return;
        }
        String hostVal = hostEdit.getText().toString().trim();
        if (hostVal.isEmpty()) hostVal = "127.0.0.1";
        ConnectionProfile active = ConnectionProfiles.active(this);
        active.host = hostVal;
        active.port = portVal;
        ConnectionProfiles.save(this, active);
        prefs(this).edit()
                .putString(K_HOST, hostVal)
                .putInt(K_PORT, portVal)
                .apply();
        // Token is encrypted separately; never write plaintext "token" prefs again.
        setToken(this, tokenEdit.getText().toString().trim());
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
                recreate(); // theme fontFamily + AppFonts cache path
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
}
