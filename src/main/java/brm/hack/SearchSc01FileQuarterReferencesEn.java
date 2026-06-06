package brm.hack;

import java.io.File;
import java.io.FileInputStream;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import brm.Conf;

public class SearchSc01FileQuarterReferencesEn {

    /*
     * Report-only.
     *
     * Searches SC01 for references to full file offsets divided by 4.
     *
     * Example:
     *
     *   full file offset 0x0608E0
     *   divided by 4     0x018238
     *
     * For the +4 shift, these would need +1:
     *
     *   0x018238 -> 0x018239
     */
    private static final Target[] TARGETS = new Target[] {
            new Target(0x060890, "expanded block start"),
            new Target(0x0608E0, "first moved block"),
            new Target(0x06092C, "moved block"),
            new Target(0x060978, "moved block"),
            new Target(0x0609C4, "moved block"),
            new Target(0x0609FC, "moved block"),
            new Target(0x060AA4, "shrink block start"),
            new Target(0x060BA0, "first aligned-after-shrink block")
    };

    public static void main(String[] args) throws Exception {
        File sc01Dir = new File(Conf.desktop + "brmen/SC01/");
        File report = new File(Conf.desktop + "sc01-file-quarter-reference-search-en.txt");

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

            scanHits(relative, data, hits);
            scanRuns(relative, data, runs);
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

        out.println("Brave Fencer Musashi English SC01 File-Quarter Reference Search");
        out.println("===============================================================");
        out.println();
        out.println("This report does not modify anything.");
        out.println();
        out.println("It searches for full file offsets divided by 4.");
        out.println();
        out.println("For the +4 diagnostic shift, file-quarter references would need +1.");
        out.println();

        printTargets(out);
        printRuns(out, runs);
        printHits(out, hits);

        out.println();
        out.println("PATCH INTERPRETATION");
        out.println("--------------------");
        out.println("For the +4 diagnostic shift:");
        out.println("  full file offset +4 means quarter value +1.");
        out.println();
        out.println("Examples:");
        out.println("  0x0608E0 / 4 = 0x018238 -> 0x018239");
        out.println("  0x06092C / 4 = 0x01824B -> 0x01824C");
        out.println("  0x060978 / 4 = 0x01825E -> 0x01825F");
        out.println();
        out.println("Do not patch yet. Upload this report first.");

        out.close();

        System.out.println("File-quarter reference search written to:");
        System.out.println(report.getAbsolutePath());
    }

    private static void printTargets(PrintWriter out) {
        out.println("TARGETS");
        out.println("-------");

        for (Target target : TARGETS) {
            out.println(
                    "file offset "
                            + hex6(target.fileOffset)
                            + " / 4 = "
                            + hex6(target.fileQuarter())
                            + "    low16="
                            + hex4(target.fileQuarter() & 0xFFFF)
                            + "    "
                            + target.note
            );
        }

        out.println();
    }

    private static void printRuns(PrintWriter out, List<Run> runs) {
        out.println("FILE-QUARTER RUNS");
        out.println("-----------------");
        out.println("Runs are more important than isolated hits.");
        out.println();

        int printed = 0;

        for (Run run : runs) {
            if (printed >= 200) {
                out.println("Stopped after 200 runs.");
                break;
            }

            out.println("File:   " + run.relativeFile);
            out.println("Offset: " + hex(run.offset));
            out.println("Format: " + run.format);
            out.println("Stride: " + run.stride);
            out.println("Count:  " + run.count);
            out.println("Score:  " + run.score());
            out.println("Values:");

            for (int i = 0; i < run.count; i++) {
                int pos = run.offset + i * run.stride;
                int value = readValue(run.data, pos, run.format);
                int fileOffset = value * 4;

                out.println(
                        "  "
                                + hex(pos)
                                + "  quarter "
                                + hex6(value)
                                + " -> file "
                                + hex6(fileOffset)
                                + targetNoteForQuarter(value)
                );
            }

            out.println("Context:");
            out.println(hexContext(run.data, run.offset, 64));
            out.println();

            printed++;
        }

        if (printed == 0) {
            out.println("No file-quarter runs found.");
            out.println();
        }
    }

    private static void printHits(PrintWriter out, List<Hit> hits) {
        out.println();
        out.println("INDIVIDUAL FILE-QUARTER HITS");
        out.println("----------------------------");
        out.println("Individual hits are lower-confidence than runs.");
        out.println();

        int printed = 0;

        for (Hit hit : hits) {
            if (printed >= 400) {
                out.println("Stopped after 400 hits.");
                break;
            }

            int fileOffset = hit.value * 4;

            out.println("File:   " + hit.relativeFile);
            out.println("Offset: " + hex(hit.offset));
            out.println("Format: " + hit.format);
            out.println(
                    "Value:  quarter "
                            + hex6(hit.value)
                            + " -> file "
                            + hex6(fileOffset)
                            + targetNoteForQuarter(hit.value)
            );
            out.println("Score:  " + hit.score());
            out.println("Context:");
            out.println(hexContext(hit.data, hit.offset, 48));
            out.println();

            printed++;
        }

        if (printed == 0) {
            out.println("No individual file-quarter hits found.");
            out.println();
        }
    }

    private static void scanHits(String relative, byte[] data, List<Hit> hits) {
        Format[] formats = new Format[] {
                Format.U16_LOW,
                Format.U24_LE,
                Format.U32_LE
        };

        for (Format format : formats) {
            int width = format.width;

            for (int pos = 0; pos + width <= data.length; pos++) {
                if (format == Format.U16_LOW && (pos % 2) != 0) {
                    continue;
                }

                if (format == Format.U32_LE && (pos % 4) != 0) {
                    continue;
                }

                int value = readValue(data, pos, format);

                if (!isTargetQuarterForFormat(value, format)) {
                    continue;
                }

                Hit hit = new Hit();
                hit.relativeFile = relative;
                hit.data = data;
                hit.offset = pos;
                hit.format = format;
                hit.value = value;

                hits.add(hit);
            }
        }
    }

    private static void scanRuns(String relative, byte[] data, List<Run> runs) {
        Format[] formats = new Format[] {
                Format.U16_LOW,
                Format.U24_LE,
                Format.U32_LE
        };

        int[] strides = new int[] {2, 3, 4, 6, 8, 12, 16};

        for (Format format : formats) {
            for (int stride : strides) {
                if (stride < format.width) {
                    continue;
                }

                for (int start = 0; start + format.width <= data.length; start++) {
                    if (format == Format.U16_LOW && (start % 2) != 0) {
                        continue;
                    }

                    if (format == Format.U32_LE && (start % 4) != 0) {
                        continue;
                    }

                    int count = 0;

                    for (int pos = start; pos + format.width <= data.length; pos += stride) {
                        int value = readValue(data, pos, format);

                        if (isUsefulFileQuarterForRun(value, format)) {
                            count++;
                        } else {
                            break;
                        }
                    }

                    if (count >= 4) {
                        Run run = new Run();
                        run.relativeFile = relative;
                        run.data = data;
                        run.offset = start;
                        run.format = format;
                        run.stride = stride;
                        run.count = count;

                        runs.add(run);
                    }
                }
            }
        }
    }

    private static boolean isTargetQuarterForFormat(int value, Format format) {
        for (Target target : TARGETS) {
            int q = target.fileQuarter();

            if (format == Format.U16_LOW) {
                if ((q & 0xFFFF) == value) {
                    return true;
                }
            } else {
                if (q == value) {
                    return true;
                }
            }
        }

        return false;
    }

    private static boolean isUsefulFileQuarterForRun(int value, Format format) {
        /*
         * Full file offsets around 0x060000-0x062000 divided by 4:
         *
         *   0x060000 / 4 = 0x018000
         *   0x062000 / 4 = 0x018800
         *
         * For U16_LOW this becomes 0x8000-0x8800.
         */
        if (format == Format.U16_LOW) {
            return value >= 0x8000 && value < 0x8800;
        }

        return value >= 0x018000 && value < 0x018800;
    }

    private static String targetNoteForQuarter(int value) {
        for (Target target : TARGETS) {
            int q = target.fileQuarter();

            if (q == value || (q & 0xFFFF) == value) {
                return "    EXACT: " + target.note;
            }
        }

        return "";
    }

    private static int readValue(byte[] data, int offset, Format format) {
        if (format == Format.U16_LOW) {
            return readUInt16LE(data, offset);
        }

        if (format == Format.U24_LE) {
            return readUInt24LE(data, offset);
        }

        if (format == Format.U32_LE) {
            return readInt32LE(data, offset);
        }

        throw new RuntimeException("Unknown format: " + format);
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

    private static int readInt32LE(byte[] data, int offset) {
        int b0 = data[offset] & 0xFF;
        int b1 = data[offset + 1] & 0xFF;
        int b2 = data[offset + 2] & 0xFF;
        int b3 = data[offset + 3] & 0xFF;

        return b0 | (b1 << 8) | (b2 << 16) | (b3 << 24);
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

            if (pos == center) {
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

    private static String hex6(int value) {
        String s = Integer.toHexString(value & 0xFFFFFF).toUpperCase();

        while (s.length() < 6) {
            s = "0" + s;
        }

        return "0x" + s;
    }

    private enum Format {
        U16_LOW(2),
        U24_LE(3),
        U32_LE(4);

        final int width;

        Format(int width) {
            this.width = width;
        }
    }

    private static class Target {
        int fileOffset;
        String note;

        Target(int fileOffset, String note) {
            this.fileOffset = fileOffset;
            this.note = note;
        }

        int fileQuarter() {
            return fileOffset / 4;
        }
    }

    private static class Hit {
        String relativeFile;
        byte[] data;
        int offset;
        Format format;
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

            if (format == Format.U24_LE || format == Format.U32_LE) {
                score += 30;
            }

            return score;
        }
    }

    private static class Run {
        String relativeFile;
        byte[] data;
        int offset;
        Format format;
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

            if (format == Format.U24_LE || format == Format.U32_LE) {
                score += 30;
            }

            return score;
        }
    }
}