package dev.pi.android.ui;

import org.json.JSONObject;

/** 展示层专用摘要，不修改 Pi 保存的消息或发给模型的上下文。 */
public final class HistoryPresentation {
    private HistoryPresentation() {}

    public static boolean hidden(JSONObject message) {
        return "custom".equals(message.optString("role"))
                && message.has("display") && !message.optBoolean("display", true);
    }

    public static String displayText(JSONObject message, String rawText) {
        // Hindsight 的 custom message 保存完整召回上下文；官方 TUI 通过自定义
        // renderer 只展示摘要。APK 不具备扩展 renderer，按结构化 details 展示。
        if ("custom".equals(message.optString("role"))
                && "hindsight-recall".equals(message.optString("customType"))) {
            JSONObject details = message.optJSONObject("details");
            int count = details == null ? 0 : details.optInt("count", 0);
            return count > 0 ? "已检索 " + count + " 条相关记忆" : "已检索相关记忆";
        }
        return rawText;
    }

    /**
     * Footer status under the composer: connection / activity only.
     * Model + thinking live on the composer chip — never prefix them here.
     */
    public static String statusLabel(String modelStatus, String label) {
        return label == null ? "" : label;
    }
}
