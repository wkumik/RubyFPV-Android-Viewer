package com.rubyfpv.viewer.recordings;

import android.util.Log;

import com.jcraft.jsch.Channel;
import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * SSH client for the drone, talking to its <b>dropbear</b> server.
 *
 * <p>The drone's dropbear has <b>no SFTP / SCP subsystem</b> (pscp / pscp&nbsp;-sftp
 * both fail against the SigmaStar build), so this class never opens an SFTP channel.
 * Instead it runs plain commands over {@code exec} channels — listing with
 * {@code stat}, transferring by streaming {@code cat} — which is the same approach
 * that works from the desktop via {@code plink + stdin}.</p>
 *
 * <p>Not thread-safe: drive all calls from a single serial executor.</p>
 */
public class RubyShell {

    private static final String TAG = "RubyShell";
    public static final String REMOTE_DIR = "/mnt/mmcblk0p1/ruby";

    public static class Config {
        public String host = "192.168.4.1";
        public int port = 22;
        public String user = "root";
        public String pass = "12345";
    }

    public interface Progress {
        void onBytes(long transferred, long total);
    }

    private final Config cfg;
    private Session session;

    public RubyShell(Config cfg) {
        this.cfg = cfg;
    }

    public void connect() throws Exception {
        JSch jsch = new JSch();
        Session s = jsch.getSession(cfg.user, cfg.host, cfg.port);
        s.setPassword(cfg.pass);
        Properties p = new Properties();
        p.put("StrictHostKeyChecking", "no");   // point-to-point AP, no host DB
        s.setConfig(p);
        s.setConfig("PreferredAuthentications", "password,keyboard-interactive");
        s.setServerAliveInterval(15000);
        s.connect(8000);
        session = s;
        Log.i(TAG, "Connected to " + cfg.user + "@" + cfg.host + ":" + cfg.port);
    }

    public boolean isConnected() {
        return session != null && session.isConnected();
    }

    public void close() {
        if (session != null) {
            session.disconnect();
            session = null;
        }
    }

    /** List {@code *.ts} (+ paired {@code *.osd}) in the recordings dir. */
    public List<Recording> listRecordings() throws Exception {
        // for-loop + stat keeps output trivially parseable: name|size|mtime
        String cmd = "cd " + REMOTE_DIR + " 2>/dev/null && "
                + "for f in *.ts *.osd; do [ -e \"$f\" ] && "
                + "stat -c '%n|%s|%Y' \"$f\"; done";
        String out = exec(cmd);

        java.util.LinkedHashMap<String, Recording> byStem = new java.util.LinkedHashMap<>();
        List<String[]> osd = new ArrayList<>();

        for (String line : out.split("\n")) {
            line = line.trim();
            if (line.isEmpty()) continue;
            String[] parts = line.split("\\|");
            if (parts.length < 3) continue;
            String name = parts[0];
            long sz;
            long mt;
            try {
                sz = Long.parseLong(parts[1].trim());
                mt = Long.parseLong(parts[2].trim());
            } catch (NumberFormatException e) {
                continue;
            }
            if (name.endsWith(".ts")) {
                String stem = name.substring(0, name.length() - 3);
                Recording r = new Recording(stem);
                r.tsName = name;
                r.remoteSize = sz;
                r.mtimeEpoch = mt;
                r.onDrone = true;
                byStem.put(stem, r);
            } else if (name.endsWith(".osd")) {
                osd.add(new String[]{name.substring(0, name.length() - 4), name});
            }
        }
        for (String[] o : osd) {
            Recording r = byStem.get(o[0]);
            if (r != null) r.osdName = o[1];
        }
        return new ArrayList<>(byStem.values());
    }

    /** Stream a remote file to {@code dest} via {@code cat}. Returns bytes written. */
    public long download(String remoteName, long expectedSize, File dest, Progress cb)
            throws Exception {
        String cmd = "cat '" + REMOTE_DIR + "/" + remoteName + "'";
        ChannelExec ch = (ChannelExec) session.openChannel("exec");
        ch.setCommand(cmd);
        ch.setInputStream(null);
        InputStream in = ch.getInputStream();
        long total = 0;
        try (OutputStream fout = new FileOutputStream(dest)) {
            ch.connect();
            byte[] buf = new byte[64 * 1024];
            int n;
            long lastCb = 0;
            while ((n = in.read(buf)) != -1) {
                fout.write(buf, 0, n);
                total += n;
                if (cb != null && (total - lastCb) > 256 * 1024) {
                    lastCb = total;
                    cb.onBytes(total, expectedSize);
                }
            }
            fout.flush();
        } finally {
            disconnect(ch);
        }
        if (cb != null) cb.onBytes(total, expectedSize);
        int code = ch.getExitStatus();
        if (code != 0 && code != -1) {
            throw new IOException("Remote cat exited " + code + " for " + remoteName);
        }
        return total;
    }

    /**
     * Pull only the first {@code bytes} of a remote file (for a thumbnail). The
     * clip starts on a keyframe, so the first few MB contain a decodable frame.
     */
    public long downloadPrefix(String remoteName, int bytes, File dest) throws Exception {
        String cmd = "head -c " + bytes + " '" + REMOTE_DIR + "/" + remoteName + "'";
        ChannelExec ch = (ChannelExec) session.openChannel("exec");
        ch.setCommand(cmd);
        ch.setInputStream(null);
        InputStream in = ch.getInputStream();
        long total = 0;
        try (OutputStream fout = new FileOutputStream(dest)) {
            ch.connect();
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) != -1) {
                fout.write(buf, 0, n);
                total += n;
            }
            fout.flush();
        } finally {
            disconnect(ch);
        }
        return total;
    }

    /** Delete one or more files from the recordings dir. */
    public void delete(String... names) throws Exception {
        StringBuilder sb = new StringBuilder("rm -f");
        for (String n : names) {
            if (n == null) continue;
            sb.append(" '").append(REMOTE_DIR).append("/").append(n).append("'");
        }
        exec(sb.toString());
    }

    // ── internals ───────────────────────────────────────────────────────

    private String exec(String cmd) throws Exception {
        ChannelExec ch = (ChannelExec) session.openChannel("exec");
        ch.setCommand(cmd);
        ch.setInputStream(null);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        InputStream in = ch.getInputStream();
        try {
            ch.connect();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        } finally {
            disconnect(ch);
        }
        return out.toString("UTF-8");
    }

    private static void disconnect(Channel ch) {
        try { ch.disconnect(); } catch (Exception ignored) {}
    }
}
