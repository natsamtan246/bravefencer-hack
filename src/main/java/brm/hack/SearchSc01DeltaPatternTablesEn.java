package brm.hack;

import java.io.File;
import java.io.FileInputStream;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import brm.Conf;

public class SearchSc01DeltaPatternTablesEn {

    /*
     * Report-only.
     *
     * Searches SC01 for tables that match the spacing pattern of the skipped
     * dialogue blocks, even if the table uses an unknown base.
     *
     * Full starts:
     *
     *   0x608E0
     *   0x6092C
     *   0x60978
     *   0x609C4
     *   0x609FC
     *   0x60AA4
     *
     * Full deltas:
     *
     *   +0x4C, +0x4C, +0x4C, +0x38, +0xA8
     *
     * Quarter deltas:
     *
     *   +0x13, +0x13, +0x13, +0x0E, +0x2A
     */
    private static final int[] FULL_DELTAS = new int[] {
            0x4C,
            0x4C,
            0x4C,
            0x38,
            0xA8
    };

    private static final int[] QUARTER_DELTAS = new int[] {
            0x13,
            0x13,
            0x13,
            0x0E,
            0x2A
    };

    public static void main(String[] args) throws Exception {
        File sc01Dir = new File(Conf.desktop + "brmen/SC01/");
        File report = new File(Conf.desktop + "sc01-delta-pattern-table-search-en.txt");

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

        for (File file : files) {
            byte[] data = readAll(file);
            String relative = rel(file);

            scanFile(relative, data, hits);
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

        PrintWriter out = new PrintWriter(report, "UTF-8");

        out.println("Brave Fencer Musashi English SC01 Delta Pattern Table Search");
        out.println("===========================================================");
        out.println();
        out.println("This report does not modify anything.");
        out.println();
        out.println("It searches for tables matching the spacing pattern of the skipped textboxes.");
        out.println();
        out.println("Full deltas:");
        printDeltaLine(out, FULL_DELTAS);
        out.println("Quarter deltas:");
        printDeltaLine(out, QUARTER_DELTAS);
        out.println();

        out.println("HITS");
        out.println("----");

        int printed = 0;

        for (Hit hit : hits) {
            if (printed >= 300) {
                out.println("Stopped after 300 hits.");
                break;
            }

            out.println("File:   " + hit.relativeFile);
            out.println("Offset: " + hex(hit.offset));
            out.println("Format: " + hit.format);
            out.println("Stride: " + hit.stride);
            out.println("Kind:   " + hit.kind);
            out.println("Score:  " + hit.score());
            out.println("Values:");

            for (int i = 0; i < 6; i++) {
                int pos = hit.offset + i * hit.stride;
                long value = readValue(hit.data, pos, hit.format);

                out.println(
                        "  entry " + i
                                + " at " + hex(pos)
                                + " = " + hexLong(value)
                );
            }

            out.println("Deltas:");
            for (int i = 0; i < 5; i++) {
                long a = readValue(hit.data, hit.offset + i * hit.stride, hit.format);
                long b = readValue(hit.data, hit.offset + (i + 1) * hit.stride, hit.format);

                out.println(
                        "  " + i + " -> " + (i + 1)
                                + " = +" + hexLong(b - a)
                );
            }

            out.println("Context:");
            out.println(hexContext(hit.data, hit.offset, 64));
            out.println();

            printed++;
        }

        if (printed == 0) {
            out.println("No delta-pattern hits found.");
        }

        out.println();
        out.println("PATCH INTERPRETATION");
        out.println("--------------------");
        out.println("For the +4 diagnostic shift:");
        out.println();
        out.println("If this is a FULL offset table, moved entries should receive +4.");
        out.println("If this is a QUARTER offset table, moved entries should receive +1.");
        out.println();
        out.println("Do not patch yet. Upload this report first.");

        out.close();

        System.out.println("Delta pattern table search written to:");
        System.out.println(report.getAbsolutePath());
    }

    private static void scanFile(String relative, byte[] data, List<Hit> hits) {
        Format[] formats = new Format[] {
                Format.U16_LE,
                Format.U16_BE,
                Format.U24_LE,
                Format.U24_BE,
                Format.U32_LE,
                Format.U32_BE
        };

        int[] strides = new int[] {
                2, 3, 4, 6, 8, 12, 16, 20, 24, 32
        };

        for (Format format : formats) {
            for (int stride : strides) {
                if (stride < format.width) {
                    continue;
                }

                for (int offset = 0; offset + stride * 5 + format.width <= data.length; offset++) {
                    if (format.width == 2 && (offset % 2) != 0) {
                        continue;
                    }

                    if (format.width == 4 && (offset % 4) != 0) {
                        continue;
                    }

                    if (matchesDeltas(data, offset, format, stride, FULL_DELTAS)) {
                        Hit hit = new Hit();
                        hit.relativeFile = relative;
                        hit.data = data;
                        hit.offset = offset;
                        hit.format = format;
                        hit.stride = stride;
                        hit.kind = "FULL_DELTAS";
                        hits.add(hit);
                    }

                    if (matchesDeltas(data, offset, format, stride, QUARTER_DELTAS)) {
                        Hit hit = new Hit();
                        hit.relativeFile = relative;
                        hit.data = data;
                        hit.offset = offset;
                        hit.format = format;
                        hit.stride = stride;
                        hit.kind = "QUARTER_DELTAS";
                        hits.add(hit);
                    }
                }
            }
        }
    }

    private static boolean matchesDeltas(
            byte[] data,
            int offset,
            Format format,
            int stride,
            int[] deltas
    ) {
        long first = readValue(data, offset, format);

        /*
         * Basic sanity filters so we don't match tiny random counters too much.
         */
        if (first == 0) {
            return false;
        }

        if (format.width == 2) {
            if (first < 0x0100 || first > 0xF000) {
                return false;
            }
        }

        if (format.width == 3 || format.width == 4) {
            if (first < 0x000100 || first > 0x90FFFFFFL) {
                return false;
            }
        }

        long previous = first;

        for (int i = 0; i < deltas.length; i++) {
            long current = readValue(data, offset + (i + 1) * stride, format);
            long diff = current - previous;

            if (diff != deltas[i]) {
                return false;
            }

            previous = current;
        }

        return true;
    }

    private static long readValue(byte[] data, int offset, Format format) {
        if (format == Format.U16_LE) {
            return readUInt16LE(data, offset);
        }

        if (format == Format.U16_BE) {
            return readUInt16BE(data, offset);
        }

        if (format == Format.U24_LE) {
            return readUInt24LE(data, offset);
        }

        if (format == Format.U24_BE) {
            return readUInt24BE(data, offset);
        }

        if (format == Format.U32_LE) {
            return readUInt32LE(data, offset);
        }

        if (format == Format.U32_BE) {
            return readUInt32BE(data, offset);
        }

        throw new RuntimeException("Unknown format: " + format);
    }

    private static int readUInt16LE(byte[] data, int offset) {
        int b0 = data[offset] & 0xFF;
        int b1 = data[offset + 1] & 0xFF;

        return b0 | (b1 << 8);
    }

    private static int readUInt16BE(byte[] data, int offset) {
        int b0 = data[offset] & 0xFF;
        int b1 = data[offset + 1] & 0xFF;

        return (b0 << 8) | b1;
    }

    private static int readUInt24LE(byte[] data, int offset) {
        int b0 = data[offset] & 0xFF;
        int b1 = data[offset + 1] & 0xFF;
        int b2 = data[offset + 2] & 0xFF;

        return b0 | (b1 << 8) | (b2 << 16);
    }

    private static int readUInt24BE(byte[] data, int offset) {
        int b0 = data[offset] & 0xFF;
        int b1 = data[offset + 1] & 0xFF;
        int b2 = data[offset + 2] & 0xFF;

        return (b0 << 16) | (b1 << 8) | b2;
    }

    private static long readUInt32LE(byte[] data, int offset) {
        long b0 = data[offset] & 0xFFL;
        long b1 = data[offset + 1] & 0xFFL;
        long b2 = data[offset + 2] & 0xFFL;
        long b3 = data[offset + 3] & 0xFFL;

        return b0 | (b1 << 8) | (b2 << 16) | (b3 << 24);
    }

    private static long readUInt32BE(byte[] data, int offset) {
        long b0 = data[offset] & 0xFFL;
        long b1 = data[offset + 1] & 0xFFL;
        long b2 = data[offset + 2] & 0xFFL;
        long b3 = data[offset + 3] & 0xFFL;

        return (b0 << 24) | (b1 << 16) | (b2 << 8) | b3;
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

    private static void printDeltaLine(PrintWriter out, int[] deltas) {
        out.print("  ");

        for (int i = 0; i < deltas.length; i++) {
            if (i > 0) {
                out.print(", ");
            }

            out.print("+");
            out.print(hex(deltas[i]));
        }

        out.println();
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

    private static String hexLong(long value) {
        return "0x" + Long.toHexString(value).toUpperCase();
    }

    private enum Format {
        U16_LE(2),
        U16_BE(2),
        U24_LE(3),
        U24_BE(3),
        U32_LE(4),
        U32_BE(4);

        final int width;

        Format(int width) {
            this.width = width;
        }
    }

    private static class Hit {
        String relativeFile;
        byte[] data;
        int offset;
        Format format;
        int stride;
        String kind;

        int score() {
            int score = 0;

            if ("SC01/006/0.4".equalsIgnoreCase(relativeFile)) {
                score += 1000;
            }

            if (relativeFile.endsWith("/0.4")) {
                score += 100;
            }

            if (offset >= 0x50000 && offset < 0xA0000) {
                score += 50;
            }

            if (format == Format.U24_LE || format == Format.U32_LE) {
                score += 40;
            }

            if ("FULL_DELTAS".equals(kind)) {
                score += 20;
            }

            if (stride == format.width) {
                score += 20;
            }

            return score;
        }
    }
}