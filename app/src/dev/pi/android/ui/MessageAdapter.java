package dev.pi.android.ui;

import android.content.Context;
import android.text.format.DateFormat;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

import dev.pi.android.R;
import dev.pi.android.model.ChatMessage;
import dev.pi.android.util.MarkdownLite;

/** 聊天列表适配器。气泡靠 gravity + 圆角 drawable 区分角色；AGENT 做轻量 Markdown。 */
public class MessageAdapter extends RecyclerView.Adapter<MessageAdapter.VH> {

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

    /** 流式输出时反复更新最后一条助手消息。 */
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

    public boolean onlySystemOrEmpty() {
        for (ChatMessage m : items) {
            if (m.role != ChatMessage.Role.SYSTEM) return false;
        }
        return true;
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_message, parent, false);
        return new VH(v);
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
        final LinearLayout root;
        final TextView role;
        final TextView time;
        final TextView text;

        VH(@NonNull View itemView) {
            super(itemView);
            ctx = itemView.getContext();
            root = itemView.findViewById(R.id.bubble_root);
            role = itemView.findViewById(R.id.role);
            time = itemView.findViewById(R.id.time);
            text = itemView.findViewById(R.id.text);
        }

        void bind(ChatMessage msg) {
            boolean mine = msg.role == ChatMessage.Role.USER;
            boolean system = msg.role == ChatMessage.Role.SYSTEM;
            int gravity = system ? Gravity.CENTER_HORIZONTAL
                    : (mine ? Gravity.END : Gravity.START);
            root.setGravity(gravity);
            ((LinearLayout) role.getParent()).setGravity(gravity);

            int padH = dp(12);
            int padV = dp(9);
            text.setPadding(padH, padV, padH, padV);
            text.setMaxWidth((int) (ctx.getResources().getDisplayMetrics().widthPixels
                    * (system ? 0.92f : 0.82f)));

            time.setText(formatTime(msg.timestampMs));

            int codeBg = ContextCompat.getColor(ctx, R.color.bg_code);
            int codeFg = ContextCompat.getColor(ctx, R.color.pi_amber);

            switch (msg.role) {
                case USER:
                    role.setText("YOU");
                    role.setTextColor(ContextCompat.getColor(ctx, R.color.role_you));
                    text.setBackgroundResource(R.drawable.bg_bubble_user);
                    text.setTextColor(ContextCompat.getColor(ctx, R.color.text_primary));
                    text.setText(msg.text);
                    break;
                case TOOL:
                    role.setText("TOOL");
                    role.setTextColor(ContextCompat.getColor(ctx, R.color.role_tool));
                    text.setBackgroundResource(R.drawable.bg_bubble_tool);
                    text.setTextColor(ContextCompat.getColor(ctx, R.color.text_primary));
                    text.setText(MarkdownLite.render(msg.text, codeBg, codeFg));
                    break;
                case SYSTEM:
                    role.setText("INFO");
                    role.setTextColor(ContextCompat.getColor(ctx, R.color.role_info));
                    text.setBackgroundResource(R.drawable.bg_bubble_system);
                    text.setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary));
                    text.setText(msg.text);
                    break;
                case AGENT:
                default:
                    role.setText(msg.streaming ? "PI · …" : "PI");
                    role.setTextColor(ContextCompat.getColor(ctx, R.color.role_pi));
                    text.setBackgroundResource(R.drawable.bg_bubble_agent);
                    text.setTextColor(ContextCompat.getColor(ctx, R.color.text_primary));
                    CharSequence body = MarkdownLite.render(msg.text, codeBg, codeFg);
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
        }

        private String formatTime(long ms) {
            if (ms <= 0) return "";
            Calendar then = Calendar.getInstance();
            then.setTimeInMillis(ms);
            Calendar now = Calendar.getInstance();
            java.text.DateFormat fmt = DateFormat.getTimeFormat(ctx);
            if (then.get(Calendar.YEAR) == now.get(Calendar.YEAR)
                    && then.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR)) {
                return fmt.format(then.getTime());
            }
            return DateFormat.format("MM-dd HH:mm", then).toString();
        }

        private int dp(int v) {
            return (int) TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP, v, ctx.getResources().getDisplayMetrics());
        }
    }
}
