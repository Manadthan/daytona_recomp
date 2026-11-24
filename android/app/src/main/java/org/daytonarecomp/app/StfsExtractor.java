package org.daytonarecomp.app;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Extracts the Daytona USA XBLA LIVE/STFS package, like scripts/extract_game.py
 * (a line-for-line port, so the two agree on the block maths). Plain Java with
 * no Android dependency, so it can be checked on a PC against the Python tool.
 */
public final class StfsExtractor {
    public interface Progress {
        void onProgress(long done, long total);
    }

    private static final int BLOCK_SIZE = 0x1000;
    private static final int FILE_TABLE_OFFSET = 0xC000;
    private static final int FILE_TABLE_ENTRY_SIZE = 0x40;
    private static final int ROOT_PARENT = 0xFFFF;
    private static final int END_OF_CHAIN = 0xFFFFFF;

    private static final class Entry {
        int index;
        String name;
        int flags;
        int blocks;
        int startBlock;
        int parent;
        long size;

        boolean isDirectory() {
            return (flags & 0x80) != 0;
        }
    }

    private StfsExtractor() {}

    public static boolean isLivePackage(byte[] head) {
        return head.length >= 4 && head[0] == 'L' && head[1] == 'I' && head[2] == 'V' && head[3] == 'E';
    }

    /** Extracts every file of the package into dest. Returns the file count. */
    public static int extract(File packageFile, File dest, Progress progress) throws IOException {
        try (RandomAccessFile pkg = new RandomAccessFile(packageFile, "r")) {
            byte[] magic = new byte[4];
            pkg.readFully(magic);
            if (!isLivePackage(magic)) {
                throw new IOException("ce fichier n'est pas un paquet Xbox 360 LIVE/STFS");
            }
            List<Entry> entries = parseEntries(pkg);
            long total = 0;
            for (Entry e : entries) {
                if (!e.isDirectory()) {
                    total += e.size;
                }
            }
            String destRoot = dest.getCanonicalPath() + File.separator;
            long[] done = {0};
            int count = 0;
            boolean sawXex = false;
            for (Entry e : entries) {
                File out = new File(dest, entryPath(e, entries));
                if (!out.getCanonicalPath().startsWith(destRoot)) {
                    throw new IOException("chemin invalide dans le paquet : " + e.name);
                }
                if (e.isDirectory()) {
                    out.mkdirs();
                    continue;
                }
                extractFile(pkg, e, out, done, total, progress);
                count++;
                if (e.name.equals("default.xex") && e.parent == ROOT_PARENT) {
                    sawXex = true;
                }
            }
            if (!sawXex) {
                throw new IOException("le paquet ne contient pas default.xex");
            }
            return count;
        }
    }

    private static long physicalBlock(long logical) {
        long group = logical / 0xAA;
        long level1Groups = group / 0xAA;
        long level1Overhead = level1Groups > 0 ? level1Groups + 1 : 0;
        return logical + 0x0C + group + (group > 0 ? 1 : 0) + level1Overhead;
    }

    private static long hashEntryOffset(long logical) {
        long group = logical / 0xAA;
        long index = logical % 0xAA;
        long level1Groups = group / 0xAA;
        long level1Overhead = level1Groups > 0 ? level1Groups + 1 : 0;
        long tableBlock = 0x0B + (group * 0xAB) + (group > 0 ? 1 : 0) + level1Overhead;
        return tableBlock * BLOCK_SIZE + index * 0x18;
    }

    private static int u24le(byte[] b, int o) {
        return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8) | ((b[o + 2] & 0xFF) << 16);
    }

    private static int nextBlock(RandomAccessFile pkg, long logical) throws IOException {
        byte[] b = new byte[3];
        pkg.seek(hashEntryOffset(logical) + 0x15);
        pkg.readFully(b);
        return ((b[0] & 0xFF) << 16) | ((b[1] & 0xFF) << 8) | (b[2] & 0xFF);
    }

    private static List<Entry> parseEntries(RandomAccessFile pkg) throws IOException {
        List<Entry> entries = new ArrayList<>();
        byte[] raw = new byte[FILE_TABLE_ENTRY_SIZE];
        pkg.seek(FILE_TABLE_OFFSET);
        int index = 0;
        while (true) {
            int n = pkg.read(raw);
            if (n != FILE_TABLE_ENTRY_SIZE || allZero(raw)) {
                break;
            }
            int nameFlags = raw[0x28] & 0xFF;
            int nameLen = nameFlags & 0x3F;
            if (nameLen == 0) {
                break;
            }
            Entry e = new Entry();
            e.index = index++;
            e.name = new String(raw, 0, nameLen, StandardCharsets.US_ASCII);
            e.flags = nameFlags;
            e.blocks = u24le(raw, 0x29);
            e.startBlock = u24le(raw, 0x2F);
            e.parent = ((raw[0x32] & 0xFF) << 8) | (raw[0x33] & 0xFF);
            e.size = ((long) (raw[0x34] & 0xFF) << 24) | ((raw[0x35] & 0xFF) << 16)
                    | ((raw[0x36] & 0xFF) << 8) | (raw[0x37] & 0xFF);
            entries.add(e);
        }
        return entries;
    }

    private static boolean allZero(byte[] b) {
        for (byte x : b) {
            if (x != 0) {
                return false;
            }
        }
        return true;
    }

    private static String entryPath(Entry entry, List<Entry> entries) throws IOException {
        StringBuilder path = new StringBuilder(entry.name);
        Set<Integer> seen = new HashSet<>();
        seen.add(entry.index);
        int parent = entry.parent;
        while (parent != ROOT_PARENT) {
            if (parent >= entries.size() || !seen.add(parent)) {
                throw new IOException("chaîne de dossiers invalide pour " + entry.name);
            }
            Entry p = entries.get(parent);
            path.insert(0, p.name + "/");
            parent = p.parent;
        }
        return path.toString();
    }

    private static void extractFile(RandomAccessFile pkg, Entry e, File out, long[] done, long total,
                                    Progress progress) throws IOException {
        File parentDir = out.getParentFile();
        if (parentDir != null) {
            parentDir.mkdirs();
        }
        long remaining = e.size;
        long blocksToCopy = Math.max(e.blocks, (e.size + BLOCK_SIZE - 1) / BLOCK_SIZE);
        long logical = e.startBlock;
        byte[] buf = new byte[BLOCK_SIZE];
        try (OutputStream os = new FileOutputStream(out)) {
            for (long i = 0; i < blocksToCopy && remaining > 0; i++) {
                if (logical == END_OF_CHAIN) {
                    throw new IOException("fin de chaîne inattendue dans " + e.name);
                }
                int want = (int) Math.min(BLOCK_SIZE, remaining);
                pkg.seek(physicalBlock(logical) * BLOCK_SIZE);
                pkg.readFully(buf, 0, want);
                os.write(buf, 0, want);
                remaining -= want;
                done[0] += want;
                if (progress != null && (i & 63) == 0) {
                    progress.onProgress(done[0], total);
                }
                if (i + 1 < blocksToCopy) {
                    logical = nextBlock(pkg, logical);
                }
            }
        }
        if (progress != null) {
            progress.onProgress(done[0], total);
        }
    }
}
