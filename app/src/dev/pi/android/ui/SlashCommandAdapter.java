package dev.pi.android.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

import dev.pi.android.R;
import dev.pi.android.model.SlashCommand;
import dev.pi.android.util.AppFonts;

public class SlashCommandAdapter extends RecyclerView.Adapter<SlashCommandAdapter.VH> {

    public interface Listener {
        void onPick(SlashCommand cmd);
    }

    private final List<SlashCommand> items = new ArrayList<>();
    private final Listener listener;
    private int selectedIndex;

    public SlashCommandAdapter(Listener listener) {
        this.listener = listener;
    }

    public void replaceAll(List<SlashCommand> next) {
        if (next == null) throw new IllegalArgumentException("items");
        items.clear();
        items.addAll(next);
        selectedIndex = 0;
        notifyDataSetChanged();
    }

    public int selectedIndex() {
        return selectedIndex;
    }

    public void moveSelection(int delta) {
        if (items.isEmpty()) {
            throw new IllegalStateException("slash selection empty");
        }
        int n = items.size();
        selectedIndex = Math.floorMod(selectedIndex + delta, n);
        notifyDataSetChanged();
    }

    @Nullable
    public SlashCommand selected() {
        if (items.isEmpty()) return null;
        return items.get(selectedIndex);
    }

    public boolean isEmpty() {
        return items.isEmpty();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_slash_command, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        SlashCommand cmd = items.get(position);
        holder.cmd.setTypeface(AppFonts.mono(holder.itemView.getContext(), false));
        holder.desc.setTypeface(AppFonts.mono(holder.itemView.getContext(), false));
        holder.cmd.setText(cmd.name);
        String desc = cmd.description;
        if (desc == null || desc.isEmpty()) {
            holder.desc.setVisibility(View.GONE);
            holder.desc.setText("");
        } else {
            holder.desc.setVisibility(View.VISIBLE);
            holder.desc.setText(desc);
        }
        holder.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onPick(cmd);
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        final TextView cmd;
        final TextView desc;

        VH(@NonNull View itemView) {
            super(itemView);
            cmd = itemView.findViewById(R.id.slash_cmd);
            desc = itemView.findViewById(R.id.slash_desc);
        }
    }
}
