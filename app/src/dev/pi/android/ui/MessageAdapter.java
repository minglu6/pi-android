package dev.pi.android.ui;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

import dev.pi.android.R;
import dev.pi.android.model.ChatMessage;
import dev.pi.android.util.MarkdownLite;

/**
 * 聊天列表：用户右对齐气泡、助手全宽正文 + 小头像、工具卡片、系统居中 chip。
 * 不再展示 INFO/YOU/PI 调试标签。
 */
public class MessageAdapter extends RecyclerView.Adapter<MessageAdapter.VH> {

    private static final int TYPE_USER = 0;
    private static final int TYPE_AGENT = 1;
    private static final int TYPE_TOOL = 2;
    private static final int TYPE_SYSTEM = 3;

    public interface OnMessageChanged {
        void onChanged(int size);
    }

    private final List<ChatMessage> items = new ArrayList<>();
    private final OnMessageChanged listener;

    public MessageAdapter(OnMessageChanged listener) {
        this.listener = listener;
        setHasStableIds(false);
    }

    public void add(ChatMessage msg) {
        items.add(msg);
        notifyItemInserted(items.size() - 1);
        if (listener != null) listener.onChanged(items.size());
    }

    public boolean upsertTail(ChatMessage msg) {
        if (!items.isEmpty()) {
            int last = items.size() - 1;
            if (items.get(last).role == ChatMessage.Role.AGENT) {
                items.set(last, msg);
                notifyItemChanged(last);
                if (listener != null) listener.onChanged(items.size());
                return true;
            }
        }
        add(msg);
        return false;
    }

    public void replaceAt(int position, ChatMessage msg) {
        if (position < 0 || position >= items.size()) return;
        items.set(position, msg);
        notifyItemChanged(position);
    }

    public void replaceAll(List<ChatMessage> newItems) {
        items.clear();
        items.addAll(newItems);
        notifyDataSetChanged();
        if (listener != null) listener.onChanged(items.size());
    }

    public void clear() {
        items.clear();
        notifyDataSetChanged();
        if (listener != null) listener.onChanged(0);
    }

    public int size() {
        return items.size();
    }

    /** 主题/旋转重建时写入 AgentState 的不可变快照。 */
    public List<ChatMessage> snapshot() {
        return new ArrayList<>(items);
    }

    /** 对话是否为空：系统 chip 不算「有内容」。 */
    public boolean onlySystemOrEmpty() {
        for (ChatMessage m : items) {
            if (m.role != ChatMessage.Role.SYSTEM) return false;
        }
        return true;
    }

    @Override
    public int getItemViewType(int position) {
        switch (items.get(position).role) {
            case USER:
                return TYPE_USER;
            case TOOL:
                return TYPE_TOOL;
            case SYSTEM:
                return TYPE_SYSTEM;
            case AGENT:
            default:
                return TYPE_AGENT;
        }
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inf = LayoutInflater.from(parent.getContext());
        int layout;
        switch (viewType) {
            case TYPE_USER:
                layout = R.layout.item_message_user;
                break;
            case TYPE_TOOL:
                layout = R.layout.item_message_tool;
                break;
            case TYPE_SYSTEM:
                layout = R.layout.item_message_system;
                break;
            case TYPE_AGENT:
            default:
                layout = R.layout.item_message_agent;
                break;
        }
        return new VH(inf.inflate(layout, parent, false), viewType);
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        holder.bind(items.get(position));
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        final Context ctx;
        final int type;
        final TextView text;

        VH(@NonNull View itemView, int type) {
            super(itemView);
            this.ctx = itemView.getContext();
            this.type = type;
            this.text = itemView.findViewById(R.id.text);
        }

        void bind(ChatMessage msg) {
            int codeBg = ContextCompat.getColor(ctx, R.color.bg_code);
            int codeFg = ContextCompat.getColor(ctx, R.color.pi_amber);
            CharSequence body;
            switch (type) {
                case TYPE_USER:
                    text.setText(msg.text);
                    break;
                case TYPE_TOOL:
                    text.setText(MarkdownLite.render(msg.text, codeBg, codeFg));
                    break;
                case TYPE_SYSTEM:
                    text.setText(msg.text);
                    break;
                case TYPE_AGENT:
                default:
                    body = MarkdownLite.render(msg.text, codeBg, codeFg);
                    if (msg.streaming) {
                        android.text.SpannableStringBuilder sb =
                                new android.text.SpannableStringBuilder(body);
                        sb.append(ctx.getString(R.string.streaming_marker));
                        text.setText(sb);
                    } else {
                        text.setText(body);
                    }
                    break;
            }
            itemView.setOnLongClickListener(v -> {
                String when = formatTime(msg.timestampMs);
                if (!when.isEmpty()) {
                    Toast.makeText(ctx, when, Toast.LENGTH_SHORT).show();
                }
                return true;
            });
        }

        private String formatTime(long ms) {
            if (ms <= 0) return "";
            Calendar then = Calendar.getInstance();
            then.setTimeInMillis(ms);
            Calendar now = Calendar.getInstance();
            DateFormat fmt = android.text.format.DateFormat.getTimeFormat(ctx);
            if (then.get(Calendar.YEAR) == now.get(Calendar.YEAR)
                    && then.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR)) {
                return fmt.format(then.getTime());
            }
            return android.text.format.DateFormat.format("MM-dd HH:mm", then).toString();
        }
    }
}
