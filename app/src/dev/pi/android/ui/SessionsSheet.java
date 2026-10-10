package dev.pi.android.ui;

import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.PopupMenu;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

import dev.pi.android.R;
import dev.pi.android.model.SessionEntry;
import dev.pi.android.util.SessionText;

/**
 * 全屏展开的会话历史 Bottom Sheet（ChatGPT / Claude 风格），替代系统 AlertDialog。
 */
public final class SessionsSheet {

    public interface Host {
        @Nullable String currentSessionPath();

        void openSession(String path);

        void newSession();

        void renameSession(String path, @Nullable String knownName);

        void deleteSession(String path);

        boolean supportsRename();

        boolean supportsDelete();
    }

    private final MainActivity activity;
    private final Host host;
    private BottomSheetDialog dialog;
    private SessionListAdapter adapter;
    private final List<SessionEntry> allSessions = new ArrayList<>();
    private View emptyView;
    private ProgressBar loading;
    private TextView emptyTitle;
    private TextView emptyBody;
    private EditText search;
    private String query = "";

    public SessionsSheet(MainActivity activity, Host host) {
        this.activity = activity;
        this.host = host;
    }

    public void showLoading() {
        ensureDialog();
        if (loading != null) loading.setVisibility(View.VISIBLE);
        if (emptyView != null) emptyView.setVisibility(View.GONE);
        dialog.show();
        expandFully();
    }

    public void showError(String message) {
        ensureDialog();
        if (loading != null) loading.setVisibility(View.GONE);
        allSessions.clear();
        if (adapter != null) adapter.replaceAll(allSessions);
        if (emptyView != null) {
            emptyView.setVisibility(View.VISIBLE);
            emptyTitle.setText(R.string.session_list_error);
            emptyBody.setText(message == null || message.isEmpty()
                    ? activity.getString(R.string.sessions_empty_body)
                    : message);
        }
        dialog.show();
        expandFully();
    }

    public void showData(@Nullable JSONObject data) {
        ensureDialog();
        if (loading != null) loading.setVisibility(View.GONE);
        allSessions.clear();
        JSONArray arr = data != null ? data.optJSONArray("sessions") : null;
        String current = host.currentSessionPath();
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject item = arr.optJSONObject(i);
                if (item == null) continue;
                String path = item.optString("path", "");
                if (path.isEmpty()) continue;
                String name = item.optString("name", "");
                String preview = item.optString("preview", "");
                long mtime = item.optLong("mtimeMs", 0L);
                boolean isCurrent = current != null && current.equals(path);
                allSessions.add(SessionEntry.session(
                        path,
                        SessionText.title(name, preview),
                        SessionText.previewLine(name, preview),
                        mtime,
                        isCurrent));
            }
        }
        applyFilter();
        dialog.show();
        expandFully();
    }

    public void dismiss() {
        if (dialog != null && dialog.isShowing()) dialog.dismiss();
    }

    private void ensureDialog() {
        if (dialog != null) return;
        dialog = new BottomSheetDialog(activity, R.style.SessionsBottomSheet);
        View root = LayoutInflater.from(activity).inflate(R.layout.sheet_sessions, null, false);
        dialog.setContentView(root);

        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            View top = root.findViewById(R.id.sessions_top);
            if (top != null) top.setPadding(top.getPaddingLeft(), bars.top + dp(8), top.getPaddingRight(), top.getPaddingBottom());
            root.setPadding(0, 0, 0, bars.bottom);
            return insets;
        });

        root.findViewById(R.id.sessions_close).setOnClickListener(v -> dialog.dismiss());
        MaterialButton neu = root.findViewById(R.id.sessions_new);
        neu.setOnClickListener(v -> {
            dialog.dismiss();
            host.newSession();
        });

        search = root.findViewById(R.id.sessions_search);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                query = s == null ? "" : s.toString().trim();
                applyFilter();
            }
            @Override public void afterTextChanged(Editable s) {}
        });

        emptyView = root.findViewById(R.id.sessions_empty);
        emptyTitle = root.findViewById(R.id.sessions_empty_title);
        emptyBody = root.findViewById(R.id.sessions_empty_body);
        loading = root.findViewById(R.id.sessions_loading);

        RecyclerView list = root.findViewById(R.id.sessions_list);
        list.setLayoutManager(new LinearLayoutManager(activity));
        adapter = new SessionListAdapter(new SessionListAdapter.Listener() {
            @Override
            public void onOpen(SessionEntry entry) {
                dialog.dismiss();
                host.openSession(entry.path);
            }

            @Override
            public void onLongPress(SessionEntry entry, View anchor) {
                showActions(entry, anchor);
            }
        });
        list.setAdapter(adapter);

        if (host.supportsDelete()) {
            new ItemTouchHelper(new ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT) {
                @Override
                public boolean onMove(@NonNull RecyclerView recyclerView,
                                      @NonNull RecyclerView.ViewHolder viewHolder,
                                      @NonNull RecyclerView.ViewHolder target) {
                    return false;
                }

                @Override
                public int getSwipeDirs(@NonNull RecyclerView recyclerView,
                                       @NonNull RecyclerView.ViewHolder viewHolder) {
                    SessionEntry e = adapter.get(viewHolder.getBindingAdapterPosition());
                    if (e == null || e.kind != SessionEntry.Kind.SESSION) return 0;
                    return super.getSwipeDirs(recyclerView, viewHolder);
                }

                @Override
                public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder, int direction) {
                    int pos = viewHolder.getBindingAdapterPosition();
                    SessionEntry e = adapter.get(pos);
                    adapter.notifyItemChanged(pos);
                    if (e != null && e.kind == SessionEntry.Kind.SESSION) {
                        confirmDelete(e);
                    }
                }
            }).attachToRecyclerView(list);
        }

        dialog.setOnShowListener(d -> expandFully());
    }

    private void expandFully() {
        if (dialog == null) return;
        FrameLayout sheet = dialog.findViewById(com.google.android.material.R.id.design_bottom_sheet);
        if (sheet == null) return;
        sheet.setBackgroundResource(android.R.color.transparent);
        BottomSheetBehavior<FrameLayout> behavior = BottomSheetBehavior.from(sheet);
        behavior.setSkipCollapsed(true);
        behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
        int h = activity.getResources().getDisplayMetrics().heightPixels;
        behavior.setPeekHeight(h);
        android.view.ViewGroup.LayoutParams lp = sheet.getLayoutParams();
        lp.height = h;
        sheet.setLayoutParams(lp);
    }

    private void applyFilter() {
        List<SessionEntry> filtered = new ArrayList<>();
        String q = query == null ? "" : query.toLowerCase(java.util.Locale.ROOT);
        for (SessionEntry e : allSessions) {
            if (q.isEmpty()) {
                filtered.add(e);
                continue;
            }
            String hay = ((e.title == null ? "" : e.title) + " " + (e.preview == null ? "" : e.preview))
                    .toLowerCase(java.util.Locale.ROOT);
            if (hay.contains(q)) filtered.add(e);
        }
        List<SessionEntry> withHeaders = withDateHeaders(filtered);
        if (adapter != null) adapter.replaceAll(withHeaders);
        boolean empty = filtered.isEmpty();
        if (emptyView != null) {
            emptyView.setVisibility(empty ? View.VISIBLE : View.GONE);
            if (empty) {
                if (!q.isEmpty()) {
                    emptyTitle.setText(R.string.sessions_search_empty_title);
                    emptyBody.setText(R.string.sessions_search_empty_body);
                } else {
                    emptyTitle.setText(R.string.sessions_empty_title);
                    emptyBody.setText(R.string.sessions_empty_body);
                }
            }
        }
    }

    private static List<SessionEntry> withDateHeaders(List<SessionEntry> sessions) {
        List<SessionEntry> out = new ArrayList<>();
        String lastBucket = null;
        Calendar now = Calendar.getInstance();
        for (SessionEntry e : sessions) {
            String bucket = bucketLabel(e.mtimeMs, now);
            if (!bucket.equals(lastBucket)) {
                out.add(SessionEntry.header(bucket));
                lastBucket = bucket;
            }
            out.add(e);
        }
        return out;
    }

    private static String bucketLabel(long ms, Calendar now) {
        if (ms <= 0) return "更早";
        Calendar then = Calendar.getInstance();
        then.setTimeInMillis(ms);
        if (sameDay(then, now)) return "今天";
        Calendar y = (Calendar) now.clone();
        y.add(Calendar.DAY_OF_YEAR, -1);
        if (sameDay(then, y)) return "昨天";
        return "更早";
    }

    private static boolean sameDay(Calendar a, Calendar b) {
        return a.get(Calendar.YEAR) == b.get(Calendar.YEAR)
                && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR);
    }

    private void showActions(SessionEntry entry, View anchor) {
        PopupMenu popup = new PopupMenu(activity, anchor);
        popup.getMenu().add(0, 1, 0, R.string.session_open);
        if (host.supportsRename() && entry.current) {
            popup.getMenu().add(0, 2, 1, R.string.session_rename);
        }
        if (host.supportsDelete()) {
            popup.getMenu().add(0, 3, 2, R.string.session_delete);
        }
        popup.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1:
                    dialog.dismiss();
                    host.openSession(entry.path);
                    return true;
                case 2:
                    host.renameSession(entry.path, entry.title);
                    return true;
                case 3:
                    confirmDelete(entry);
                    return true;
                default:
                    return false;
            }
        });
        popup.show();
    }

    private void confirmDelete(SessionEntry entry) {
        new AlertDialog.Builder(activity)
                .setMessage(R.string.session_delete_confirm)
                .setPositiveButton(R.string.session_delete, (d, w) -> host.deleteSession(entry.path))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private int dp(int v) {
        return Math.round(v * activity.getResources().getDisplayMetrics().density);
    }
}
