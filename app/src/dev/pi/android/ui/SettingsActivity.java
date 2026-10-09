package dev.pi.android.ui;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import dev.pi.android.R;

/**
 * 连接设置。没有 PreferenceScreen 依赖，就用最朴素的三个输入框 —— 这里只需要
 * host / port / token，其余都可以后期再加。
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

    public static void setToken(android.content.Context ctx, String value) {
        prefs(ctx).edit().putString(K_TOKEN, value).apply();
    }

    public static void setPort(android.content.Context ctx, int value) {
        prefs(ctx).edit().putInt(K_PORT, value).apply();
    }

    private static SharedPreferences prefs(android.content.Context ctx) {
        return ctx.getSharedPreferences(PREF, MODE_PRIVATE);
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, pad);
        root.setBackgroundColor(getResources().getColor(R.color.bg_base));

        root.addView(label("Host（Termux 通常就是 127.0.0.1）"));
        hostEdit = input(host(this));
        root.addView(hostEdit);

        root.addView(label("Port"));
        portEdit = input(String.valueOf(port(this)));
        portEdit.setInputType(InputType.TYPE_CLASS_NUMBER);
        root.addView(portEdit);

        root.addView(label("Token（~/.pi/agent/pi-android.token 的内容）"));
        tokenEdit = input(token(this));
        root.addView(tokenEdit);

        root.addView(label("保存后返回聊天页会自动重连。"));
        setContentView(root);
    }

    @Override
    protected void onPause() {
        super.onPause();
        prefs(this).edit()
                .putString(K_HOST, hostEdit.getText().toString().trim())
                .putInt(K_PORT, safeInt(portEdit.getText().toString(), 8945))
                .putString(K_TOKEN, tokenEdit.getText().toString().trim())
                .apply();
    }

    private static int safeInt(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return def;
        }
    }

    private TextView label(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(getResources().getColor(R.color.text_secondary));
        tv.setTextSize(13f);
        tv.setPadding(0, (int) (10 * getResources().getDisplayMetrics().density), 0, 4);
        return tv;
    }

    private EditText input(String value) {
        EditText et = new EditText(this);
        et.setText(value);
        et.setTextColor(getResources().getColor(R.color.text_primary));
        et.setBackgroundColor(getResources().getColor(R.color.bg_elevated));
        int p = (int) (10 * getResources().getDisplayMetrics().density);
        et.setPadding(p, p, p, p);
        return et;
    }
}
