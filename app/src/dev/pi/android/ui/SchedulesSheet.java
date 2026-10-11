package dev.pi.android.ui;

import android.app.Activity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;

import java.util.ArrayList;
import java.util.List;

import dev.pi.android.R;
import dev.pi.android.util.AppFonts;

/**
 * Schedules list bottom sheet with real empty state (icon + CTA + example chips).
 */
public final class SchedulesSheet {

    public static final class Row {
        @NonNull public final String id;
        @NonNull public final String when;
        @NonNull public final String text;
        @Nullable public final String every;

        public Row(@NonNull String id, @NonNull String when, @NonNull String text,
                   @Nullable String every) {
            this.id = id;
            this.when = when;
            this.text = text;
            this.every = every;
        }
    }

    public interface Listener {
        void onCancel(@NonNull String scheduleId);

        /** Prefill composer with a /schedule draft and dismiss. */
        void onComposeDraft(@NonNull String draft);
    }

    private final Activity activity;
    private final BottomSheetDialog dialog;
    private final Listener listener;
    private final RowsAdapter adapter;
    private final View empty;
    private final View loading;
    private final RecyclerView list;
    private final View footer;

    public SchedulesSheet(@NonNull Activity activity, @NonNull Listener listener) {
        this.activity = activity;
        this.listener = listener;
        dialog = new BottomSheetDialog(activity, R.style.PickerBottomSheet);
        View root = LayoutInflater.from(activity).inflate(R.layout.sheet_schedules, null, false);
        AppFonts.applyTree(root);
        dialog.setContentView(root);

        list = root.findViewById(R.id.schedules_list);
        empty = root.findViewById(R.id.schedules_empty);
        loading = root.findViewById(R.id.schedules_loading);
        footer = root.findViewById(R.id.schedules_footer_new);
        list.setLayoutManager(new LinearLayoutManager(activity));
        adapter = new RowsAdapter(row -> {
            MaterialDialogs.confirm(activity,
                    R.string.schedules_cancel,
                    R.string.schedules_cancel_confirm,
                    android.R.string.ok,
                    () -> listener.onCancel(row.id));
        });
        list.setAdapter(adapter);

        View.OnClickListener newClick = v -> {
            dialog.dismiss();
            listener.onComposeDraft(activity.getString(R.string.schedules_draft_once));
        };
        footer.setOnClickListener(newClick);
        TextView emptyCta = root.findViewById(R.id.schedules_empty_cta);
        if (emptyCta != null) emptyCta.setOnClickListener(newClick);

        TextView exA = root.findViewById(R.id.schedules_ex_a);
        TextView exB = root.findViewById(R.id.schedules_ex_b);
        if (exA != null) {
            exA.setOnClickListener(v -> {
                dialog.dismiss();
                listener.onComposeDraft(activity.getString(R.string.schedules_draft_once));
            });
        }
        if (exB != null) {
            exB.setOnClickListener(v -> {
                dialog.dismiss();
                listener.onComposeDraft(activity.getString(R.string.schedules_draft_every));
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
        });
    }

    public void setLoading() {
        loading.setVisibility(View.VISIBLE);
        empty.setVisibility(View.GONE);
        list.setVisibility(View.GONE);
        footer.setVisibility(View.GONE);
        adapter.replaceAll(java.util.Collections.emptyList());
    }

    public void setSchedules(@NonNull List<Row> rows) {
        loading.setVisibility(View.GONE);
        adapter.replaceAll(rows);
        boolean isEmpty = rows.isEmpty();
        empty.setVisibility(isEmpty ? View.VISIBLE : View.GONE);
        list.setVisibility(isEmpty ? View.GONE : View.VISIBLE);
        footer.setVisibility(isEmpty ? View.GONE : View.VISIBLE);
    }

    public void show() {
        dialog.show();
    }

    public void dismiss() {
        dialog.dismiss();
    }

    interface OnCancelRow {
        void onCancel(Row row);
    }

    static final class RowsAdapter extends RecyclerView.Adapter<RowsAdapter.VH> {
        private final List<Row> items = new ArrayList<>();
        private final OnCancelRow onCancel;

        RowsAdapter(OnCancelRow onCancel) {
            this.onCancel = onCancel;
        }

        void replaceAll(List<Row> next) {
            items.clear();
            items.addAll(next);
            notifyDataSetChanged();
        }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_schedule_row, parent, false);
            AppFonts.applyTree(v);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int position) {
            Row row = items.get(position);
            h.when.setText(row.when.isEmpty() ? "—" : row.when);
            h.text.setText(row.text);
            if (row.every != null && !row.every.isEmpty()) {
                h.every.setVisibility(View.VISIBLE);
                h.every.setText(row.every);
            } else {
                h.every.setVisibility(View.GONE);
            }
            h.cancel.setOnClickListener(v -> onCancel.onCancel(row));
        }

        @Override public int getItemCount() { return items.size(); }

        static class VH extends RecyclerView.ViewHolder {
            final TextView when;
            final TextView text;
            final TextView every;
            final TextView cancel;
            VH(View itemView) {
                super(itemView);
                when = itemView.findViewById(R.id.schedule_when);
                text = itemView.findViewById(R.id.schedule_text);
                every = itemView.findViewById(R.id.schedule_every);
                cancel = itemView.findViewById(R.id.schedule_cancel);
            }
        }
    }
}
