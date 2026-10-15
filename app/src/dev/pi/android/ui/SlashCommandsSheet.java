package dev.pi.android.ui;

import android.app.Activity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;

import java.util.List;

import dev.pi.android.R;
import dev.pi.android.model.SlashCommand;
import dev.pi.android.util.AppFonts;

/**
 * 斜杠命令菜单：Tokyo Night {@link BottomSheetDialog}，半高封顶、点外部 / 返回 / 下滑关闭。
 * 矮列表贴合内容，不占满屏。
 */
public final class SlashCommandsSheet {

    public interface Listener {
        void onPick(@NonNull SlashCommand cmd);

        /** 用户点外部、系统返回或下滑关闭（不是选中条目 / 编程 dismiss）。 */
        void onDismissedByUser();
    }

    private final Activity activity;
    private final BottomSheetDialog dialog;
    private final SlashCommandAdapter adapter;
    private final MaxHeightRecyclerView list;
    private final View root;
    private boolean programmaticDismiss;
    private boolean picking;
    private boolean callbackAttached;

    public SlashCommandsSheet(@NonNull Activity activity, @NonNull Listener listener) {
        this.activity = activity;
        dialog = new BottomSheetDialog(activity, R.style.PickerBottomSheet);
        root = LayoutInflater.from(activity).inflate(R.layout.sheet_slash_commands, null, false);
        AppFonts.applyTree(root);
        dialog.setContentView(root);
        dialog.setCancelable(true);
        dialog.setCanceledOnTouchOutside(true);

        list = root.findViewById(R.id.slash_list);
        if (list == null) {
            throw new IllegalStateException("sheet_slash_commands missing slash_list");
        }
        list.setLayoutManager(new LinearLayoutManager(activity));
        adapter = new SlashCommandAdapter(cmd -> {
            picking = true;
            dialog.dismiss();
            listener.onPick(cmd);
        });
        list.setAdapter(adapter);

        dialog.setOnShowListener(d -> {
            hideIme();
            applySheetChrome();
        });
        dialog.setOnDismissListener(d -> {
            boolean user = !programmaticDismiss && !picking;
            programmaticDismiss = false;
            picking = false;
            if (user) listener.onDismissedByUser();
        });
    }

    public void show(@NonNull List<SlashCommand> items) {
        adapter.replaceAll(items);
        if (dialog.isShowing()) {
            applySheetChrome();
            return;
        }
        dialog.show();
    }

    public void dismiss() {
        if (!dialog.isShowing()) return;
        programmaticDismiss = true;
        dialog.dismiss();
    }

    public boolean isShowing() {
        return dialog.isShowing();
    }

    public boolean isEmpty() {
        return adapter.isEmpty();
    }

    @Nullable
    public SlashCommand first() {
        return adapter.first();
    }

    private void applySheetChrome() {
        FrameLayout sheet = dialog.findViewById(com.google.android.material.R.id.design_bottom_sheet);
        if (sheet == null) {
            throw new IllegalStateException("BottomSheetDialog missing design_bottom_sheet");
        }
        sheet.setBackgroundResource(android.R.color.transparent);
        BottomSheetBehavior<FrameLayout> behavior = BottomSheetBehavior.from(sheet);
        int screen = activity.getResources().getDisplayMetrics().heightPixels;
        int maxH = SheetHeight.capPx(screen, SheetHeight.HALF);
        int header = Math.max(nonListHeight(), dp(56));
        int listMax = maxH - header;
        if (listMax <= 0) {
            throw new IllegalStateException("slash list maxHeight=" + listMax + " maxH=" + maxH);
        }
        list.setMaxHeight(listMax);
        behavior.setFitToContents(true);
        behavior.setMaxHeight(maxH);
        behavior.setSkipCollapsed(true);
        behavior.setHideable(true);
        behavior.setDraggable(true);
        behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
        if (!callbackAttached) {
            behavior.addBottomSheetCallback(hiddenDismissesDialog);
            callbackAttached = true;
        }
        ViewGroup.LayoutParams lp = sheet.getLayoutParams();
        lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
        sheet.setLayoutParams(lp);
    }

    private final BottomSheetBehavior.BottomSheetCallback hiddenDismissesDialog =
            new BottomSheetBehavior.BottomSheetCallback() {
                @Override
                public void onStateChanged(@NonNull View bottomSheet, int newState) {
                    if (newState == BottomSheetBehavior.STATE_HIDDEN && dialog.isShowing()) {
                        dialog.dismiss();
                    }
                }

                @Override
                public void onSlide(@NonNull View bottomSheet, float slideOffset) {}
            };

    private int nonListHeight() {
        int used = root.getPaddingTop() + root.getPaddingBottom();
        if (!(root instanceof ViewGroup)) return used;
        ViewGroup vg = (ViewGroup) root;
        for (int i = 0; i < vg.getChildCount(); i++) {
            View child = vg.getChildAt(i);
            if (child == list) continue;
            used += Math.max(child.getHeight(), child.getMeasuredHeight());
            ViewGroup.LayoutParams lp = child.getLayoutParams();
            if (lp instanceof ViewGroup.MarginLayoutParams) {
                ViewGroup.MarginLayoutParams mlp = (ViewGroup.MarginLayoutParams) lp;
                used += mlp.topMargin + mlp.bottomMargin;
            }
        }
        return used;
    }

    private int dp(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    private void hideIme() {
        View focus = activity.getCurrentFocus();
        if (focus == null) return;
        InputMethodManager imm = (InputMethodManager)
                activity.getSystemService(Activity.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(focus.getWindowToken(), 0);
    }
}
