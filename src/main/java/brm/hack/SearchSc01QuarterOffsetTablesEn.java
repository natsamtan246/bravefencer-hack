package brm.hack;

import java.io.File;
import java.io.FileInputStream;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import brm.Conf;

public class SearchSc01QuarterOffsetTablesEn {

    /*
     * We are investigating why shifting the middle dialogue area by even +4
     * breaks the same textboxes.
     *
     * Full local offsets:
     *
     *   0x08E0
     *   0x092C
     *   0x0978
     *   0x09C4
     *   0x09FC
     *   0x0AA4
     *
     * Quarter offsets:
     *
     *   0x0238
     *   0x024B
     *   0x025E
     *   0x0271
     *   0x027F
     *   0x02A9
     */
    private static final int[] LOCAL_OFFSETS = new int[] {
            0x0890,
            0x08E0,
            0x092C,
            0x0978,
            0x09C4,
            0x09FC,
            0x0AA4,
            0x0BA0
    };

    private static final String[] NOTES = new String[] {
            "expanded block start",
            "first moved block",
            "moved block",
            "moved block",
            "moved block",
            "moved block",
            "shrink block start",
            "first aligned-after-shrink block"
    };

    public static void main(String[] args) throws Exception {
        File sc01Dir = new File(Conf.desktop + "brmen/SC01/");
        File report = new File(Conf.desktop + "sc01-quarter-offset-table-search-en.txt");

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

        List<Hit> hits = new ArrayList<Hit>();
        List<Run> runs = new ArrayList<Run>();

        for (File file : files) {
            byte[] data = readAll(file);
            String relative = rel(file);

            scanIndividualQuarterHits(file, relative, data, hits);
            scanQuarterRuns(file, relative, data, runs);
        }

        Collections.sort(hits, new Comparator<Hit>() {
            @Override
            public int compare(Hit a, Hit b) {
                int scoreDiff = b.score() - a.score();

                if (scoreDiff != 0) {
                    return scoreDiff;
                }

                int fileDiff = a.relativeFile.compareTo(b.relativeFile);

                if (fileDiff != 0) {
                    return fileDiff;
                }

                return a.offset - b.offset;
            }
        });

        Collections.sort(runs, new Comparator<Run>() {
            @Override
            public int compare(Run a, Run b) {
                int scoreDiff = b.score() - a.score();

                if (scoreDiff != 0) {
                    return scoreDiff;
                }

                int fileDiff = a.relativeFile.compareTo(b.relativeFile);

                if (fileDiff != 0) {
                    return fileDiff;
                }

                return a.offset - b.offset;
            }
        });

        PrintWriter out = new PrintWriter(report, "UTF-8");

        out.println("Brave Fencer Musashi English SC01 Quarter-Offset Table Search");
        out.println("=============================================================");
        out.println();
        out.println("This report does not modify anything.");
        out.println();
        out.println("Looking for possible tables that store local offsets divided by 4.");
        out.println();

        printTargets(out);
        printRuns(out, runs);
        printIndividualHits(out, hits);

        out.println();
        out.println("PATCH INTERPRETATION");
        out.println("--------------------");
        out.println("For the +4 diagnostic shift:");
        out.println("  full offset +4 means quarter offset +1.");
        out.println();
        out.println("Examples:");
        out.println("  full 0x08E0 -> 0x08E4 means quarter 0x0238 -> 0x0239");
        out.println("  full 0x092C -> 0x0930 means quarter 0x024B -> 0x024C");
        out.println("  full 0x0978 -> 0x097C means quarter 0x025E -> 0x025F");
        out.println();
        out.println("Do not patch yet. Upload this report first.");

        out.close();

        System.out.println("Quarter-offset table search written to:");
        System.out.println(report.getAbsolutePath());
    }

    private static void printTargets(PrintWriter out) {
        out.println("TARGETS");
        out.println("-------");

        for (int i = 0; i < LOCAL_OFFSETS.length; i++) {
            int local = LOCAL_OFFSETS[i];
            int quarter = local / 4;

            out.println(
                    "local "
                            + hex4(local)
                            + " / 4 = "
                            + hex4(quarter)
                            + "    "
                            + NOTES[i]
            );
        }

        out.println();
    }

    private static void printRuns(PrintWriter out, List<Run> runs) {
        out.println("QUARTER-OFFSET RUNS");
        out.println("-------------------");
        out.println("These are more important than isolated hits.");
        out.println();

        int printed = 0;

        for (Run run : runs) {
            if (printed >= 200) {
                out.println("Stopped after 200 runs.");
                break;
            }

            out.println("File:   " + run.relativeFile);
            out.println("Offset: " + hex(run.offset));
            out.println("Stride: " + run.stride);
            out.println("Count:  " + run.count);
            out.println("Score:  " + run.score());
            out.println("Values:");

            for (int i = 0; i < run.count; i++) {
                int pos = run.offset + i * run.stride;
                int value = readUInt16LE(run.data, pos);
                int local = value * 4;

                out.println(
                        "  "
                                + hex(pos)
                                + "  quarter "
                                + hex4(value)
                                + " -> local "
                                + hex4(local)
                                + targetNoteForQuarter(value)
                );
            }

            out.println("Context:");
            out.println(hexContext(run.data, run.offset, 64));
            out.println();

            printed++;
        }

        if (printed == 0) {
            out.println("No quarter-offset runs found.");
            out.println();
        }
    }

    private static void printIndividualHits(PrintWriter out, List<Hit> hits) {
        out.println();
        out.println("INDIVIDUAL QUARTER-OFFSET HITS");
        out.println("------------------------------");
        out.println("These are lower-confidence than runs.");
        out.println();

        int printed = 0;

        for (Hit hit : hits) {
            if (printed >= 300) {
                out.println("Stopped after 300 hits.");
                break;
            }

            out.println("File:   " + hit.relativeFile);
            out.println("Offset: " + hex(hit.offset));
            out.println("Value:  quarter " + hex4(hit.value)
                    + " -> local " + hex4(hit.value * 4)
                    + targetNoteForQuarter(hit.value));
            out.println("Score:  " + hit.score());
            out.println("Context:");
            out.println(hexContext(hit.data, hit.offset, 48));
            out.println();

            printed++;
        }

        if (printed == 0) {
            out.println("No individual quarter-offset hits found.");
            out.println();
        }
    }

    private static void scanIndividualQuarterHits(
            File file,
            String relative,
            byte[] data,
            List<Hit> hits
    ) {
        for (int pos = 0; pos + 1 < data.length; pos += 2) {
            int value = readUInt16LE(data, pos);

            if (!isTargetQuarter(value)) {
                continue;
            }

            Hit hit = new Hit();
            hit.file = file;
            hit.relativeFile = relative;
            hit.data = data;
            hit.offset = pos;
            hit.value = value;

            hits.add(hit);
        }
    }

    private static void scanQuarterRuns(
            File file,
            String relative,
            byte[] data,
            List<Run> runs
    ) {
        int[] strides = new int[] {2, 4, 6, 8, 12, 16};

        for (int s = 0; s < strides.length; s++) {
            int stride = strides[s];

            for (int start = 0; start + 1 < data.length; start += 2) {
                int count = 0;

                for (int pos = start; pos + 1 < data.length; pos += stride) {
                    int value = readUInt16LE(data, pos);

                    if (isUsefulQuarter(value)) {
                        count++;
                    } else {
                        break;
                    }
                }

                if (count >= 4) {
                    Run run = new Run();
                    run.file = file;
                    run.relativeFile = relative;
                    run.data = data;
                    run.offset = start;
                    run.stride = stride;
                    run.count = count;

                    runs.add(run);
                }
            }
        }
    }

    private static boolean isTargetQuarter(int value) {
        for (int i = 0; i < LOCAL_OFFSETS.length; i++) {
            if ((LOCAL_OFFSETS[i] % 4) != 0) {
                continue;
            }

            if ((LOCAL_OFFSETS[i] / 4) == value) {
                return true;
            }
        }

        return false;
    }

    private static boolean isUsefulQuarter(int value) {
        /*
         * Quarter range corresponding roughly to local offsets:
         *
         *   0x0800 through 0x0C00
         *
         * divided by 4:
         *
         *   0x0200 through 0x0300
         */
        return value >= 0x0200 && value < 0x0300;
    }

    private static String targetNoteForQuarter(int value) {
        for (int i = 0; i < LOCAL_OFFSETS.length; i++) {
            if ((LOCAL_OFFSETS[i] % 4) != 0) {
                continue;
            }

            if ((LOCAL_OFFSETS[i] / 4) == value) {
                return "    EXACT: " + NOTES[i];
            }
        }

        return "";
    }

    private static void collectFiles(File dir, List<File> files) {
        File[] children = dir.listFiles();

        if (children == null) {
            return;
        }

        for (File child : children) {
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

    private static String hexContext(byte[] data, int center, int radius) {
        int start = center - radius;
        int end = center + radius;

        if (start < 0) {
            start = 0;
        }

        if (end >= data.length) {
            end = data.length - 1;
        }

        StringBuilder sb = new StringBuilder();

        for (int pos = start; pos <= end; pos++) {
            if (pos == center) {
                sb.append("[");
            }

            sb.append(String.format("%02X", data[pos] & 0xFF));

            if (pos == center + 1) {
                sb.append("]");
            }

            if (pos < end) {
                sb.append(" ");
            }
        }

        return sb.toString();
    }

    private static String rel(File file) {
        String full = file.getAbsolutePath().replace("\\", "/");
        String root = new File(Conf.desktop + "brmen/").getAbsolutePath().replace("\\", "/");

        if (!root.endsWith("/")) {
            root = root + "/";
        }

        if (full.startsWith(root)) {
            return full.substring(root.length());
        }

        return full;
    }

    private static String hex(int value) {
        return "0x" + Integer.toHexString(value).toUpperCase();
    }

    private static String hex4(int value) {
        String s = Integer.toHexString(value & 0xFFFF).toUpperCase();

        while (s.length() < 4) {
            s = "0" + s;
        }

        return "0x" + s;
    }

    private static class Hit {
        File file;
        String relativeFile;
        byte[] data;
        int offset;
        int value;

        int score() {
            int score = 0;

            if ("SC01/006/0.4".equalsIgnoreCase(relativeFile)) {
                score += 100;
            }

            if (relativeFile.endsWith("/0.4")) {
                score += 20;
            }

            if (offset >= 0x60000 && offset < 0x70000) {
                score += 10;
            }

            return score;
        }
    }

    private static class Run {
        File file;
        String relativeFile;
        byte[] data;
        int offset;
        int stride;
        int count;

        int score() {
            int score = count * 10;

            if ("SC01/006/0.4".equalsIgnoreCase(relativeFile)) {
                score += 100;
            }

            if (relativeFile.endsWith("/0.4")) {
                score += 20;
            }

            if (offset >= 0x60000 && offset < 0x70000) {
                score += 10;
            }

            return score;
        }
    }
}