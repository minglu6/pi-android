package dev.pi.android.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.graphics.fonts.Font;
import android.graphics.fonts.FontFamily;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.core.content.res.ResourcesCompat;

import java.io.IOException;

import dev.pi.android.R;

/**
 * 终端 / 代码块字体：默认 MesloLGLDZ Nerd Font Mono（大行距、点状 0），
 * 可选系统等宽。CJK 缺字走系统 sans 回退（API 29+ CustomFallbackBuilder）。
 */
public final class AppFonts {
    public static final String PREF = "pi_fonts";
    public static final String K_FAMILY = "term_font_family"; // nerd | system
    public static final String K_SIZE_SP = "term_font_size_sp";
    public static final float SIZE_DEFAULT = 13f;
    public static final float SIZE_MIN = 9f;
    public static final float SIZE_MAX = 28f;

    public static final String FAMILY_NERD = "nerd";
    public static final String FAMILY_SYSTEM = "system";

    private AppFonts() {}

    public static String family(@NonNull Context ctx) {
        return prefs(ctx).getString(K_FAMILY, FAMILY_NERD);
    }

    public static void setFamily(@NonNull Context ctx, @NonNull String family) {
        prefs(ctx).edit().putString(K_FAMILY, family).apply();
    }

    public static float sizeSp(@NonNull Context ctx) {
        return prefs(ctx).getFloat(K_SIZE_SP, SIZE_DEFAULT);
    }

    public static void setSizeSp(@NonNull Context ctx, float sp) {
        float clamped = Math.max(SIZE_MIN, Math.min(SIZE_MAX, sp));
        prefs(ctx).edit().putFloat(K_SIZE_SP, clamped).apply();
    }

    @NonNull
    public static Typeface mono(@NonNull Context ctx, boolean bold) {
        if (FAMILY_SYSTEM.equals(family(ctx))) {
            return Typeface.create(Typeface.MONOSPACE, bold ? Typeface.BOLD : Typeface.NORMAL);
        }
        return nerdWithCjkFallback(ctx, bold);
    }

    /** 聊天代码块：同样跟随设置。 */
    @NonNull
    public static Typeface code(@NonNull Context ctx) {
        return mono(ctx, false);
    }

    private static Typeface nerdWithCjkFallback(Context ctx, boolean bold) {
        int res = bold ? R.font.meslo_lgldz_nf_bold : R.font.meslo_lgldz_nf_regular;
        Typeface primary = ResourcesCompat.getFont(ctx, res);
        if (primary == null) {
            return Typeface.create(Typeface.MONOSPACE, bold ? Typeface.BOLD : Typeface.NORMAL);
        }
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                Font font = new Font.Builder(ctx.getResources(), res).build();
                FontFamily family = new FontFamily.Builder(font).build();
                return new Typeface.CustomFallbackBuilder(family)
                        .setSystemFallback("sans-serif")
                        .build();
            } catch (IOException e) {
                return primary;
            }
        }
        return primary;
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }
}
