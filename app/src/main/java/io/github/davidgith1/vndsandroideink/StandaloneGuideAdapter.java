package io.github.davidgith1.vndsandroideink;

import android.graphics.Typeface;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

/** Backs the Guides page's list: one row per standalone guide entry. Reuses item_vn.xml as-is --
 * same icon/title/vndbInfo/menu/delete shape as a real library row, just without a resume badge,
 * engine badge, or play time (entries have no reader session or engine at all). */
public class StandaloneGuideAdapter extends RecyclerView.Adapter<StandaloneGuideAdapter.EntryVH> {

    public interface Listener {
        void onEntryClick(StandaloneGuideManager.Entry entry);
        void onDeleteClick(StandaloneGuideManager.Entry entry);
        void onMenuClick(StandaloneGuideManager.Entry entry);
    }

    private final List<StandaloneGuideManager.Entry> entries = new ArrayList<>();
    private final Listener listener;

    public StandaloneGuideAdapter(Listener listener) {
        this.listener = listener;
    }

    public void setEntries(List<StandaloneGuideManager.Entry> newEntries) {
        entries.clear();
        entries.addAll(newEntries);
        notifyDataSetChanged();
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    @NonNull
    @Override
    public EntryVH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_vn, parent, false);
        return new EntryVH(view);
    }

    @Override
    public void onBindViewHolder(@NonNull EntryVH holder, int position) {
        StandaloneGuideManager.Entry entry = entries.get(position);
        String title = entry.title;
        holder.title.setText(buildTitleText(entry));
        holder.icon.setImageResource(R.drawable.ic_book);
        holder.engineLabel.setVisibility(View.GONE);
        holder.playTime.setVisibility(View.GONE);

        if (entry.meta != null) {
            holder.vndbInfo.setVisibility(View.VISIBLE);
            holder.vndbInfo.setText(formatVndbInfo(entry.meta));
        } else {
            holder.vndbInfo.setVisibility(View.GONE);
        }

        holder.deleteButton.setContentDescription(holder.itemView.getContext().getString(R.string.delete_entry_desc, title));
        holder.vnMenuButton.setContentDescription(holder.itemView.getContext().getString(R.string.standalone_guide_menu_desc, title));
        holder.itemView.setOnClickListener(v -> {
            if (listener != null) {
                listener.onEntryClick(entry);
            }
        });
        holder.deleteButton.setOnClickListener(v -> {
            if (listener != null) {
                listener.onDeleteClick(entry);
            }
        });
        holder.vnMenuButton.setOnClickListener(v -> {
            if (listener != null) {
                listener.onMenuClick(entry);
            }
        });
    }

    @Override
    public int getItemCount() {
        return entries.size();
    }

    /** Same title-row merge as {@code LibraryAdapter.buildTitleText} -- puts VNDB's alt title on the
     * title's own row, smaller and italic, instead of a separate row. */
    private static CharSequence buildTitleText(StandaloneGuideManager.Entry entry) {
        if (entry.meta == null) {
            return entry.title;
        }
        String alt = entry.meta.altTitle;
        if (alt == null || alt.isEmpty() || alt.equals(entry.title)) {
            return entry.title;
        }
        SpannableStringBuilder sb = new SpannableStringBuilder(entry.title);
        int start = sb.length();
        sb.append("  ").append(alt);
        sb.setSpan(new RelativeSizeSpan(0.72f), start, sb.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.setSpan(new StyleSpan(Typeface.ITALIC), start, sb.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        return sb;
    }

    /** Same formatting as LibraryAdapter's own VNDB info line, kept in sync deliberately. */
    private static String formatVndbInfo(VndbMeta meta) {
        List<String> parts = new ArrayList<>();
        if (meta.released != null && !meta.released.isEmpty()) {
            parts.add(meta.released);
        }
        if (meta.rating != null) {
            parts.add("★" + String.format(java.util.Locale.US, "%.1f", meta.rating / 10.0));
        }
        if (meta.length != null) {
            String label = VndbMeta.lengthLabel(meta.length);
            if (meta.lengthMinutes != null) {
                label += " (~" + formatHours(meta.lengthMinutes) + ")";
            }
            parts.add(label);
        } else if (meta.lengthMinutes != null) {
            parts.add("~" + formatHours(meta.lengthMinutes));
        }
        return TextUtils.join(" · ", parts);
    }

    private static String formatHours(int minutes) {
        int hours = minutes / 60;
        int mins = minutes % 60;
        return hours > 0 ? (mins > 0 ? hours + "h " + mins + "m" : hours + "h") : mins + "m";
    }

    static class EntryVH extends RecyclerView.ViewHolder {
        final ImageView icon;
        final TextView title;
        final TextView engineLabel;
        final TextView playTime;
        final TextView vndbInfo;
        final TextView vnMenuButton;
        final TextView deleteButton;

        EntryVH(View itemView) {
            super(itemView);
            icon = itemView.findViewById(R.id.icon);
            title = itemView.findViewById(R.id.title);
            engineLabel = itemView.findViewById(R.id.engineLabel);
            playTime = itemView.findViewById(R.id.playTime);
            vndbInfo = itemView.findViewById(R.id.vndbInfo);
            vnMenuButton = itemView.findViewById(R.id.vnMenuButton);
            deleteButton = itemView.findViewById(R.id.deleteButton);
        }
    }
}
