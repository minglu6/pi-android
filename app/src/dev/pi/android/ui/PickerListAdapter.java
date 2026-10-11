package dev.pi.android.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

import dev.pi.android.R;
import dev.pi.android.model.PickerItem;
import dev.pi.android.util.AppFonts;

public class PickerListAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    public interface Listener {
        void onPick(PickerItem item);
    }

    private static final int TYPE_HEADER = 0;
    private static final int TYPE_ROW = 1;

    private final List<PickerItem> items = new ArrayList<>();
    private final Listener listener;

    public PickerListAdapter(Listener listener) {
        this.listener = listener;
    }

    public void replaceAll(List<PickerItem> next) {
        items.clear();
        if (next != null) items.addAll(next);
        notifyDataSetChanged();
    }

    public int indexOfSelected() {
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).kind == PickerItem.Kind.ROW && items.get(i).selected) return i;
        }
        return -1;
    }

    @Override
    public int getItemViewType(int position) {
        return items.get(position).kind == PickerItem.Kind.HEADER ? TYPE_HEADER : TYPE_ROW;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inf = LayoutInflater.from(parent.getContext());
        if (viewType == TYPE_HEADER) {
            return new HeaderVH(inf.inflate(R.layout.item_picker_header, parent, false));
        }
        return new RowVH(inf.inflate(R.layout.item_picker_row, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        PickerItem item = items.get(position);
        if (holder instanceof HeaderVH) {
            ((HeaderVH) holder).bind(item);
        } else if (holder instanceof RowVH) {
            ((RowVH) holder).bind(item, listener);
        }
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class HeaderVH extends RecyclerView.ViewHolder {
        final TextView text;

        HeaderVH(@NonNull View itemView) {
            super(itemView);
            text = (TextView) itemView;
        }

        void bind(PickerItem item) {
            text.setText(item.header);
        }
    }

    static class RowVH extends RecyclerView.ViewHolder {
        final TextView title;
        final TextView subtitle;
        final ImageView check;

        RowVH(@NonNull View itemView) {
            super(itemView);
            title = itemView.findViewById(R.id.picker_title_text);
            subtitle = itemView.findViewById(R.id.picker_subtitle);
            check = itemView.findViewById(R.id.picker_check);
        }

        void bind(PickerItem item, Listener listener) {
            // Titles + Chinese subtitles: AppFonts (Meslo+CJK fallback, or system mono).
            title.setTypeface(AppFonts.mono(itemView.getContext(), false));
            title.setText(item.title);
            if (item.subtitle == null || item.subtitle.isEmpty()) {
                subtitle.setVisibility(View.GONE);
            } else {
                subtitle.setVisibility(View.VISIBLE);
                subtitle.setTypeface(AppFonts.mono(itemView.getContext(), false));
                subtitle.setText(item.subtitle);
            }
            check.setVisibility(item.selected ? View.VISIBLE : View.GONE);
            itemView.setOnClickListener(v -> {
                if (listener != null) listener.onPick(item);
            });
        }
    }
}
