package com.rubyfpv.viewer.recordings;

import android.graphics.Bitmap;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.PopupMenu;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.rubyfpv.viewer.R;

import java.util.ArrayList;
import java.util.List;

public class RecordingsAdapter extends RecyclerView.Adapter<RecordingsAdapter.VH> {

    private static final int TYPE_LARGE = 0;
    private static final int TYPE_COMPACT = 1;

    public interface Listener {
        void onPrimary(Recording r);  // download / retry
        void onPlay(Recording r);
        void onShare(Recording r);
        void onDelete(Recording r);
    }

    private final List<Recording> items = new ArrayList<>();
    private final Listener listener;
    private boolean compact;

    public RecordingsAdapter(Listener listener) {
        this.listener = listener;
        setHasStableIds(true);
    }

    public void submit(List<Recording> next) {
        items.clear();
        items.addAll(next);
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

        boolean ready = r.state == Recording.State.READY;
        h.playOverlay.setVisibility(ready ? View.VISIBLE : View.GONE);

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

        // Primary button only exists in the large layout.
        if (h.primary != null) {
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
            if (r.state == Recording.State.READY) listener.onPlay(r);
            else if (r.state == Recording.State.ON_DRONE
                    || r.state == Recording.State.FAILED) listener.onPrimary(r);
        };
        h.playOverlay.setOnClickListener(tap);
        h.thumb.setOnClickListener(tap);
        // In compact mode the whole card acts; long-press always offers the full menu.
        if (h.primary == null) h.itemView.setOnClickListener(tap);
        h.itemView.setOnLongClickListener(v -> { showContextMenu(v, r); return true; });
    }

    private void showContextMenu(View anchor, Recording r) {
        boolean ready = r.state == Recording.State.READY;
        PopupMenu pm = new PopupMenu(anchor.getContext(), anchor);
        pm.getMenuInflater().inflate(R.menu.item_context, pm.getMenu());
        pm.getMenu().findItem(R.id.ctx_play).setVisible(ready);
        pm.getMenu().findItem(R.id.ctx_download).setVisible(!ready && r.onDrone);
        pm.getMenu().findItem(R.id.ctx_share).setVisible(ready);
        pm.getMenu().findItem(R.id.ctx_delete).setVisible(r.onDrone || r.isLocal());
        pm.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            if (id == R.id.ctx_play) listener.onPlay(r);
            else if (id == R.id.ctx_download) listener.onPrimary(r);
            else if (id == R.id.ctx_share) listener.onShare(r);
            else if (id == R.id.ctx_delete) listener.onDelete(r);
            else return false;
            return true;
        });
        pm.show();
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
        final ImageView thumb, playOverlay;
        final TextView badge, title, meta;
        final LinearProgressIndicator progress;
        final MaterialButton primary, share, delete;  // null in compact layout

        VH(@NonNull View v) {
            super(v);
            thumb = v.findViewById(R.id.thumb);
            playOverlay = v.findViewById(R.id.play_overlay);
            badge = v.findViewById(R.id.badge_duration);
            title = v.findViewById(R.id.title);
            meta = v.findViewById(R.id.meta);
            progress = v.findViewById(R.id.progress);
            primary = v.findViewById(R.id.btn_primary);
            share = v.findViewById(R.id.btn_share);
            delete = v.findViewById(R.id.btn_delete);
        }

        String ctx(int resId, Object... args) {
            return itemView.getContext().getString(resId, args);
        }
    }
}
