package dev.pi.android.util;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.List;

import dev.pi.android.model.ChatMessage;

/** Build a Markdown transcript from chat messages (client-side; no RPC required). */
public final class ExportMarkdown {
    private ExportMarkdown() {}

    @NonNull
    public static String from(@NonNull List<ChatMessage> messages, @Nullable String title) {
        StringBuilder sb = new StringBuilder();
        if (title != null && !title.isEmpty()) {
            sb.append("# ").append(title.trim()).append("\n\n");
        }
        for (ChatMessage m : messages) {
            if (m == null) continue;
            switch (m.role) {
                case USER:
                    sb.append("### 你\n\n");
                    if (m.text != null && !m.text.isEmpty()) sb.append(m.text).append("\n\n");
                    break;
                case AGENT:
                    sb.append("### Pi\n\n");
                    if (m.text != null && !m.text.isEmpty()) sb.append(m.text).append("\n\n");
                    break;
                case TOOL:
                    appendTool(sb, m);
                    break;
                case SYSTEM:
                    if (m.text != null && !m.text.isEmpty()) {
                        sb.append("> ").append(m.text.replace("\n", "\n> ")).append("\n\n");
                    }
                    break;
                default:
                    break;
            }
        }
        return sb.toString().trim() + "\n";
    }

    private static void appendTool(StringBuilder sb, ChatMessage m) {
        ChatMessage.ToolData t = m.tool;
        boolean thinking = t != null && ChatMessage.ToolData.NAME_THINKING.equals(t.name);
        String name = t == null ? "tool"
                : thinking ? "思考" : t.name;
        String command = t != null ? t.command : "";
        String output = t != null ? t.output : (m.text == null ? "" : m.text);
        String state = t != null ? t.state : "";
        sb.append("<details>\n<summary>");
        sb.append(escape(name));
        if (!thinking && !command.isEmpty()) sb.append(": ").append(escape(firstLine(command)));
        if (!thinking && !state.isEmpty()) sb.append(" (").append(escape(state)).append(')');
        sb.append("</summary>\n\n");
        if (!thinking && !command.isEmpty()) {
            sb.append("```bash\n").append(command);
            if (!command.endsWith("\n")) sb.append('\n');
            sb.append("```\n\n");
        }
        if (!output.isEmpty()) {
            sb.append("```\n").append(output);
            if (!output.endsWith("\n")) sb.append('\n');
            sb.append("```\n\n");
        }
        sb.append("</details>\n\n");
    }

    private static String firstLine(String s) {
        int n = s.indexOf('\n');
        return n < 0 ? s : s.substring(0, n);
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
