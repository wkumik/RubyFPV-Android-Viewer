package com.rubyfpv.viewer.player;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.rubyfpv.viewer.R;

import java.io.File;

/**
 * Plays a downloaded/remuxed clip with ExoPlayer (media3). ExoPlayer reads the
 * file in-process, so app-private files in {@code Android/data/<pkg>/} play fine —
 * unlike {@code MediaPlayer}/{@code VideoView}, whose {@code mediaserver} process
 * can't open them ("no video player available"). It also demuxes raw {@code .ts}
 * if the remux to MP4 failed.
 */
public class PlaybackActivity extends AppCompatActivity {

    public static final String EXTRA_PATH = "path";
    public static final String EXTRA_URI = "uri";
    public static final String EXTRA_TITLE = "title";

    private ExoPlayer player;
    private PlayerView playerView;
    private File file;          // set when playing an app-local file (fallback)
    private Uri mediaUri;       // set when playing a content:// gallery item

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_playback);

        String uriStr = getIntent().getStringExtra(EXTRA_URI);
        String path = getIntent().getStringExtra(EXTRA_PATH);
        String title = getIntent().getStringExtra(EXTRA_TITLE);
        if (uriStr != null) {
            mediaUri = Uri.parse(uriStr);
        } else if (path != null) {
            file = new File(path);
            mediaUri = Uri.fromFile(file);
        } else {
            finish();
            return;
        }

        MaterialToolbar toolbar = findViewById(R.id.player_toolbar);
        toolbar.setTitle(title != null ? title : (file != null ? file.getName() : "Recording"));
        toolbar.setNavigationOnClickListener(v -> finish());
        toolbar.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == R.id.menu_share) { share(); return true; }
            return false;
        });

        playerView = findViewById(R.id.player_view);
        // Mirror the controller visibility onto the toolbar for a clean look.
        playerView.setControllerVisibilityListener(
                (PlayerView.ControllerVisibilityListener) visibility ->
                        toolbar.setVisibility(visibility));
    }

    private void initPlayer() {
        if (player != null) return;
        player = new ExoPlayer.Builder(this).build();
        playerView.setPlayer(player);
        player.addListener(new Player.Listener() {
            @Override
            public void onPlayerError(PlaybackException error) {
                Toast.makeText(PlaybackActivity.this,
                        "Playback error: " + error.getErrorCodeName(), Toast.LENGTH_LONG).show();
            }
        });
        player.setMediaItem(MediaItem.fromUri(mediaUri));
        player.prepare();
        player.setPlayWhenReady(true);
    }

    private void releasePlayer() {
        if (player != null) {
            player.release();
            player = null;
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        initPlayer();
    }

    @Override
    protected void onStop() {
        super.onStop();
        releasePlayer();
    }

    private void share() {
        try {
            Uri uri;
            if (mediaUri != null && "content".equals(mediaUri.getScheme())) {
                uri = mediaUri;   // gallery item — share directly with a read grant
            } else {
                uri = FileProvider.getUriForFile(
                        this, getPackageName() + ".fileprovider", file);
            }
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("video/mp4");
            send.putExtra(Intent.EXTRA_STREAM, uri);
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(send, getString(R.string.share_title)));
        } catch (Exception e) {
            Toast.makeText(this, R.string.toast_share_failed, Toast.LENGTH_SHORT).show();
        }
    }
}
