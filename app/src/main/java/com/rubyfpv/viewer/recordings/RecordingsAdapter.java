package com.rubyfpv.viewer.recordings;

import android.graphics.Bitmap;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.rubyfpv.viewer.R;

import java.util.ArrayList;
import java.util.List;

public class RecordingsAdapter extends RecyclerView.Adapter<RecordingsAdapter.VH> {

    public interface Listener {
        void onPrimary(Recording r);  // download / retry / play
        void onPlay(Recording r);
        void onShare(Recording r);
        void onDelete(Recording r);
    }

    private final List<Recording> items = new ArrayList<>();
    private final Listener listener;

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

    @Override
    public long getItemId(int position) {
        return items.get(position).stem.hashCode();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_recording, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        Recording r = items.get(position);
        h.title.setText(r.stem);

        // Thumbnail
        Bitmap bmp = Thumbs.CACHE.get(r.stem);
        if (bmp != null) {
            h.thumb.setImageBitmap(bmp);
        } else {
            h.thumb.setImageDrawable(null); // reveal placeholder background
        }

        // Duration badge
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
                h.primary.setVisibility(View.GONE);
                break;
            case REMUXING:
                h.progress.setVisibility(View.VISIBLE);
                h.progress.setIndeterminate(true);
                h.meta.setText(R.string.state_remuxing);
                h.primary.setVisibility(View.GONE);
                break;
            case READY:
                h.progress.setVisibility(View.GONE);
                h.meta.setText(join(Format.size(shownSize), when));
                h.primary.setVisibility(View.VISIBLE);
                h.primary.setText(R.string.action_play);
                h.primary.setIconResource(R.drawable.ic_play_arrow);
                break;
            case FAILED:
                h.progress.setVisibility(View.GONE);
                h.meta.setText(R.string.state_failed);
                h.primary.setVisibility(View.VISIBLE);
                h.primary.setText(R.string.action_download);
                h.primary.setIconResource(R.drawable.ic_download);
                break;
            case ON_DRONE:
            default:
                h.progress.setVisibility(View.GONE);
                h.meta.setText(join(Format.size(shownSize), when));
                h.primary.setVisibility(View.VISIBLE);
                h.primary.setText(R.string.action_download);
                h.primary.setIconResource(R.drawable.ic_download);
                break;
        }

        h.share.setEnabled(ready);
        h.delete.setEnabled(r.onDrone || r.isLocal());

        h.primary.setOnClickListener(v -> {
            if (r.state == Recording.State.READY) listener.onPlay(r);
            else listener.onPrimary(r);
        });
        View.OnClickListener playClick = v -> {
            if (r.state == Recording.State.READY) listener.onPlay(r);
        };
        h.playOverlay.setOnClickListener(playClick);
        h.thumb.setOnClickListener(playClick);
        h.share.setOnClickListener(v -> listener.onShare(r));
        h.delete.setOnClickListener(v -> listener.onDelete(r));
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
        final MaterialButton primary, share, delete;

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
