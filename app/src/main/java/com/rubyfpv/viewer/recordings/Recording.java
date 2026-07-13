package com.rubyfpv.viewer.recordings;

import java.io.File;

/**
 * One onboard clip. Keyed by {@link #stem} (filename without extension) so the
 * drone-side {@code .ts} and the downloaded copy describe the same item.
 */
public class Recording {

    public enum State {
        ON_DRONE,     // exists on the drone, not yet downloaded
        DOWNLOADING,  // SFTP/exec pull in progress
        REMUXING,     // post-download: remux .ts→.mp4 + thumbnail; labelled "Converting to MP4…"
        READY,        // local playable file present
        FAILED        // download error
    }

    public final String stem;          // e.g. rec_00h03m08s_4c3c
    public String tsName;              // e.g. rec_00h03m08s_4c3c.ts (null if drone copy gone)
    public String osdName;             // paired .osd on drone, or null

    public long remoteSize;            // bytes on drone (0 if unknown / local-only)
    public long mtimeEpoch;            // seconds; drone mtime or local lastModified

    public State state = State.ON_DRONE;
    public int progress;               // 0..100 during download
    public boolean onDrone;            // still present on the SD card

    public android.net.Uri localUri;   // published gallery item (Movies/RubyFPV) — store of record; null until published
    public File localFile;             // transient/fallback only: staged .mp4 (publish failed) or .ts (remux failed)
    public long durationMs = -1;       // best-effort; raw TS carries no duration header

    public Recording(String stem) {
        this.stem = stem;
    }

    public boolean isLocal() {
        return localUri != null || (localFile != null && localFile.exists());
    }
}
