package dev.pi.android.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Base64;
import android.util.LruCache;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
// LayoutInflater used for code blocks
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import dev.pi.android.R;
import dev.pi.android.model.ChatMessage;
import dev.pi.android.util.AppFonts;
import dev.pi.android.util.DiffLite;
import dev.pi.android.util.MarkdownLite;
import dev.pi.android.util.SyntaxLite;

/**
 * 聊天列表：用户右对齐气泡、助手全宽正文 + 小头像、工具卡片、系统居中 chip。
 * 不再展示 INFO/YOU/PI 调试标签。
 *
 * 工具卡片参照 pi-pocket web transcript.js 的 ToolStep / .shell-card：
 * 头部一行元数据（glyph + 名称 + 主题 + 状态），命令/输出为等宽 plaintext
 * （不再是 Markdown 渲染），横向滚动保留长路径；默认折叠（仅摘要行），点按展开。
 * 思考行在 streaming 时展开，结束后折叠。展开状态按 tool id 记忆。
 */
public class MessageAdapter extends RecyclerView.Adapter<MessageAdapter.VH> {

    private static final int TYPE_USER = 0;
    private static final int TYPE_AGENT = 1;
    private static final int TYPE_TOOL = 2;
    private static final int TYPE_SYSTEM = 3;

    /** 输出预览行数（pi-pocket SHELL_LINES 的移动端收缩版）。 */
    private static final int PREVIEW_LINES = 6;
    /** Notice detail collapsed preview lines before「展开全文». */
    private static final int NOTICE_PREVIEW_LINES = 14;
    /** Nested scroll cap inside notice / tool cards (~40% screen). */
    private static final float CARD_SCROLL_MAX_SCREEN = 0.40f;

    // 单线程、有界队列：历史里的原生图片绝不在主线程做 base64/摘要/Bitmap 解码。
    private static final int THUMB_EDGE = 256;
    private static final Handler THUMB_MAIN = new Handler(Looper.getMainLooper());
    private static final ThreadPoolExecutor THUMB_WORKER = new ThreadPoolExecutor(
            1, 1, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(32), runnable -> {
                Thread thread = new Thread(runnable, "attachment-chat-thumbnail");
                thread.setDaemon(true);
                return thread;
            });
    private static final LruCache<String, Bitmap> THUMB_CACHE = new LruCache<String, Bitmap>(4 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap value) { return value.getByteCount(); }
    };
    private static final LruCache<String, Boolean> THUMB_FAILURES = new LruCache<>(32);

    public interface OnMessageChanged {
        void onChanged(int size);
    }

    /** 助手 / 用户消息动作。 */
    public interface AgentActionListener {
        void onRetry(@NonNull ChatMessage message);

        void onRetryOtherModel(@NonNull ChatMessage message);

        void onFork(@NonNull ChatMessage message);

        void onEditResend(@NonNull ChatMessage userMessage);

        /** Optional notice action chip (e.g. /mcp). Default no-op. */
        default void onNoticeAction(@NonNull String command) {}

        /** Pocket !shell row Stop — abort_bash. Default no-op. */
        default void onShellStop(@NonNull String toolId) {}

        /** Fullscreen image preview for chat attachments. Default no-op. */
        default void onImageFullscreen(@NonNull ChatMessage.AttachmentData attachment) {}
    }

    private static final int CODE_FOLD_LINES = 40;

    static final class Seg {
        final boolean code;
        final String lang;
        final String body;
        Seg(boolean code, String lang, String body) {
            this.code = code;
            this.lang = lang;
            this.body = body;
        }
    }

    private static String firstNLines(String s, int n) {
        int seen = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '\n') {
                seen++;
                if (seen >= n) return s.substring(0, i) + "\n…";
            }
        }
        return s;
    }

    private static List<Seg> splitMarkdown(String raw) {
        List<Seg> out = new ArrayList<>();
        if (raw == null || raw.isEmpty()) return out;
        int i = 0;
        final int n = raw.length();
        StringBuilder text = new StringBuilder();
        while (i < n) {
            if (raw.startsWith("```", i)) {
                if (text.length() > 0) {
                    out.add(new Seg(false, "", text.toString()));
                    text.setLength(0);
                }
                int endFence = raw.indexOf("```", i + 3);
                if (endFence < 0) {
                    text.append(raw.substring(i));
                    break;
                }
                int lineEnd = raw.indexOf('\n', i + 3);
                String lang = "";
                int contentStart;
                if (lineEnd >= 0 && lineEnd < endFence) {
                    lang = raw.substring(i + 3, lineEnd).trim();
                    contentStart = lineEnd + 1;
                } else {
                    contentStart = i + 3;
                }
                String code = raw.substring(contentStart, endFence);
                if (code.endsWith("\n")) code = code.substring(0, code.length() - 1);
                out.add(new Seg(true, lang, code));
                i = endFence + 3;
                if (i < n && raw.charAt(i) == '\n') i++;
                continue;
            }
            text.append(raw.charAt(i));
            i++;
        }
        if (text.length() > 0) out.add(new Seg(false, "", text.toString()));
        return out;
    }

    private final List<ChatMessage> items = new ArrayList<>();
    private final OnMessageChanged listener;
    @Nullable private AgentActionListener agentActions;
    /** 工具卡片展开状态，key 为 tool id（legacy 为派生 key）。 */
    private final Map<String, Boolean> toolExpanded = new HashMap<>();
    /** Notice「展开全文」状态，key 为 detail hash. */
    private final Map<String, Boolean> noticeExpanded = new HashMap<>();
    /** Assistant code-block fold state (adapter-scoped so recycle keeps it). */
    private final Map<String, Boolean> codeBlockExpanded = new HashMap<>();

    public MessageAdapter(OnMessageChanged listener) {
        this.listener = listener;
        setHasStableIds(false);
    }

    public void setAgentActionListener(@Nullable AgentActionListener listener) {
        this.agentActions = listener;
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

    /** Update a TOOL row by ToolData.id; returns index or -1. */
    public int replaceToolById(@NonNull String toolId, @NonNull ChatMessage.ToolData data) {
        if (toolId.isEmpty()) return -1;
        for (int i = 0; i < items.size(); i++) {
            ChatMessage m = items.get(i);
            if (m.role == ChatMessage.Role.TOOL && m.tool != null && toolId.equals(m.tool.id)) {
                replaceAt(i, ChatMessage.tool(data, m.timestampMs));
                return i;
            }
        }
        return -1;
    }

    @Nullable
    public ChatMessage.ToolData findTool(@NonNull String toolId) {
        if (toolId.isEmpty()) return null;
        for (ChatMessage m : items) {
            if (m.role == ChatMessage.Role.TOOL && m.tool != null && toolId.equals(m.tool.id)) {
                return m.tool;
            }
        }
        return null;
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
        noticeExpanded.clear();
        codeBlockExpanded.clear();
    }

    public void clear() {
        items.clear();
        toolExpanded.clear();
        noticeExpanded.clear();
        codeBlockExpanded.clear();
        notifyDataSetChanged();
        if (listener != null) listener.onChanged(0);
    }

    public int size() {
        return items.size();
    }

    /** 运行中重连回填：把快照里缺失的历史行插到流式区之前。 */
    public void insertAt(int position, ChatMessage message) {
        items.add(position, message);
        notifyItemInserted(position);
        if (listener != null) listener.onChanged(items.size());
    }

    /** 主题/旋转重建时写入 AgentState 的不可变快照。 */
    public List<ChatMessage> snapshot() {
        List<ChatMessage> copy = new ArrayList<>();
        for (ChatMessage message : items) copy.add(message.copy());
        return copy;
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
        holder.bind(items.get(position), position);
    }

    @Override
    public void onViewRecycled(@NonNull VH holder) {
        holder.cancelThumbnails();
        if (holder.attachmentItems != null) holder.attachmentItems.removeAllViews();
        super.onViewRecycled(holder);
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    /** 仅在后台调用；缓存只存小 Bitmap 和摘要，不持有大 base64 作为 key。 */
    private static Bitmap thumbnail(String data) {
        String key = null;
        try {
            if (Thread.currentThread().isInterrupted()) return null;
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(data.getBytes(StandardCharsets.US_ASCII));
            StringBuilder hex = new StringBuilder(64);
            for (byte b : digest) hex.append(String.format(Locale.US, "%02x", b & 255));
            key = hex.toString();
            Bitmap cached = THUMB_CACHE.get(key);
            if (cached != null) return cached;
            if (THUMB_FAILURES.get(key) != null || Thread.currentThread().isInterrupted()) return null;
            byte[] encoded = Base64.decode(data, Base64.DEFAULT);
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(encoded, 0, encoded.length, options);
            if (options.outWidth <= 0 || options.outHeight <= 0 || options.outWidth > 32768
                    || options.outHeight > 32768 || (long) options.outWidth * options.outHeight > 100_000_000) {
                THUMB_FAILURES.put(key, true);
                return null;
            }
            options.inSampleSize = 1;
            while (Math.max(options.outWidth, options.outHeight) / options.inSampleSize > THUMB_EDGE) {
                options.inSampleSize *= 2;
            }
            if (Thread.currentThread().isInterrupted()) return null;
            options.inJustDecodeBounds = false;
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            Bitmap bitmap = BitmapFactory.decodeByteArray(encoded, 0, encoded.length, options);
            if (bitmap == null) {
                THUMB_FAILURES.put(key, true);
                return null;
            }
            // 部分格式/解码器忽略 inSampleSize；拒收非小图，不让缓存持有大 Bitmap。
            if (bitmap.getWidth() > THUMB_EDGE || bitmap.getHeight() > THUMB_EDGE) {
                bitmap.recycle();
                THUMB_FAILURES.put(key, true);
                return null;
            }
            THUMB_CACHE.put(key, bitmap);
            return bitmap;
        } catch (Exception | OutOfMemoryError ignored) {
            if (key != null) THUMB_FAILURES.put(key, true);
            return null;
        }
    }

    class VH extends RecyclerView.ViewHolder {
        final Context ctx;
        final int type;
        final TextView text;
        LinearLayout attachmentItems;
        Object attachmentBinding = new Object();
        final List<Future<?>> thumbnailTasks = new ArrayList<>();

        // 工具卡片视图（仅 TYPE_TOOL 时非空）
        View toolStateBar;
        LinearLayout toolHeader;
        TextView toolGlyph;
        TextView toolName;
        TextView toolSubject;
        TextView toolDiff;
        TextView toolStatus;
        TextView toolChevron;
        View toolOutputScroll;
        TextView toolCommand;
        TextView toolOutput;
        LinearLayout toolFooter;
        TextView toolToggle;
        TextView toolCopy;
        TextView toolStop;

        // 用户 / 助手可选行头与动作
        TextView userHeader;
        LinearLayout agentBody;
        View agentActionsRow;
        View agentReactions;
        TextView agentActionCopy;
        TextView agentActionRetry;
        TextView agentActionFork;
        TextView agentActionMore;

        // SYSTEM notice card
        View noticeAccent;
        TextView noticeIcon;
        TextView noticeTitle;
        TextView noticeDetail;
        TextView noticeAction;
        TextView noticeExpand;
        androidx.core.widget.NestedScrollView noticeDetailScroll;
        View toolOutputVScroll;

        VH(@NonNull View itemView, int type) {
            super(itemView);
            this.ctx = itemView.getContext();
            this.type = type;
            this.text = itemView.findViewById(R.id.text);
            if (type == TYPE_USER) {
                attachmentItems = itemView.findViewById(R.id.attachment_chat_items);
                userHeader = itemView.findViewById(R.id.user_header);
            }
            if (type == TYPE_AGENT) {
                agentBody = itemView.findViewById(R.id.agent_body);
                agentActionsRow = itemView.findViewById(R.id.agent_actions);
                agentReactions = itemView.findViewById(R.id.agent_reactions);
                agentActionCopy = itemView.findViewById(R.id.agent_action_copy);
                agentActionRetry = itemView.findViewById(R.id.agent_action_retry);
                agentActionFork = itemView.findViewById(R.id.agent_action_fork);
                agentActionMore = itemView.findViewById(R.id.agent_action_more);
            }
            if (type == TYPE_SYSTEM) {
                noticeAccent = itemView.findViewById(R.id.notice_accent);
                noticeIcon = itemView.findViewById(R.id.notice_icon);
                noticeTitle = itemView.findViewById(R.id.notice_title);
                noticeDetail = itemView.findViewById(R.id.notice_detail);
                noticeAction = itemView.findViewById(R.id.notice_action);
                noticeExpand = itemView.findViewById(R.id.notice_expand);
                noticeDetailScroll = itemView.findViewById(R.id.notice_detail_scroll);
                if (noticeDetail != null) {
                    noticeDetail.setTypeface(AppFonts.mono(ctx, false));
                    noticeDetail.setMaxLines(Integer.MAX_VALUE);
                    noticeDetail.setEllipsize(null);
                }
                if (noticeAction != null) noticeAction.setTypeface(AppFonts.mono(ctx, false));
                if (noticeIcon != null) noticeIcon.setTypeface(AppFonts.mono(ctx, true));
            }
            if (type == TYPE_TOOL) {
                toolStateBar = itemView.findViewById(R.id.tool_state_bar);
                toolHeader = itemView.findViewById(R.id.tool_header);
                toolGlyph = itemView.findViewById(R.id.tool_glyph);
                toolName = itemView.findViewById(R.id.tool_name);
                toolSubject = itemView.findViewById(R.id.tool_subject);
                toolDiff = itemView.findViewById(R.id.tool_diff);
                toolStatus = itemView.findViewById(R.id.tool_status);
                toolChevron = itemView.findViewById(R.id.tool_chevron);
                toolOutputScroll = itemView.findViewById(R.id.tool_output_scroll);
                toolOutputVScroll = itemView.findViewById(R.id.tool_output_vscroll);
                toolCommand = itemView.findViewById(R.id.tool_command);
                toolOutput = itemView.findViewById(R.id.tool_output);
                toolFooter = itemView.findViewById(R.id.tool_footer);
                toolToggle = itemView.findViewById(R.id.tool_toggle);
                toolCopy = itemView.findViewById(R.id.tool_copy);
                toolStop = itemView.findViewById(R.id.tool_stop);
                android.graphics.Typeface mono = AppFonts.mono(ctx, false);
                if (toolGlyph != null) toolGlyph.setTypeface(mono);
                if (toolCommand != null) toolCommand.setTypeface(mono);
                if (toolOutput != null) {
                    toolOutput.setTypeface(mono);
                    toolOutput.setMaxLines(Integer.MAX_VALUE);
                    toolOutput.setEllipsize(null);
                }
                if (toolName != null) toolName.setTypeface(mono);
                if (toolSubject != null) toolSubject.setTypeface(mono);
                if (toolStatus != null) toolStatus.setTypeface(mono);
                if (toolDiff != null) toolDiff.setVisibility(View.GONE);
            }
        }

        void bind(ChatMessage msg, int position) {
            int codeBg = ContextCompat.getColor(ctx, R.color.bg_code);
            int codeFg = ContextCompat.getColor(ctx, R.color.pi_amber);
            switch (type) {
                case TYPE_USER:
                    if (userHeader != null) {
                        String when = formatTime(msg.timestampMs);
                        userHeader.setText(when.isEmpty()
                                ? ctx.getString(R.string.user_you)
                                : ctx.getString(R.string.user_you) + " · " + when);
                        userHeader.setVisibility(View.VISIBLE);
                    }
                    text.setText(msg.text);
                    text.setVisibility(msg.text.isEmpty() ? View.GONE : View.VISIBLE);
                    bindAttachments(msg);
                    break;
                case TYPE_TOOL:
                    bindTool(msg, position);
                    break;
                case TYPE_SYSTEM:
                    bindNotice(msg);
                    break;
                case TYPE_AGENT:
                default: {
                    if (msg.streaming) {
                        CharSequence body = MarkdownLite.render(ctx, msg.text, codeBg, codeFg);
                        SpannableStringBuilder sb = new SpannableStringBuilder(body);
                        sb.append(ctx.getString(R.string.streaming_marker));
                        if (text != null) {
                            text.setVisibility(View.VISIBLE);
                            text.setText(sb);
                        }
                        clearExtraAgentViews();
                        if (agentActionsRow != null) agentActionsRow.setVisibility(View.GONE);
                        if (agentReactions != null) agentReactions.setVisibility(View.GONE);
                    } else {
                        bindAgentBody(msg, codeBg, codeFg);
                        bindAgentActions(msg);
                    }
                    break;
                }
            }
            itemView.setOnLongClickListener(v -> {
                if (type == TYPE_USER && MessageAdapter.this.agentActions != null
                        && msg.text != null && !msg.text.isEmpty()) {
                    PopupMenu menu = new PopupMenu(ctx, v);
                    menu.getMenu().add(0, 1, 0, R.string.action_edit_resend);
                    menu.getMenu().add(0, 2, 1, R.string.action_copy);
                    menu.setOnMenuItemClickListener(item -> {
                        if (item.getItemId() == 1) {
                            MessageAdapter.this.agentActions.onEditResend(msg);
                            return true;
                        }
                        if (item.getItemId() == 2) {
                            copyPlain(msg.text);
                            return true;
                        }
                        return false;
                    });
                    menu.show();
                    return true;
                }
                String when = formatTime(msg.timestampMs);
                if (!when.isEmpty()) {
                    Toast.makeText(ctx, when, Toast.LENGTH_SHORT).show();
                }
                return true;
            });
        }

        private void clearExtraAgentViews() {
            if (agentBody == null || text == null) return;
            for (int i = agentBody.getChildCount() - 1; i >= 0; i--) {
                View child = agentBody.getChildAt(i);
                if (child != text) agentBody.removeViewAt(i);
            }
        }

        private void bindAgentBody(ChatMessage msg, int codeBg, int codeFg) {
            if (agentBody == null || text == null) {
                if (text != null) text.setText(MarkdownLite.render(ctx, msg.text, codeBg, codeFg));
                return;
            }
            clearExtraAgentViews();
            List<Seg> segs = splitMarkdown(msg.text == null ? "" : msg.text);
            boolean firstText = true;
            for (int si = 0; si < segs.size(); si++) {
                Seg seg = segs.get(si);
                if (seg.code) {
                    View block = LayoutInflater.from(ctx).inflate(R.layout.item_code_block, agentBody, false);
                    TextView lang = block.findViewById(R.id.code_lang);
                    TextView body = block.findViewById(R.id.code_body);
                    TextView copy = block.findViewById(R.id.code_copy);
                    TextView fold = block.findViewById(R.id.code_fold);
                    String langLabel = SyntaxLite.normalizeLang(seg.lang);
                    lang.setText(langLabel);
                    body.setTypeface(AppFonts.mono(ctx, false));
                    final String key = "code|" + si + "|" + seg.body.hashCode();
                    boolean expanded = Boolean.TRUE.equals(codeBlockExpanded.get(key));
                    int lines = countLines(seg.body);
                    boolean foldable = lines > CODE_FOLD_LINES;
                    String shown = (!foldable || expanded) ? seg.body
                            : firstNLines(seg.body, CODE_FOLD_LINES);
                    int kw = ContextCompat.getColor(ctx, R.color.pi_blue);
                    int str = ContextCompat.getColor(ctx, R.color.status_online);
                    int cmt = ContextCompat.getColor(ctx, R.color.text_muted);
                    int num = ContextCompat.getColor(ctx, R.color.pi_amber);
                    body.setMaxLines(Integer.MAX_VALUE);
                    body.setEllipsize(null);
                    body.setText(SyntaxLite.highlight(shown, langLabel, kw, str, cmt, num));
                    body.setBackgroundColor(codeBg);
                    if (foldable) {
                        fold.setVisibility(View.VISIBLE);
                        fold.setText(expanded ? R.string.code_fold : R.string.code_show_all);
                        fold.setOnClickListener(v -> {
                            codeBlockExpanded.put(key,
                                    !Boolean.TRUE.equals(codeBlockExpanded.get(key)));
                            int pos = getBindingAdapterPosition();
                            if (pos != RecyclerView.NO_POSITION) notifyItemChanged(pos);
                        });
                    } else {
                        fold.setVisibility(View.GONE);
                    }
                    final String copyText = seg.body;
                    copy.setOnClickListener(v -> copyPlain(copyText));
                    agentBody.addView(block);
                } else if (!seg.body.isEmpty()) {
                    if (firstText) {
                        text.setVisibility(View.VISIBLE);
                        text.setText(MarkdownLite.render(ctx, seg.body, codeBg, codeFg));
                        firstText = false;
                    } else {
                        TextView tv = new TextView(ctx);
                        tv.setText(MarkdownLite.render(ctx, seg.body, codeBg, codeFg));
                        tv.setTextColor(ContextCompat.getColor(ctx, R.color.text_primary));
                        tv.setTextSize(14);
                        tv.setTextIsSelectable(true);
                        agentBody.addView(tv);
                    }
                }
            }
            if (firstText) {
                text.setVisibility(View.GONE);
            }
        }

        private void bindAgentActions(ChatMessage msg) {
            if (agentActionsRow == null) return;
            final String copyText = msg.text == null ? "" : msg.text;
            // Always-visible compact action row once reply is complete.
            agentActionsRow.setVisibility(View.VISIBLE);
            if (agentReactions != null) agentReactions.setVisibility(View.GONE);
            if (agentActionCopy != null) {
                agentActionCopy.setOnClickListener(v -> copyPlain(copyText));
            }
            if (agentActionRetry != null) {
                agentActionRetry.setOnClickListener(v -> {
                    if (MessageAdapter.this.agentActions != null) {
                        MessageAdapter.this.agentActions.onRetry(msg);
                    }
                });
            }
            if (agentActionFork != null) {
                agentActionFork.setOnClickListener(v -> {
                    if (MessageAdapter.this.agentActions != null) {
                        MessageAdapter.this.agentActions.onFork(msg);
                    }
                });
            }
            if (agentActionMore != null) {
                agentActionMore.setOnClickListener(v -> {
                    PopupMenu menu = new PopupMenu(ctx, v);
                    menu.getMenu().add(0, 1, 0, R.string.action_copy);
                    menu.getMenu().add(0, 2, 1, R.string.retry_same);
                    menu.getMenu().add(0, 3, 2, R.string.retry_other);
                    menu.getMenu().add(0, 4, 3, R.string.action_fork_short);
                    menu.setOnMenuItemClickListener(item -> {
                        if (MessageAdapter.this.agentActions == null) return false;
                        switch (item.getItemId()) {
                            case 1:
                                copyPlain(copyText);
                                return true;
                            case 2:
                                MessageAdapter.this.agentActions.onRetry(msg);
                                return true;
                            case 3:
                                MessageAdapter.this.agentActions.onRetryOtherModel(msg);
                                return true;
                            case 4:
                                MessageAdapter.this.agentActions.onFork(msg);
                                return true;
                            default:
                                return false;
                        }
                    });
                    menu.show();
                });
            }
        }

        private void copyPlain(String copyText) {
            if (copyText == null || copyText.isEmpty()) return;
            ClipboardManager cm =
                    (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText(
                        ctx.getString(R.string.action_copy), copyText));
            }
            Toast.makeText(ctx, R.string.tool_copied, Toast.LENGTH_SHORT).show();
        }

        void cancelThumbnails() {
            attachmentBinding = new Object();
            for (Future<?> task : thumbnailTasks) task.cancel(true);
            thumbnailTasks.clear();
            THUMB_WORKER.purge();
        }

        private void bindAttachments(ChatMessage message) {
            cancelThumbnails();
            attachmentItems.removeAllViews();
            int count = Math.min(ChatMessage.MAX_ATTACHMENTS, message.attachments.size());
            attachmentItems.setVisibility(count == 0 ? View.GONE : View.VISIBLE);
            for (int i = 0; i < count; i++) {
                ChatMessage.AttachmentData attachment = message.attachments.get(i);
                if (attachment == null) continue;
                View card = "image".equals(attachment.kind) ? imageCard(attachment) : fileChip(attachment);
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                        Math.max(dp(64), Math.min(dp(232), ctx.getResources().getDisplayMetrics().widthPixels - dp(96))),
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                params.topMargin = i == 0 && message.text.isEmpty() ? 0 : dp(8);
                attachmentItems.addView(card, params);
            }
            if (message.attachments.size() > count) {
                TextView more = attachmentLabel(ctx.getString(R.string.attachment_chat_more,
                        message.attachments.size() - count), 12, R.color.attachment_chat_secondary);
                more.setPadding(0, dp(8), 0, 0);
                attachmentItems.addView(more);
            }
        }

        private TextView attachmentLabel(String label, int sp, int color) {
            TextView view = new TextView(ctx);
            view.setText(label);
            view.setTextSize(sp);
            view.setTextColor(ContextCompat.getColor(ctx, color));
            view.setMaxLines(2);
            view.setEllipsize(TextUtils.TruncateAt.END);
            return view;
        }

        private View fileChip(ChatMessage.AttachmentData attachment) {
            LinearLayout row = new LinearLayout(ctx);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(10), dp(8), dp(10), dp(8));
            row.setBackgroundResource(R.drawable.attachment_chat_card);
            ImageView icon = new ImageView(ctx);
            icon.setImageResource(R.drawable.attachment_chat_file);
            row.addView(icon, new LinearLayout.LayoutParams(dp(24), dp(28)));
            LinearLayout labels = new LinearLayout(ctx);
            labels.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            lp.setMarginStart(dp(8));
            row.addView(labels, lp);
            String name = attachment.name.isEmpty() ? ctx.getString(R.string.attachment_chat_file_name) : attachment.name;
            labels.addView(attachmentLabel(name, 14, R.color.attachment_chat_primary));
            labels.addView(attachmentLabel(fileSize(attachment.size), 12, R.color.attachment_chat_secondary));
            // 故意没有点击处理器：附件描述中的 path 不是可执行或可打开的 URI。
            row.setContentDescription(name + "，" + fileSize(attachment.size));
            return row;
        }

        private View imageCard(ChatMessage.AttachmentData attachment) {
            LinearLayout card = new LinearLayout(ctx);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setBackgroundResource(R.drawable.attachment_chat_card);
            FrameLayout preview = new FrameLayout(ctx);
            card.addView(preview, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(156)));
            TextView placeholder = attachmentLabel(ctx.getString(R.string.attachment_chat_image_unavailable),
                    12, R.color.attachment_chat_secondary);
            placeholder.setGravity(Gravity.CENTER);
            preview.addView(placeholder, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            ImageView image = new ImageView(ctx);
            image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            image.setPadding(dp(4), dp(4), dp(4), dp(4));
            String name = attachment.name.isEmpty() ? ctx.getString(R.string.attachment_chat_image_name) : attachment.name;
            image.setContentDescription(name);
            preview.addView(image, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            TextView caption = attachmentLabel(name, 12, R.color.attachment_chat_primary);
            caption.setPadding(dp(8), dp(4), dp(8), dp(6));
            card.addView(caption);
            card.setContentDescription(ctx.getString(R.string.image_fullscreen_cd));
            card.setOnClickListener(v -> {
                if (agentActions != null) agentActions.onImageFullscreen(attachment);
            });
            String data = attachment.imageData;
            if (data == null || data.isEmpty() || data.length() > ChatMessage.MAX_IMAGE_DATA_CHARS) return card;
            Object binding = attachmentBinding;
            WeakReference<VH> holderRef = new WeakReference<>(this);
            WeakReference<ImageView> imageRef = new WeakReference<>(image);
            WeakReference<TextView> placeholderRef = new WeakReference<>(placeholder);
            try {
                thumbnailTasks.add(THUMB_WORKER.submit(() -> {
                    Bitmap bitmap = thumbnail(data);
                    if (Thread.currentThread().isInterrupted() || bitmap == null) return;
                    THUMB_MAIN.post(() -> {
                        VH holder = holderRef.get();
                        ImageView target = imageRef.get();
                        TextView label = placeholderRef.get();
                        if (holder == null || holder.attachmentBinding != binding || target == null) return;
                        target.setImageBitmap(bitmap);
                        if (label != null) label.setVisibility(View.GONE);
                    });
                }));
            } catch (RejectedExecutionException ignored) {
                // 快速滚动/恶意历史超过队列上限时保留小占位卡片，不阻塞 UI。
            }
            return card;
        }

        private String fileSize(long bytes) {
            if (bytes < 1024) return bytes + " B";
            if (bytes < 1024 * 1024) return String.format(Locale.getDefault(), "%.1f KB", bytes / 1024.0);
            if (bytes < 1024L * 1024 * 1024) return String.format(Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024));
            return String.format(Locale.getDefault(), "%.1f GB", bytes / (1024.0 * 1024 * 1024));
        }

        private int dp(int value) { return Math.round(value * ctx.getResources().getDisplayMetrics().density); }

        private void bindNotice(ChatMessage msg) {
            ChatMessage.NoticeData n = msg.notice;
            if (n == null) {
                n = dev.pi.android.util.NoticeLite.fromLegacyText(msg.text);
            }
            int accent = noticeAccentColor(n.level);
            if (noticeAccent != null) noticeAccent.setBackgroundColor(accent);
            if (noticeIcon != null) {
                noticeIcon.setText(noticeGlyph(n.level));
                noticeIcon.setTextColor(accent);
            }
            if (noticeTitle != null) {
                noticeTitle.setText(n.title.isEmpty()
                        ? ctx.getString(R.string.notice_title_info) : n.title);
                noticeTitle.setTextColor(ContextCompat.getColor(ctx, R.color.text_primary));
            }
            if (text != null) text.setVisibility(View.GONE);

            final String detail = n.detail == null ? "" : n.detail;
            final String key = "notice|" + Integer.toHexString(detail.hashCode());
            final int lines = countLines(detail);
            final boolean foldable = lines > NOTICE_PREVIEW_LINES;
            final boolean expanded = Boolean.TRUE.equals(noticeExpanded.get(key));

            if (noticeDetailScroll != null && noticeDetail != null) {
                if (detail.isEmpty()) {
                    noticeDetailScroll.setVisibility(View.GONE);
                    noticeDetail.setVisibility(View.GONE);
                } else {
                    noticeDetailScroll.setVisibility(View.VISIBLE);
                    noticeDetail.setVisibility(View.VISIBLE);
                    noticeDetail.setMaxLines(Integer.MAX_VALUE);
                    noticeDetail.setEllipsize(null);
                    String shown = (!foldable || expanded) ? detail
                            : firstNLines(detail, NOTICE_PREVIEW_LINES);
                    noticeDetail.setText(shown);
                    // Cap expanded long bodies so the list stays scrollable with IME open.
                    int maxH = cardScrollMaxPx();
                    ViewGroup.LayoutParams lp = noticeDetailScroll.getLayoutParams();
                    if (expanded && foldable && lines > NOTICE_PREVIEW_LINES) {
                        lp.height = maxH;
                        noticeDetailScroll.setLayoutParams(lp);
                        noticeDetailScroll.setNestedScrollingEnabled(true);
                    } else {
                        lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
                        noticeDetailScroll.setLayoutParams(lp);
                        noticeDetailScroll.setNestedScrollingEnabled(false);
                    }
                }
            }

            if (noticeExpand != null) {
                if (!foldable || detail.isEmpty()) {
                    noticeExpand.setVisibility(View.GONE);
                    noticeExpand.setOnClickListener(null);
                } else {
                    noticeExpand.setVisibility(View.VISIBLE);
                    noticeExpand.setText(expanded ? R.string.notice_collapse : R.string.notice_expand);
                    noticeExpand.setOnClickListener(v -> {
                        noticeExpanded.put(key, !expanded);
                        int pos = getBindingAdapterPosition();
                        if (pos != RecyclerView.NO_POSITION) notifyItemChanged(pos);
                    });
                }
            }

            if (noticeAction != null) {
                if (n.actionCommand.isEmpty()) {
                    noticeAction.setVisibility(View.GONE);
                    noticeAction.setOnClickListener(null);
                } else {
                    noticeAction.setVisibility(View.VISIBLE);
                    String label = n.actionLabel.isEmpty()
                            ? ctx.getString(R.string.notice_run_slash, n.actionCommand)
                            : n.actionLabel;
                    noticeAction.setText(label);
                    final String cmd = n.actionCommand;
                    noticeAction.setOnClickListener(v -> {
                        if (MessageAdapter.this.agentActions != null) {
                            MessageAdapter.this.agentActions.onNoticeAction(cmd);
                        }
                    });
                }
            }
        }

        private int cardScrollMaxPx() {
            return Math.max(dp(160),
                    Math.round(ctx.getResources().getDisplayMetrics().heightPixels * CARD_SCROLL_MAX_SCREEN));
        }

        private int noticeAccentColor(String level) {
            int res;
            if (ChatMessage.NoticeData.LEVEL_ERROR.equals(level)) res = R.color.notice_error;
            else if (ChatMessage.NoticeData.LEVEL_WARNING.equals(level)) res = R.color.notice_warning;
            else res = R.color.notice_info;
            return ContextCompat.getColor(ctx, res);
        }

        private String noticeGlyph(String level) {
            if (ChatMessage.NoticeData.LEVEL_ERROR.equals(level)) return "!";
            if (ChatMessage.NoticeData.LEVEL_WARNING.equals(level)) return "!";
            return "i";
        }

        /**
         * 工具卡片绑定。结构化 ToolData 直接使用；legacy 文本消息
         * （"⟶ name args" 运行中 / "✓ name\n输出" / "✗ name\n输出"，以及历史回放的
         * 无标记整段文本）解析成同一卡片形态，任何旧消息都有 fallback。
         */
        private void bindTool(ChatMessage msg, int position) {
            ChatMessage.ToolData td = msg.tool;
            final String key;
            String name;
            String command;
            final String output;
            String state;
            long durationMs = 0;
            int additions = -1;
            int deletions = -1;
            String summary = "";

            if (td != null) {
                key = "tool|" + (td.id.isEmpty()
                        ? Integer.toHexString((td.name + "|" + td.command).hashCode())
                        : td.id);
                name = td.name;
                command = td.command;
                output = td.output;
                state = td.state;
                durationMs = td.durationMs;
                additions = td.additions;
                deletions = td.deletions;
                summary = td.summary;
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
                    state = ChatMessage.ToolData.STATE_DONE;
                    name = "";
                    command = "";
                    output = msg.text;
                }
                key = "legacy|" + name + "|" + (command.isEmpty()
                        ? Integer.toHexString(output.hashCode())
                        : command);
            }

            // Tools default COLLAPSED (summary only) — including latest turn — unless the user
            // opened them. Thinking expands while streaming, then collapses when done.
            final boolean isThinkingEarly = td != null
                    && ChatMessage.ToolData.NAME_THINKING.equals(td.name);
            final boolean thinkingLive = isThinkingEarly
                    && ChatMessage.ToolData.STATE_RUNNING.equals(td.state);
            boolean defaultExpand = thinkingLive;
            final boolean expanded = toolExpanded.containsKey(key)
                    ? Boolean.TRUE.equals(toolExpanded.get(key))
                    : defaultExpand;

            if (toolDiff != null) {
                if (additions >= 0 || deletions >= 0) {
                    SpannableStringBuilder diff = new SpannableStringBuilder();
                    if (additions >= 0) {
                        int start = diff.length();
                        diff.append("+").append(String.valueOf(additions));
                        diff.setSpan(new ForegroundColorSpan(
                                        ContextCompat.getColor(ctx, R.color.diff_add)),
                                start, diff.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    }
                    if (deletions >= 0) {
                        if (diff.length() > 0) diff.append(' ');
                        int start = diff.length();
                        diff.append("-").append(String.valueOf(deletions));
                        diff.setSpan(new ForegroundColorSpan(
                                        ContextCompat.getColor(ctx, R.color.diff_del)),
                                start, diff.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    }
                    toolDiff.setText(diff);
                    toolDiff.setVisibility(View.VISIBLE);
                } else {
                    toolDiff.setVisibility(View.GONE);
                }
            }

            final boolean isThinking = ChatMessage.ToolData.NAME_THINKING.equals(name);

            int stateColor = stateColor(state);
            toolStateBar.setBackgroundColor(isThinking
                    ? ContextCompat.getColor(ctx, R.color.text_muted) : stateColor);
            toolGlyph.setText(glyphFor(name));
            toolGlyph.setTextColor(isThinking
                    ? ContextCompat.getColor(ctx, R.color.text_muted) : stateColor);
            if (isThinking) {
                toolName.setText(R.string.tool_thinking);
            } else {
                toolName.setText(name.isEmpty() ? ctx.getString(R.string.tool_legacy_name) : name);
            }

            String subject = isThinking ? "" : firstLine(command);
            toolSubject.setText(subject);
            toolSubject.setVisibility(subject.isEmpty() ? View.GONE : View.VISIBLE);

            String status;
            if (isThinking) {
                if (ChatMessage.ToolData.STATE_RUNNING.equals(state)) {
                    status = ctx.getString(R.string.tool_thinking_running);
                } else if (durationMs > 0) {
                    status = formatDuration(durationMs);
                } else {
                    status = "";
                }
            } else {
                status = !summary.isEmpty() ? summary : statusText(state, durationMs);
            }
            toolStatus.setText(status);
            toolStatus.setVisibility(status.isEmpty() ? View.GONE : View.VISIBLE);
            toolStatus.setTextColor(ChatMessage.ToolData.STATE_PENDING.equals(state)
                    ? ContextCompat.getColor(ctx, R.color.pi_amber)
                    : (isThinking ? ContextCompat.getColor(ctx, R.color.text_muted) : stateColor));
            toolChevron.setText(expanded ? "▾" : "▸");

            final boolean hasOutput = !output.isEmpty();
            boolean showCommand = expanded && !command.isEmpty() && !isThinking;
            boolean showBody = expanded && (hasOutput || showCommand);
            toolOutputScroll.setVisibility(showBody ? View.VISIBLE : View.GONE);
            toolCommand.setVisibility(showCommand ? View.VISIBLE : View.GONE);
            if (showCommand) toolCommand.setText(command);
            if (toolOutputVScroll != null) {
                toolOutputVScroll.setVisibility(expanded && hasOutput ? View.VISIBLE : View.GONE);
            }
            toolOutput.setVisibility(expanded && hasOutput ? View.VISIBLE : View.GONE);
            int outLines = countLines(output);
            if (expanded && hasOutput) {
                // Preview first PREVIEW_LINES when collapsed-into-card; full text when user
                // already expanded the tool row. Cap height so IME-open lists stay scrollable.
                boolean longOut = outLines > PREVIEW_LINES;
                String shownOut = output;
                // Always show full text inside the nested scroll — never hard-clip mid-line.
                boolean isEdit = "edit".equals(name) || "write".equals(name);
                if (isEdit && DiffLite.looksLikeDiff(shownOut)) {
                    toolOutput.setText(DiffLite.render(shownOut,
                            ContextCompat.getColor(ctx, R.color.diff_add),
                            ContextCompat.getColor(ctx, R.color.diff_del),
                            ContextCompat.getColor(ctx, R.color.text_muted),
                            ContextCompat.getColor(ctx, R.color.text_secondary)));
                } else {
                    toolOutput.setText(shownOut);
                    toolOutput.setTextColor(ContextCompat.getColor(ctx,
                            isThinking ? R.color.text_muted
                                    : ChatMessage.ToolData.STATE_ERROR.equals(state)
                                    ? R.color.status_offline
                                    : R.color.text_secondary));
                }
                toolOutput.setMaxLines(Integer.MAX_VALUE);
                toolOutput.setEllipsize(null);
                if (toolOutputVScroll != null) {
                    ViewGroup.LayoutParams lp = toolOutputVScroll.getLayoutParams();
                    if (longOut) {
                        lp.height = cardScrollMaxPx();
                        toolOutputVScroll.setLayoutParams(lp);
                        if (toolOutputVScroll instanceof androidx.core.widget.NestedScrollView) {
                            ((androidx.core.widget.NestedScrollView) toolOutputVScroll)
                                    .setNestedScrollingEnabled(true);
                        }
                    } else {
                        lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
                        toolOutputVScroll.setLayoutParams(lp);
                    }
                }
            }

            boolean expandable = outLines > 0 || !command.isEmpty();
            boolean showStop = "bash".equals(name)
                    && ChatMessage.ToolData.STATE_RUNNING.equals(state)
                    && td != null && !td.id.isEmpty();
            boolean showFooter = (expanded && (expandable || hasOutput)) || showStop;
            toolFooter.setVisibility(showFooter ? View.VISIBLE : View.GONE);
            toolToggle.setVisibility(expandable ? View.VISIBLE : View.GONE);
            if (expandable) {
                if (!expanded && outLines > PREVIEW_LINES) {
                    toolToggle.setText(ctx.getString(R.string.tool_expand_lines, outLines));
                } else {
                    toolToggle.setText(expanded
                            ? ctx.getString(R.string.tool_collapse)
                            : ctx.getString(R.string.tool_expand));
                }
            }
            toolCopy.setVisibility(hasOutput ? View.VISIBLE : View.GONE);
            if (toolStop != null) {
                toolStop.setVisibility(showStop ? View.VISIBLE : View.GONE);
                if (showStop) {
                    final String stopId = td.id;
                    toolStop.setOnClickListener(v -> {
                        if (agentActions != null) agentActions.onShellStop(stopId);
                    });
                } else {
                    toolStop.setOnClickListener(null);
                }
            }

            View.OnClickListener toggle = v -> {
                boolean next = !(toolExpanded.containsKey(key)
                        ? Boolean.TRUE.equals(toolExpanded.get(key))
                        : defaultExpand);
                toolExpanded.put(key, next);
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
                case ChatMessage.ToolData.STATE_PENDING:
                    res = R.color.pi_amber;
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

        /** pi-pocket GLYPHS：thinking "⋯"、bash "$"、read "→"、write "+"、edit "✎"、grep/find "⌕"、subagent "⑂"。 */
        private String glyphFor(String name) {
            if (name == null) return "›";
            switch (name) {
                case ChatMessage.ToolData.NAME_THINKING:
                    return "⋯";
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
                case ChatMessage.ToolData.STATE_PENDING:
                    label = R.string.tool_state_pending;
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
            if (durationMs > 0 && !ChatMessage.ToolData.STATE_RUNNING.equals(state)
                    && !ChatMessage.ToolData.STATE_PENDING.equals(state)) {
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
