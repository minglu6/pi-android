package dev.pi.android.ui;

import android.app.Activity;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;

import dev.pi.android.R;
import dev.pi.android.util.AppFonts;

/** Themed sheet showing Termux RUN_COMMAND progress / stdout for connector update. */
public final class ConnectorProgressSheet {
    private final BottomSheetDialog dialog;
    private final TextView subtitle;
    private final TextView body;
    private final StringBuilder log = new StringBuilder();

    public ConnectorProgressSheet(@NonNull Activity activity, @NonNull String title) {
        dialog = new BottomSheetDialog(activity, R.style.PickerBottomSheet);
        View root = LayoutInflater.from(activity).inflate(R.layout.sheet_connector_progress, null, false);
        AppFonts.applyTree(root);
        dialog.setContentView(root);
        TextView titleView = root.findViewById(R.id.connector_sheet_title);
        subtitle = root.findViewById(R.id.connector_sheet_subtitle);
        body = root.findViewById(R.id.connector_sheet_body);
        titleView.setText(title);
        body.setTypeface(AppFonts.mono(activity, false));
        root.findViewById(R.id.connector_sheet_dismiss).setOnClickListener(v -> dialog.dismiss());
        dialog.setOnShowListener(d -> {
            FrameLayout sheet = dialog.findViewById(com.google.android.material.R.id.design_bottom_sheet);
            if (sheet == null) return;
            sheet.setBackgroundResource(android.R.color.transparent);
            BottomSheetBehavior<FrameLayout> behavior = BottomSheetBehavior.from(sheet);
            behavior.setSkipCollapsed(true);
            behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
            int h = (int) (activity.getResources().getDisplayMetrics().heightPixels * 0.72f);
            behavior.setPeekHeight(h);
            android.view.ViewGroup.LayoutParams lp = sheet.getLayoutParams();
            lp.height = h;
            sheet.setLayoutParams(lp);
        });
    }

    public void show() {
        dialog.show();
    }

    public void setSubtitle(@Nullable CharSequence s) {
        if (subtitle != null) subtitle.setText(s == null ? "" : s);
    }

    public void append(@NonNull String line) {
        if (log.length() > 0) log.append('\n');
        log.append(line);
        if (body != null) body.setText(log.toString());
    }

    public void setBody(@NonNull String text) {
        log.setLength(0);
        log.append(text);
        if (body != null) body.setText(text);
    }

    public boolean isShowing() {
        return dialog.isShowing();
    }
}
