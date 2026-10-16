package dev.pi.android.ui;

import androidx.annotation.Nullable;

/**
 * Composer 确认策略：普通输入软键盘 Enter 换行、实体键盘 Enter 发送；
 * 斜杠菜单可见时，IME 回车 / 发送 / 换行一律确认当前高亮项。
 * 常量与 {@code android.view.inputmethod.EditorInfo} / {@code KeyEvent} 对齐，避免测试依赖 android.jar。
 */
final class ComposerIme {

    static final int IME_ACTION_UNSPECIFIED = 0;
    static final int IME_ACTION_NONE = 1;
    static final int IME_ACTION_GO = 2;
    static final int IME_ACTION_SEND = 4;
    static final int IME_ACTION_DONE = 6;
    static final int IME_FLAG_NO_EXTRACT_UI = 0x10000000;
    static final int KEYCODE_ENTER = 66;
    static final int KEYCODE_NUMPAD_ENTER = 160;
    static final int KEY_ACTION_DOWN = 0;

    private ComposerIme() {}

    static int imeOptionsCompose() {
        return IME_ACTION_NONE | IME_FLAG_NO_EXTRACT_UI;
    }

    static int imeOptionsSlash() {
        return IME_ACTION_SEND | IME_FLAG_NO_EXTRACT_UI;
    }

    static boolean isEnterKey(int keyCode) {
        return keyCode == KEYCODE_ENTER || keyCode == KEYCODE_NUMPAD_ENTER;
    }

    /**
     * 斜杠菜单可见时，该 editor action 应确认选中项。
     *
     * @param eventAction {@code KeyEvent.getAction()}；无 KeyEvent 时忽略
     */
    static boolean slashConfirmEditorAction(int actionId, boolean hasEvent, int eventAction, int keyCode) {
        if (hasEvent && eventAction != KEY_ACTION_DOWN) return false;
        int action = actionId & 0xff;
        if (action == IME_ACTION_SEND || action == IME_ACTION_DONE || action == IME_ACTION_GO) {
            return true;
        }
        if (action == IME_ACTION_UNSPECIFIED || action == IME_ACTION_NONE) {
            return hasEvent && isEnterKey(keyCode);
        }
        return false;
    }

    static boolean slashConfirmKey(int keyCode, int eventAction, boolean shiftPressed) {
        return eventAction == KEY_ACTION_DOWN && !shiftPressed && isEnterKey(keyCode);
    }

    /**
     * Multiline EditText 上多数软键盘以 commitText("\\n") 表示回车，而不是 IME_ACTION_SEND。
     */
    static boolean slashConfirmNewline(@Nullable CharSequence source, int start, int end) {
        if (source == null) return false;
        if (start < 0 || end < start || end > source.length()) {
            throw new IllegalArgumentException("newline range start=" + start + " end=" + end
                    + " len=" + source.length());
        }
        for (int i = start; i < end; i++) {
            char c = source.charAt(i);
            if (c == '\n' || c == '\r') return true;
        }
        return false;
    }
}
