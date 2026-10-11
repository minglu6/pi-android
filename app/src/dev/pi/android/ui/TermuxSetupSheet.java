package dev.pi.android.ui;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;

import dev.pi.android.R;
import dev.pi.android.termux.ConnectorController;
import dev.pi.android.termux.TermuxConstants;
import dev.pi.android.termux.TermuxRunCommand;
import dev.pi.android.util.AppFonts;

/** Guided one-time Termux + RUN_COMMAND + allow-external-apps setup. */
public final class TermuxSetupSheet {
    public static final int REQ_RUN_COMMAND = 4701;

    private final Activity activity;
    private final BottomSheetDialog dialog;
    private final View termuxAccent;
    private final TextView termuxBody;
    private final TextView termuxAction;
    private final View permAccent;
    private final TextView permBody;
    private final TextView permAction;
    private final View allowAccent;
    private final TextView allowCmd;

    public TermuxSetupSheet(@NonNull Activity activity) {
        this.activity = activity;
        dialog = new BottomSheetDialog(activity, R.style.PickerBottomSheet);
        View root = LayoutInflater.from(activity).inflate(R.layout.sheet_termux_setup, null, false);
        AppFonts.applyTree(root);
        dialog.setContentView(root);

        termuxAccent = root.findViewById(R.id.setup_termux_accent);
        termuxBody = root.findViewById(R.id.setup_termux_body);
        termuxAction = root.findViewById(R.id.setup_termux_action);
        permAccent = root.findViewById(R.id.setup_perm_accent);
        permBody = root.findViewById(R.id.setup_perm_body);
        permAction = root.findViewById(R.id.setup_perm_action);
        allowAccent = root.findViewById(R.id.setup_allow_accent);
        allowCmd = root.findViewById(R.id.setup_allow_cmd);
        TextView allowCopy = root.findViewById(R.id.setup_allow_copy);

        String cmd = ConnectorController.allowExternalHint();
        allowCmd.setText(cmd);
        allowCmd.setTypeface(AppFonts.mono(activity, false));
        allowCopy.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("termux", cmd));
                Toast.makeText(activity, R.string.termux_setup_copied, Toast.LENGTH_SHORT).show();
            }
        });
        termuxAction.setOnClickListener(v -> {
            try {
                activity.startActivity(new Intent(Intent.ACTION_VIEW,
                        Uri.parse("market://details?id=com.termux")));
            } catch (Exception e) {
                activity.startActivity(new Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://f-droid.org/packages/com.termux/")));
            }
        });
        permAction.setOnClickListener(v ->
                ActivityCompat.requestPermissions(activity,
                        new String[]{TermuxConstants.PERMISSION_RUN_COMMAND}, REQ_RUN_COMMAND));
        root.findViewById(R.id.setup_dismiss).setOnClickListener(v -> dialog.dismiss());

        dialog.setOnShowListener(d -> {
            FrameLayout sheet = dialog.findViewById(com.google.android.material.R.id.design_bottom_sheet);
            if (sheet == null) return;
            sheet.setBackgroundResource(android.R.color.transparent);
            BottomSheetBehavior<FrameLayout> behavior = BottomSheetBehavior.from(sheet);
            behavior.setSkipCollapsed(true);
            behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
            int h = (int) (activity.getResources().getDisplayMetrics().heightPixels * 0.78f);
            behavior.setPeekHeight(h);
            android.view.ViewGroup.LayoutParams lp = sheet.getLayoutParams();
            lp.height = h;
            sheet.setLayoutParams(lp);
            refresh();
        });
    }

    public void show() {
        refresh();
        dialog.show();
    }

    public void refresh() {
        boolean installed = TermuxRunCommand.isTermuxInstalled(activity);
        boolean granted = TermuxRunCommand.hasRunCommandPermission(activity);
        int ok = ContextCompat.getColor(activity, R.color.status_online);
        int warn = ContextCompat.getColor(activity, R.color.pi_amber);

        termuxAccent.setBackgroundColor(installed ? ok : warn);
        termuxBody.setText(installed
                ? R.string.termux_setup_install_ok
                : R.string.termux_setup_install_need);
        termuxAction.setVisibility(installed ? View.GONE : View.VISIBLE);

        permAccent.setBackgroundColor(granted ? ok : warn);
        permBody.setText(granted
                ? R.string.termux_setup_perm_ok
                : R.string.termux_setup_perm_need);
        permAction.setVisibility(granted ? View.GONE : View.VISIBLE);

        allowAccent.setBackgroundColor(warn);
    }

    /** True if Termux missing or RUN_COMMAND not granted (allow-external needs runtime probe). */
    public static boolean needsSetup(@NonNull Context ctx) {
        return !TermuxRunCommand.isTermuxInstalled(ctx)
                || !TermuxRunCommand.hasRunCommandPermission(ctx);
    }
}
