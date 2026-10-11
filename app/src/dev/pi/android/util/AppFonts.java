package dev.pi.android.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.graphics.fonts.Font;
import android.graphics.fonts.FontFamily;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.content.res.ResourcesCompat;

import java.io.IOException;

import dev.pi.android.R;

/**
 * 终端 / 代码块字体：默认 MesloLGLDZ Nerd Font Mono（大行距、点状 0），
 * 可选系统等宽。CJK 缺字走系统 sans 回退（API 29+ CustomFallbackBuilder）。
 *
 * <p>Nerd Font 文件约 2+ MB：{@link Typeface} 必须进程内静态缓存。
 * MessageAdapter / 代码块每次 bind 都会调 {@link #mono} / {@link #code}，
 * 绝不能在每次调用时 {@code new Font.Builder(...).build()}。
 */
public final class AppFonts {
    private static final String TAG = "AppFonts";

    public static final String PREF = "pi_fonts";
    public static final String K_FAMILY = "term_font_family"; // nerd | system
    public static final String K_SIZE_SP = "term_font_size_sp";
    public static final float SIZE_DEFAULT = 13f;
    public static final float SIZE_MIN = 9f;
    public static final float SIZE_MAX = 28f;

    public static final String FAMILY_NERD = "nerd";
    public static final String FAMILY_SYSTEM = "system";

    /** Process-wide held faces — primary cache (not SoftReference). */
    private static volatile Typeface nerdRegular;
    private static volatile Typeface nerdBold;
    private static volatile Typeface systemRegular;
    private static volatile Typeface systemBold;

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
            return systemMono(bold);
        }
        return nerdWithCjkFallback(ctx.getApplicationContext(), bold);
    }

    /** 聊天代码块：同样跟随设置。 */
    @NonNull
    public static Typeface code(@NonNull Context ctx) {
        return mono(ctx, false);
    }

    @NonNull
    private static Typeface systemMono(boolean bold) {
        Typeface cached = bold ? systemBold : systemRegular;
        if (cached != null) return cached;
        synchronized (AppFonts.class) {
            cached = bold ? systemBold : systemRegular;
            if (cached != null) return cached;
            Typeface created = Typeface.create(Typeface.MONOSPACE, bold ? Typeface.BOLD : Typeface.NORMAL);
            if (bold) systemBold = created;
            else systemRegular = created;
            return created;
        }
    }

    @NonNull
    private static Typeface nerdWithCjkFallback(@NonNull Context appCtx, boolean bold) {
        Typeface cached = bold ? nerdBold : nerdRegular;
        if (cached != null) return cached;
        synchronized (AppFonts.class) {
            cached = bold ? nerdBold : nerdRegular;
            if (cached != null) return cached;

            int res = bold ? R.font.meslo_lgldz_nf_bold : R.font.meslo_lgldz_nf_regular;
            Typeface primary = ResourcesCompat.getFont(appCtx, res);
            if (primary == null) {
                Log.w(TAG, "Nerd font resource missing; falling back to MONOSPACE");
                Typeface fallback = systemMono(bold);
                if (bold) nerdBold = fallback;
                else nerdRegular = fallback;
                return fallback;
            }

            Typeface result = primary;
            if (Build.VERSION.SDK_INT >= 29) {
                try {
                    // Build CustomFallbackBuilder once; hold the result forever.
                    Font font = new Font.Builder(appCtx.getResources(), res).build();
                    FontFamily family = new FontFamily.Builder(font).build();
                    result = new Typeface.CustomFallbackBuilder(family)
                            .setSystemFallback("sans-serif")
                            .build();
                } catch (IOException e) {
                    Log.w(TAG, "CJK fallback Typeface build failed; using primary Nerd face", e);
                    result = primary;
                }
            }
            if (bold) nerdBold = result;
            else nerdRegular = result;
            Log.i(TAG, "cached Nerd Typeface bold=" + bold);
            return result;
        }
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }
}
