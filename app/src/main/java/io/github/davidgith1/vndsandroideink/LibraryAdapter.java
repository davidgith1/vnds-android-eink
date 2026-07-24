package io.github.davidgith1.vndsandroideink;

import android.graphics.BitmapFactory;
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

/** Backs the library list: one row per already-imported VN. Importing is triggered separately,
 * from the library screen's own "+ Import novel" button, not from anything in this list. */
public class LibraryAdapter extends RecyclerView.Adapter<LibraryAdapter.VnVH> {

    public interface Listener {
        void onVnClick(VnEntry entry);
        void onDeleteClick(VnEntry entry);
        void onVnMenuClick(VnEntry entry);
    }

    private final List<VnEntry> entries = new ArrayList<>();
    private final Listener listener;

    public LibraryAdapter(Listener listener) {
        this.listener = listener;
    }

    public void setEntries(List<VnEntry> newEntries) {
        entries.clear();
        entries.addAll(newEntries);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VnVH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_vn, parent, false);
        return new VnVH(view);
    }

    @Override
    public void onBindViewHolder(@NonNull VnVH holder, int position) {
        VnEntry entry = entries.get(position);
        holder.title.setText(buildTitleText(entry));
        boolean isVnds = entry.engineType == VnEntry.EngineType.VNDS;
        holder.engineLabel.setVisibility(View.VISIBLE);
        holder.engineLabel.setText(isVnds ? R.string.engine_row_label_vnds : R.string.engine_row_label_nscripter);
        holder.engineLabel.setContentDescription(holder.itemView.getContext()
                .getString(isVnds ? R.string.engine_label_vnds : R.string.engine_label_nscripter));
        if (entry.icon != null) {
            holder.icon.setImageBitmap(BitmapFactory.decodeFile(entry.icon.getAbsolutePath()));
        } else {
            holder.icon.setImageResource(R.drawable.ic_book);
        }
        if (entry.playMillis > 0) {
            holder.playTime.setVisibility(View.VISIBLE);
            holder.playTime.setText(formatPlayTime(entry.playMillis));
        } else {
            holder.playTime.setVisibility(View.GONE);
        }
        if (entry.vndbMeta != null) {
            holder.vndbInfo.setVisibility(View.VISIBLE);
            holder.vndbInfo.setText(formatVndbInfo(entry.vndbMeta));
        } else {
            holder.vndbInfo.setVisibility(View.GONE);
        }
        holder.deleteButton.setContentDescription(holder.itemView.getContext().getString(R.string.delete_novel_desc, entry.title));
        holder.vnMenuButton.setContentDescription(holder.itemView.getContext().getString(R.string.vn_menu_desc, entry.title));
        holder.itemView.setOnClickListener(v -> {
            if (listener != null) {
                listener.onVnClick(entry);
            }
        });
        holder.deleteButton.setOnClickListener(v -> {
            if (listener != null) {
                listener.onDeleteClick(entry);
            }
        });
        holder.vnMenuButton.setOnClickListener(v -> {
            if (listener != null) {
                listener.onVnMenuClick(entry);
            }
        });
    }

    @Override
    public int getItemCount() {
        return entries.size();
    }

    /** Builds the title row's text: the pack's own title in the TextView's normal bold size, plus --
     * on the same row, just smaller and italic, rather than a separate row each -- the pack's own
     * subtitle (NScripter's "versionstr"; never touched by a VNDB fetch) and/or VNDB's fetched title
     * (plus its alt title, if different), so a fetch can't push the row layout around. */
    private static CharSequence buildTitleText(VnEntry entry) {
        List<String> extras = new ArrayList<>();
        if (entry.subtitle != null && !entry.subtitle.isEmpty() && !entry.subtitle.equals(entry.title)) {
            extras.add(entry.subtitle);
        }
        if (entry.vndbMeta != null) {
            String vTitle = entry.vndbMeta.title;
            String vAlt = entry.vndbMeta.altTitle;
            String display = vTitle;
            if (vAlt != null && !vAlt.isEmpty() && !vAlt.equals(vTitle)) {
                display = (display == null || display.isEmpty()) ? vAlt : display + " / " + vAlt;
            }
            if (display != null && !display.isEmpty() && !display.equals(entry.title)) {
                extras.add(display);
            }
        }
        if (extras.isEmpty()) {
            return entry.title;
        }
        SpannableStringBuilder sb = new SpannableStringBuilder(entry.title);
        int start = sb.length();
        sb.append("  ").append(TextUtils.join(" · ", extras));
        sb.setSpan(new RelativeSizeSpan(0.72f), start, sb.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.setSpan(new StyleSpan(Typeface.ITALIC), start, sb.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        return sb;
    }

    /** "2h 15m read" / "45m read" / "<1m read" -- coarse on purpose, this is a library-list hint
     * rather than a stopwatch. */
    private static String formatPlayTime(long millis) {
        long totalMinutes = millis / 60_000;
        long hours = totalMinutes / 60;
        long minutes = totalMinutes % 60;
        if (hours > 0) {
            return hours + "h " + minutes + "m read";
        }
        if (minutes > 0) {
            return minutes + "m read";
        }
        return "<1m read";
    }

    /** "2000-12-26 · ★8.2 · Long (~29h)" -- whatever pieces VNDB actually returned for this VN;
     * any of released/rating/length can be individually missing. */
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

    static class VnVH extends RecyclerView.ViewHolder {
        final ImageView icon;
        final TextView title;
        final TextView engineLabel;
        final TextView playTime;
        final TextView vndbInfo;
        final TextView vnMenuButton;
        final TextView deleteButton;

        VnVH(View itemView) {
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
