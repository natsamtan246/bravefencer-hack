package brm.hack;

import java.io.File;
import java.io.FileInputStream;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import brm.Conf;

public class SearchSc01RelativeOffsetsEn {

    /*
     * Balanced expansion test:
     *
     * Expanded block:
     *   0x060890, original len 0x50
     *
     * Moved middle range:
     *   0x0608E0 through before 0x060AA4
     *
     * Insert delta:
     *   +0x32
     */
    private static final int EXPAND_ADDRESS = 0x060890;
    private static final int MOVED_START = 0x0608E0;
    private static final int MOVED_END_EXCLUSIVE = 0x060AA4;
    private static final int DELTA = 0x32;

    private static final int CONTEXT_BYTES = 32;
    private static final int MIN_RUN = 3;
    private static final int MAX_RUNS_TO_PRINT = 200;
    private static final int MAX_EXACT_HITS_TO_PRINT = 120;

    /*
     * Known nearby text/script blocks from the SCRIPTS sheet.
     */
    private static final Target[] TARGETS = new Target[] {
            new Target(0x06083C, "rows 1138-1140 len 44"),
            new Target(0x060868, "rows 1141-1144 len 40"),
            new Target(0x060890, "rows 1145-1151 len 80 EXPANDED"),
            new Target(0x0608E0, "rows 1152-1156 len 76 MOVED"),
            new Target(0x06092C, "rows 1157-1163 len 76 MOVED"),
            new Target(0x060978, "rows 1164-1169 len 76 MOVED"),
            new Target(0x0609C4, "rows 1170-1173 len 56 MOVED"),
            new Target(0x0609FC, "rows 1174-1181 len 168 MOVED"),
            new Target(0x060AA4, "rows 1182-1193 len 252 SHRINK"),
            new Target(0x060BA0, "rows 1194-1202 len 168 AFTER")
    };

    /*
     * Candidate bases.
     *
     * 0x060890 is the most important one because the relative sequence would be:
     *   0x0000, 0x0050, 0x009C, 0x00E8, 0x0134, 0x016C, 0x0214, 0x0310
     */
    private static final Base[] BASES = new Base[] {
            new Base(0x060890, "expanded block start"),
            new Base(0x06083C, "two blocks before expanded block"),
            new Base(0x060868, "one block before expanded block"),
            new Base(0x0608E0, "first moved block"),
            new Base(0x060000, "script page base 0x060000"),
            new Base(0x060800, "nearby 0x060800 page base")
    };

    public static void main(String[] args) throws Exception {
        File sc01Dir = new File(Conf.desktop + "brmen/SC01/");
        File report = new File(Conf.desktop + "sc01-relative-offset-search-en.txt");

        if (!sc01Dir.exists()) {
            throw new RuntimeException("SC01 directory not found: " + sc01Dir.getAbsolutePath());
        }

        List<File> files = new ArrayList<File>();
        collectFiles(sc01Dir, files);

        Collections.sort(files, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return rel(a).compareTo(rel(b));
            }
        });

        PrintWriter out = new PrintWriter(report, "UTF-8");

        out.println("Brave Fencer Musashi English SC01 Relative Offset Search");
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
        out.println("  Delta needed:   +" + DELTA + " decimal / +" + hexNoPrefix(DELTA, 4));
        out.println();
        out.println("Files scanned: " + files.size());
        out.println();

        printTargetsAndBases(out);

        searchExactRelativeSequences(out, files);
        searchRelativeRuns(out, files);
        searchRelativeClusters(out, files);

        out.println();
        out.println("INTERPRETATION");
        out.println("--------------");
        out.println("Best results would be:");
        out.println("1. Exact sequence hits for base 0x060890.");
        out.println("2. Relative runs containing 0x0050, 0x009C, 0x00E8, 0x0134, or 0x016C.");
        out.println("3. Clusters in SC01/006/0.4 or a nearby controller file.");
        out.println();
        out.println("If we find the active table, the patch rule for base 0x060890 is:");
        out.println("  0x0050 -> 0x0082");
        out.println("  0x009C -> 0x00CE");
        out.println("  0x00E8 -> 0x011A");
        out.println("  0x0134 -> 0x0166");
        out.println("  0x016C -> 0x019E");
        out.println("  0x0214 -> usually unchanged or handled carefully, because this is the shrink block.");
        out.println();

        out.close();

        System.out.println("SC01 relative offset search written to:");
        System.out.println(report.getAbsolutePath());
    }

    private static void printTargetsAndBases(PrintWriter out) {
        out.println("KNOWN TARGET BLOCKS");
        out.println("-------------------");

        for (int i = 0; i < TARGETS.length; i++) {
            Target t = TARGETS[i];

            out.println(hex(t.address, 6) + " " + t.note);
        }

        out.println();
        out.println("CANDIDATE BASES AND RELATIVE VALUES");
        out.println("-----------------------------------");

        for (int b = 0; b < BASES.length; b++) {
            Base base = BASES[b];

            out.println();
            out.println("Base " + hex(base.address, 6) + " = " + base.note);

            for (int i = 0; i < TARGETS.length; i++) {
                Target t = TARGETS[i];
                int rel = t.address - base.address;

                if (rel < 0 || rel > 0xFFFF) {
                    continue;
                }

                out.println("  target " + hex(t.address, 6)
                        + " rel16 " + hex(rel, 4)
                        + " bytes " + bytesToHex(uint16LE(rel))
                        + " " + t.note);
            }
        }

        out.println();
    }

    private static void searchExactRelativeSequences(PrintWriter out, List<File> files) throws Exception {
        out.println();
        out.println("EXACT RELATIVE SEQUENCE HITS");
        out.println("----------------------------");
        out.println("Searches packed relative offset sequences.");
        out.println();

        List<PatternSet> patterns = new ArrayList<PatternSet>();

        for (int b = 0; b < BASES.length; b++) {
            Base base = BASES[b];

            addSequencePatternsForBase(patterns, base);
        }

        for (int p = 0; p < patterns.size(); p++) {
            PatternSet pattern = patterns.get(p);

            out.println();
            out.println("Pattern: " + pattern.name);
            out.println("Bytes:   " + bytesToHex(pattern.bytes));
            out.println();

            int printed = 0;
            boolean capped = false;

            for (int f = 0; f < files.size(); f++) {
                File file = files.get(f);
                byte[] data = readAll(file);

                List<Integer> hits = findAll(data, pattern.bytes);

                for (int i = 0; i < hits.size(); i++) {
                    if (printed >= MAX_EXACT_HITS_TO_PRINT) {
                        capped = true;
                        break;
                    }

                    int hit = hits.get(i).intValue();

                    out.println("  " + rel(file) + " @ " + hex(hit, 8));
                    out.println("    " + context(data, hit, pattern.bytes.length));

                    printed++;
                }

                if (capped) {
                    break;
                }
            }

            if (printed == 0) {
                out.println("  no hits");
            } else {
                out.println("  printed hits: " + printed + (capped ? " capped" : ""));
            }
        }

        out.println();
    }

    private static void addSequencePatternsForBase(List<PatternSet> patterns, Base base) {
        List<Integer> allOffsets = new ArrayList<Integer>();
        List<Integer> movedOffsets = new ArrayList<Integer>();
        List<Integer> movedPlusShrinkOffsets = new ArrayList<Integer>();

        for (int i = 0; i < TARGETS.length; i++) {
            Target t = TARGETS[i];
            int rel = t.address - base.address;

            if (rel < 0 || rel > 0xFFFF) {
                continue;
            }

            allOffsets.add(Integer.valueOf(rel));

            if (t.address >= MOVED_START && t.address < MOVED_END_EXCLUSIVE) {
                movedOffsets.add(Integer.valueOf(rel));
                movedPlusShrinkOffsets.add(Integer.valueOf(rel));
            }

            if (t.address == MOVED_END_EXCLUSIVE) {
                movedPlusShrinkOffsets.add(Integer.valueOf(rel));
            }
        }

        if (allOffsets.size() >= 3) {
            patterns.add(new PatternSet(
                    "packed16 ALL offsets from base " + hex(base.address, 6) + " " + base.note,
                    packed16(allOffsets)
            ));

            patterns.add(new PatternSet(
                    "packed32 ALL offsets from base " + hex(base.address, 6) + " " + base.note,
                    packed32(allOffsets)
            ));
        }

        if (movedOffsets.size() >= 3) {
            patterns.add(new PatternSet(
                    "packed16 MOVED offsets from base " + hex(base.address, 6) + " " + base.note,
                    packed16(movedOffsets)
            ));

            patterns.add(new PatternSet(
                    "packed32 MOVED offsets from base " + hex(base.address, 6) + " " + base.note,
                    packed32(movedOffsets)
            ));
        }

        if (movedPlusShrinkOffsets.size() >= 3) {
            patterns.add(new PatternSet(
                    "packed16 MOVED+SHRINK offsets from base " + hex(base.address, 6) + " " + base.note,
                    packed16(movedPlusShrinkOffsets)
            ));

            patterns.add(new PatternSet(
                    "packed32 MOVED+SHRINK offsets from base " + hex(base.address, 6) + " " + base.note,
                    packed32(movedPlusShrinkOffsets)
            ));
        }
    }

    private static void searchRelativeRuns(PrintWriter out, List<File> files) throws Exception {
        out.println();
        out.println("HIGH-PRIORITY RELATIVE RUNS");
        out.println("---------------------------");
        out.println("Searches little-endian 16-bit relative offsets with stride 2, 4, 8, 12, or 16.");
        out.println("Runs are sorted by score. These may still include false positives.");
        out.println();

        List<Run> allRuns = new ArrayList<Run>();

        for (int b = 0; b < BASES.length; b++) {
            Base base = BASES[b];
            Map<Integer, List<Target>> offsetMap = buildRelativeOffsetMap(base, false);

            for (int f = 0; f < files.size(); f++) {
                File file = files.get(f);
                byte[] data = readAll(file);

                allRuns.addAll(findRuns(file, data, base, offsetMap));
            }
        }

        Collections.sort(allRuns, new Comparator<Run>() {
            @Override
            public int compare(Run a, Run b) {
                int sa = a.score();
                int sb = b.score();

                if (sa != sb) {
                    return sb - sa;
                }

                if (a.entries.size() != b.entries.size()) {
                    return b.entries.size() - a.entries.size();
                }

                int c = a.relativeFile.compareTo(b.relativeFile);

                if (c != 0) {
                    return c;
                }

                return a.startOffset - b.startOffset;
            }
        });

        int printed = 0;

        for (int i = 0; i < allRuns.size(); i++) {
            Run run = allRuns.get(i);

            /*
             * Keep the report focused.
             */
            if (!run.touchesMovedRange && run.entries.size() < 4) {
                continue;
            }

            if (printed >= MAX_RUNS_TO_PRINT) {
                out.println("Stopped after " + MAX_RUNS_TO_PRINT + " runs.");
                break;
            }

            printRun(out, run);
            printed++;
        }

        if (printed == 0) {
            out.println("No relative runs found.");
        }

        out.println();
    }

    private static List<Run> findRuns(
            File file,
            byte[] data,
            Base base,
            Map<Integer, List<Target>> offsetMap
    ) {
        List<Run> runs = new ArrayList<Run>();

        int[] strides = new int[] {2, 4, 8, 12, 16};

        for (int s = 0; s < strides.length; s++) {
            int stride = strides[s];

            for (int start = 0; start <= data.length - 2; start++) {
                int prev = start - stride;

                if (prev >= 0 && prev + 1 < data.length) {
                    int prevValue = readUInt16LE(data, prev);

                    if (offsetMap.containsKey(Integer.valueOf(prevValue))) {
                        continue;
                    }
                }

                Run run = new Run();
                run.file = file;
                run.relativeFile = rel(file);
                run.data = data;
                run.base = base;
                run.startOffset = start;
                run.stride = stride;

                int pos = start;

                while (pos + 1 < data.length) {
                    int value = readUInt16LE(data, pos);
                    List<Target> matchedTargets = offsetMap.get(Integer.valueOf(value));

                    if (matchedTargets == null) {
                        break;
                    }

                    Target target = matchedTargets.get(0);

                    Entry entry = new Entry();
                    entry.fileOffset = pos;
                    entry.relOffset = value;
                    entry.target = target;

                    if (target.address >= MOVED_START && target.address < MOVED_END_EXCLUSIVE) {
                        entry.inMovedRange = true;
                        run.touchesMovedRange = true;
                    }

                    if (target.address == MOVED_END_EXCLUSIVE) {
                        entry.isShrinkStart = true;
                        run.touchesShrinkStart = true;
                    }

                    run.entries.add(entry);
                    pos += stride;
                }

                if (run.entries.size() >= MIN_RUN) {
                    runs.add(run);
                }
            }
        }

        return runs;
    }

    private static void printRun(PrintWriter out, Run run) {
        out.println("Run in " + run.relativeFile
                + " at " + hex(run.startOffset, 8)
                + " base " + hex(run.base.address, 6)
                + " [" + run.base.note + "]"
                + " stride " + run.stride
                + " matches " + run.entries.size()
                + " score " + run.score()
                + (run.touchesMovedRange ? " MOVED" : "")
                + (run.touchesShrinkStart ? " SHRINK" : ""));

        out.println("Context:");
        out.println("  " + context(run.data, run.startOffset, run.stride * run.entries.size()));

        out.println("Entries:");

        for (int i = 0; i < run.entries.size(); i++) {
            Entry e = run.entries.get(i);
            int patchedRel = e.relOffset;

            if (e.target.address >= MOVED_START && e.target.address < MOVED_END_EXCLUSIVE) {
                patchedRel += DELTA;
            }

            out.println("  file " + hex(e.fileOffset, 8)
                    + " rel " + hex(e.relOffset, 4)
                    + " -> target " + hex(e.target.address, 6)
                    + " " + e.target.note
                    + (e.inMovedRange ? " would become rel " + hex(patchedRel, 4) : "")
                    + (e.isShrinkStart ? " SHRINK-START" : ""));
        }

        out.println();
    }

    private static void searchRelativeClusters(PrintWriter out, List<File> files) throws Exception {
        out.println();
        out.println("RELATIVE OFFSET CLUSTERS");
        out.println("------------------------");
        out.println("Looks for 128-byte windows with 4 or more known relative offsets.");
        out.println("This catches tables that are not perfectly sequential.");
        out.println();

        int printed = 0;

        for (int b = 0; b < BASES.length; b++) {
            Base base = BASES[b];
            Map<Integer, List<Target>> offsetMap = buildRelativeOffsetMap(base, false);

            for (int f = 0; f < files.size(); f++) {
                File file = files.get(f);
                byte[] data = readAll(file);

                for (int start = 0; start < data.length; start += 32) {
                    int end = Math.min(data.length, start + 128);

                    int count = 0;
                    int movedCount = 0;

                    for (int pos = start; pos + 1 < end; pos++) {
                        int value = readUInt16LE(data, pos);
                        List<Target> targets = offsetMap.get(Integer.valueOf(value));

                        if (targets != null) {
                            count++;

                            Target t = targets.get(0);

                            if (t.address >= MOVED_START && t.address < MOVED_END_EXCLUSIVE) {
                                movedCount++;
                            }
                        }
                    }

                    if (count >= 4 && movedCount >= 1) {
                        out.println("Cluster in " + rel(file)
                                + " around " + hex(start, 8)
                                + " base " + hex(base.address, 6)
                                + " [" + base.note + "]"
                                + " count " + count
                                + " movedCount " + movedCount);
                        out.println("  " + contextWindow(data, start, 128));
                        out.println();

                        printed++;

                        if (printed >= MAX_RUNS_TO_PRINT) {
                            out.println("Stopped after " + MAX_RUNS_TO_PRINT + " clusters.");
                            out.println();
                            return;
                        }
                    }
                }
            }
        }

        if (printed == 0) {
            out.println("No relative offset clusters found.");
        }

        out.println();
    }

    private static Map<Integer, List<Target>> buildRelativeOffsetMap(Base base, boolean includeZero) {
        Map<Integer, List<Target>> map = new LinkedHashMap<Integer, List<Target>>();

        for (int i = 0; i < TARGETS.length; i++) {
            Target t = TARGETS[i];
            int rel = t.address - base.address;

            if (rel < 0 || rel > 0xFFFF) {
                continue;
            }

            if (!includeZero && rel == 0) {
                continue;
            }

            Integer key = Integer.valueOf(rel);
            List<Target> list = map.get(key);

            if (list == null) {
                list = new ArrayList<Target>();
                map.put(key, list);
            }

            list.add(t);
        }

        return map;
    }

    private static byte[] packed16(List<Integer> values) {
        byte[] out = new byte[values.size() * 2];

        int p = 0;

        for (int i = 0; i < values.size(); i++) {
            int value = values.get(i).intValue();
            byte[] b = uint16LE(value);

            out[p++] = b[0];
            out[p++] = b[1];
        }

        return out;
    }

    private static byte[] packed32(List<Integer> values) {
        byte[] out = new byte[values.size() * 4];

        int p = 0;

        for (int i = 0; i < values.size(); i++) {
            int value = values.get(i).intValue();
            byte[] b = int32LE(value);

            out[p++] = b[0];
            out[p++] = b[1];
            out[p++] = b[2];
            out[p++] = b[3];
        }

        return out;
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

    private static int readUInt16LE(byte[] data, int offset) {
        int b0 = data[offset] & 0xFF;
        int b1 = data[offset + 1] & 0xFF;

        return b0 | (b1 << 8);
    }

    private static byte[] int32LE(int value) {
        return new byte[] {
                (byte) (value & 0xFF),
                (byte) ((value >> 8) & 0xFF),
                (byte) ((value >> 16) & 0xFF),
                (byte) ((value >> 24) & 0xFF)
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

    private static class Base {
        int address;
        String note;

        Base(int address, String note) {
            this.address = address;
            this.note = note;
        }
    }

    private static class PatternSet {
        String name;
        byte[] bytes;

        PatternSet(String name, byte[] bytes) {
            this.name = name;
            this.bytes = bytes;
        }
    }

    private static class Entry {
        int fileOffset;
        int relOffset;
        Target target;
        boolean inMovedRange;
        boolean isShrinkStart;
    }

    private static class Run {
        File file;
        String relativeFile;
        byte[] data;
        Base base;
        int startOffset;
        int stride;
        boolean touchesMovedRange;
        boolean touchesShrinkStart;
        List<Entry> entries = new ArrayList<Entry>();

        int score() {
            int score = entries.size() * 10;

            if (touchesMovedRange) {
                score += 100;
            }

            if (touchesShrinkStart) {
                score += 25;
            }

            if ("SC01/006/0.4".equalsIgnoreCase(relativeFile)) {
                score += 25;
            }

            if (base.address == EXPAND_ADDRESS) {
                score += 40;
            }

            return score;
        }
    }
}