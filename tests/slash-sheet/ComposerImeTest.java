package dev.pi.android.ui;

public final class ComposerImeTest {
    public static void main(String[] args) {
        int send = ComposerIme.IME_ACTION_SEND | ComposerIme.IME_FLAG_NO_EXTRACT_UI;
        int none = ComposerIme.IME_ACTION_NONE | ComposerIme.IME_FLAG_NO_EXTRACT_UI;
        if (ComposerIme.imeOptionsSlash() != send) {
            throw new AssertionError("slash IME must be ACTION_SEND|NO_EXTRACT");
        }
        if (ComposerIme.imeOptionsCompose() != none) {
            throw new AssertionError("compose IME must stay ACTION_NONE|NO_EXTRACT");
        }

        if (!ComposerIme.slashConfirmEditorAction(ComposerIme.IME_ACTION_SEND, false, 0, 0)) {
            throw new AssertionError("IME_ACTION_SEND must confirm slash");
        }
        if (!ComposerIme.slashConfirmEditorAction(ComposerIme.IME_ACTION_DONE, false, 0, 0)) {
            throw new AssertionError("IME_ACTION_DONE must confirm slash");
        }
        if (!ComposerIme.slashConfirmEditorAction(ComposerIme.IME_ACTION_GO, false, 0, 0)) {
            throw new AssertionError("IME_ACTION_GO must confirm slash");
        }
        if (ComposerIme.slashConfirmEditorAction(ComposerIme.IME_ACTION_NONE, false, 0, 0)) {
            throw new AssertionError("ACTION_NONE without key must not confirm");
        }
        if (!ComposerIme.slashConfirmEditorAction(
                ComposerIme.IME_ACTION_UNSPECIFIED, true,
                ComposerIme.KEY_ACTION_DOWN, ComposerIme.KEYCODE_ENTER)) {
            throw new AssertionError("UNSPECIFIED+ENTER must confirm slash");
        }
        if (ComposerIme.slashConfirmEditorAction(
                ComposerIme.IME_ACTION_UNSPECIFIED, true, 1, ComposerIme.KEYCODE_ENTER)) {
            throw new AssertionError("KEY_UP must not confirm");
        }
        if (ComposerIme.slashConfirmEditorAction(3, false, 0, 0)) {
            throw new AssertionError("IME_ACTION_SEARCH must not confirm composer slash");
        }

        if (!ComposerIme.slashConfirmKey(ComposerIme.KEYCODE_ENTER, ComposerIme.KEY_ACTION_DOWN, false)) {
            throw new AssertionError("virtual or physical ENTER must confirm slash");
        }
        if (!ComposerIme.slashConfirmKey(ComposerIme.KEYCODE_NUMPAD_ENTER, ComposerIme.KEY_ACTION_DOWN, false)) {
            throw new AssertionError("NUMPAD_ENTER must confirm slash");
        }
        if (ComposerIme.slashConfirmKey(ComposerIme.KEYCODE_ENTER, ComposerIme.KEY_ACTION_DOWN, true)) {
            throw new AssertionError("Shift+Enter is newline, not slash confirm");
        }
        if (ComposerIme.slashConfirmKey(ComposerIme.KEYCODE_ENTER, 1, false)) {
            throw new AssertionError("KEY_UP must not confirm");
        }

        if (!ComposerIme.slashConfirmNewline("\n", 0, 1)) {
            throw new AssertionError("commitText newline must confirm slash");
        }
        if (!ComposerIme.slashConfirmNewline("ab\n", 2, 3)) {
            throw new AssertionError("trailing newline in insertion must confirm");
        }
        if (ComposerIme.slashConfirmNewline("/model", 0, 6)) {
            throw new AssertionError("plain typing must not confirm");
        }
        if (ComposerIme.slashConfirmNewline(null, 0, 0)) {
            throw new AssertionError("null source is not a newline");
        }
        try {
            ComposerIme.slashConfirmNewline("x", 0, 2);
            throw new AssertionError("bad range must throw");
        } catch (IllegalArgumentException expected) {
            if (!expected.getMessage().contains("newline range")) {
                throw new AssertionError(expected);
            }
        }
        System.out.println("ComposerImeTest ok");
    }
}
