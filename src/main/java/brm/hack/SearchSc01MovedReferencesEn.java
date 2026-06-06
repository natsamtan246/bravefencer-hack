package brm.hack;

import java.io.File;
import java.io.FileInputStream;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import brm.Conf;

public class SearchSc01MovedReferencesEn {

    /*
     * Balanced expansion test:
     *
     * Expanded:
     *   0x060890, original len 0x50
     *
     * Moved middle range:
     *   0x0608E0 through before 0x060AA4
     *
     * Insert delta:
     *   +0x32
     */
    private static final int MOVED_START = 0x0608E0;
    private static final int MOVED_END_EXCLUSIVE = 0x060AA4;
    private static final int DELTA = 0x32;

    private static final int RAM_BASE = 0x80110000;

    private static final int CONTEXT_BYTES = 32;
    private static final int MAX_LOW16_HITS_TO_PRINT = 300;
    private static final int MAX_24BIT_HITS_TO_PRINT = 300;
    private static final int MAX_EXACT_HITS_TO_PRINT_PER_PATTERN = 80;

    private static final Target[] TARGETS = new Target[] {
            new Target(0x0608E0, "rows 1152-1156"),
            new Target(0x06092C, "rows 1157-1163"),
            new Target(0x060978, "rows 1164-1169"),
            new Target(0x0609C4, "rows 1170-1173"),
            new Target(0x0609FC, "rows 1174-1181")
    };

    public static void main(String[] args) throws Exception {
        File sc01Dir = new File(Conf.desktop + "brmen/SC01/");
        File report = new File(Conf.desktop + "sc01-moved-reference-search-en.txt");

        if (!sc01Dir.exists()) {
            throw new RuntimeException("SC01 directory not found: " + sc01Dir.getAbsolutePath());
        }

        List<File> files = new ArrayList<File>();
        collectFiles(sc01Dir, files);

        Collections.sort(files, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return normalize(a).compareTo(normalize(b));
            }
        });

        PrintWriter out = new PrintWriter(report, "UTF-8");

        out.println("Brave Fencer Musashi English SC01 Moved Reference Search");
        out.println("========================================================");
        out.println();
        out.println("This report does not modify anything.");
        out.println();
        out.println("Search root:");
        out.println("  " + sc01Dir.getAbsolutePath());
        out.println();
        out.println("Moved range from balanced expansion test:");
        out.println("  Full addresses: " + hex(MOVED_START, 6)
                + " through before " + hex(MOVED_END_EXCLUSIVE, 6));
        out.println("  Local offsets:  " + hex(MOVED_START & 0xFFFF, 4)
                + " through before " + hex(MOVED_END_EXCLUSIVE & 0xFFFF, 4));
        out.println("  RAM pointers:   " + hex(RAM_BASE + (MOVED_START & 0xFFFF), 8)
                + " through before " + hex(RAM_BASE + (MOVED_END_EXCLUSIVE & 0xFFFF), 8));
        out.println("  Delta needed:   +" + DELTA + " decimal / +" + hexNoPrefix(DELTA, 4));
        out.println();

        printTargets(out);

        out.println();
        out.println("FILES SCANNED");
        out.println("-------------");
        out.println("Total files: " + files.size());
        out.println();

        searchAdjustableRam32(out, files);
        searchExactTargetPatterns(out, files);
        searchLow16Range(out, files);
        searchLow16Clusters(out, files);
        search24BitRange(out, files);

        out.println();
        out.println("INTERPRETATION");
        out.println("--------------");
        out.println("Most useful results are:");
        out.println("1. ADJUSTABLE RAM32 HITS outside SC01/006/0.4.");
        out.println("2. EXACT TARGET HITS for missing blocks, especially 0x0608E0, 0x06092C, 0x060978, and 0x0609C4.");
        out.println("3. LOW16 CLUSTERS where multiple moved-range offsets appear near each other.");
        out.println();
        out.println("If the active references are found, the next test is to patch only that specific file/table by +0x32.");
        out.println("Do not patch broad LOW16 hits blindly; they are noisy.");
        out.println();

        out.close();

        System.out.println("SC01 moved reference search written to:");
        System.out.println(report.getAbsolutePath());
    }

    private static void printTargets(PrintWriter out) {
        out.println("TARGET BLOCKS");
        out.println("-------------");

        for (int i = 0; i < TARGETS.length; i++) {
            Target t = TARGETS[i];

            int local = t.address & 0xFFFF;
            int ram = RAM_BASE + local;

            out.println(
                    hex(t.address, 6)
                            + " local " + hex(local, 4)
                            + " RAM " + hex(ram, 8)
                            + " RAM bytes " + bytesToHex(int32LE(ram))
                            + " file32 bytes " + bytesToHex(int32LE(t.address))
                            + " " + t.note
            );
        }
    }

    private static void searchAdjustableRam32(PrintWriter out, List<File> files) throws Exception {
        out.println();
        out.println("ADJUSTABLE RAM32 HITS");
        out.println("---------------------");
        out.println("Searches all 32-bit little-endian values where:");
        out.println("  value = 0x80110000 + local");
        out.println("  local is inside 0x08E0 through before 0x0AA4");
        out.println();

        int total = 0;

        for (File file : files) {
            byte[] data = readAll(file);
            boolean printedHeader = false;

            for (int pos = 0; pos + 3 < data.length; pos++) {
                int value = readInt32LE(data, pos);
                int local = value - RAM_BASE;

                if (local >= (MOVED_START & 0xFFFF)
                        && local < (MOVED_END_EXCLUSIVE & 0xFFFF)) {
                    if (!printedHeader) {
                        out.println("File: " + rel(file));
                        printedHeader = true;
                    }

                    int newValue = value + DELTA;

                    out.println("  hit at " + hex(pos, 8)
                            + " value " + hex(value, 8)
                            + " local " + hex(local, 4)
                            + " would become " + hex(newValue, 8));
                    out.println("    " + context(data, pos, 4));

                    total++;
                }
            }

            if (printedHeader) {
                out.println();
            }
        }

        if (total == 0) {
            out.println("No adjustable RAM32 hits found.");
            out.println();
        } else {
            out.println("Total adjustable RAM32 hits: " + total);
            out.println();
        }
    }

    private static void searchExactTargetPatterns(PrintWriter out, List<File> files) throws Exception {
        out.println();
        out.println("EXACT TARGET HITS");
        out.println("-----------------");
        out.println("Searches exact bytes for each moved text block in several possible formats.");
        out.println();

        PatternSet[] patternSets = buildPatternSets();

        for (int p = 0; p < patternSets.length; p++) {
            PatternSet patternSet = patternSets[p];

            out.println();
            out.println("Pattern: " + patternSet.name);
            out.println("Bytes:   " + bytesToHex(patternSet.bytes));
            out.println("Target:  " + patternSet.targetDescription);
            out.println();

            int hitsForPattern = 0;
            boolean capped = false;

            for (File file : files) {
                byte[] data = readAll(file);
                List<Integer> hits = findAll(data, patternSet.bytes);

                for (int i = 0; i < hits.size(); i++) {
                    if (hitsForPattern >= MAX_EXACT_HITS_TO_PRINT_PER_PATTERN) {
                        capped = true;
                        break;
                    }

                    int hit = hits.get(i).intValue();

                    out.println("  " + rel(file) + " @ " + hex(hit, 8));
                    out.println("    " + context(data, hit, patternSet.bytes.length));

                    hitsForPattern++;
                }

                if (capped) {
                    break;
                }
            }

            if (hitsForPattern == 0) {
                out.println("  no hits");
            } else {
                out.println("  printed hits: " + hitsForPattern
                        + (capped ? " capped" : ""));
            }
        }

        out.println();
    }

    private static void searchLow16Range(PrintWriter out, List<File> files) throws Exception {
        out.println();
        out.println("LOW16 RANGE HITS");
        out.println("----------------");
        out.println("Searches little-endian 16-bit values inside 0x08E0 through before 0x0AA4.");
        out.println("These are noisy. Use them only if they appear in a cluster/table.");
        out.println();

        int total = 0;
        boolean capped = false;

        for (File file : files) {
            byte[] data = readAll(file);
            boolean printedHeader = false;

            for (int pos = 0; pos + 1 < data.length; pos++) {
                int value = readUInt16LE(data, pos);

                if (value >= (MOVED_START & 0xFFFF)
                        && value < (MOVED_END_EXCLUSIVE & 0xFFFF)) {
                    if (total >= MAX_LOW16_HITS_TO_PRINT) {
                        capped = true;
                        break;
                    }

                    if (!printedHeader) {
                        out.println("File: " + rel(file));
                        printedHeader = true;
                    }

                    out.println("  hit at " + hex(pos, 8)
                            + " value " + hex(value, 4)
                            + " would become " + hex(value + DELTA, 4));
                    out.println("    " + context(data, pos, 2));

                    total++;
                }
            }

            if (printedHeader) {
                out.println();
            }

            if (capped) {
                break;
            }
        }

        if (total == 0) {
            out.println("No LOW16 range hits found.");
        } else {
            out.println("Printed LOW16 hits: " + total
                    + (capped ? " capped" : ""));
        }

        out.println();
    }

    private static void searchLow16Clusters(PrintWriter out, List<File> files) throws Exception {
        out.println();
        out.println("LOW16 CLUSTERS");
        out.println("--------------");
        out.println("Looks for 64-byte windows with at least 3 little-endian LOW16 values inside the moved range.");
        out.println("These are more interesting than single LOW16 hits.");
        out.println();

        int clusterCount = 0;

        for (File file : files) {
            byte[] data = readAll(file);

            for (int start = 0; start < data.length; start += 16) {
                int end = Math.min(data.length, start + 64);
                int count = 0;

                for (int pos = start; pos + 1 < end; pos++) {
                    int value = readUInt16LE(data, pos);

                    if (value >= (MOVED_START & 0xFFFF)
                            && value < (MOVED_END_EXCLUSIVE & 0xFFFF)) {
                        count++;
                    }
                }

                if (count >= 3) {
                    out.println("Possible LOW16 cluster in " + rel(file)
                            + " around " + hex(start, 8)
                            + " count " + count);
                    out.println("  " + contextWindow(data, start, 64));
                    out.println();

                    clusterCount++;
                }
            }
        }

        if (clusterCount == 0) {
            out.println("No LOW16 clusters found.");
            out.println();
        } else {
            out.println("Total LOW16 clusters: " + clusterCount);
            out.println();
        }
    }

    private static void search24BitRange(PrintWriter out, List<File> files) throws Exception {
        out.println();
        out.println("24-BIT RANGE HITS");
        out.println("-----------------");
        out.println("Searches 24-bit little-endian values in two styles:");
        out.println("  file address low24: E0 08 06");
        out.println("  RAM pointer low24:  E0 08 11");
        out.println();

        int printed = 0;
        boolean capped = false;

        for (File file : files) {
            byte[] data = readAll(file);
            boolean printedHeader = false;

            for (int pos = 0; pos + 2 < data.length; pos++) {
                int value24 = readUInt24LE(data, pos);

                boolean fileAddressStyle = value24 >= MOVED_START
                        && value24 < MOVED_END_EXCLUSIVE;

                int ramLow24Start = (RAM_BASE + (MOVED_START & 0xFFFF)) & 0xFFFFFF;
                int ramLow24End = (RAM_BASE + (MOVED_END_EXCLUSIVE & 0xFFFF)) & 0xFFFFFF;

                boolean ramStyle = value24 >= ramLow24Start
                        && value24 < ramLow24End;

                if (fileAddressStyle || ramStyle) {
                    if (printed >= MAX_24BIT_HITS_TO_PRINT) {
                        capped = true;
                        break;
                    }

                    if (!printedHeader) {
                        out.println("File: " + rel(file));
                        printedHeader = true;
                    }

                    out.println("  hit at " + hex(pos, 8)
                            + " value24 " + hex(value24, 6)
                            + (fileAddressStyle ? " FILE24" : "")
                            + (ramStyle ? " RAM24" : ""));
                    out.println("    " + context(data, pos, 3));

                    printed++;
                }
            }

            if (printedHeader) {
                out.println();
            }

            if (capped) {
                break;
            }
        }

        if (printed == 0) {
            out.println("No 24-bit moved-range hits found.");
        } else {
            out.println("Printed 24-bit hits: " + printed
                    + (capped ? " capped" : ""));
        }

        out.println();
    }

    private static PatternSet[] buildPatternSets() {
        List<PatternSet> list = new ArrayList<PatternSet>();

        for (int i = 0; i < TARGETS.length; i++) {
            Target t = TARGETS[i];

            int local = t.address & 0xFFFF;
            int ram = RAM_BASE + local;

            list.add(new PatternSet(
                    "RAM32 LE for " + hex(t.address, 6),
                    int32LE(ram),
                    hex(t.address, 6) + " " + t.note + " as " + hex(ram, 8)
            ));

            list.add(new PatternSet(
                    "FILE32 LE for " + hex(t.address, 6),
                    int32LE(t.address),
                    hex(t.address, 6) + " as 32-bit file address"
            ));

            list.add(new PatternSet(
                    "LOW16 LE for " + hex(t.address, 6),
                    uint16LE(local),
                    hex(t.address, 6) + " local " + hex(local, 4)
            ));

            list.add(new PatternSet(
                    "FILE24 LE for " + hex(t.address, 6),
                    uint24LE(t.address),
                    hex(t.address, 6) + " as 24-bit file address"
            ));

            list.add(new PatternSet(
                    "RAM24 LE for " + hex(t.address, 6),
                    uint24LE(ram),
                    hex(t.address, 6) + " as low 24 bits of RAM pointer " + hex(ram, 8)
            ));
        }

        PatternSet[] arr = new PatternSet[list.size()];

        for (int i = 0; i < list.size(); i++) {
            arr[i] = list.get(i);
        }

        return arr;
    }

    private static void collectFiles(File dir, List<File> files) {
        File[] children = dir.listFiles();

        if (children == null) {
            return;
        }

        for (int i = 0; i < children.length; i++) {
            File child = children[i];

            if (child.isDirectory()) {
                collectFiles(child, files);
            } else {
                String name = child.getName().toLowerCase();

                if (name.endsWith(".bak")) {
                    continue;
                }

                files.add(child);
            }
        }
    }

    private static List<Integer> findAll(byte[] data, byte[] pattern) {
        List<Integer> hits = new ArrayList<Integer>();

        for (int i = 0; i <= data.length - pattern.length; i++) {
            boolean match = true;

            for (int j = 0; j < pattern.length; j++) {
                if (data[i + j] != pattern[j]) {
                    match = false;
                    break;
                }
            }

            if (match) {
                hits.add(Integer.valueOf(i));
            }
        }

        return hits;
    }

    private static byte[] readAll(File file) throws Exception {
        FileInputStream in = new FileInputStream(file);
        byte[] data = new byte[(int) file.length()];

        int offset = 0;

        while (offset < data.length) {
            int read = in.read(data, offset, data.length - offset);

            if (read < 0) {
                break;
            }

            offset += read;
        }

        in.close();

        return data;
    }

    private static int readInt32LE(byte[] data, int offset) {
        int b0 = data[offset] & 0xFF;
        int b1 = data[offset + 1] & 0xFF;
        int b2 = data[offset + 2] & 0xFF;
        int b3 = data[offset + 3] & 0xFF;

        return b0 | (b1 << 8) | (b2 << 16) | (b3 << 24);
    }

    private static int readUInt16LE(byte[] data, int offset) {
        int b0 = data[offset] & 0xFF;
        int b1 = data[offset + 1] & 0xFF;

        return b0 | (b1 << 8);
    }

    private static int readUInt24LE(byte[] data, int offset) {
        int b0 = data[offset] & 0xFF;
        int b1 = data[offset + 1] & 0xFF;
        int b2 = data[offset + 2] & 0xFF;

        return b0 | (b1 << 8) | (b2 << 16);
    }

    private static byte[] int32LE(int value) {
        return new byte[] {
                (byte) (value & 0xFF),
                (byte) ((value >> 8) & 0xFF),
                (byte) ((value >> 16) & 0xFF),
                (byte) ((value >> 24) & 0xFF)
        };
    }

    private static byte[] uint24LE(int value) {
        return new byte[] {
                (byte) (value & 0xFF),
                (byte) ((value >> 8) & 0xFF),
                (byte) ((value >> 16) & 0xFF)
        };
    }

    private static byte[] uint16LE(int value) {
        return new byte[] {
                (byte) (value & 0xFF),
                (byte) ((value >> 8) & 0xFF)
        };
    }

    private static String context(byte[] data, int hit, int len) {
        int start = Math.max(0, hit - CONTEXT_BYTES);
        int end = Math.min(data.length, hit + len + CONTEXT_BYTES);

        StringBuilder sb = new StringBuilder();

        for (int i = start; i < end; i++) {
            if (i > start) {
                sb.append(' ');
            }

            if (i == hit) {
                sb.append('[');
            }

            sb.append(String.format("%02X", data[i] & 0xFF));

            if (i == hit + len - 1) {
                sb.append(']');
            }
        }

        return sb.toString();
    }

    private static String contextWindow(byte[] data, int start, int len) {
        int safeStart = Math.max(0, start);
        int safeEnd = Math.min(data.length, start + len);

        StringBuilder sb = new StringBuilder();

        for (int i = safeStart; i < safeEnd; i++) {
            if (i > safeStart) {
                sb.append(' ');
            }

            sb.append(String.format("%02X", data[i] & 0xFF));
        }

        return sb.toString();
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();

        for (int i = 0; i < bytes.length; i++) {
            if (i > 0) {
                sb.append(' ');
            }

            sb.append(String.format("%02X", bytes[i] & 0xFF));
        }

        return sb.toString();
    }

    private static String rel(File file) {
        String full = normalize(file);
        String root = normalize(new File(Conf.desktop + "brmen/"));

        if (full.startsWith(root)) {
            return full.substring(root.length());
        }

        return full;
    }

    private static String normalize(File file) {
        return file.getAbsolutePath().replace("\\", "/");
    }

    private static String hex(int value, int digits) {
        String s = Integer.toHexString(value).toUpperCase();

        while (s.length() < digits) {
            s = "0" + s;
        }

        return "0x" + s;
    }

    private static String hexNoPrefix(int value, int digits) {
        String s = Integer.toHexString(value).toUpperCase();

        while (s.length() < digits) {
            s = "0" + s;
        }

        return s;
    }

    private static class Target {
        int address;
        String note;

        Target(int address, String note) {
            this.address = address;
            this.note = note;
        }
    }

    private static class PatternSet {
        String name;
        byte[] bytes;
        String targetDescription;

        PatternSet(String name, byte[] bytes, String targetDescription) {
            this.name = name;
            this.bytes = bytes;
            this.targetDescription = targetDescription;
        }
    }
}