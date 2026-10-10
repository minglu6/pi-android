package dev.pi.android.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import dev.pi.android.R;
import dev.pi.android.model.ChatMessage;
import dev.pi.android.util.MarkdownLite;

/**
 * 聊天列表：用户右对齐气泡、助手全宽正文 + 小头像、工具卡片、系统居中 chip。
 * 不再展示 INFO/YOU/PI 调试标签。
 *
 * 工具卡片参照 pi-pocket web transcript.js 的 ToolStep / .shell-card：
 * 头部一行元数据（glyph + 名称 + 主题 + 状态），命令/输出为等宽 plaintext
 * （不再是 Markdown 渲染），横向滚动保留长路径；默认 6 行预览，可展开/折叠/复制。
 * 展开状态按 tool id 记忆（legacy 文本消息用派生 key），replaceAt/rebind 不串状态。
 */
public class MessageAdapter extends RecyclerView.Adapter<MessageAdapter.VH> {

    private static final int TYPE_USER = 0;
    private static final int TYPE_AGENT = 1;
    private static final int TYPE_TOOL = 2;
    private static final int TYPE_SYSTEM = 3;

    /** 输出预览行数（pi-pocket SHELL_LINES 的移动端收缩版）。 */
    private static final int PREVIEW_LINES = 6;

    public interface OnMessageChanged {
        void onChanged(int size);
    }

    private final List<ChatMessage> items = new ArrayList<>();
    private final OnMessageChanged listener;
    /** 工具卡片展开状态，key 为 tool id（legacy 为派生 key）。 */
    private final Map<String, Boolean> toolExpanded = new HashMap<>();

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
        // get_messages 也会在同一会话结算/重连后刷新；不能因此收起用户正在查看的工具。
        notifyDataSetChanged();
        if (listener != null) listener.onChanged(items.size());
    }

    /** 只在确认切换会话时清除用户的工具展开选择。 */
    public void clearToolExpansions() {
        toolExpanded.clear();
    }

    public void clear() {
        items.clear();
        toolExpanded.clear();
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

    class VH extends RecyclerView.ViewHolder {
        final Context ctx;
        final int type;
        final TextView text;

        // 工具卡片视图（仅 TYPE_TOOL 时非空）
        View toolStateBar;
        LinearLayout toolHeader;
        TextView toolGlyph;
        TextView toolName;
        TextView toolSubject;
        TextView toolStatus;
        TextView toolChevron;
        HorizontalScrollView toolOutputScroll;
        TextView toolCommand;
        TextView toolOutput;
        LinearLayout toolFooter;
        TextView toolToggle;
        TextView toolCopy;

        VH(@NonNull View itemView, int type) {
            super(itemView);
            this.ctx = itemView.getContext();
            this.type = type;
            this.text = itemView.findViewById(R.id.text);
            if (type == TYPE_TOOL) {
                toolStateBar = itemView.findViewById(R.id.tool_state_bar);
                toolHeader = itemView.findViewById(R.id.tool_header);
                toolGlyph = itemView.findViewById(R.id.tool_glyph);
                toolName = itemView.findViewById(R.id.tool_name);
                toolSubject = itemView.findViewById(R.id.tool_subject);
                toolStatus = itemView.findViewById(R.id.tool_status);
                toolChevron = itemView.findViewById(R.id.tool_chevron);
                toolOutputScroll = itemView.findViewById(R.id.tool_output_scroll);
                toolCommand = itemView.findViewById(R.id.tool_command);
                toolOutput = itemView.findViewById(R.id.tool_output);
                toolFooter = itemView.findViewById(R.id.tool_footer);
                toolToggle = itemView.findViewById(R.id.tool_toggle);
                toolCopy = itemView.findViewById(R.id.tool_copy);
            }
        }

        void bind(ChatMessage msg) {
            int codeBg = ContextCompat.getColor(ctx, R.color.bg_code);
            int codeFg = ContextCompat.getColor(ctx, R.color.pi_amber);
            switch (type) {
                case TYPE_USER:
                    text.setText(msg.text);
                    break;
                case TYPE_TOOL:
                    bindTool(msg);
                    break;
                case TYPE_SYSTEM:
                    text.setText(msg.text);
                    break;
                case TYPE_AGENT:
                default: {
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
            itemView.setOnLongClickListener(v -> {
                String when = formatTime(msg.timestampMs);
                if (!when.isEmpty()) {
                    Toast.makeText(ctx, when, Toast.LENGTH_SHORT).show();
                }
                return true;
            });
        }

        /**
         * 工具卡片绑定。结构化 ToolData 直接使用；legacy 文本消息
         * （"⟶ name args" 运行中 / "✓ name\n输出" / "✗ name\n输出"，以及历史回放的
         * 无标记整段文本）解析成同一卡片形态，任何旧消息都有 fallback。
         */
        private void bindTool(ChatMessage msg) {
            ChatMessage.ToolData td = msg.tool;
            final String key;
            String name;
            String command;
            final String output;
            String state;
            long durationMs = 0;

            if (td != null) {
                key = "tool|" + (td.id.isEmpty()
                        ? Integer.toHexString((td.name + "|" + td.command).hashCode())
                        : td.id);
                name = td.name;
                command = td.command;
                output = td.output;
                state = td.state;
                durationMs = td.durationMs;
            } else {
                String head = msg.text;
                String rest = "";
                int nl = msg.text.indexOf('\n');
                if (nl >= 0) {
                    head = msg.text.substring(0, nl);
                    rest = msg.text.substring(nl + 1);
                }
                if (head.startsWith("✓") || head.startsWith("✗") || head.startsWith("⟶")) {
                    state = head.startsWith("✗") ? ChatMessage.ToolData.STATE_ERROR
                            : head.startsWith("⟶") ? ChatMessage.ToolData.STATE_RUNNING
                            : ChatMessage.ToolData.STATE_DONE;
                    head = head.substring(1).trim();
                    int sp = head.indexOf(' ');
                    name = sp < 0 ? head : head.substring(0, sp);
                    command = sp < 0 ? "" : head.substring(sp + 1).trim();
                    output = rest;
                } else {
                    // 历史回放等无标记文本：整体当作输出。
                    state = ChatMessage.ToolData.STATE_DONE;
                    name = "";
                    command = "";
                    output = msg.text;
                }
                key = "legacy|" + name + "|" + (command.isEmpty()
                        ? Integer.toHexString(output.hashCode())
                        : command);
            }

            final boolean expanded = Boolean.TRUE.equals(toolExpanded.get(key));

            int stateColor = stateColor(state);
            toolStateBar.setBackgroundColor(stateColor);
            toolGlyph.setText(glyphFor(name));
            toolGlyph.setTextColor(stateColor);
            toolName.setText(name.isEmpty() ? ctx.getString(R.string.tool_legacy_name) : name);

            String subject = firstLine(command);
            toolSubject.setText(subject);
            toolSubject.setVisibility(subject.isEmpty() ? View.GONE : View.VISIBLE);

            toolStatus.setText(statusText(state, durationMs));
            toolStatus.setTextColor(stateColor);
            toolChevron.setText(expanded ? "▾" : "▸");

            final boolean hasOutput = !output.isEmpty();
            boolean showCommand = expanded && !command.isEmpty();
            toolOutputScroll.setVisibility(hasOutput || showCommand ? View.VISIBLE : View.GONE);
            toolCommand.setVisibility(showCommand ? View.VISIBLE : View.GONE);
            if (showCommand) toolCommand.setText(command);
            toolOutput.setVisibility(hasOutput ? View.VISIBLE : View.GONE);
            if (hasOutput) {
                toolOutput.setText(output);
                toolOutput.setMaxLines(expanded ? Integer.MAX_VALUE : PREVIEW_LINES);
                toolOutput.setTextColor(ContextCompat.getColor(ctx,
                        ChatMessage.ToolData.STATE_ERROR.equals(state)
                                ? R.color.status_offline
                                : R.color.text_secondary));
            }

            int outLines = countLines(output);
            boolean expandable = outLines > PREVIEW_LINES || !command.isEmpty();
            toolFooter.setVisibility(expandable || hasOutput ? View.VISIBLE : View.GONE);
            toolToggle.setVisibility(expandable ? View.VISIBLE : View.GONE);
            if (expandable) {
                toolToggle.setText(expanded
                        ? ctx.getString(R.string.tool_collapse)
                        : outLines > PREVIEW_LINES
                        ? ctx.getString(R.string.tool_expand_lines, outLines)
                        : ctx.getString(R.string.tool_expand));
            }
            toolCopy.setVisibility(hasOutput ? View.VISIBLE : View.GONE);

            View.OnClickListener toggle = v -> {
                toolExpanded.put(key, !Boolean.TRUE.equals(toolExpanded.get(key)));
                int pos = getBindingAdapterPosition();
                if (pos != RecyclerView.NO_POSITION) notifyItemChanged(pos);
            };
            toolHeader.setOnClickListener(toggle);
            toolToggle.setOnClickListener(toggle);
            toolCopy.setOnClickListener(v -> {
                ClipboardManager cm =
                        (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) {
                    cm.setPrimaryClip(ClipData.newPlainText(
                            ctx.getString(R.string.tool_copy_output), output));
                }
                Toast.makeText(ctx, R.string.tool_copied, Toast.LENGTH_SHORT).show();
            });
        }

        private int stateColor(String state) {
            int res;
            switch (state) {
                case ChatMessage.ToolData.STATE_RUNNING:
                    res = R.color.pi_blue;
                    break;
                case ChatMessage.ToolData.STATE_ERROR:
                    res = R.color.status_offline;
                    break;
                case ChatMessage.ToolData.STATE_CANCELLED:
                    res = R.color.text_muted;
                    break;
                case ChatMessage.ToolData.STATE_DONE:
                default:
                    res = R.color.status_online;
                    break;
            }
            return ContextCompat.getColor(ctx, res);
        }

        /** pi-pocket GLYPHS：bash "$"、read "→"、write "+"、edit "✎"、grep/find "⌕"、subagent "⑂"。 */
        private String glyphFor(String name) {
            if (name == null) return "›";
            switch (name) {
                case "bash":
                    return "$";
                case "read":
                    return "→";
                case "write":
                    return "+";
                case "edit":
                    return "✎";
                case "grep":
                case "find":
                    return "⌕";
                case "subagent":
                    return "⑂";
                default:
                    return "›";
            }
        }

        private String statusText(String state, long durationMs) {
            int label;
            switch (state) {
                case ChatMessage.ToolData.STATE_RUNNING:
                    label = R.string.tool_state_running;
                    break;
                case ChatMessage.ToolData.STATE_ERROR:
                    label = R.string.tool_state_error;
                    break;
                case ChatMessage.ToolData.STATE_CANCELLED:
                    label = R.string.tool_state_cancelled;
                    break;
                case ChatMessage.ToolData.STATE_DONE:
                default:
                    label = R.string.tool_state_done;
                    break;
            }
            String s = ctx.getString(label);
            if (durationMs > 0 && !ChatMessage.ToolData.STATE_RUNNING.equals(state)) {
                s += " · " + formatDuration(durationMs);
            }
            return s;
        }

        private String formatDuration(long ms) {
            if (ms < 1000) return ms + " ms";
            if (ms < 60_000) return String.format(Locale.US, "%.1f s", ms / 1000f);
            return String.format(Locale.US, "%d:%02d", ms / 60_000, (ms % 60_000) / 1000);
        }

        private String firstLine(String s) {
            if (s == null || s.isEmpty()) return "";
            int nl = s.indexOf('\n');
            return nl < 0 ? s : s.substring(0, nl);
        }

        private int countLines(String s) {
            if (s == null || s.isEmpty()) return 0;
            int n = 1;
            for (int i = 0; i < s.length(); i++) {
                if (s.charAt(i) == '\n') n++;
            }
            return n;
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
