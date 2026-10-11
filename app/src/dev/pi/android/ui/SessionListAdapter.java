package dev.pi.android.ui;

import android.graphics.drawable.GradientDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

import android.widget.Toast;

import dev.pi.android.R;
import dev.pi.android.model.SessionEntry;
import dev.pi.android.util.AppFonts;
import dev.pi.android.util.SessionPins;

/** 会话列表：分组头 + 紧凑行。 */
public class SessionListAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    public interface Listener {
        void onOpen(SessionEntry entry);

        void onLongPress(SessionEntry entry, View anchor);

        /** Optional: refresh list after pin toggle. */
        default void onPinChanged() {}
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
        if (min < 1) return "now";
        if (min < 60) return min + "m";
        long h = min / 60L;
        if (h < 24) return h + "h";
        long d = h / 24L;
        if (d < 7) return d + "d";
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(ms);
        return String.format(Locale.CHINA, "%d/%d",
                cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH));
    }

    static class HeaderVH extends RecyclerView.ViewHolder {
        final TextView text;

        HeaderVH(@NonNull View itemView) {
            super(itemView);
            text = (TextView) itemView;
        }

        void bind(SessionEntry entry) {
            text.setTypeface(AppFonts.mono(itemView.getContext(), true));
            text.setText(entry.headerLabel);
        }
    }

    static class SessionVH extends RecyclerView.ViewHolder {
        final View card;
        final View dot;
        final TextView title;
        final TextView pinMark;
        final View previewRow;
        final TextView preview;
        final TextView time;
        final TextView model;
        final TextView msgCount;

        SessionVH(@NonNull View itemView) {
            super(itemView);
            card = itemView.findViewById(R.id.session_card);
            dot = itemView.findViewById(R.id.session_dot);
            title = itemView.findViewById(R.id.session_title);
            pinMark = itemView.findViewById(R.id.session_pin_mark);
            previewRow = itemView.findViewById(R.id.session_preview_row);
            preview = itemView.findViewById(R.id.session_preview);
            time = itemView.findViewById(R.id.session_time);
            model = itemView.findViewById(R.id.session_model);
            msgCount = itemView.findViewById(R.id.session_msg_count);
            if (time != null) time.setTypeface(AppFonts.mono(itemView.getContext(), false));
            if (model != null) model.setTypeface(AppFonts.mono(itemView.getContext(), false));
            if (msgCount != null) msgCount.setTypeface(AppFonts.mono(itemView.getContext(), false));
        }

        void bind(SessionEntry entry, Listener listener) {
            title.setTypeface(AppFonts.mono(itemView.getContext(), false));
            title.setText(entry.title);
            boolean sameAsTitle = entry.preview != null && entry.title != null
                    && entry.preview.equals(entry.title);
            boolean showPreview = entry.preview != null && !entry.preview.isEmpty() && !sameAsTitle;
            boolean showCount = entry.messageCount >= 0;
            if (previewRow != null) {
                previewRow.setVisibility(showPreview || showCount ? View.VISIBLE : View.GONE);
            }
            if (preview != null) {
                if (showPreview) {
                    preview.setVisibility(View.VISIBLE);
                    preview.setText(entry.preview);
                } else {
                    preview.setVisibility(View.GONE);
                    preview.setText("");
                }
            }
            String when = relativeTime(entry.mtimeMs);
            if (when.isEmpty()) {
                time.setVisibility(View.GONE);
            } else {
                time.setVisibility(View.VISIBLE);
                time.setText(when);
            }
            if (model != null) {
                if (entry.model == null || entry.model.isEmpty()) {
                    model.setVisibility(View.GONE);
                } else {
                    model.setVisibility(View.VISIBLE);
                    model.setText(entry.model);
                }
            }
            if (msgCount != null) {
                if (showCount) {
                    msgCount.setVisibility(View.VISIBLE);
                    msgCount.setText(String.valueOf(entry.messageCount));
                } else {
                    msgCount.setVisibility(View.GONE);
                }
            }
            boolean pinned = entry.path != null
                    && SessionPins.isPinned(itemView.getContext(), entry.path);
            if (pinMark != null) {
                pinMark.setVisibility(pinned ? View.VISIBLE : View.GONE);
            }
            card.setBackgroundResource(entry.current
                    ? R.drawable.bg_session_item_current
                    : R.drawable.bg_session_item);
            if (dot != null) {
                GradientDrawable shape = new GradientDrawable();
                shape.setShape(GradientDrawable.OVAL);
                shape.setColor(ContextCompat.getColor(itemView.getContext(),
                        entry.current ? R.color.pi_blue
                                : pinned ? R.color.pi_amber
                                : entry.archived ? R.color.role_pi : R.color.text_muted));
                dot.setBackground(shape);
            }
            card.setOnClickListener(v -> {
                if (listener != null) listener.onOpen(entry);
            });
            card.setOnLongClickListener(v -> {
                if (listener != null) listener.onLongPress(entry, v);
                return true;
            });
            if (pinMark != null) {
                pinMark.setOnClickListener(v -> {
                    if (entry.path == null) return;
                    boolean now = SessionPins.toggle(itemView.getContext(), entry.path);
                    Toast.makeText(itemView.getContext(),
                            now ? R.string.session_pinned_toast : R.string.session_unpinned_toast,
                            Toast.LENGTH_SHORT).show();
                    if (listener != null) listener.onPinChanged();
                });
            }
        }
    }
}
