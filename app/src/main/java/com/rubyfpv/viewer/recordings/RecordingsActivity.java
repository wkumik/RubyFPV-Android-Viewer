package com.rubyfpv.viewer.recordings;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Build;
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
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
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

    /** Read-media permission so the gallery scan can see clips a PRIOR install created. */
    private final ActivityResultLauncher<String> readMediaPerm =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                DebugLog.add("read-media permission " + (granted ? "granted" : "denied"));
                // Items the current install created are queryable regardless; rescan either way.
                loadLocal();
            });

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
        requestReadMediaIfNeeded();
        setStatus(StatusKind.OFFLINE, getString(R.string.status_disconnected));
    }

    /**
     * Request the read-media permission (non-blocking) so the gallery scan can list
     * clips created by a PRIOR install. If denied we still proceed: clips this install
     * created remain queryable without the permission.
     */
    private void requestReadMediaIfNeeded() {
        final String perm = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                ? android.Manifest.permission.READ_MEDIA_VIDEO
                : android.Manifest.permission.READ_EXTERNAL_STORAGE;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                && ContextCompat.checkSelfPermission(this, perm)
                != PackageManager.PERMISSION_GRANTED) {
            readMediaPerm.launch(perm);
        }
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
        // 1) The gallery (Movies/RubyFPV) is the store of record — scan it first.
        List<GalleryStore.GalleryItem> items = GalleryStore.query(getApplicationContext());
        for (GalleryStore.GalleryItem g : items) {
            if (g.stem == null || g.stem.isEmpty()) continue;
            Recording r = byStem.get(g.stem);
            if (r == null) { r = new Recording(g.stem); byStem.put(g.stem, r); }
            r.localUri = g.uri;
            r.localFile = null;
            if (g.durationMs > 0) r.durationMs = g.durationMs;
            if (g.mtimeEpochSec > 0) r.mtimeEpoch = g.mtimeEpochSec;
            r.state = Recording.State.READY;
        }
        for (Recording r : byStem.values()) {
            if (r.state == Recording.State.READY && r.localUri != null) extractMeta(r);
        }
        refreshUi();

        // 2) Best-effort migration of any leftover private files (older builds /
        //    interrupted publish). Normally zero on this device; never fatal.
        migratePrivateLeftovers();
    }

    /**
     * Move any clips still sitting in the app-private {@code dir} (from older builds
     * or a publish interrupted mid-write) into the gallery, then delete the private
     * copy. Purges {@code .part} and 0-byte leftovers. Runs scans on {@link #work}.
     */
    private void migratePrivateLeftovers() {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            String name = f.getName();
            if (name.endsWith(".part")) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
                continue;
            }
            boolean mp4 = name.endsWith(".mp4");
            boolean ts = name.endsWith(".ts");
            if (!mp4 && !ts) continue;
            if (f.length() == 0) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
                continue;
            }
            String stem = name.substring(0, name.lastIndexOf('.'));
            Recording existing = byStem.get(stem);
            // Already published to the gallery? Drop the private dup.
            if (existing != null && existing.localUri != null) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
                continue;
            }
            Recording r = existing;
            if (r == null) { r = new Recording(stem); byStem.put(stem, r); }
            // Prefer migrating the .mp4 if both exist; the .ts is handled below.
            if (mp4) {
                r.localFile = f;
                r.state = Recording.State.REMUXING;
                final Recording fr = r;
                final File ff = f;
                ui.post(() -> adapter.update(fr));
                work.execute(() -> publishAndFinalize(fr, ff));
            } else { // .ts and no sibling .mp4 being migrated
                if (new File(dir, stem + ".mp4").exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();   // .mp4 sibling will be migrated instead
                    continue;
                }
                r.localFile = f;
                convertLocal(r);
            }
        }
        refreshUi();
    }

    /**
     * Publish an already-converted private {@code mp4} into the gallery, delete the
     * private copy on success, extract the thumbnail and mark READY. On failure keep
     * the private {@code mp4} as the playable/shareable fallback. Runs on {@link #work}.
     */
    private void publishAndFinalize(Recording r, File mp4) {
        Uri uri = GalleryStore.publish(getApplicationContext(), mp4, r.stem + ".mp4");
        if (uri != null) {
            //noinspection ResultOfMethodCallIgnored
            mp4.delete();
            Thumbs.Meta m = Thumbs.extract(getApplicationContext(), uri, r.stem);
            if (m.bitmap != null) Thumbs.saveDisk(thumbsDir, r.stem, m.bitmap);
            final long durMs = m.durationMs;
            ui.post(() -> {
                r.localUri = uri;
                r.localFile = null;
                if (durMs > 0) r.durationMs = durMs;
                r.state = Recording.State.READY;
                adapter.update(r);
            });
        } else {
            Thumbs.Meta m = Thumbs.extract(getApplicationContext(), mp4, r.stem);
            if (m.bitmap != null) Thumbs.saveDisk(thumbsDir, r.stem, m.bitmap);
            final long durMs = m.durationMs;
            ui.post(() -> {
                r.localFile = mp4;
                if (durMs > 0) r.durationMs = durMs;
                r.state = Recording.State.READY;
                adapter.update(r);
            });
        }
    }

    private void extractMeta(Recording r) {
        if (Thumbs.CACHE.get(r.stem) != null && r.durationMs > 0) return;
        final Uri uri = r.localUri;
        final File f = r.localFile;
        if (uri == null && f == null) return;
        work.execute(() -> {
            Thumbs.Meta m = uri != null
                    ? Thumbs.extract(getApplicationContext(), uri, r.stem)
                    : Thumbs.extract(getApplicationContext(), f, r.stem);
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
     * Finalise a freshly-downloaded clip: remux the {@code .ts} into a share-ready
     * {@code .mp4} (social apps reject MPEG-TS), then extract the thumbnail. The
     * caller has already moved the row to {@link Recording.State#REMUXING}.
     */
    private void finishDownload(Recording r, File ts) {
        work.execute(() -> remuxAndFinalize(r, ts));
    }

    /**
     * Upgrade a clip that is still backed locally by a {@code .ts} (downloaded by
     * an older build) to a shareable {@code .mp4}, in the background.
     */
    private void convertLocal(Recording r) {
        final File ts = r.localFile;
        ui.post(() -> { r.state = Recording.State.REMUXING; adapter.update(r); });
        work.execute(() -> remuxAndFinalize(r, ts));
    }

    /**
     * Remux {@code ts} → a staged {@code stem.mp4} (HEVC stream-copy), then publish it
     * into the gallery ({@code Movies/RubyFPV}) as the store of record and delete both
     * staged private files. Extracts the thumbnail and marks READY.
     *
     * <p>Fallbacks (private file kept, {@code localUri} stays null):</p>
     * <ul>
     *   <li>publish fails → keep staged {@code .mp4} as playable/shareable;</li>
     *   <li>remux fails → keep the {@code .ts} as playable/shareable.</li>
     * </ul>
     * Runs on {@link #work}.
     */
    private void remuxAndFinalize(Recording r, File ts) {
        File mp4 = new File(dir, r.stem + ".mp4");
        if (Mp4Export.remux(getApplicationContext(), ts, mp4)) {
            //noinspection ResultOfMethodCallIgnored
            ts.delete();
            Uri uri = GalleryStore.publish(getApplicationContext(), mp4, r.stem + ".mp4");
            if (uri != null) {
                //noinspection ResultOfMethodCallIgnored
                mp4.delete();
                Thumbs.Meta m = Thumbs.extract(getApplicationContext(), uri, r.stem);
                if (m.bitmap != null) Thumbs.saveDisk(thumbsDir, r.stem, m.bitmap);
                final long durMs = m.durationMs;
                ui.post(() -> {
                    r.localUri = uri;
                    r.localFile = null;
                    if (durMs > 0) r.durationMs = durMs;
                    r.state = Recording.State.READY;
                    adapter.update(r);
                });
                return;
            }
            // Publish failed — keep the staged .mp4 as the fallback.
            DebugLog.add("publish returned null, keeping private mp4 for " + r.stem);
            finalizeFallback(r, mp4);
            return;
        }
        // Remux failed — keep the .ts as the playable/shareable fallback.
        finalizeFallback(r, ts);
    }

    /** Mark READY backed by a private file (publish/remux fallback path). */
    private void finalizeFallback(Recording r, File pf) {
        Thumbs.Meta m = Thumbs.extract(getApplicationContext(), pf, r.stem);
        if (m.bitmap != null) Thumbs.saveDisk(thumbsDir, r.stem, m.bitmap);
        final long durMs = m.durationMs;
        ui.post(() -> {
            r.localFile = pf;
            r.localUri = null;
            if (durMs > 0) r.durationMs = durMs;
            r.state = Recording.State.READY;
            adapter.update(r);
        });
    }

    @Override
    public void onPlay(Recording r) {
        Intent i = new Intent(this, PlaybackActivity.class);
        if (r.localUri != null) {
            i.putExtra(PlaybackActivity.EXTRA_URI, r.localUri.toString());
        } else if (r.localFile != null && r.localFile.exists()) {
            i.putExtra(PlaybackActivity.EXTRA_PATH, r.localFile.getAbsolutePath());
        } else {
            return;
        }
        i.putExtra(PlaybackActivity.EXTRA_TITLE, r.stem);
        startActivity(i);
    }

    @Override
    public void onShare(Recording r) {
        try {
            Uri uri;
            String type;
            if (r.localUri != null) {
                uri = r.localUri;          // gallery item — share directly
                type = "video/mp4";
            } else if (r.localFile != null && r.localFile.exists()) {
                uri = FileProvider.getUriForFile(
                        this, getPackageName() + ".fileprovider", r.localFile);
                type = mimeFor(r.localFile);
            } else {
                return;
            }
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType(type);
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
        // Primary store of record: the gallery item.
        if (r.localUri != null) {
            final Uri uri = r.localUri;
            work.execute(() -> GalleryStore.delete(getApplicationContext(), uri));
            r.localUri = null;
        }
        // Any private staged/fallback files.
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
