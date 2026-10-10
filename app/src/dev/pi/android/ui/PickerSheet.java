package dev.pi.android.ui;

import android.app.Activity;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import dev.pi.android.R;
import dev.pi.android.model.PickerItem;

/**
 * Shared Material bottom sheet: search-as-you-type, optional 模型/思考 segments,
 * optional footer action (设为默认).
 */
public final class PickerSheet {

    public interface OnPick {
        void onPick(@NonNull PickerItem item);
    }

    public interface OnSegment {
        void onSegment(int index);
    }

    private final Activity activity;
    private final BottomSheetDialog dialog;
    private final PickerListAdapter adapter;
    private final List<PickerItem> all = new ArrayList<>();
    private final TextView empty;
    private final RecyclerView list;
    private final EditText search;
    private final View segments;
    private final TextView segA;
    private final TextView segB;
    private final TextView footerAction;
    private String query = "";
    private int segmentIndex;
    @Nullable private OnSegment segmentListener;
    @Nullable private Runnable footerListener;
    @Nullable private PickerItem lastPicked;

    public PickerSheet(@NonNull Activity activity, @StringRes int titleRes, boolean searchable,
                       @NonNull OnPick onPick) {
        this.activity = activity;
        dialog = new BottomSheetDialog(activity, R.style.PickerBottomSheet);
        View root = LayoutInflater.from(activity).inflate(R.layout.sheet_picker, null, false);
        dialog.setContentView(root);

        TextView title = root.findViewById(R.id.picker_title);
        title.setText(titleRes);
        search = root.findViewById(R.id.picker_search);
        empty = root.findViewById(R.id.picker_empty);
        list = root.findViewById(R.id.picker_list);
        segments = root.findViewById(R.id.picker_segments);
        segA = root.findViewById(R.id.picker_seg_a);
        segB = root.findViewById(R.id.picker_seg_b);
        footerAction = root.findViewById(R.id.picker_footer_action);
        list.setLayoutManager(new LinearLayoutManager(activity));
        adapter = new PickerListAdapter(item -> {
            lastPicked = item;
            // Keep sheet open when footer "设为默认" may still be used; dismiss on pick.
            dialog.dismiss();
            onPick.onPick(item);
        });
        list.setAdapter(adapter);

        if (searchable) {
            search.setVisibility(View.VISIBLE);
            search.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                    query = s == null ? "" : s.toString().trim();
                    applyFilter();
                }
                @Override public void afterTextChanged(Editable s) {}
            });
        } else {
            search.setVisibility(View.GONE);
        }

        if (segA != null) {
            segA.setOnClickListener(v -> selectSegment(0));
        }
        if (segB != null) {
            segB.setOnClickListener(v -> selectSegment(1));
        }
        if (footerAction != null) {
            footerAction.setOnClickListener(v -> {
                if (footerListener != null) footerListener.run();
            });
        }

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
            int sel = adapter.indexOfSelected();
            if (sel >= 0) list.post(() -> list.scrollToPosition(sel));
            if (searchable && search.getVisibility() == View.VISIBLE) {
                search.requestFocus();
                InputMethodManager imm = (InputMethodManager)
                        activity.getSystemService(Activity.INPUT_METHOD_SERVICE);
                if (imm != null) imm.showSoftInput(search, InputMethodManager.SHOW_IMPLICIT);
            }
        });
    }

    public void enableSegments(@NonNull String labelA, @NonNull String labelB,
                               @NonNull OnSegment listener) {
        segmentListener = listener;
        if (segments != null) segments.setVisibility(View.VISIBLE);
        if (segA != null) segA.setText(labelA);
        if (segB != null) segB.setText(labelB);
        selectSegment(0);
    }

    public void enableFooter(@NonNull String label, @NonNull Runnable action) {
        footerListener = action;
        if (footerAction != null) {
            footerAction.setText(label);
            footerAction.setVisibility(View.VISIBLE);
        }
    }

    public void setItems(@NonNull List<PickerItem> items) {
        all.clear();
        all.addAll(items);
        applyFilter();
    }

    public void show() {
        dialog.show();
    }

    @Nullable
    public PickerItem lastPicked() {
        return lastPicked;
    }

    private void selectSegment(int index) {
        segmentIndex = index;
        int on = R.drawable.bg_segmented_on;
        int onFg = ContextCompat.getColor(activity, R.color.md_theme_onPrimary);
        int offFg = ContextCompat.getColor(activity, R.color.text_muted);
        if (segA != null && segB != null) {
            if (index == 0) {
                segA.setBackgroundResource(on);
                segA.setTextColor(onFg);
                segB.setBackground(null);
                segB.setTextColor(offFg);
            } else {
                segB.setBackgroundResource(on);
                segB.setTextColor(onFg);
                segA.setBackground(null);
                segA.setTextColor(offFg);
            }
        }
        // Search only on models tab by default.
        if (search != null) {
            search.setVisibility(index == 0 ? View.VISIBLE : View.GONE);
            if (index != 0) {
                query = "";
                search.setText("");
            }
        }
        if (segmentListener != null) segmentListener.onSegment(index);
    }

    private void applyFilter() {
        String q = query.toLowerCase(Locale.ROOT);
        if (q.isEmpty()) {
            adapter.replaceAll(all);
            empty.setVisibility(all.isEmpty() ? View.VISIBLE : View.GONE);
            return;
        }
        List<PickerItem> out = new ArrayList<>();
        PickerItem pendingHeader = null;
        for (PickerItem item : all) {
            if (item.kind == PickerItem.Kind.HEADER) {
                pendingHeader = item;
                continue;
            }
            boolean match = item.title.toLowerCase(Locale.ROOT).contains(q)
                    || (item.subtitle != null && item.subtitle.toLowerCase(Locale.ROOT).contains(q))
                    || (pendingHeader != null && pendingHeader.header != null
                    && pendingHeader.header.toLowerCase(Locale.ROOT).contains(q));
            if (!match) continue;
            if (pendingHeader != null) {
                out.add(pendingHeader);
                pendingHeader = null;
            }
            out.add(item);
        }
        adapter.replaceAll(out);
        empty.setVisibility(out.isEmpty() ? View.VISIBLE : View.GONE);
    }
}
