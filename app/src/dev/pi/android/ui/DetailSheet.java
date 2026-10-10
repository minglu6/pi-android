package dev.pi.android.ui;

import android.app.Activity;
import android.graphics.drawable.Drawable;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.drawable.DrawableCompat;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;

import java.util.List;

import dev.pi.android.R;

/**
 * Dense Tokyo Night detail bottom sheet: title, status chips, body/log, tonal actions, text cancel.
 */
public final class DetailSheet {

    public interface Action {
        @NonNull String label();
        void run();
    }

    private final Activity activity;
    private final BottomSheetDialog dialog;
    private final TextView titleView;
    private final ImageView titleIcon;
    private final View chipsScroll;
    private final ViewGroup chips;
    private final TextView body;
    private final ScrollView logScroll;
    private final TextView log;
    private final ProgressBar progress;
    private final LinearLayout actions;
    private final TextView cancel;

    public DetailSheet(@NonNull Activity activity, @NonNull String title) {
        this.activity = activity;
        dialog = new BottomSheetDialog(activity, R.style.PickerBottomSheet);
        View root = LayoutInflater.from(activity).inflate(R.layout.sheet_detail, null, false);
        dialog.setContentView(root);
        titleView = root.findViewById(R.id.detail_title);
        titleIcon = root.findViewById(R.id.detail_title_icon);
        chipsScroll = root.findViewById(R.id.detail_chips_scroll);
        chips = root.findViewById(R.id.detail_chips);
        body = root.findViewById(R.id.detail_body);
        logScroll = root.findViewById(R.id.detail_log_scroll);
        log = root.findViewById(R.id.detail_log);
        progress = root.findViewById(R.id.detail_progress);
        actions = root.findViewById(R.id.detail_actions);
        cancel = root.findViewById(R.id.detail_cancel);
        titleView.setText(title);
        cancel.setOnClickListener(v -> dialog.dismiss());

        dialog.setOnShowListener(d -> {
            FrameLayout sheet = dialog.findViewById(com.google.android.material.R.id.design_bottom_sheet);
            if (sheet == null) return;
            sheet.setBackgroundResource(android.R.color.transparent);
            BottomSheetBehavior<FrameLayout> behavior = BottomSheetBehavior.from(sheet);
            behavior.setSkipCollapsed(true);
            behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
        });
    }

    @NonNull
    public DetailSheet setTitleIcon(@DrawableRes int iconRes) {
        Drawable icon = ContextCompat.getDrawable(activity, iconRes);
        if (icon == null) {
            titleIcon.setVisibility(View.GONE);
            return this;
        }
        icon = DrawableCompat.wrap(icon.mutate());
        DrawableCompat.setTint(icon, ContextCompat.getColor(activity, R.color.text_secondary));
        titleIcon.setImageDrawable(icon);
        titleIcon.setVisibility(View.VISIBLE);
        return this;
    }

    @NonNull
    public DetailSheet setChips(@Nullable List<String> labels) {
        chips.removeAllViews();
        if (labels == null || labels.isEmpty()) {
            chipsScroll.setVisibility(View.GONE);
            return this;
        }
        float d = activity.getResources().getDisplayMetrics().density;
        int padH = Math.round(8 * d);
        int padV = Math.round(3 * d);
        int gap = Math.round(6 * d);
        for (int i = 0; i < labels.size(); i++) {
            String label = labels.get(i);
            if (label == null || label.trim().isEmpty()) continue;
            TextView chip = new TextView(activity);
            chip.setText(label.trim());
            chip.setTextColor(ContextCompat.getColor(activity, R.color.text_secondary));
            chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
            chip.setBackgroundResource(R.drawable.bg_detail_chip);
            chip.setPadding(padH, padV, padH, padV);
            chip.setIncludeFontPadding(false);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (chips.getChildCount() > 0) lp.setMarginStart(gap);
            chip.setLayoutParams(lp);
            chips.addView(chip);
        }
        chipsScroll.setVisibility(chips.getChildCount() > 0 ? View.VISIBLE : View.GONE);
        return this;
    }

    @NonNull
    public DetailSheet setBody(@Nullable CharSequence text) {
        if (text == null || text.length() == 0) {
            body.setVisibility(View.GONE);
            body.setText("");
            return this;
        }
        body.setText(text);
        body.setVisibility(View.VISIBLE);
        return this;
    }

    @NonNull
    public DetailSheet setLog(@Nullable CharSequence text) {
        if (text == null || text.length() == 0) {
            logScroll.setVisibility(View.GONE);
            log.setText("");
            return this;
        }
        log.setText(text);
        logScroll.setVisibility(View.VISIBLE);
        return this;
    }

    @NonNull
    public DetailSheet setProgress(boolean on) {
        progress.setVisibility(on ? View.VISIBLE : View.GONE);
        return this;
    }

    @NonNull
    public DetailSheet setCancelVisible(boolean visible) {
        cancel.setVisibility(visible ? View.VISIBLE : View.GONE);
        return this;
    }

    @NonNull
    public DetailSheet setCancelLabel(@NonNull CharSequence label) {
        cancel.setText(label);
        return this;
    }

    @NonNull
    public DetailSheet setPrimaryActions(@Nullable List<Action> list) {
        actions.removeAllViews();
        if (list == null || list.isEmpty()) {
            actions.setVisibility(View.GONE);
            return this;
        }
        float d = activity.getResources().getDisplayMetrics().density;
        int h = Math.round(44 * d);
        int gap = Math.round(8 * d);
        for (int i = 0; i < list.size(); i++) {
            Action action = list.get(i);
            TextView btn = new TextView(activity);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, h);
            if (i > 0) lp.topMargin = gap;
            btn.setLayoutParams(lp);
            btn.setGravity(android.view.Gravity.CENTER);
            btn.setText(action.label());
            btn.setTextColor(ContextCompat.getColor(activity, R.color.pi_blue));
            btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
            btn.setBackgroundResource(R.drawable.bg_tonal_button);
            btn.setOnClickListener(v -> {
                dialog.dismiss();
                action.run();
            });
            actions.addView(btn);
        }
        actions.setVisibility(View.VISIBLE);
        return this;
    }

    public void show() {
        dialog.show();
    }

    public void dismiss() {
        dialog.dismiss();
    }

    public boolean isShowing() {
        return dialog.isShowing();
    }
}
