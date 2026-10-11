package dev.pi.android.ui;

import android.app.Activity;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;

import dev.pi.android.R;
import dev.pi.android.util.AppFonts;
import dev.pi.android.util.ConnectionProfile;
import dev.pi.android.util.ConnectionProfiles;
import dev.pi.android.util.SecureTokenStore;

/** Add / edit a connection profile (themed sheet, no AlertDialog). */
public final class ProfileEditorSheet {

    public interface Listener {
        void onSaved();
    }

    private final Activity activity;
    private final BottomSheetDialog dialog;
    private final EditText name;
    private final EditText host;
    private final EditText port;
    private final EditText token;
    private final EditText cwd;
    private final TextView remoteWarn;
    private final String profileId;
    private final boolean isNew;

    public ProfileEditorSheet(@NonNull Activity activity,
                              @Nullable ConnectionProfile existing,
                              @NonNull Listener listener) {
        this.activity = activity;
        isNew = existing == null;
        profileId = existing != null ? existing.id : ConnectionProfiles.newId();
        dialog = new BottomSheetDialog(activity, R.style.PickerBottomSheet);
        View root = LayoutInflater.from(activity).inflate(R.layout.sheet_profile_editor, null, false);
        AppFonts.applyTree(root);
        dialog.setContentView(root);

        TextView title = root.findViewById(R.id.profile_editor_title);
        name = root.findViewById(R.id.profile_name);
        host = root.findViewById(R.id.profile_host);
        port = root.findViewById(R.id.profile_port);
        token = root.findViewById(R.id.profile_token);
        cwd = root.findViewById(R.id.profile_cwd);
        remoteWarn = root.findViewById(R.id.profile_remote_warn);
        TextView delete = root.findViewById(R.id.profile_delete);
        TextView save = root.findViewById(R.id.profile_save);

        title.setText(isNew ? R.string.profiles_add_title : R.string.profiles_edit_title);
        if (existing != null) {
            name.setText(existing.name);
            host.setText(existing.host);
            port.setText(String.valueOf(existing.port));
            token.setText(SecureTokenStore.getForProfile(activity, existing.id));
            if (existing.cwd != null) cwd.setText(existing.cwd);
            if (!ConnectionProfiles.DEFAULT_ID.equals(existing.id)) {
                delete.setVisibility(View.VISIBLE);
            }
        } else {
            name.setText("");
            host.setText("100.");
            port.setText("8945");
        }
        updateRemoteWarn();
        TextWatcher watcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                updateRemoteWarn();
            }
            @Override public void afterTextChanged(Editable s) {}
        };
        host.addTextChangedListener(watcher);
        token.addTextChangedListener(watcher);

        delete.setOnClickListener(v -> MaterialDialogs.confirm(activity,
                R.string.profiles_delete, R.string.profiles_delete_confirm,
                android.R.string.ok, () -> {
                    ConnectionProfiles.delete(activity, profileId);
                    dialog.dismiss();
                    listener.onSaved();
                }));
        save.setOnClickListener(v -> {
            if (!saveProfile()) return;
            dialog.dismiss();
            listener.onSaved();
        });

        dialog.setOnShowListener(d -> {
            FrameLayout sheet = dialog.findViewById(com.google.android.material.R.id.design_bottom_sheet);
            if (sheet == null) return;
            sheet.setBackgroundResource(android.R.color.transparent);
            BottomSheetBehavior<FrameLayout> behavior = BottomSheetBehavior.from(sheet);
            behavior.setSkipCollapsed(true);
            behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
            int h = (int) (activity.getResources().getDisplayMetrics().heightPixels * 0.82f);
            behavior.setPeekHeight(h);
            android.view.ViewGroup.LayoutParams lp = sheet.getLayoutParams();
            lp.height = h;
            sheet.setLayoutParams(lp);
        });
    }

    public void show() {
        dialog.show();
    }

    private void updateRemoteWarn() {
        String h = host.getText() == null ? "" : host.getText().toString().trim();
        String t = token.getText() == null ? "" : token.getText().toString().trim();
        boolean remote = !ConnectionProfiles.isLoopbackHost(h);
        boolean needToken = remote && t.isEmpty();
        remoteWarn.setVisibility(needToken ? View.VISIBLE : View.GONE);
    }

    private boolean saveProfile() {
        String n = name.getText() == null ? "" : name.getText().toString().trim();
        String h = host.getText() == null ? "" : host.getText().toString().trim();
        String pStr = port.getText() == null ? "" : port.getText().toString().trim();
        String tok = token.getText() == null ? "" : token.getText().toString().trim();
        String c = cwd.getText() == null ? "" : cwd.getText().toString().trim();
        if (n.isEmpty()) n = h.isEmpty() ? "未命名" : h;
        if (h.isEmpty()) {
            Toast.makeText(activity, R.string.profiles_host_required, Toast.LENGTH_SHORT).show();
            return false;
        }
        int portVal;
        try {
            portVal = Integer.parseInt(pStr);
            if (portVal < 1 || portVal > 65535) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            Toast.makeText(activity, R.string.profiles_port_invalid, Toast.LENGTH_SHORT).show();
            return false;
        }
        if (!ConnectionProfiles.isLoopbackHost(h) && tok.isEmpty()) {
            Toast.makeText(activity, R.string.profiles_remote_need_token, Toast.LENGTH_LONG).show();
            return false;
        }
        ConnectionProfile profile = new ConnectionProfile(profileId, n, h, portVal);
        profile.cwd = c.isEmpty() ? null : c;
        // Preserve last model/session if editing.
        if (!isNew) {
            ConnectionProfile prev = null;
            for (ConnectionProfile x : ConnectionProfiles.list(activity)) {
                if (x.id.equals(profileId)) { prev = x; break; }
            }
            if (prev != null) {
                profile.lastModelId = prev.lastModelId;
                profile.lastSessionPath = prev.lastSessionPath;
            }
        }
        ConnectionProfiles.save(activity, profile);
        SecureTokenStore.setForProfile(activity, profileId, tok);
        if (isNew) ConnectionProfiles.setActive(activity, profileId);
        return true;
    }
}
