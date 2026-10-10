package dev.pi.android.ui;

import android.content.Context;
import android.text.InputType;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import dev.pi.android.R;

/** Material 3 风格确认 / 输入对话框（圆角 28dp，跟随应用主题）。 */
public final class MaterialDialogs {
    private MaterialDialogs() {}

    public interface ConfirmListener {
        void onConfirm();
    }

    public interface TextListener {
        void onSubmit(@NonNull String text);
    }

    public static void confirm(@NonNull Context ctx, @StringRes int messageRes,
                               @StringRes int positiveRes, @NonNull ConfirmListener ok) {
        confirm(ctx, 0, messageRes, positiveRes, ok);
    }

    public static void confirm(@NonNull Context ctx, @StringRes int titleRes, @StringRes int messageRes,
                               @StringRes int positiveRes, @NonNull ConfirmListener ok) {
        MaterialAlertDialogBuilder b = new MaterialAlertDialogBuilder(ctx, R.style.PiMaterialAlertDialog);
        if (titleRes != 0) b.setTitle(titleRes);
        b.setMessage(messageRes)
                .setPositiveButton(positiveRes, (d, w) -> ok.onConfirm())
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    public static void promptText(@NonNull Context ctx, @StringRes int titleRes, @StringRes int hintRes,
                                  @Nullable String prefill, @NonNull TextListener ok) {
        promptText(ctx, titleRes, hintRes, prefill,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES, ok);
    }

    /** Absolute path entry — no auto-cap / suggestions. */
    public static void promptPath(@NonNull Context ctx, @StringRes int titleRes, @StringRes int hintRes,
                                  @Nullable String prefill, @NonNull TextListener ok) {
        promptText(ctx, titleRes, hintRes, prefill,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI
                        | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS, ok);
    }

    public static void promptText(@NonNull Context ctx, @StringRes int titleRes, @StringRes int hintRes,
                                  @Nullable String prefill, int inputType, @NonNull TextListener ok) {
        float density = ctx.getResources().getDisplayMetrics().density;
        int pad = Math.round(24 * density);
        EditText input = new EditText(ctx);
        input.setInputType(inputType);
        input.setHint(hintRes);
        input.setText(prefill == null ? "" : prefill);
        input.setSelection(input.getText().length());
        input.setTextColor(ctx.getResources().getColor(R.color.text_primary, ctx.getTheme()));
        input.setHintTextColor(ctx.getResources().getColor(R.color.text_muted, ctx.getTheme()));
        input.setBackgroundResource(R.drawable.bg_search_field);
        input.setPadding(Math.round(14 * density), Math.round(12 * density),
                Math.round(14 * density), Math.round(12 * density));
        FrameLayout wrap = new FrameLayout(ctx);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(pad, Math.round(8 * density), pad, 0);
        wrap.addView(input, lp);
        new MaterialAlertDialogBuilder(ctx, R.style.PiMaterialAlertDialog)
                .setTitle(titleRes)
                .setView(wrap)
                .setPositiveButton(android.R.string.ok, (d, w) ->
                        ok.onSubmit(input.getText().toString().trim()))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }
}
