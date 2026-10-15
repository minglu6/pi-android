package dev.pi.android.ui;

import android.view.View;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;

import java.util.List;

import dev.pi.android.R;
import dev.pi.android.model.SlashCommand;
import dev.pi.android.util.AppFonts;

/**
 * 斜杠命令菜单：旧 slash_panel 外观（11sp 标题、无圆角抬高层、无 dim），
 * 锚在 composer 上方；列表视口约 5 行（Pi autocompleteMaxVisible），
 * 完整过滤结果仍可滚动。点上方区域 / 返回 / 下滑关闭。
 * 标题计数为 Pi SelectList 语义：选中序号 / 当前命中数。
 */
public final class SlashCommandsSheet {

    public interface Listener {
        void onPick(@NonNull SlashCommand cmd);

        void onDismissedByUser();
    }

    private final AppCompatActivity activity;
    private final Listener listener;
    private final SwipeDownLayout panel;
    private final View dismissHit;
    private final TextView title;
    private final MaxHeightRecyclerView list;
    private final SlashCommandAdapter adapter;
    private final OnBackPressedCallback backCallback;

    public SlashCommandsSheet(@NonNull AppCompatActivity activity, @NonNull Listener listener) {
        this.activity = activity;
        this.listener = listener;
        panel = activity.findViewById(R.id.slash_panel);
        dismissHit = activity.findViewById(R.id.slash_dismiss_hit);
        title = activity.findViewById(R.id.slash_sheet_title);
        list = activity.findViewById(R.id.slash_list);
        if (panel == null || dismissHit == null || title == null || list == null) {
            throw new IllegalStateException("slash panel views missing");
        }
        AppFonts.applyTree(panel);
        list.setLayoutManager(new LinearLayoutManager(activity));
        adapter = new SlashCommandAdapter(cmd -> {
            hidePanel();
            listener.onPick(cmd);
        });
        list.setAdapter(adapter);
        panel.setList(list);
        panel.setOnDismiss(this::dismissByUser);
        dismissHit.setOnClickListener(v -> dismissByUser());
        backCallback = new OnBackPressedCallback(false) {
            @Override
            public void handleOnBackPressed() {
                dismissByUser();
            }
        };
        activity.getOnBackPressedDispatcher().addCallback(activity, backCallback);
        hidePanel();
    }

    public void show(@NonNull List<SlashCommand> items) {
        if (items == null) throw new IllegalArgumentException("items");
        if (items.isEmpty()) {
            throw new IllegalArgumentException("slash items empty");
        }
        adapter.replaceAll(items);
        renderTitle();
        capListHeight();
        panel.setTranslationY(0f);
        panel.setVisibility(View.VISIBLE);
        dismissHit.setVisibility(View.VISIBLE);
        backCallback.setEnabled(true);
        list.scrollToPosition(adapter.selectedIndex());
    }

    public boolean moveSelection(int delta) {
        if (!isShowing() || adapter.isEmpty()) return false;
        adapter.moveSelection(delta);
        renderTitle();
        list.scrollToPosition(adapter.selectedIndex());
        return true;
    }

    public void dismiss() {
        hidePanel();
    }

    public boolean isShowing() {
        return panel.getVisibility() == View.VISIBLE;
    }

    public boolean isEmpty() {
        return adapter.isEmpty();
    }

    @Nullable
    public SlashCommand selected() {
        return adapter.selected();
    }

    private void renderTitle() {
        int total = adapter.getItemCount();
        int index = adapter.selectedIndex() + 1;
        title.setText(activity.getString(R.string.slash_hint_count, index, total));
    }

    private void dismissByUser() {
        if (!isShowing()) return;
        hidePanel();
        listener.onDismissedByUser();
    }

    private void hidePanel() {
        panel.setVisibility(View.GONE);
        panel.setTranslationY(0f);
        dismissHit.setVisibility(View.GONE);
        backCallback.setEnabled(false);
    }

    private void capListHeight() {
        int screen = activity.getResources().getDisplayMetrics().heightPixels;
        int width = panel.getWidth();
        if (width <= 0) width = screen;
        title.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int header = title.getMeasuredHeight() + panel.getPaddingTop() + panel.getPaddingBottom();
        int rowHeight = measureSlashRowHeight(width);
        int listPadding = list.getPaddingTop() + list.getPaddingBottom();
        list.setMaxHeight(SheetHeight.slashListMaxPx(screen, header, rowHeight, listPadding));
    }

    /** 测一行 {@code item_slash_command} 的单行高度，供视口 × {@link SheetHeight#SLASH_MAX_VISIBLE}。 */
    private int measureSlashRowHeight(int width) {
        View row = activity.getLayoutInflater().inflate(R.layout.item_slash_command, list, false);
        TextView cmd = row.findViewById(R.id.slash_cmd);
        TextView desc = row.findViewById(R.id.slash_desc);
        if (cmd == null || desc == null) {
            throw new IllegalStateException("slash row views missing");
        }
        cmd.setTypeface(AppFonts.mono(activity, false));
        desc.setTypeface(AppFonts.mono(activity, false));
        cmd.setText("/cmd");
        desc.setText("desc");
        row.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int h = row.getMeasuredHeight();
        if (h <= 0) {
            throw new IllegalStateException("slash row height=" + h);
        }
        return h;
    }
}
