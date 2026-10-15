package dev.pi.android.ui;

import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.coordinatorlayout.widget.CoordinatorLayout;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomsheet.BottomSheetBehavior;

import java.util.List;

import dev.pi.android.R;
import dev.pi.android.model.SlashCommand;
import dev.pi.android.util.AppFonts;

/**
 * 斜杠命令菜单：锚在 composer 上方的 Tokyo Night BottomSheet。
 * 大半屏展开，点遮罩 / 系统返回 / 下滑关闭；不盖住输入框、不抢焦点。
 */
public final class SlashCommandsSheet {

    public interface Listener {
        void onPick(@NonNull SlashCommand cmd);

        /** 用户点外部、系统返回或下滑关闭（不是选中条目 / 编程 dismiss）。 */
        void onDismissedByUser();
    }

    private final AppCompatActivity activity;
    private final Listener listener;
    private final View sheet;
    private final View scrim;
    private final SlashCommandAdapter adapter;
    private final BottomSheetBehavior<View> behavior;
    private final OnBackPressedCallback backCallback;
    private boolean programmaticDismiss;
    private boolean picking;

    public SlashCommandsSheet(@NonNull AppCompatActivity activity, @NonNull Listener listener) {
        this.activity = activity;
        this.listener = listener;
        sheet = activity.findViewById(R.id.slash_sheet_root);
        scrim = activity.findViewById(R.id.slash_scrim);
        RecyclerView list = activity.findViewById(R.id.slash_list);
        if (sheet == null || scrim == null || list == null) {
            throw new IllegalStateException("slash sheet views missing");
        }
        AppFonts.applyTree(sheet);
        list.setLayoutManager(new LinearLayoutManager(activity));
        adapter = new SlashCommandAdapter(cmd -> {
            picking = true;
            hideSheet();
            listener.onPick(cmd);
        });
        list.setAdapter(adapter);
        behavior = attachBehavior(sheet);
        behavior.setFitToContents(false);
        behavior.setSkipCollapsed(true);
        behavior.setHideable(true);
        behavior.setDraggable(true);
        behavior.setState(BottomSheetBehavior.STATE_HIDDEN);
        behavior.addBottomSheetCallback(new BottomSheetBehavior.BottomSheetCallback() {
            @Override
            public void onStateChanged(@NonNull View bottomSheet, int newState) {
                if (newState != BottomSheetBehavior.STATE_HIDDEN) return;
                scrim.setVisibility(View.GONE);
                backCallback.setEnabled(false);
                boolean user = !programmaticDismiss && !picking;
                programmaticDismiss = false;
                picking = false;
                if (user) listener.onDismissedByUser();
            }

            @Override
            public void onSlide(@NonNull View bottomSheet, float slideOffset) {}
        });
        scrim.setOnClickListener(v -> dismissByUser());
        ViewParent parent = sheet.getParent();
        if (parent instanceof View) {
            ((View) parent).addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
                if (!isShowing()) return;
                if ((b - t) == (ob - ot)) return;
                applyExpandedOffset(false);
            });
        }
        backCallback = new OnBackPressedCallback(false) {
            @Override
            public void handleOnBackPressed() {
                dismissByUser();
            }
        };
        activity.getOnBackPressedDispatcher().addCallback(activity, backCallback);
    }

    public void show(@NonNull List<SlashCommand> items) {
        adapter.replaceAll(items);
        if (isShowing()) return;
        scrim.setVisibility(View.VISIBLE);
        expand();
    }

    public void dismiss() {
        if (!isShowing()) return;
        programmaticDismiss = true;
        hideSheet();
    }

    public boolean isShowing() {
        int state = behavior.getState();
        return state == BottomSheetBehavior.STATE_EXPANDED
                || state == BottomSheetBehavior.STATE_SETTLING
                || state == BottomSheetBehavior.STATE_DRAGGING;
    }

    public boolean isEmpty() {
        return adapter.isEmpty();
    }

    @Nullable
    public SlashCommand first() {
        return adapter.first();
    }

    private void dismissByUser() {
        if (!isShowing()) return;
        programmaticDismiss = false;
        picking = false;
        hideSheet();
    }

    private void hideSheet() {
        behavior.setState(BottomSheetBehavior.STATE_HIDDEN);
    }

    private void expand() {
        applyExpandedOffset(true);
    }

    private void applyExpandedOffset(boolean thenExpand) {
        ViewParent parent = sheet.getParent();
        if (!(parent instanceof View)) {
            throw new IllegalStateException("slash sheet parent is not a view");
        }
        View host = (View) parent;
        int parentH = host.getHeight();
        if (parentH <= 0) {
            host.post(() -> applyExpandedOffset(thenExpand));
            return;
        }
        int screen = activity.getResources().getDisplayMetrics().heightPixels;
        int minScrim = Math.round(48 * activity.getResources().getDisplayMetrics().density);
        behavior.setExpandedOffset(SheetHeight.expandedOffsetPx(parentH, screen, minScrim));
        if (thenExpand) {
            backCallback.setEnabled(true);
            behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
        }
    }

    private static BottomSheetBehavior<View> attachBehavior(View sheet) {
        ViewGroup.LayoutParams raw = sheet.getLayoutParams();
        CoordinatorLayout.LayoutParams lp;
        if (raw instanceof CoordinatorLayout.LayoutParams) {
            lp = (CoordinatorLayout.LayoutParams) raw;
        } else {
            lp = new CoordinatorLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            lp.gravity = Gravity.BOTTOM;
        }
        BottomSheetBehavior<View> next = new BottomSheetBehavior<>();
        lp.setBehavior(next);
        sheet.setLayoutParams(lp);
        return next;
    }
}
