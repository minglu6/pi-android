package dev.pi.android.ui;

import android.app.Activity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;

import java.util.ArrayList;
import java.util.List;

import dev.pi.android.R;
import dev.pi.android.util.AppFonts;
import dev.pi.android.util.ConnectionProfile;
import dev.pi.android.util.ConnectionProfiles;
import dev.pi.android.util.ProfileConnectTester;
import dev.pi.android.util.SecureTokenStore;

/** Switch / add / edit / test connection profiles. */
public final class ProfilesSheet {

    public interface Host {
        void onProfileSwitched(@NonNull ConnectionProfile profile);
    }

    private final Activity activity;
    private final Host host;
    private final BottomSheetDialog dialog;
    private final Adapter adapter;

    public ProfilesSheet(@NonNull Activity activity, @NonNull Host host) {
        this.activity = activity;
        this.host = host;
        dialog = new BottomSheetDialog(activity, R.style.PickerBottomSheet);
        View root = LayoutInflater.from(activity).inflate(R.layout.sheet_profiles, null, false);
        AppFonts.applyTree(root);
        dialog.setContentView(root);
        RecyclerView list = root.findViewById(R.id.profiles_list);
        list.setLayoutManager(new LinearLayoutManager(activity));
        adapter = new Adapter();
        list.setAdapter(adapter);
        root.findViewById(R.id.profiles_add).setOnClickListener(v -> openEditor(null));
        dialog.setOnShowListener(d -> {
            FrameLayout sheet = dialog.findViewById(com.google.android.material.R.id.design_bottom_sheet);
            if (sheet == null) return;
            sheet.setBackgroundResource(android.R.color.transparent);
            BottomSheetBehavior<FrameLayout> behavior = BottomSheetBehavior.from(sheet);
            behavior.setSkipCollapsed(true);
            behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
            int h = (int) (activity.getResources().getDisplayMetrics().heightPixels * 0.72f);
            behavior.setPeekHeight(h);
            android.view.ViewGroup.LayoutParams lp = sheet.getLayoutParams();
            lp.height = h;
            sheet.setLayoutParams(lp);
            refresh();
        });
    }

    public void show() {
        refresh();
        dialog.show();
    }

    private void refresh() {
        adapter.replace(ConnectionProfiles.list(activity), ConnectionProfiles.active(activity).id);
    }

    private void openEditor(ConnectionProfile existing) {
        new ProfileEditorSheet(activity, existing, () -> {
            refresh();
            host.onProfileSwitched(ConnectionProfiles.active(activity));
        }).show();
    }

    private final class Adapter extends RecyclerView.Adapter<Adapter.VH> {
        private final List<ConnectionProfile> items = new ArrayList<>();
        private String activeId = "";

        void replace(List<ConnectionProfile> next, String active) {
            items.clear();
            items.addAll(next);
            activeId = active == null ? "" : active;
            notifyDataSetChanged();
        }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_profile_row, parent, false);
            AppFonts.applyTree(v);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int position) {
            ConnectionProfile p = items.get(position);
            h.name.setText(p.name);
            h.endpoint.setText(p.host + ":" + p.port
                    + (p.isLocalLoopback() ? " · 本机"
                    : (p.isTailscaleHost() ? " · Tailscale" : "")));
            boolean active = p.id.equals(activeId);
            h.check.setVisibility(active ? View.VISIBLE : View.GONE);
            h.itemView.setOnClickListener(v -> {
                ConnectionProfiles.setActive(activity, p.id);
                dialog.dismiss();
                host.onProfileSwitched(p);
            });
            h.edit.setOnClickListener(v -> openEditor(p));
            h.test.setOnClickListener(v -> {
                h.test.setEnabled(false);
                h.test.setText("…");
                String token = SecureTokenStore.getForProfile(activity, p.id);
                ProfileConnectTester.test(p.host, p.port, token, (ok, ms, detail) -> {
                    h.test.setEnabled(true);
                    h.test.setText(activity.getString(R.string.profiles_test));
                    String msg = ok
                            ? activity.getString(R.string.profiles_test_ok, ms)
                            : activity.getString(R.string.profiles_test_fail,
                            detail == null ? "?" : detail);
                    Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show();
                });
            });
        }

        @Override public int getItemCount() { return items.size(); }

        class VH extends RecyclerView.ViewHolder {
            final TextView name;
            final TextView endpoint;
            final TextView test;
            final TextView edit;
            final ImageView check;
            VH(View itemView) {
                super(itemView);
                name = itemView.findViewById(R.id.profile_name);
                endpoint = itemView.findViewById(R.id.profile_endpoint);
                test = itemView.findViewById(R.id.profile_test);
                edit = itemView.findViewById(R.id.profile_edit);
                check = itemView.findViewById(R.id.profile_check);
            }
        }
    }
}
