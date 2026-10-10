package dev.pi.android.ui;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.EditText;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import dev.pi.android.R;

/**
 * 连接设置：host / port / token。
 * 返回聊天页时 MainActivity 会按配置签名决定是否重连。
 */
public class SettingsActivity extends AppCompatActivity {

    private static final String PREF = "pi_bridge";
    private static final String K_HOST = "host";
    private static final String K_PORT = "port";
    private static final String K_TOKEN = "token";

    private EditText hostEdit;
    private EditText portEdit;
    private EditText tokenEdit;

    public static String host(android.content.Context ctx) {
        return prefs(ctx).getString(K_HOST, "127.0.0.1");
    }

    public static int port(android.content.Context ctx) {
        return prefs(ctx).getInt(K_PORT, 8945);
    }

    public static String token(android.content.Context ctx) {
        return prefs(ctx).getString(K_TOKEN, "");
    }

    public static boolean hasToken(android.content.Context ctx) {
        String t = token(ctx);
        return t != null && !t.trim().isEmpty();
    }

    public static void setToken(android.content.Context ctx, String value) {
        prefs(ctx).edit().putString(K_TOKEN, value).apply();
    }

    public static void setPort(android.content.Context ctx, int value) {
        prefs(ctx).edit().putInt(K_PORT, value).apply();
    }

    public static String configSignature(android.content.Context ctx) {
        return host(ctx) + ":" + port(ctx) + ":" + token(ctx);
    }

    private static SharedPreferences prefs(android.content.Context ctx) {
        return ctx.getSharedPreferences(PREF, MODE_PRIVATE);
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        Toolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setTitle(R.string.settings_title);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        toolbar.setNavigationOnClickListener(v -> finish());

        hostEdit = findViewById(R.id.host);
        portEdit = findViewById(R.id.port);
        tokenEdit = findViewById(R.id.token);
        hostEdit.setText(host(this));
        portEdit.setText(String.valueOf(port(this)));
        tokenEdit.setText(token(this));
    }

    @Override
    protected void onPause() {
        super.onPause();
        int port;
        try {
            port = requirePort(portEdit.getText().toString());
        } catch (IllegalArgumentException e) {
            Toast.makeText(this, e.getMessage(), Toast.LENGTH_SHORT).show();
            return;
        }
        prefs(this).edit()
                .putString(K_HOST, hostEdit.getText().toString().trim())
                .putInt(K_PORT, port)
                .putString(K_TOKEN, tokenEdit.getText().toString().trim())
                .apply();
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
