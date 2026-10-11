package dev.pi.android.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
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

    public SlashCommandAdapter(Listener listener) {
        this.listener = listener;
    }

    public void replaceAll(List<SlashCommand> next) {
        items.clear();
        if (next != null) items.addAll(next);
        notifyDataSetChanged();
    }

    public SlashCommand first() {
        return items.isEmpty() ? null : items.get(0);
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
            holder.desc.setText(R.string.slash_no_desc);
        } else {
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
