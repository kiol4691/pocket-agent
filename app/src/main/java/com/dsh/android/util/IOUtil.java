package com.dsh.android.util;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.zip.GZIPInputStream;

/** File helpers and a minimal ustar/POSIX tar.gz extractor (no external deps). */
public final class IOUtil {

    private IOUtil() {
    }

    public static void writeText(File f, String text) throws IOException {
        File parent = f.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("Cannot create directory " + parent);
        }
        try (Writer w = new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8)) {
            w.write(text);
        }
    }

    public static byte[] readBytes(File f, int maxBytes) throws IOException {
        try (InputStream in = new java.io.FileInputStream(f)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while (bos.size() < maxBytes && (n = in.read(buf)) != -1) {
                int allowed = Math.min(n, maxBytes - bos.size());
                bos.write(buf, 0, allowed);
            }
            return bos.toByteArray();
        }
    }

    public static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "\n…[truncated]";
    }

    public static void copyFile(File src, File dst) throws IOException {
        try (InputStream in = new java.io.FileInputStream(src);
             FileOutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        }
    }

    public static void deleteRecursively(File f) {
        if (!f.exists()) return;
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) for (File c : children) deleteRecursively(c);
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    public static void extractTarGz(File tarball, File destDir) throws IOException {
        try (InputStream fin = new java.io.FileInputStream(tarball);
             GZIPInputStream gzin = new GZIPInputStream(fin)) {
            extractTar(gzin, destDir);
        }
    }

    /** Extract from an already-open stream (e.g. an APK asset); caller owns closing {@code in}. */
    public static void extractTarGz(InputStream in, File destDir) throws IOException {
        try (GZIPInputStream gzin = new GZIPInputStream(in)) {
            extractTar(gzin, destDir);
        }
    }

    /** Minimal ustar/PAX/GNU extractor: regular files, dirs, symlinks, hardlinks, PAX 'x'/'g' and GNU 'L'/'K' headers, with perms. */
    public static void extractTar(InputStream in, File destDir) throws IOException {
        if (!destDir.isDirectory() && !destDir.mkdirs()) {
            throw new IOException("Cannot create " + destDir);
        }
        byte[] block = new byte[512];
        long t0 = System.currentTimeMillis();
        int entryCount = 0;
        // Overrides pending from any PAX/GNU extended header, applied to the next real entry.
        String paxPath = null;
        String paxLink = null;
        long paxSize = -1;
        int paxMode = -1;

        while (true) {
            int got = readFully(in, block);
            if (got == 0) break;
            if (got != 512) throw new IOException("Truncated tar header");

            String name = cstr(block, 0, 100);
            if (name.isEmpty()) break; // end-of-archive marker

            String prefix = cstr(block, 345, 155);
            String headerName = prefix.isEmpty() ? name : prefix + "/" + name;
            long size = parseOctal(cstr(block, 124, 12));
            int type = block[156] == 0 ? '0' : (block[156] & 0xff);
            int mode = (int) parseOctal(cstr(block, 100, 8));
            String linkName = cstr(block, 157, 100);

            entryCount++;
            if (entryCount <= 20 || entryCount % 500 == 0) {
                DebugLog.i("extract: #" + entryCount + " " + headerName
                        + " type=" + (char) type + " size=" + size
                        + " (" + (System.currentTimeMillis() - t0) + "ms)");
            }

            if (paxSize >= 0) size = paxSize;
            if (paxMode >= 0) mode = paxMode;
            String full = paxPath != null ? paxPath : headerName;
            if (paxLink != null) linkName = paxLink;
            paxPath = null;
            paxLink = null;
            paxSize = -1;
            paxMode = -1;

            if (type == 'x' || type == 'g' || type == 'L' || type == 'K') { // extended headers carry no payload files
                byte[] pd = readExact(in, size);
                if (type == 'x' || type == 'g') {
                    Pax pax = parsePax(pd);
                    if (pax.path != null) paxPath = pax.path;
                    if (pax.link != null) paxLink = pax.link;
                    if (pax.size >= 0) paxSize = pax.size;
                    if (pax.mode >= 0) paxMode = pax.mode;
                } else { // 'L' long path, 'K' long link target
                    int end = 0;
                    while (end < pd.length && pd[end] != 0) end++;
                    String s = new String(pd, 0, end, StandardCharsets.UTF_8);
                    if (type == 'L') paxPath = s;
                    else paxLink = s;
                }
            } else {
                String clean = full.replaceAll("^\\./+", "");
                if (!clean.isEmpty() && !clean.contains("..")) {
                    File out = new File(destDir, clean);
                    try {
                        switch (type) {
                            case '1': { // hard link
                                DebugLog.i("X: hardlink " + full + " -> " + linkName);
                                out.getParentFile().mkdirs();
                                Files.createLink(out.toPath(), new File(destDir, linkName).toPath());
                                DebugLog.i("X: hardlink done");
                                break;
                            }
                            case '2': { // symbolic link
                                DebugLog.i("X: symlink " + full + " -> " + linkName);
                                out.getParentFile().mkdirs();
                                Files.createSymbolicLink(out.toPath(), new File(linkName).toPath());
                                DebugLog.i("X: symlink done");
                                break;
                            }
                            case '5': // directory
                                out.mkdirs();
                                break;
                            default: // regular file
                                out.getParentFile().mkdirs();
                                writeFileFromTar(in, out, size);
                                break;
                        }
                        if (mode > 0) applyMode(out, mode);
                    } catch (Exception e) {
                        DebugLog.w("X: entry failed (continuing): " + full + " " + e);
                    }
                } else if (size > 0) {
                    skipFully(in, size);
                }
            }
            long rem = size % 512;
            if (rem != 0) skipFully(in, 512 - rem);
        }
    }

    /** Apply owner-relevant POSIX mode bits (the app owns the whole tree). */
    private static void applyMode(File f, int mode) {
        f.setReadable((mode & 0400) != 0, false);
        f.setWritable((mode & 0200) != 0, false);
        f.setExecutable((mode & 0100) != 0, false);
    }

    /** Reads exactly {@code size} bytes (may be 0). */
    private static byte[] readExact(InputStream in, long size) throws IOException {
        if (size > Integer.MAX_VALUE) throw new IOException("Tar entry too large: " + size);
        byte[] buf = new byte[(int) size];
        int off = 0;
        while (off < buf.length) {
            int n = in.read(buf, off, buf.length - off);
            if (n < 0) throw new IOException("Truncated tar data");
            off += n;
        }
        return buf;
    }

    /** Parse a PAX payload: records are "len key=value\n"; numbers are decimal per spec. */
    private static Pax parsePax(byte[] pd) {
        Pax pax = new Pax();
        int i = 0;
        while (i < pd.length) {
            int sp = indexOf(pd, i, (byte) ' ');
            if (sp < 0) break;
            long len = 0;
            for (int k = i; k < sp; k++) len = len * 10 + (pd[k] - '0');
            if (len <= 0) { i = sp + 1; continue; }
            int eq = indexOf(pd, i, (byte) '=');
            int nl = indexOf(pd, i, (byte) '\n');
            if (eq < 0 || nl < 0) break;
            String key = new String(pd, sp + 1, eq - sp - 1, StandardCharsets.UTF_8);
            String value = new String(pd, eq + 1, nl - eq - 1, StandardCharsets.UTF_8);
            if ("path".equals(key)) pax.path = value;
            else if ("linkpath".equals(key)) pax.link = value;
            else if ("size".equals(key)) pax.size = parseLong(value, -1);
            else if ("mode".equals(key)) pax.mode = (int) parseLong(value, -1);
            i += (int) len; // len counts its own "len " prefix and the trailing \n
        }
        return pax;
    }

    private static long parseLong(String s, long def) {
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static int indexOf(byte[] b, int from, byte c) {
        for (int i = from; i < b.length; i++) if (b[i] == c) return i;
        return -1;
    }

    private static void writeFileFromTar(InputStream in, File out, long size) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(out)) {
            long left = size;
            byte[] buf = new byte[8192];
            while (left > 0) {
                int want = (int) Math.min(buf.length, left);
                int got = in.read(buf, 0, want);
                if (got < 0) throw new IOException("Truncated file data: " + out);
                fos.write(buf, 0, got);
                left -= got;
            }
        }
    }

    private static int readFully(InputStream in, byte[] buf) throws IOException {
        int off = 0;
        while (off < buf.length) {
            int n = in.read(buf, off, buf.length - off);
            if (n < 0) break;
            off += n;
        }
        return off;
    }

    private static void skipFully(InputStream in, long n) throws IOException {
        long left = n;
        while (left > 0) {
            long skipped = in.skip(left);
            if (skipped <= 0) {
                if (in.read() == -1) throw new IOException("Unexpected EOF in tar stream");
                left--;
            } else {
                left -= skipped;
            }
        }
    }

    private static String cstr(byte[] block, int off, int len) {
        int end = off;
        int max = off + len;
        while (end < max && block[end] != 0) end++;
        String s = new String(block, off, end - off, StandardCharsets.UTF_8).trim();
        while (s.startsWith("./")) s = s.substring(2);
        return s;
    }

    private static long parseOctal(String s) {
        long value = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '0' && c <= '7') value = (value << 3) + (c - '0');
        }
        return value;
    }

    private static final class Pax {
        String path;
        String link;
        long size = -1;
        int mode = -1;
    }
}