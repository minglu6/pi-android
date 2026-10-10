package dev.pi.android.ui;

import android.app.Activity;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.StringRes;
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
 * 共享 Material 3 选择底部表：拖动手柄、标题、可选搜索、分组列表、勾选当前项。
 */
public final class PickerSheet {

    public interface OnPick {
        void onPick(@NonNull PickerItem item);
    }

    private final Activity activity;
    private final BottomSheetDialog dialog;
    private final PickerListAdapter adapter;
    private final List<PickerItem> all = new ArrayList<>();
    private final TextView empty;
    private final RecyclerView list;
    private String query = "";

    public PickerSheet(@NonNull Activity activity, @StringRes int titleRes, boolean searchable,
                       @NonNull OnPick onPick) {
        this.activity = activity;
        dialog = new BottomSheetDialog(activity, R.style.PickerBottomSheet);
        View root = LayoutInflater.from(activity).inflate(R.layout.sheet_picker, null, false);
        dialog.setContentView(root);

        TextView title = root.findViewById(R.id.picker_title);
        title.setText(titleRes);
        EditText search = root.findViewById(R.id.picker_search);
        empty = root.findViewById(R.id.picker_empty);
        list = root.findViewById(R.id.picker_list);
        list.setLayoutManager(new LinearLayoutManager(activity));
        adapter = new PickerListAdapter(item -> {
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
        });
    }

    public void setItems(@NonNull List<PickerItem> items) {
        all.clear();
        all.addAll(items);
        applyFilter();
    }

    public void show() {
        dialog.show();
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
