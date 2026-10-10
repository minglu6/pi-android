package dev.pi.android.ui;

import android.content.Context;
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
import java.util.List;

import dev.pi.android.R;
import dev.pi.android.model.ChatMessage;

/** 聊天列表适配器。气泡靠 gravity + 圆角 drawable 区分角色。 */
public class MessageAdapter extends RecyclerView.Adapter<MessageAdapter.VH> {

    private static final String TAG = "PiUI";

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
        android.util.Log.i(TAG, "add " + msg.role + " len=" + msg.text.length());
        items.add(msg);
        notifyItemInserted(items.size() - 1);
        if (listener != null) listener.onChanged(items.size());
    }

    /** 流式输出时反复更新最后一条助手消息，避免疯狂插入 item。 */
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

    /** 更新指定位置（工具结果回填时用）。 */
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

    /** 是否只有系统提示（用于空状态判断：纯引导文案仍算「空」）。 */
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
        final TextView text;

        VH(@NonNull View itemView) {
            super(itemView);
            ctx = itemView.getContext();
            root = itemView.findViewById(R.id.bubble_root);
            role = itemView.findViewById(R.id.role);
            text = itemView.findViewById(R.id.text);
        }

        void bind(ChatMessage msg) {
            boolean mine = msg.role == ChatMessage.Role.USER;
            boolean system = msg.role == ChatMessage.Role.SYSTEM;
            int gravity = system ? Gravity.CENTER_HORIZONTAL
                    : (mine ? Gravity.END : Gravity.START);
            root.setGravity(gravity);
            role.setGravity(gravity);
            text.setText(msg.text);

            int padH = dp(12);
            int padV = dp(9);
            text.setPadding(padH, padV, padH, padV);
            text.setMaxWidth((int) (ctx.getResources().getDisplayMetrics().widthPixels
                    * (system ? 0.92f : 0.82f)));

            switch (msg.role) {
                case USER:
                    role.setText("YOU");
                    role.setTextColor(ContextCompat.getColor(ctx, R.color.role_you));
                    text.setBackgroundResource(R.drawable.bg_bubble_user);
                    break;
                case TOOL:
                    role.setText("TOOL");
                    role.setTextColor(ContextCompat.getColor(ctx, R.color.role_tool));
                    text.setBackgroundResource(R.drawable.bg_bubble_tool);
                    break;
                case SYSTEM:
                    role.setText("INFO");
                    role.setTextColor(ContextCompat.getColor(ctx, R.color.role_info));
                    text.setBackgroundResource(R.drawable.bg_bubble_system);
                    text.setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary));
                    break;
                case AGENT:
                default:
                    role.setText("PI");
                    role.setTextColor(ContextCompat.getColor(ctx, R.color.role_pi));
                    text.setBackgroundResource(R.drawable.bg_bubble_agent);
                    text.setTextColor(ContextCompat.getColor(ctx, R.color.text_primary));
                    break;
            }
            if (msg.role != ChatMessage.Role.SYSTEM) {
                text.setTextColor(ContextCompat.getColor(ctx, R.color.text_primary));
            }
        }

        private int dp(int v) {
            return (int) TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP, v, ctx.getResources().getDisplayMetrics());
        }
    }
}
