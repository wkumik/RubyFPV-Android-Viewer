package com.rubyfpv.viewer.recordings;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.TextInputEditText;
import com.rubyfpv.viewer.MainActivity;
import com.rubyfpv.viewer.R;
import com.rubyfpv.viewer.player.PlaybackActivity;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Primary screen: browse, download, preview and share onboard drone recordings.
 *
 * <p>All drone I/O runs on {@link #io} (a single serial thread so the dropbear
 * session is never used concurrently); CPU work (remux, thumbnail) runs on
 * {@link #work}. UI updates are posted back to {@link #ui}.</p>
 */
public class RecordingsActivity extends AppCompatActivity
        implements RecordingsAdapter.Listener {

    private static final String PREFS = "ruby_conn";

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ExecutorService work = Executors.newSingleThreadExecutor();

    private final Map<String, Recording> byStem = new LinkedHashMap<>();
    private final Set<String> prefetching = Collections.synchronizedSet(new HashSet<>());
    private RecordingsAdapter adapter;

    private MaterialToolbar toolbar;
    private RecyclerView list;
    private View statusDot;
    private TextView statusText;
    private MaterialButton btnConnect;
    private SwipeRefreshLayout swipe;
    private View emptyView;
    private TextView emptyTitle, emptyBody;
    private View loading;

    private RubyShell shell;
    private WifiJoiner wifi;
    private volatile boolean connected;
    private boolean compact;
    private File dir;
    private File thumbsDir;
    private File cacheDir;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_recordings);

        File base = getExternalFilesDir(null);
        if (base == null) base = getFilesDir();   // external storage unavailable
        dir = new File(base, "recordings");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        thumbsDir = new File(getFilesDir(), "thumbs");
        //noinspection ResultOfMethodCallIgnored
        thumbsDir.mkdirs();
        cacheDir = getCacheDir();
        wifi = new WifiJoiner(this);

        SharedPreferences prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        compact = prefs.getBoolean("compact", false);

        toolbar = findViewById(R.id.toolbar);
        toolbar.setSubtitle("v" + com.rubyfpv.viewer.BuildConfig.VERSION_NAME
                + " (" + com.rubyfpv.viewer.BuildConfig.VERSION_CODE + ")");
        toolbar.setOnMenuItemClickListener(this::onMenu);

        statusDot = findViewById(R.id.status_dot);
        statusText = findViewById(R.id.status_text);
        btnConnect = findViewById(R.id.btn_connect);
        swipe = findViewById(R.id.swipe);
        emptyView = findViewById(R.id.empty_view);
        emptyTitle = findViewById(R.id.empty_title);
        emptyBody = findViewById(R.id.empty_body);
        loading = findViewById(R.id.loading);

        adapter = new RecordingsAdapter(this);
        adapter.setCompact(compact);
        list = findViewById(R.id.list);
        list.setAdapter(adapter);
        applyLayoutManager();
        updateLayoutIcon();
        applyInsets();

        swipe.setColorSchemeColors(ContextCompat.getColor(this, R.color.ruby));
        swipe.setProgressBackgroundColorSchemeColor(ContextCompat.getColor(this, R.color.surface));
        swipe.setOnRefreshListener(() -> {
            if (connected) refresh();
            else { swipe.setRefreshing(false); connect(); }
        });

        btnConnect.setOnClickListener(v -> {
            if (connected) disconnect();
            else connect();
        });

        // Preload any thumbnails we cached on previous sessions.
        work.execute(() -> {
            Thumbs.loadDiskInto(thumbsDir);
            ui.post(() -> adapter.notifyDataSetChanged());
        });

        // Back exits multi-select before leaving the screen.
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (adapter.isSelectionMode()) {
                    adapter.exitSelection();
                } else {
                    setEnabled(false);
                    getOnBackPressedDispatcher().onBackPressed();
                }
            }
        });

        loadLocal();
        setStatus(StatusKind.OFFLINE, getString(R.string.status_disconnected));
    }

    /** Pad the app bar for the status bar and the list for the nav bar (edge-to-edge). */
    private void applyInsets() {
        View appbar = findViewById(R.id.appbar);
        ViewCompat.setOnApplyWindowInsetsListener(appbar, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(v.getPaddingLeft(), bars.top, v.getPaddingRight(), v.getPaddingBottom());
            return insets;
        });
        final int basePad = list.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(list, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(v.getPaddingLeft(), v.getPaddingTop(), v.getPaddingRight(),
                    basePad + bars.bottom);
            return insets;
        });
    }

    private void applyLayoutManager() {
        list.setLayoutManager(compact
                ? new GridLayoutManager(this, 2)
                : new LinearLayoutManager(this));
    }

    private void updateLayoutIcon() {
        if (toolbar.getMenu() != null && toolbar.getMenu().findItem(R.id.menu_layout) != null) {
            toolbar.getMenu().findItem(R.id.menu_layout)
                    .setIcon(compact ? R.drawable.ic_list : R.drawable.ic_grid);
        }
    }

    // ── Menu ────────────────────────────────────────────────────────────

    private boolean onMenu(android.view.MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.menu_layout) {
            compact = !compact;
            getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putBoolean("compact", compact).apply();
            applyLayoutManager();
            adapter.setCompact(compact);
            updateLayoutIcon();
            return true;
        } else if (id == R.id.menu_refresh) {
            if (connected) refresh(); else connect();
            return true;
        } else if (id == R.id.menu_live) {
            startActivity(new Intent(this, MainActivity.class));
            return true;
        } else if (id == R.id.menu_return_fpv) {
            confirmReturnToFpv();
            return true;
        } else if (id == R.id.menu_connection) {
            showConnectionDialog();
            return true;
        } else if (id == R.id.menu_diagnostics) {
            showDiagnosticsDialog();
            return true;
        } else if (id == R.id.menu_delete) {
            bulkDelete(adapter.selectedItems());
            return true;
        }
        return false;
    }

    /** Show the in-app {@link DebugLog} ring buffer so the thumbnail/transfer pipeline
     *  can be inspected on-device (no adb), with a Copy button to paste it back out. */
    private void showDiagnosticsDialog() {
        final String dump = DebugLog.dump();

        TextView tv = new TextView(this);
        tv.setText(dump);
        tv.setTextIsSelectable(true);
        tv.setTypeface(android.graphics.Typeface.MONOSPACE);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        int pad = Math.round(16 * getResources().getDisplayMetrics().density);
        tv.setPadding(pad, pad, pad, pad);

        ScrollView sv = new ScrollView(this);
        sv.addView(tv);

        new MaterialAlertDialogBuilder(this)
                .setTitle("Diagnostics")
                .setView(sv)
                .setNeutralButton("Clear", (d, w) -> {
                    DebugLog.clear();
                    Toast.makeText(this, "Diagnostics cleared", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Copy", (d, w) -> {
                    ClipboardManager cb =
                            (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    if (cb != null) {
                        cb.setPrimaryClip(ClipData.newPlainText("RubyFPV diagnostics", dump));
                        Toast.makeText(this, "Copied to clipboard", Toast.LENGTH_SHORT).show();
                    }
                })
                .setPositiveButton("Close", null)
                .show();
    }

    // ── Return to FPV ───────────────────────────────────────────────────

    /**
     * Reboot the drone out of phone-transfer mode, back into normal FPV. Distinct from
     * {@link #disconnect()} (which only drops our SSH session): leaving AP mode requires a
     * drone reboot — {@code ap_mode.sh stop} == {@code reboot} — so this is a deliberate,
     * confirmed action. The AP (and our link) drop as the drone goes down; that's expected.
     */
    private void confirmReturnToFpv() {
        if (!connected || shell == null) {
            Toast.makeText(this, R.string.return_fpv_need_connection, Toast.LENGTH_SHORT).show();
            return;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.return_fpv_title)
                .setMessage(R.string.return_fpv_message)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.return_fpv_confirm, (d, w) -> doReturnToFpv())
                .show();
    }

    private void doReturnToFpv() {
        final RubyShell s = shell;
        if (s == null) return;
        setStatus(StatusKind.BUSY, getString(R.string.status_return_fpv));
        io.execute(() -> {
            try {
                s.returnToFpv();
            } catch (Exception ignored) {
                // The reboot drops the link mid-command — that's success, not an error.
            }
            ui.post(() -> {
                Toast.makeText(this, R.string.status_return_fpv, Toast.LENGTH_LONG).show();
                disconnect();   // tear down our side; the drone is rebooting into FPV
            });
        });
    }

    // ── Connection ──────────────────────────────────────────────────────

    private void connect() {
        if (connected) return;
        setStatus(StatusKind.BUSY, getString(R.string.status_connecting));
        btnConnect.setEnabled(false);
        RubyShell.Config cfg = readConfig();
        io.execute(() -> {
            RubyShell s = new RubyShell(cfg);
            try {
                s.connect();
                List<Recording> remote = s.listRecordings();
                ui.post(() -> {
                    shell = s;
                    connected = true;
                    btnConnect.setEnabled(true);
                    btnConnect.setText(R.string.disconnect);
                    btnConnect.setIconResource(R.drawable.ic_check_circle);
                    setStatus(StatusKind.ONLINE, getString(R.string.status_connected, cfg.host));
                    mergeRemote(remote);
                });
            } catch (Exception e) {
                s.close();
                ui.post(() -> {
                    btnConnect.setEnabled(true);
                    setStatus(StatusKind.OFFLINE, getString(R.string.status_error));
                    Snackbar.make(swipe, getString(R.string.status_error) + ": " + e.getMessage(),
                                    Snackbar.LENGTH_LONG)
                            .setAction(R.string.join_wifi, v -> WifiJoiner.openWifiPanel(this))
                            .show();
                    refreshUi();
                });
            }
        });
    }

    private void disconnect() {
        connected = false;
        btnConnect.setText(R.string.connect);
        btnConnect.setIconResource(R.drawable.ic_wifi);
        setStatus(StatusKind.OFFLINE, getString(R.string.status_disconnected));
        io.execute(() -> {
            if (shell != null) { shell.close(); shell = null; }
        });
        wifi.release();
        // Drop drone-only items, keep local ones.
        ui.post(() -> {
            List<String> drop = new ArrayList<>();
            for (Recording r : byStem.values()) {
                r.onDrone = false;
                if (!r.isLocal()) drop.add(r.stem);
            }
            for (String k : drop) byStem.remove(k);
            refreshUi();
        });
    }

    private void refresh() {
        if (!connected || shell == null) return;
        swipe.setRefreshing(true);
        io.execute(() -> {
            try {
                List<Recording> remote = shell.listRecordings();
                ui.post(() -> { mergeRemote(remote); swipe.setRefreshing(false); });
            } catch (Exception e) {
                ui.post(() -> {
                    swipe.setRefreshing(false);
                    Toast.makeText(this, "Refresh failed: " + e.getMessage(),
                            Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    /** Merge a fresh drone listing into the model, preserving local state. */
    private void mergeRemote(List<Recording> remote) {
        for (Recording r : byStem.values()) r.onDrone = false;
        for (Recording rr : remote) {
            Recording cur = byStem.get(rr.stem);
            if (cur == null) {
                byStem.put(rr.stem, rr);
            } else {
                cur.tsName = rr.tsName;
                cur.osdName = rr.osdName;
                cur.remoteSize = rr.remoteSize;
                if (cur.mtimeEpoch <= 0) cur.mtimeEpoch = rr.mtimeEpoch;
                cur.onDrone = true;
            }
        }
        // purge entries that are neither on the drone nor downloaded
        List<String> drop = new ArrayList<>();
        for (Recording r : byStem.values()) {
            if (!r.onDrone && !r.isLocal()) drop.add(r.stem);
        }
        for (String k : drop) byStem.remove(k);
        refreshUi();
        prefetchThumbs();
    }

    /** Fetch a small .ts prefix for any clip lacking a cached thumbnail. */
    private void prefetchThumbs() {
        if (!connected || shell == null) return;
        for (Recording r : byStem.values()) {
            if (r.tsName == null) continue;
            if (Thumbs.CACHE.get(r.stem) != null) continue;
            prefetchThumb(r);
        }
    }

    private void prefetchThumb(Recording r) {
        if (!prefetching.add(r.stem)) return;   // already queued
        io.execute(() -> {
            File part = new File(cacheDir, r.stem + ".tspart");
            try {
                if (shell == null || Thumbs.CACHE.get(r.stem) != null) return;
                shell.downloadPrefix(r.tsName, 4 * 1024 * 1024, part);
                Thumbs.Meta m = Thumbs.extract(getApplicationContext(), part, r.stem);   // bitmap only
                if (m.bitmap != null) {
                    Thumbs.saveDisk(thumbsDir, r.stem, m.bitmap);
                    ui.post(() -> adapter.update(r));
                }
            } catch (Exception ignored) {
                // best-effort; clip just shows the placeholder
            } finally {
                //noinspection ResultOfMethodCallIgnored
                part.delete();
                prefetching.remove(r.stem);
            }
        });
    }

    // ── Local scan ──────────────────────────────────────────────────────

    private void loadLocal() {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            String name = f.getName();
            boolean mp4 = name.endsWith(".mp4");
            boolean ts = name.endsWith(".ts");
            if (!mp4 && !ts) continue;
            // Purge stray 0-byte .mp4s left by older builds' failed remux attempts
            // (the framework muxer creates the output before discovering it can't
            // demux HEVC-TS). A 0-byte file would otherwise become the "playable"
            // file and ExoPlayer would report container-unsupported.
            if (f.length() == 0) {
                //noinspection ResultOfMethodCallIgnored
                if (mp4) f.delete();
                continue;
            }
            String stem = name.substring(0, name.lastIndexOf('.'));
            Recording r = byStem.get(stem);
            if (r == null) { r = new Recording(stem); byStem.put(stem, r); }
            // Prefer the .ts: ExoPlayer plays HEVC-in-TS directly. Only fall back
            // to a (non-empty) .mp4 if no .ts is present.
            if (ts || r.localFile == null) r.localFile = f;
            r.state = Recording.State.READY;
            r.mtimeEpoch = f.lastModified() / 1000L;
        }
        // lazily extract thumbnails/durations for ready clips
        for (Recording r : byStem.values()) {
            if (r.state == Recording.State.READY && r.localFile != null) extractMeta(r);
        }
        refreshUi();
    }

    private void extractMeta(Recording r) {
        if (Thumbs.CACHE.get(r.stem) != null && r.durationMs > 0) return;
        final File f = r.localFile;
        work.execute(() -> {
            Thumbs.Meta m = Thumbs.extract(getApplicationContext(), f, r.stem);
            if (m.bitmap != null) Thumbs.saveDisk(thumbsDir, r.stem, m.bitmap);
            ui.post(() -> {
                if (m.durationMs > 0) r.durationMs = m.durationMs;
                adapter.update(r);
            });
        });
    }

    // ── Adapter callbacks ───────────────────────────────────────────────

    @Override
    public void onPrimary(Recording r) {
        // Download (or retry)
        if (!connected || shell == null) {
            Snackbar.make(swipe, R.string.empty_disconnected_title, Snackbar.LENGTH_SHORT)
                    .setAction(R.string.connect, v -> connect()).show();
            return;
        }
        if (r.tsName == null) return;
        r.state = Recording.State.DOWNLOADING;
        r.progress = 0;
        adapter.update(r);

        io.execute(() -> {
            File ts = new File(dir, r.tsName);
            try {
                shell.download(r.tsName, r.remoteSize, ts, (t, total) -> {
                    int pct = total > 0 ? (int) (t * 100 / total) : 0;
                    if (pct != r.progress) {
                        r.progress = pct;
                        ui.post(() -> adapter.update(r));
                    }
                });
                if (r.osdName != null) {
                    try {
                        shell.download(r.osdName, 0, new File(dir, r.osdName), null);
                    } catch (Exception ignore) { /* OSD sidecar is optional */ }
                }
                ui.post(() -> { r.state = Recording.State.REMUXING; adapter.update(r); });
                finishDownload(r, ts);
            } catch (Exception e) {
                //noinspection ResultOfMethodCallIgnored
                ts.delete();
                ui.post(() -> {
                    r.state = Recording.State.FAILED;
                    adapter.update(r);
                    Toast.makeText(this, "Download failed: " + e.getMessage(),
                            Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    /**
     * Finalise a freshly-downloaded clip. The {@code .ts} is the playable file:
     * ExoPlayer demuxes HEVC-in-TS directly, so there is no remux step (the
     * framework muxer can't demux HEVC-TS and would only leave a 0-byte .mp4).
     */
    private void finishDownload(Recording r, File ts) {
        work.execute(() -> {
            Thumbs.Meta m = Thumbs.extract(getApplicationContext(), ts, r.stem);
            if (m.bitmap != null) Thumbs.saveDisk(thumbsDir, r.stem, m.bitmap);
            ui.post(() -> {
                r.localFile = ts;
                if (m.durationMs > 0) r.durationMs = m.durationMs;
                r.state = Recording.State.READY;
                adapter.update(r);
            });
        });
    }

    @Override
    public void onPlay(Recording r) {
        if (r.localFile == null || !r.localFile.exists()) return;
        Intent i = new Intent(this, PlaybackActivity.class);
        i.putExtra(PlaybackActivity.EXTRA_PATH, r.localFile.getAbsolutePath());
        i.putExtra(PlaybackActivity.EXTRA_TITLE, r.stem);
        startActivity(i);
    }

    @Override
    public void onShare(Recording r) {
        if (r.localFile == null || !r.localFile.exists()) return;
        try {
            android.net.Uri uri = FileProvider.getUriForFile(
                    this, getPackageName() + ".fileprovider", r.localFile);
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType(mimeFor(r.localFile));
            send.putExtra(Intent.EXTRA_STREAM, uri);
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(send, getString(R.string.share_title)));
        } catch (Exception e) {
            Toast.makeText(this, R.string.toast_share_failed, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onDelete(Recording r) {
        boolean onPhone = r.isLocal();
        boolean onDrone = r.onDrone && connected;
        if (onPhone && onDrone) {
            // Let the user pick which copy/copies to remove.
            CharSequence[] opts = {
                    getString(R.string.delete_phone_only),
                    getString(R.string.delete_sd_only),
                    getString(R.string.delete_both),
            };
            new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.delete_title)
                    .setItems(opts, (d, which) ->
                            confirmDelete(r, which == 0 || which == 2, which == 1 || which == 2))
                    .setNegativeButton(R.string.cancel, null)
                    .show();
        } else {
            confirmDelete(r, onPhone, onDrone);
        }
    }

    private void confirmDelete(Recording r, boolean phone, boolean drone) {
        if (!phone && !drone) return;
        String msg;
        if (phone && drone) msg = getString(R.string.delete_both_body, r.stem);
        else if (drone)     msg = getString(R.string.delete_drone_body, r.stem);
        else                msg = getString(R.string.delete_phone_body, r.stem);
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.delete_title)
                .setMessage(msg)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.delete_confirm, (d, w) -> {
                    removeRecording(r, phone, drone);
                    refreshUi();
                })
                .show();
    }

    // ── Multi-select ────────────────────────────────────────────────────

    @Override
    public void onSelectionChanged(int count) {
        if (count > 0) {
            toolbar.setNavigationIcon(R.drawable.ic_arrow_back);
            toolbar.setNavigationOnClickListener(v -> adapter.exitSelection());
            toolbar.setTitle(getString(R.string.selected_count, count));
            toolbar.setSubtitle(null);
        } else {
            toolbar.setNavigationIcon(null);
            toolbar.setNavigationOnClickListener(null);
            toolbar.setTitle(R.string.recordings_title);
            toolbar.setSubtitle("v" + com.rubyfpv.viewer.BuildConfig.VERSION_NAME
                    + " (" + com.rubyfpv.viewer.BuildConfig.VERSION_CODE + ")");
        }
        Menu m = toolbar.getMenu();
        if (m != null) {
            boolean selecting = count > 0;
            int[] normal = { R.id.menu_layout, R.id.menu_refresh, R.id.menu_live,
                    R.id.menu_connection, R.id.menu_diagnostics };
            for (int id : normal) {
                MenuItem it = m.findItem(id);
                if (it != null) it.setVisible(!selecting);
            }
            MenuItem del = m.findItem(R.id.menu_delete);
            if (del != null) del.setVisible(selecting);
        }
    }

    private void bulkDelete(java.util.List<Recording> sel) {
        if (sel == null || sel.isEmpty()) return;
        boolean anyPhone = false, anyDrone = false;
        for (Recording r : sel) {
            if (r.isLocal()) anyPhone = true;
            if (r.onDrone && connected) anyDrone = true;
        }
        if (!anyPhone && !anyDrone) return;
        if (anyPhone && anyDrone) {
            CharSequence[] opts = {
                    getString(R.string.delete_phone_only),
                    getString(R.string.delete_sd_only),
                    getString(R.string.delete_both),
            };
            new MaterialAlertDialogBuilder(this)
                    .setTitle(getString(R.string.delete_selected_title, sel.size()))
                    .setItems(opts, (d, which) ->
                            confirmBulk(sel, which == 0 || which == 2, which == 1 || which == 2))
                    .setNegativeButton(R.string.cancel, null)
                    .show();
        } else {
            confirmBulk(sel, anyPhone, anyDrone);
        }
    }

    private void confirmBulk(java.util.List<Recording> sel, boolean phone, boolean drone) {
        String where = (phone && drone) ? getString(R.string.delete_where_both)
                : drone ? getString(R.string.delete_where_sd)
                : getString(R.string.delete_where_phone);
        new MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.delete_selected_title, sel.size()))
                .setMessage(getString(R.string.delete_selected_body, sel.size(), where))
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.delete_confirm, (d, w) -> {
                    for (Recording r : new ArrayList<>(sel)) {
                        removeRecording(r, phone && r.isLocal(),
                                drone && r.onDrone && connected);
                    }
                    adapter.exitSelection();
                    refreshUi();
                })
                .show();
    }

    /** Remove one clip from the chosen location(s); does not touch the UI. */
    private void removeRecording(Recording r, boolean phone, boolean drone) {
        if (phone) {
            deleteLocal(r);
            Thumbs.CACHE.remove(r.stem);
            File thumb = new File(thumbsDir, r.stem + ".jpg");
            if (thumb.exists()) //noinspection ResultOfMethodCallIgnored
                thumb.delete();
        }
        if (drone && shell != null) {
            final String ts = r.tsName, osd = r.osdName;
            io.execute(() -> {
                try { shell.delete(ts, osd); } catch (Exception ignored) {}
            });
            r.onDrone = false;
        }
        if (!r.isLocal() && !r.onDrone) {
            byStem.remove(r.stem);
        } else if (r.isLocal()) {
            r.state = Recording.State.READY;
        } else {
            r.state = Recording.State.ON_DRONE;
        }
    }

    private static String mimeFor(File f) {
        return f != null && f.getName().endsWith(".ts") ? "video/mp2t" : "video/mp4";
    }

    private void deleteLocal(Recording r) {
        File[] victims = {
                r.localFile,
                new File(dir, r.stem + ".mp4"),
                new File(dir, r.stem + ".ts"),
                new File(dir, r.stem + ".osd")
        };
        for (File f : victims) {
            if (f != null && f.exists()) //noinspection ResultOfMethodCallIgnored
                f.delete();
        }
        r.localFile = null;
    }

    // ── UI helpers ──────────────────────────────────────────────────────

    private void refreshUi() {
        List<Recording> sorted = new ArrayList<>(byStem.values());
        Collections.sort(sorted, (a, b) -> Long.compare(b.mtimeEpoch, a.mtimeEpoch));
        adapter.submit(sorted);
        loading.setVisibility(View.GONE);

        if (sorted.isEmpty()) {
            emptyView.setVisibility(View.VISIBLE);
            if (connected) {
                emptyTitle.setText(R.string.empty_none_title);
                emptyBody.setText(R.string.empty_none_body);
            } else {
                emptyTitle.setText(R.string.empty_disconnected_title);
                emptyBody.setText(R.string.empty_disconnected_body);
            }
        } else {
            emptyView.setVisibility(View.GONE);
        }
    }

    private enum StatusKind { OFFLINE, BUSY, ONLINE }

    private void setStatus(StatusKind kind, String text) {
        int colorRes;
        switch (kind) {
            case ONLINE: colorRes = R.color.status_online; break;
            case BUSY:   colorRes = R.color.status_busy; break;
            default:     colorRes = R.color.status_offline; break;
        }
        statusDot.setBackgroundTintList(
                ColorStateList.valueOf(ContextCompat.getColor(this, colorRes)));
        statusText.setText(text);
    }

    // ── Connection settings ─────────────────────────────────────────────

    private RubyShell.Config readConfig() {
        SharedPreferences p = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        RubyShell.Config c = new RubyShell.Config();
        c.host = p.getString("host", "192.168.4.1");
        c.port = p.getInt("port", 22);
        c.user = p.getString("user", "root");
        c.pass = p.getString("pass", "12345");
        return c;
    }

    private void showConnectionDialog() {
        SharedPreferences p = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        View v = getLayoutInflater().inflate(R.layout.dialog_connection, null);
        TextInputEditText host = v.findViewById(R.id.in_host);
        TextInputEditText user = v.findViewById(R.id.in_user);
        TextInputEditText pass = v.findViewById(R.id.in_pass);
        TextInputEditText ssid = v.findViewById(R.id.in_ssid);
        TextInputEditText psk = v.findViewById(R.id.in_psk);
        host.setText(p.getString("host", "192.168.4.1"));
        user.setText(p.getString("user", "root"));
        pass.setText(p.getString("pass", "12345"));
        ssid.setText(p.getString("ssid", ""));
        psk.setText(p.getString("psk", ""));

        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.settings_title)
                .setView(v)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.settings_save, (d, w) -> {
                    p.edit()
                            .putString("host", text(host, "192.168.4.1"))
                            .putString("user", text(user, "root"))
                            .putString("pass", text(pass, "12345"))
                            .putString("ssid", text(ssid, ""))
                            .putString("psk", text(psk, ""))
                            .apply();
                    Toast.makeText(this, R.string.settings_save, Toast.LENGTH_SHORT).show();
                })
                .show();
    }

    private static String text(TextInputEditText e, String def) {
        CharSequence c = e.getText();
        String s = c == null ? "" : c.toString().trim();
        return s.isEmpty() ? def : s;
    }

    // ── Lifecycle ───────────────────────────────────────────────────────

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.execute(() -> { if (shell != null) shell.close(); });
        wifi.release();
        io.shutdown();
        work.shutdown();
    }
}
