package dev.pi.android.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

import dev.pi.android.R;
import dev.pi.android.model.SessionEntry;

/** 会话列表：分组头 + 卡片行。 */
public class SessionListAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    public interface Listener {
        void onOpen(SessionEntry entry);

        void onLongPress(SessionEntry entry, View anchor);
    }

    private static final int TYPE_HEADER = 0;
    private static final int TYPE_SESSION = 1;

    private final List<SessionEntry> items = new ArrayList<>();
    private final Listener listener;

    public SessionListAdapter(Listener listener) {
        this.listener = listener;
    }

    public void replaceAll(List<SessionEntry> next) {
        items.clear();
        if (next != null) items.addAll(next);
        notifyDataSetChanged();
    }

    public SessionEntry get(int position) {
        if (position < 0 || position >= items.size()) return null;
        return items.get(position);
    }

    public int indexOfPath(String path) {
        if (path == null) return -1;
        for (int i = 0; i < items.size(); i++) {
            SessionEntry e = items.get(i);
            if (e.kind == SessionEntry.Kind.SESSION && path.equals(e.path)) return i;
        }
        return -1;
    }

    @Override
    public int getItemViewType(int position) {
        return items.get(position).kind == SessionEntry.Kind.HEADER ? TYPE_HEADER : TYPE_SESSION;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inf = LayoutInflater.from(parent.getContext());
        if (viewType == TYPE_HEADER) {
            return new HeaderVH(inf.inflate(R.layout.item_session_header, parent, false));
        }
        return new SessionVH(inf.inflate(R.layout.item_session, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        SessionEntry entry = items.get(position);
        if (holder instanceof HeaderVH) {
            ((HeaderVH) holder).bind(entry);
        } else if (holder instanceof SessionVH) {
            ((SessionVH) holder).bind(entry, listener);
        }
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static String relativeTime(long ms) {
        if (ms <= 0) return "";
        long diff = System.currentTimeMillis() - ms;
        long min = diff / 60000L;
        if (min < 1) return "刚刚";
        if (min < 60) return min + " 分钟前";
        long h = min / 60L;
        if (h < 24) return h + " 小时前";
        long d = h / 24L;
        if (d < 7) return d + " 天前";
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(ms);
        return String.format(Locale.CHINA, "%d月%d日",
                cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH));
    }

    static class HeaderVH extends RecyclerView.ViewHolder {
        final TextView text;

        HeaderVH(@NonNull View itemView) {
            super(itemView);
            text = (TextView) itemView;
        }

        void bind(SessionEntry entry) {
            text.setText(entry.headerLabel);
        }
    }

    static class SessionVH extends RecyclerView.ViewHolder {
        final View card;
        final TextView title;
        final TextView preview;
        final TextView time;

        SessionVH(@NonNull View itemView) {
            super(itemView);
            card = itemView.findViewById(R.id.session_card);
            title = itemView.findViewById(R.id.session_title);
            preview = itemView.findViewById(R.id.session_preview);
            time = itemView.findViewById(R.id.session_time);
        }

        void bind(SessionEntry entry, Listener listener) {
            title.setText(entry.title);
            if (entry.preview == null || entry.preview.isEmpty()) {
                preview.setVisibility(View.GONE);
            } else {
                preview.setVisibility(View.VISIBLE);
                preview.setText(entry.preview);
            }
            time.setText(relativeTime(entry.mtimeMs));
            card.setBackgroundResource(entry.current
                    ? R.drawable.bg_session_item_current
                    : R.drawable.bg_session_item);
            card.setOnClickListener(v -> {
                if (listener != null) listener.onOpen(entry);
            });
            card.setOnLongClickListener(v -> {
                if (listener != null) listener.onLongPress(entry, v);
                return true;
            });
        }
    }
}
