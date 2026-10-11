package dev.pi.android.ui;

import android.app.Activity;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;

import dev.pi.android.R;
import dev.pi.android.util.AppFonts;

/**
 * Themed multi-line editor bottom sheet (会话说明 etc.).
 * Same chrome as {@link PickerSheet} / {@link FindSheet}: drag handle, Meslo, Tokyo Night.
 */
public final class EditorSheet {

    public interface Listener {
        void onSave(@NonNull String text);

        void onClear();
    }

    private final Activity activity;
    private final BottomSheetDialog dialog;
    private final EditText body;
    private final TextView charHint;

    public EditorSheet(@NonNull Activity activity,
                       @StringRes int titleRes,
                       @StringRes int hintRes,
                       @Nullable String prefill,
                       @NonNull Listener listener) {
        this.activity = activity;
        dialog = new BottomSheetDialog(activity, R.style.PickerBottomSheet);
        View root = LayoutInflater.from(activity).inflate(R.layout.sheet_editor, null, false);
        AppFonts.applyTree(root);
        dialog.setContentView(root);

        TextView title = root.findViewById(R.id.editor_title);
        body = root.findViewById(R.id.editor_body);
        charHint = root.findViewById(R.id.editor_char_hint);
        TextView clear = root.findViewById(R.id.editor_clear);
        TextView save = root.findViewById(R.id.editor_save);

        title.setText(titleRes);
        // Hint lives on the EditText only — no redundant subtitle under the title.
        body.setHint(hintRes);
        body.setTypeface(AppFonts.mono(activity, false));
        if (prefill != null && !prefill.isEmpty()) {
            body.setText(prefill);
            body.setSelection(body.getText().length());
        }
        updateCharHint();

        body.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                updateCharHint();
            }
            @Override public void afterTextChanged(Editable s) {}
        });

        clear.setOnClickListener(v -> {
            body.setText("");
            listener.onClear();
            dialog.dismiss();
        });
        save.setOnClickListener(v -> {
            String text = body.getText() == null ? "" : body.getText().toString().trim();
            listener.onSave(text);
            dialog.dismiss();
        });

        dialog.setOnShowListener(d -> {
            FrameLayout sheet = dialog.findViewById(com.google.android.material.R.id.design_bottom_sheet);
            if (sheet == null) return;
            sheet.setBackgroundResource(android.R.color.transparent);
            BottomSheetBehavior<FrameLayout> behavior = BottomSheetBehavior.from(sheet);
            behavior.setSkipCollapsed(true);
            behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
            int h = (int) (activity.getResources().getDisplayMetrics().heightPixels * 0.78f);
            behavior.setPeekHeight(h);
            android.view.ViewGroup.LayoutParams lp = sheet.getLayoutParams();
            lp.height = h;
            sheet.setLayoutParams(lp);
            body.requestFocus();
            InputMethodManager imm = (InputMethodManager)
                    activity.getSystemService(Activity.INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(body, InputMethodManager.SHOW_IMPLICIT);
        });
    }

    public void show() {
        dialog.show();
    }

    private void updateCharHint() {
        if (charHint == null) return;
        int n = body.getText() == null ? 0 : body.getText().length();
        charHint.setText(activity.getString(R.string.editor_char_hint, n));
    }
}
