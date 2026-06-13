package com.rubyfpv.viewer.player;

import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.MediaController;
import android.widget.Toast;
import android.widget.VideoView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;

import com.google.android.material.appbar.MaterialToolbar;
import com.rubyfpv.viewer.R;

import java.io.File;

/**
 * Plays a downloaded/remuxed clip with the platform {@link VideoView} (hardware
 * HEVC decode + reliable seeking on the MP4). Share action re-uses the same file.
 */
public class PlaybackActivity extends AppCompatActivity {

    public static final String EXTRA_PATH = "path";
    public static final String EXTRA_TITLE = "title";

    private VideoView video;
    private File file;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_playback);

        String path = getIntent().getStringExtra(EXTRA_PATH);
        String title = getIntent().getStringExtra(EXTRA_TITLE);
        if (path == null) { finish(); return; }
        file = new File(path);

        MaterialToolbar toolbar = findViewById(R.id.player_toolbar);
        toolbar.setTitle(title != null ? title : file.getName());
        toolbar.setNavigationOnClickListener(v -> finish());
        toolbar.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == R.id.menu_share) { share(); return true; }
            return false;
        });

        video = findViewById(R.id.video);
        MediaController controller = new MediaController(this);
        controller.setAnchorView(video);
        video.setMediaController(controller);
        video.setVideoURI(Uri.fromFile(file));
        video.setOnPreparedListener(mp -> {
            mp.setLooping(false);
            video.start();
        });
        video.setOnErrorListener((mp, what, extra) -> {
            Toast.makeText(this, R.string.toast_no_player, Toast.LENGTH_LONG).show();
            return true;
        });

        // Tap toggles the system bars / toolbar for an immersive look.
        findViewById(R.id.player_root).setOnClickListener(v -> {
            boolean shown = toolbar.getVisibility() == View.VISIBLE;
            toolbar.setVisibility(shown ? View.GONE : View.VISIBLE);
        });
    }

    private void share() {
        try {
            Uri uri = FileProvider.getUriForFile(
                    this, getPackageName() + ".fileprovider", file);
            android.content.Intent send = new android.content.Intent(android.content.Intent.ACTION_SEND);
            send.setType("video/mp4");
            send.putExtra(android.content.Intent.EXTRA_STREAM, uri);
            send.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(android.content.Intent.createChooser(send, getString(R.string.share_title)));
        } catch (Exception e) {
            Toast.makeText(this, R.string.toast_share_failed, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (video != null && video.isPlaying()) video.pause();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (video != null) video.stopPlayback();
    }
}
