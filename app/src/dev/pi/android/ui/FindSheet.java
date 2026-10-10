package dev.pi.android.ui;

import android.app.Activity;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import dev.pi.android.R;
import dev.pi.android.model.ChatMessage;

/** Find-in-session sheet over messages and tool outputs. */
public final class FindSheet {

    public interface Host {
        @NonNull List<ChatMessage> messages();

        void jumpTo(int adapterIndex);
    }

    private final Activity activity;
    private final Host host;
    private BottomSheetDialog dialog;
    private HitsAdapter adapter;
    private TextView count;
    private TextView empty;

    public FindSheet(@NonNull Activity activity, @NonNull Host host) {
        this.activity = activity;
        this.host = host;
    }

    public void show() {
        if (dialog == null) {
            dialog = new BottomSheetDialog(activity, R.style.PickerBottomSheet);
            View root = LayoutInflater.from(activity).inflate(R.layout.sheet_find, null, false);
            dialog.setContentView(root);
            EditText query = root.findViewById(R.id.find_query);
            count = root.findViewById(R.id.find_count);
            empty = root.findViewById(R.id.find_empty);
            RecyclerView list = root.findViewById(R.id.find_list);
            list.setLayoutManager(new LinearLayoutManager(activity));
            adapter = new HitsAdapter(hit -> {
                dialog.dismiss();
                host.jumpTo(hit.index);
            });
            list.setAdapter(adapter);
            query.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                    filter(s == null ? "" : s.toString());
                }
                @Override public void afterTextChanged(Editable s) {}
            });
            dialog.setOnShowListener(d -> {
                FrameLayout sheet = dialog.findViewById(com.google.android.material.R.id.design_bottom_sheet);
                if (sheet == null) return;
                sheet.setBackgroundResource(android.R.color.transparent);
                BottomSheetBehavior<FrameLayout> behavior = BottomSheetBehavior.from(sheet);
                behavior.setSkipCollapsed(true);
                behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
                int h = (int) (activity.getResources().getDisplayMetrics().heightPixels * 0.85f);
                behavior.setPeekHeight(h);
                android.view.ViewGroup.LayoutParams lp = sheet.getLayoutParams();
                lp.height = h;
                sheet.setLayoutParams(lp);
                query.requestFocus();
            });
        }
        filter("");
        dialog.show();
    }

    private void filter(String q) {
        String needle = q.trim().toLowerCase(Locale.ROOT);
        List<Hit> hits = new ArrayList<>();
        List<ChatMessage> msgs = host.messages();
        for (int i = 0; i < msgs.size(); i++) {
            ChatMessage m = msgs.get(i);
            String hay = haystack(m);
            if (hay.isEmpty()) continue;
            if (!needle.isEmpty() && !hay.toLowerCase(Locale.ROOT).contains(needle)) continue;
            if (needle.isEmpty()) continue; // require a query
            hits.add(new Hit(i, roleLabel(m), snippet(hay, needle)));
        }
        adapter.replaceAll(hits);
        if (count != null) {
            count.setText(activity.getString(R.string.find_count_fmt, hits.size()));
        }
        if (empty != null) empty.setVisibility(hits.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private static String haystack(ChatMessage m) {
        if (m.tool != null) {
            return (m.tool.name + "\n" + m.tool.command + "\n" + m.tool.output).trim();
        }
        return m.text == null ? "" : m.text;
    }

    private String roleLabel(ChatMessage m) {
        switch (m.role) {
            case USER: return activity.getString(R.string.user_you);
            case AGENT: return "Pi";
            case TOOL: return m.tool != null ? m.tool.name : activity.getString(R.string.tool_legacy_name);
            default: return "系统";
        }
    }

    private static String snippet(String hay, String needle) {
        String lower = hay.toLowerCase(Locale.ROOT);
        int at = lower.indexOf(needle);
        if (at < 0) return hay.length() > 160 ? hay.substring(0, 160) + "…" : hay;
        int start = Math.max(0, at - 40);
        int end = Math.min(hay.length(), at + needle.length() + 80);
        String s = hay.substring(start, end).replace('\n', ' ');
        if (start > 0) s = "…" + s;
        if (end < hay.length()) s = s + "…";
        return s;
    }

    static final class Hit {
        final int index;
        final String role;
        final String snippet;
        Hit(int index, String role, String snippet) {
            this.index = index;
            this.role = role;
            this.snippet = snippet;
        }
    }

    interface OnHit { void onHit(Hit hit); }

    static final class HitsAdapter extends RecyclerView.Adapter<HitsAdapter.VH> {
        private final List<Hit> items = new ArrayList<>();
        private final OnHit onHit;
        HitsAdapter(OnHit onHit) { this.onHit = onHit; }
        void replaceAll(List<Hit> next) {
            items.clear();
            items.addAll(next);
            notifyDataSetChanged();
        }
        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_find_hit, parent, false);
            return new VH(v);
        }
        @Override
        public void onBindViewHolder(@NonNull VH h, int position) {
            Hit hit = items.get(position);
            h.role.setText(hit.role);
            h.snippet.setText(hit.snippet);
            h.itemView.setOnClickListener(v -> onHit.onHit(hit));
        }
        @Override public int getItemCount() { return items.size(); }
        static class VH extends RecyclerView.ViewHolder {
            final TextView role;
            final TextView snippet;
            VH(View itemView) {
                super(itemView);
                role = itemView.findViewById(R.id.find_role);
                snippet = itemView.findViewById(R.id.find_snippet);
            }
        }
    }
}
