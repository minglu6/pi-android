package dev.pi.android.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
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

/** 聊天列表适配器。气泡靠 gravity + 圆角 drawable 区分角色，避免复杂 ItemDecoration。 */
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
        android.util.Log.i(TAG, "add " + msg.role + " len=" + msg.text.length() + " :: " + msg.text.replace("\n", "\\n"));
        items.add(msg);
        notifyItemInserted(items.size() - 1);
        if (listener != null) listener.onChanged(items.size());
    }

    /** 流式输出时反复更新最后一条「助手消息」，避免疯狂插入 item。 */
    public boolean upsertTail(ChatMessage msg) {
        if (!items.isEmpty()) {
            int last = items.size() - 1;
            if (items.get(last).role == ChatMessage.Role.AGENT || items.get(last).role == ChatMessage.Role.TOOL) {
                android.util.Log.i(TAG, "upsertTail len=" + msg.text.length());
                items.set(last, msg);
                notifyItemChanged(last);
                return true;
            }
        }
        add(msg);
        return false;
    }

    /** 更新指定位置（工具结果回填时用，避免重复插入条目）。 */
    public void replaceAt(int position, ChatMessage msg) {
        if (position < 0 || position >= items.size()) return;
        android.util.Log.i(TAG, "replaceAt " + position + " " + msg.role + " len=" + msg.text.length());
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
            root.setGravity(mine ? android.view.Gravity.END : android.view.Gravity.START);
            text.setText(msg.text);
            int pad = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 12, ctx.getResources().getDisplayMetrics());
            int radius = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 14, ctx.getResources().getDisplayMetrics());

            GradientDrawable bubble = new GradientDrawable();
            bubble.setCornerRadius(radius);
            switch (msg.role) {
                case USER:
                    bubble.setColor(ContextCompat.getColor(ctx, R.color.bg_bubble_user));
                    role.setText("YOU");
                    break;
                case TOOL:
                    bubble.setColor(ContextCompat.getColor(ctx, R.color.bg_code));
                    role.setText("TOOL");
                    break;
                case SYSTEM:
                    bubble.setColor(Color.TRANSPARENT);
                    role.setText("INFO");
                    break;
                case AGENT:
                default:
                    bubble.setColor(ContextCompat.getColor(ctx, R.color.bg_bubble_agent));
                    role.setText("PI");
                    break;
            }
            text.setBackground(bubble);
            text.setPadding(pad, (int) (pad * 0.7f), pad, (int) (pad * 0.7f));
            text.setTextColor(ContextCompat.getColor(ctx, R.color.text_primary));
            // 气泡最多占屏宽 88%，否则长回复会把角色标签挤没了
            text.setMaxWidth((int) (ctx.getResources().getDisplayMetrics().widthPixels * 0.88f));
        }
    }
}
