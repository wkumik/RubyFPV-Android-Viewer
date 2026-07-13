package com.rubyfpv.viewer.recordings;

import android.graphics.Bitmap;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.rubyfpv.viewer.R;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class RecordingsAdapter extends RecyclerView.Adapter<RecordingsAdapter.VH> {

    private static final int TYPE_LARGE = 0;
    private static final int TYPE_COMPACT = 1;

    public interface Listener {
        void onPrimary(Recording r);  // download / retry
        void onPlay(Recording r);
        void onShare(Recording r);
        void onDelete(Recording r);
        void onSelectionChanged(int count);  // 0 = selection mode off
    }

    private final List<Recording> items = new ArrayList<>();
    private final Set<String> selected = new HashSet<>();
    private final Listener listener;
    private boolean compact;
    private boolean selectionMode;

    public RecordingsAdapter(Listener listener) {
        this.listener = listener;
        setHasStableIds(true);
    }

    public void submit(List<Recording> next) {
        items.clear();
        items.addAll(next);
        // Drop selected stems that no longer exist (e.g. just deleted).
        if (!selected.isEmpty()) {
            Set<String> present = new HashSet<>();
            for (Recording r : items) present.add(r.stem);
            if (selected.retainAll(present) && selected.isEmpty()) {
                selectionMode = false;
                listener.onSelectionChanged(0);
            }
        }
        notifyDataSetChanged();
    }

    public void update(Recording r) {
        int i = items.indexOf(r);
        if (i >= 0) notifyItemChanged(i);
    }

    public void setCompact(boolean c) {
        if (compact != c) {
            compact = c;
            notifyDataSetChanged();
        }
    }

    public boolean isCompact() {
        return compact;
    }

    // ── Multi-select ────────────────────────────────────────────────────

    public boolean isSelectionMode() {
        return selectionMode;
    }

    public List<Recording> selectedItems() {
        List<Recording> out = new ArrayList<>();
        for (Recording r : items) if (selected.contains(r.stem)) out.add(r);
        return out;
    }

    public void exitSelection() {
        if (!selectionMode && selected.isEmpty()) return;
        selectionMode = false;
        selected.clear();
        notifyDataSetChanged();
        listener.onSelectionChanged(0);
    }

    private void enterSelection(Recording r) {
        selectionMode = true;
        selected.add(r.stem);
        notifyDataSetChanged();
        listener.onSelectionChanged(selected.size());
    }

    private void toggle(Recording r) {
        if (!selected.remove(r.stem)) selected.add(r.stem);
        if (selected.isEmpty()) selectionMode = false;
        notifyDataSetChanged();
        listener.onSelectionChanged(selected.size());
    }

    @Override
    public int getItemViewType(int position) {
        return compact ? TYPE_COMPACT : TYPE_LARGE;
    }

    @Override
    public long getItemId(int position) {
        return items.get(position).stem.hashCode();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        int layout = viewType == TYPE_COMPACT
                ? R.layout.item_recording_compact : R.layout.item_recording;
        View v = LayoutInflater.from(parent.getContext()).inflate(layout, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        Recording r = items.get(position);
        h.title.setText(r.stem);

        Bitmap bmp = Thumbs.CACHE.get(r.stem);
        if (bmp != null) h.thumb.setImageBitmap(bmp);
        else h.thumb.setImageDrawable(null);

        if (r.durationMs > 0) {
            h.badge.setText(Format.clock(r.durationMs));
            h.badge.setVisibility(View.VISIBLE);
        } else {
            h.badge.setVisibility(View.GONE);
        }

        // Selection visuals.
        boolean isSelected = selected.contains(r.stem);
        h.selectCheck.setVisibility(isSelected ? View.VISIBLE : View.GONE);
        if (h.card != null) {
            float density = h.itemView.getResources().getDisplayMetrics().density;
            h.card.setStrokeWidth(Math.round((isSelected ? 2f : 1f) * density));
            h.card.setStrokeColor(ContextCompat.getColor(h.card.getContext(),
                    isSelected ? R.color.ruby : R.color.outline));
        }

        boolean ready = r.state == Recording.State.READY;
        h.playOverlay.setVisibility(ready && !selectionMode ? View.VISIBLE : View.GONE);

        long shownSize = r.remoteSize > 0 ? r.remoteSize
                : (r.localFile != null ? r.localFile.length() : 0);
        String when = Format.date(r.mtimeEpoch);

        switch (r.state) {
            case DOWNLOADING:
                h.progress.setVisibility(View.VISIBLE);
                h.progress.setIndeterminate(false);
                h.progress.setProgress(r.progress);
                h.meta.setText(h.ctx(R.string.state_downloading, r.progress));
                break;
            case REMUXING:
                h.progress.setVisibility(View.VISIBLE);
                h.progress.setIndeterminate(true);
                h.meta.setText(R.string.state_remuxing);
                break;
            case FAILED:
                h.progress.setVisibility(View.GONE);
                h.meta.setText(R.string.state_failed);
                break;
            case READY:
            case ON_DRONE:
            default:
                h.progress.setVisibility(View.GONE);
                h.meta.setText(join(Format.size(shownSize), when));
                break;
        }

        // Action row (large layout only) is hidden while selecting.
        if (h.actions != null) {
            h.actions.setVisibility(selectionMode ? View.GONE : View.VISIBLE);
        }
        if (h.primary != null && !selectionMode) {
            boolean busy = r.state == Recording.State.DOWNLOADING
                    || r.state == Recording.State.REMUXING;
            h.primary.setVisibility(busy ? View.GONE : View.VISIBLE);
            if (ready) {
                h.primary.setText(R.string.action_play);
                h.primary.setIconResource(R.drawable.ic_play_arrow);
            } else {
                h.primary.setText(R.string.action_download);
                h.primary.setIconResource(R.drawable.ic_download);
            }
            h.primary.setOnClickListener(v -> {
                if (r.state == Recording.State.READY) listener.onPlay(r);
                else listener.onPrimary(r);
            });
            h.share.setEnabled(ready);
            h.delete.setEnabled(r.onDrone || r.isLocal());
            h.share.setOnClickListener(v -> listener.onShare(r));
            h.delete.setOnClickListener(v -> listener.onDelete(r));
        }

        View.OnClickListener tap = v -> {
            if (selectionMode) { toggle(r); return; }
            if (r.state == Recording.State.READY) listener.onPlay(r);
            else if (r.state == Recording.State.ON_DRONE
                    || r.state == Recording.State.FAILED) listener.onPrimary(r);
        };
        // Long-press must be wired on every clickable child too: a child with an
        // OnClickListener is `clickable` and swallows the touch, so the card's
        // own long-press detector never fires when you hold the thumbnail.
        View.OnLongClickListener hold = v -> {
            if (selectionMode) toggle(r); else enterSelection(r);
            return true;
        };
        h.playOverlay.setOnClickListener(tap);
        h.thumb.setOnClickListener(tap);
        h.itemView.setOnClickListener(tap);
        h.playOverlay.setOnLongClickListener(hold);
        h.thumb.setOnLongClickListener(hold);
        h.itemView.setOnLongClickListener(hold);
    }

    private static String join(String a, String b) {
        if (b == null || b.isEmpty()) return a;
        return a + "  ·  " + b;
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        final ImageView thumb, playOverlay, selectCheck;
        final TextView badge, title, meta;
        final LinearProgressIndicator progress;
        final MaterialCardView card;
        final View actions;                            // null in compact layout
        final MaterialButton primary, share, delete;   // null in compact layout

        VH(@NonNull View v) {
            super(v);
            thumb = v.findViewById(R.id.thumb);
            playOverlay = v.findViewById(R.id.play_overlay);
            selectCheck = v.findViewById(R.id.select_check);
            badge = v.findViewById(R.id.badge_duration);
            title = v.findViewById(R.id.title);
            meta = v.findViewById(R.id.meta);
            progress = v.findViewById(R.id.progress);
            card = (v instanceof MaterialCardView) ? (MaterialCardView) v : null;
            actions = v.findViewById(R.id.actions);
            primary = v.findViewById(R.id.btn_primary);
            share = v.findViewById(R.id.btn_share);
            delete = v.findViewById(R.id.btn_delete);
        }

        String ctx(int resId, Object... args) {
            return itemView.getContext().getString(resId, args);
        }
    }
}
