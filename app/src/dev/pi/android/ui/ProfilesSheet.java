package dev.pi.android.ui;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.graphics.drawable.GradientDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import dev.pi.android.R;
import dev.pi.android.util.AppFonts;
import dev.pi.android.util.ConnectionProfile;
import dev.pi.android.util.ConnectionProfiles;
import dev.pi.android.util.ProfileConnectTester;
import dev.pi.android.util.SecureTokenStore;

/**
 * Switch / add / edit / probe connection profiles.
 * Each row shows a live status dot (green / red / grey-pulsing).
 */
public final class ProfilesSheet {

    public interface Host {
        void onProfileSwitched(@NonNull ConnectionProfile profile);

        /**
         * Real WebSocket state for the active profile while this activity owns the
         * connection: {@code online}/{@code starting}/{@code connecting}/{@code offline}.
         * Return null when unknown (e.g. Settings) — active row falls back to probe.
         */
        @Nullable
        String activeConnectionState();
    }

    enum Kind { IDLE, CHECKING, OK, FAIL }

    static final class Probe {
        Kind kind = Kind.IDLE;
        long latencyMs;
        @Nullable String detail;
    }

    private final Activity activity;
    private final Host host;
    private final BottomSheetDialog dialog;
    private final Adapter adapter;
    private final SwipeRefreshLayout refresh;
    private final Map<String, Probe> probes = new HashMap<>();
    private final Set<String> inFlight = new HashSet<>();
    private int pendingProbes;
    private boolean shown;

    public ProfilesSheet(@NonNull Activity activity, @NonNull Host host) {
        this.activity = activity;
        this.host = host;
        dialog = new BottomSheetDialog(activity, R.style.PickerBottomSheet);
        View root = LayoutInflater.from(activity).inflate(R.layout.sheet_profiles, null, false);
        AppFonts.applyTree(root);
        dialog.setContentView(root);
        refresh = root.findViewById(R.id.profiles_refresh);
        RecyclerView list = root.findViewById(R.id.profiles_list);
        list.setLayoutManager(new LinearLayoutManager(activity));
        adapter = new Adapter();
        list.setAdapter(adapter);
        refresh.setColorSchemeResources(R.color.pi_blue, R.color.status_online, R.color.status_checking);
        refresh.setOnRefreshListener(this::probeAll);
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
        });
        dialog.setOnDismissListener(d -> shown = false);
    }

    public void show() {
        shown = true;
        refreshList(false);
        dialog.show();
        probeAll();
    }

    /** Called from MainActivity when live connection visual changes while sheet is open. */
    public void onActiveConnectionChanged() {
        if (!shown) return;
        adapter.notifyDataSetChanged();
    }

    private void refreshList(boolean keepProbes) {
        if (!keepProbes) {
            // Keep prior probe results across reopen when refreshing after edit.
        }
        adapter.replace(ConnectionProfiles.list(activity), ConnectionProfiles.active(activity).id);
    }

    private void openEditor(@Nullable ConnectionProfile existing) {
        String activeBefore = ConnectionProfiles.active(activity).id;
        String editingId = existing != null ? existing.id : null;
        new ProfileEditorSheet(activity, existing, () -> {
            refreshList(true);
            // Probe right after save/edit (uses fixed auth: wait for online).
            probeAll();
            ConnectionProfile active = ConnectionProfiles.active(activity);
            // Reconnect only when active profile changed or the active one was edited.
            if (editingId == null
                    || editingId.equals(active.id)
                    || !activeBefore.equals(active.id)) {
                host.onProfileSwitched(active);
            }
        }).show();
    }

    private void probeAll() {
        List<ConnectionProfile> list = ConnectionProfiles.list(activity);
        pendingProbes = 0;
        refresh.setRefreshing(true);
        for (ConnectionProfile p : list) {
            probeOne(p, false);
        }
        if (pendingProbes == 0) refresh.setRefreshing(false);
        adapter.notifyDataSetChanged();
    }

    private void probeOne(@NonNull ConnectionProfile p, boolean toast) {
        if (!inFlight.add(p.id)) return;
        pendingProbes++;
        Probe probe = probes.get(p.id);
        if (probe == null) {
            probe = new Probe();
            probes.put(p.id, probe);
        }
        probe.kind = Kind.CHECKING;
        probe.detail = null;
        adapter.notifyDataSetChanged();
        String token = SecureTokenStore.getForProfile(activity, p.id);
        ProfileConnectTester.test(p.host, p.port, token, (ok, ms, detail) -> {
            inFlight.remove(p.id);
            pendingProbes = Math.max(0, pendingProbes - 1);
            if (pendingProbes == 0) refresh.setRefreshing(false);
            Probe result = probes.get(p.id);
            if (result == null) {
                result = new Probe();
                probes.put(p.id, result);
            }
            if (ok) {
                result.kind = Kind.OK;
                result.latencyMs = ms;
                result.detail = null;
            } else {
                result.kind = Kind.FAIL;
                result.latencyMs = ms;
                result.detail = shortReason(detail);
            }
            adapter.notifyDataSetChanged();
            if (toast) {
                String msg = ok
                        ? activity.getString(R.string.profiles_test_ok, ms)
                        : activity.getString(R.string.profiles_test_fail,
                        result.detail == null ? "?" : result.detail);
                Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show();
            }
        });
    }

    @NonNull
    private static String shortReason(@Nullable String detail) {
        if (detail == null || detail.isEmpty()) return "失败";
        String d = detail.trim();
        if (d.length() > 28) d = d.substring(0, 27) + "…";
        // Prefer Chinese-friendly short forms.
        String low = d.toLowerCase();
        if (low.contains("bad token") || low.contains("鉴权")) return "鉴权失败";
        if (low.contains("timeout") || low.contains("超时") || low.contains("timed out")) return "超时";
        if (low.contains("refused") || low.contains("failed to connect")) return "无法连接";
        if (low.contains("unreachable") || low.contains("network")) return "网络不可达";
        return d;
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
            bindStatus(h, p, active);
            h.itemView.setOnClickListener(v -> {
                ConnectionProfiles.setActive(activity, p.id);
                dialog.dismiss();
                host.onProfileSwitched(p);
            });
            h.edit.setOnClickListener(v -> openEditor(p));
            h.test.setEnabled(!inFlight.contains(p.id));
            h.test.setText(inFlight.contains(p.id) ? "…" : activity.getString(R.string.profiles_test));
            h.test.setOnClickListener(v -> {
                if (inFlight.contains(p.id)) return;
                probeOne(p, true);
            });
        }

        private void bindStatus(@NonNull VH h, @NonNull ConnectionProfile p, boolean active) {
            String live = active ? host.activeConnectionState() : null;
            Kind kind;
            String detail;
            if (live != null) {
                switch (live) {
                    case "online":
                    case "starting":
                        kind = Kind.OK;
                        detail = activity.getString(R.string.profiles_status_live_online);
                        // Prefer probe latency if we have a fresh OK for this id.
                        Probe okProbe = probes.get(p.id);
                        if (okProbe != null && okProbe.kind == Kind.OK && okProbe.latencyMs > 0) {
                            detail = activity.getString(R.string.profiles_status_ok, okProbe.latencyMs)
                                    + " · " + activity.getString(R.string.profiles_status_live_online);
                        }
                        break;
                    case "connecting":
                        kind = Kind.CHECKING;
                        detail = activity.getString(R.string.profiles_status_live_connecting);
                        break;
                    case "offline":
                    default:
                        // Fall through to probe if available (sheet may show better reason).
                        Probe probed = probes.get(p.id);
                        if (probed != null && probed.kind != Kind.IDLE) {
                            kind = probed.kind;
                            detail = statusDetail(probed);
                        } else {
                            kind = Kind.FAIL;
                            detail = activity.getString(R.string.profiles_status_live_offline);
                        }
                        break;
                }
            } else {
                Probe probed = probes.get(p.id);
                if (probed == null || probed.kind == Kind.IDLE) {
                    kind = Kind.CHECKING;
                    detail = activity.getString(R.string.profiles_status_checking);
                } else {
                    kind = probed.kind;
                    detail = statusDetail(probed);
                }
            }
            h.statusDetail.setText(detail);
            applyDot(h, kind);
        }

        @NonNull
        private String statusDetail(@NonNull Probe probed) {
            switch (probed.kind) {
                case OK:
                    return activity.getString(R.string.profiles_status_ok, probed.latencyMs);
                case FAIL:
                    return activity.getString(R.string.profiles_status_fail,
                            probed.detail == null ? "失败" : probed.detail);
                case CHECKING:
                    return activity.getString(R.string.profiles_status_checking);
                default:
                    return "";
            }
        }

        private void applyDot(@NonNull VH h, @NonNull Kind kind) {
            if (h.pulse != null) {
                h.pulse.cancel();
                h.pulse = null;
            }
            h.dot.setAlpha(1f);
            int colorRes;
            switch (kind) {
                case OK:
                    colorRes = R.color.status_online;
                    break;
                case FAIL:
                    colorRes = R.color.status_offline;
                    break;
                case CHECKING:
                default:
                    colorRes = R.color.status_checking;
                    break;
            }
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(ContextCompat.getColor(activity, colorRes));
            h.dot.setBackground(g);
            if (kind == Kind.CHECKING) {
                ObjectAnimator anim = ObjectAnimator.ofFloat(h.dot, View.ALPHA, 1f, 0.28f);
                anim.setDuration(650);
                anim.setRepeatMode(ValueAnimator.REVERSE);
                anim.setRepeatCount(ValueAnimator.INFINITE);
                anim.setInterpolator(new AccelerateDecelerateInterpolator());
                anim.start();
                h.pulse = anim;
            }
        }

        @Override
        public void onViewRecycled(@NonNull VH h) {
            if (h.pulse != null) {
                h.pulse.cancel();
                h.pulse = null;
            }
            h.dot.setAlpha(1f);
            super.onViewRecycled(h);
        }

        @Override public int getItemCount() { return items.size(); }

        class VH extends RecyclerView.ViewHolder {
            final View dot;
            final TextView name;
            final TextView endpoint;
            final TextView statusDetail;
            final TextView test;
            final TextView edit;
            final ImageView check;
            @Nullable ObjectAnimator pulse;
            VH(View itemView) {
                super(itemView);
                dot = itemView.findViewById(R.id.profile_status_dot);
                name = itemView.findViewById(R.id.profile_name);
                endpoint = itemView.findViewById(R.id.profile_endpoint);
                statusDetail = itemView.findViewById(R.id.profile_status_detail);
                test = itemView.findViewById(R.id.profile_test);
                edit = itemView.findViewById(R.id.profile_edit);
                check = itemView.findViewById(R.id.profile_check);
            }
        }
    }
}
