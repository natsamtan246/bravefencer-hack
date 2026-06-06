package brm.hack;

import java.io.File;
import java.io.FileInputStream;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import brm.Conf;

public class SearchEnJpTargetLengthTablesEn {

    /*
     * Report-only.
     *
     * This searches EN and JP SC01 split files for tables that match the
     * target scene's slot starts or slot lengths.
     *
     * Why this matters:
     *
     * The JP target-slot locator proved the corresponding JP slots have
     * different lengths than EN. That means Square's tools probably updated
     * a length/start table somewhere.
     *
     * If we can find that table, we may be able to patch real long-text
     * expansions without bridge hacks.
     */

    private static final String TARGET_REL_FILE = "006/0.4";

    private static final long LOCAL_BASE = 0x060000L;

    private static final long[] EN_START_A = new long[] {
            0x06083CL, 0x060868L, 0x060890L, 0x0608E0L, 0x06092CL,
            0x060978L, 0x0609C4L, 0x0609FCL, 0x060AA4L, 0x060BA0L
    };

    private static final long[] EN_LENGTH_A = new long[] {
            44, 40, 80, 76, 76, 76, 56, 168, 252
    };

    private static final long[] JP_START_A = new long[] {
            0x060660L, 0x060678L, 0x06069CL, 0x0606F8L, 0x060740L,
            0x060788L, 0x0607E4L, 0x060814L, 0x0608ACL, 0x060988L
    };

    private static final long[] JP_LENGTH_A = new long[] {
            24, 36, 92, 72, 72, 92, 48, 152, 220
    };

    private static final long[] EN_START_B = new long[] {
            0x060C7CL, 0x060DF8L, 0x060EB0L, 0x060F1CL,
            0x060F54L, 0x060F84L, 0x060FC4L
    };

    private static final long[] EN_LENGTH_B = new long[] {
            380, 184, 108, 56, 48, 64
    };

    private static final long[] JP_START_B = new long[] {
            0x060A44L, 0x060C00L, 0x060CA0L, 0x060CF4L,
            0x060D28L, 0x060D4CL, 0x060D88L
    };

    private static final long[] JP_LENGTH_B = new long[] {
            444, 160, 84, 52, 36, 60
    };

    private static final long[] EN_START_C = new long[] {
            0x06111CL, 0x0611B0L, 0x061288L, 0x06134CL, 0x0613A0L
    };

    private static final long[] EN_LENGTH_C = new long[] {
            148, 216, 196, 84
    };

    private static final long[] JP_START_C = new long[] {
            0x060E90L, 0x060F14L, 0x060FA4L, 0x061044L, 0x06108CL
    };

    private static final long[] JP_LENGTH_C = new long[] {
            132, 144, 160, 72
    };

    public static void main(String[] args) throws Exception {
        File enSc01Dir = new File(Conf.desktop + "brmen/SC01/");
        File jpSc01Dir = new File(Conf.desktop + "brmjp/SC01/");
        File report = new File(Conf.desktop + "en-jp-target-length-table-search.txt");

        PrintWriter out = new PrintWriter(report, "UTF-8");

        out.println("Brave Fencer Musashi EN/JP Target Length Table Search");
        out.println("====================================================");
        out.println();
        out.println("This report does not modify anything.");
        out.println();
        out.println("Purpose:");
        out.println("  Search EN and JP SC01 split files for exact table runs matching");
        out.println("  the target scene's slot starts and slot lengths.");
        out.println();
        out.println("If the same kind of table appears in both EN and JP, but with each");
        out.println("language's own values, that is likely the real resize metadata.");
        out.println();

        out.println("INPUTS");
        out.println("------");
        out.println("EN SC01 dir: " + enSc01Dir.getAbsolutePath() + " exists=" + enSc01Dir.exists());
        out.println("JP SC01 dir: " + jpSc01Dir.getAbsolutePath() + " exists=" + jpSc01Dir.exists());
        out.println();

        if (!enSc01Dir.exists()) {
            out.close();
            throw new RuntimeException("Missing EN SC01 dir: " + enSc01Dir.getAbsolutePath());
        }

        if (!jpSc01Dir.exists()) {
            out.close();
            throw new RuntimeException("Missing JP SC01 dir: " + jpSc01Dir.getAbsolutePath());
        }

        List<SequenceSpec> specs = buildSpecs();

        out.println("SEQUENCES SEARCHED");
        out.println("------------------");

        for (SequenceSpec spec : specs) {
            out.println(spec.name + " values=" + spec.values.length + " first=" + hexLong(spec.values[0]));
        }

        out.println();

        List<Hit> hits = new ArrayList<Hit>();

        scanLanguage("EN", enSc01Dir, specs, hits);
        scanLanguage("JP", jpSc01Dir, specs, hits);

        Collections.sort(hits, new Comparator<Hit>() {
            @Override
            public int compare(Hit a, Hit b) {
                int scoreDiff = b.score() - a.score();

                if (scoreDiff != 0) {
                    return scoreDiff;
                }

                int langDiff = a.language.compareTo(b.language);

                if (langDiff != 0) {
                    return langDiff;
                }

                int fileDiff = a.relativeFile.compareTo(b.relativeFile);

                if (fileDiff != 0) {
                    return fileDiff;
                }

                return a.offset - b.offset;
            }
        });

        printHitSummary(out, hits);
        printTopHits(out, hits);
        printLikelyPairs(out, hits);

        out.println("INTERPRETATION");
        out.println("--------------");
        out.println("Strong hit:");
        out.println("  A hit in EN and a corresponding hit in JP with the same value kind,");
        out.println("  same transform, same format, and similar-looking location.");
        out.println();
        out.println("Most likely useful hits are LENGTH / DIV4 or LENGTH / RAW tables.");
        out.println();
        out.println("If there are no strong hits, then the slot metadata may be encoded");
        out.println("inside a more complex event structure instead of a plain table.");
        out.println();

        out.close();

        System.out.println("EN/JP target length table search written to:");
        System.out.println(report.getAbsolutePath());
    }

    private static List<SequenceSpec> buildSpecs() {
        List<SequenceSpec> specs = new ArrayList<SequenceSpec>();

        addLengthSpecs(specs, "EN", "SEG_A", EN_LENGTH_A);
        addLengthSpecs(specs, "EN", "SEG_B", EN_LENGTH_B);
        addLengthSpecs(specs, "EN", "SEG_C", EN_LENGTH_C);

        addLengthSpecs(specs, "JP", "SEG_A", JP_LENGTH_A);
        addLengthSpecs(specs, "JP", "SEG_B", JP_LENGTH_B);
        addLengthSpecs(specs, "JP", "SEG_C", JP_LENGTH_C);

        addStartSpecs(specs, "EN", "SEG_A", EN_START_A);
        addStartSpecs(specs, "EN", "SEG_B", EN_START_B);
        addStartSpecs(specs, "EN", "SEG_C", EN_START_C);

        addStartSpecs(specs, "JP", "SEG_A", JP_START_A);
        addStartSpecs(specs, "JP", "SEG_B", JP_START_B);
        addStartSpecs(specs, "JP", "SEG_C", JP_START_C);

        return specs;
    }

    private static void addLengthSpecs(List<SequenceSpec> specs, String language, String segment, long[] lengths) {
        addSpec(specs, language, segment, "LENGTH", "RAW", lengths);
        addDivSpecIfPossible(specs, language, segment, "LENGTH", "DIV2", lengths, 2);
        addDivSpecIfPossible(specs, language, segment, "LENGTH", "DIV4", lengths, 4);
    }

    private static void addStartSpecs(List<SequenceSpec> specs, String language, String segment, long[] starts) {
        addSpec(specs, language, segment, "START_FULL", "RAW", starts);
        addDivSpecIfPossible(specs, language, segment, "START_FULL", "DIV2", starts, 2);
        addDivSpecIfPossible(specs, language, segment, "START_FULL", "DIV4", starts, 4);

        long[] locals = subtract(starts, LOCAL_BASE);

        addSpec(specs, language, segment, "START_LOCAL", "RAW", locals);
        addDivSpecIfPossible(specs, language, segment, "START_LOCAL", "DIV2", locals, 2);
        addDivSpecIfPossible(specs, language, segment, "START_LOCAL", "DIV4", locals, 4);

        long[] ram8000Full = add(starts, 0x80000000L);
        addSpec(specs, language, segment, "START_RAM_80000000_PLUS_FULL", "RAW", ram8000Full);

        long[] ram8011Local = add(locals, 0x80110000L);
        addSpec(specs, language, segment, "START_RAM_80110000_PLUS_LOCAL", "RAW", ram8011Local);
    }

    private static void addSpec(
            List<SequenceSpec> specs,
            String language,
            String segment,
            String valueKind,
            String transform,
            long[] values
    ) {
        SequenceSpec spec = new SequenceSpec();

        spec.language = language;
        spec.segment = segment;
        spec.valueKind = valueKind;
        spec.transform = transform;
        spec.values = values;

        spec.name = language + " " + segment + " " + valueKind + " " + transform;

        specs.add(spec);
    }

    private static void addDivSpecIfPossible(
            List<SequenceSpec> specs,
            String language,
            String segment,
            String valueKind,
            String transform,
            long[] values,
            int divisor
    ) {
        if (!allDivisible(values, divisor)) {
            return;
        }

        long[] divided = new long[values.length];

        for (int i = 0; i < values.length; i++) {
            divided[i] = values[i] / divisor;
        }

        addSpec(specs, language, segment, valueKind, transform, divided);
    }

    private static boolean allDivisible(long[] values, int divisor) {
        for (int i = 0; i < values.length; i++) {
            if ((values[i] % divisor) != 0) {
                return false;
            }
        }

        return true;
    }

    private static long[] subtract(long[] values, long amount) {
        long[] ret = new long[values.length];

        for (int i = 0; i < values.length; i++) {
            ret[i] = values[i] - amount;
        }

        return ret;
    }

    private static long[] add(long[] values, long amount) {
        long[] ret = new long[values.length];

        for (int i = 0; i < values.length; i++) {
            ret[i] = values[i] + amount;
        }

        return ret;
    }

    private static void scanLanguage(
            String language,
            File rootDir,
            List<SequenceSpec> specs,
            List<Hit> hits
    ) throws Exception {
        List<File> files = new ArrayList<File>();
        collectFiles(rootDir, files);

        Collections.sort(files, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return a.getAbsolutePath().compareTo(b.getAbsolutePath());
            }
        });

        for (File file : files) {
            byte[] data = readAll(file);
            String relative = rel(rootDir, file);

            for (SequenceSpec spec : specs) {
                if (!language.equals(spec.language)) {
                    continue;
                }

                scanFileForSpec(language, relative, data, spec, hits);
            }
        }
    }

    private static void scanFileForSpec(
            String language,
            String relativeFile,
            byte[] data,
            SequenceSpec spec,
            List<Hit> hits
    ) {
        Format[] formats = new Format[] {
                Format.U8,
                Format.U16_LE,
                Format.U16_BE,
                Format.U24_LE,
                Format.U24_BE,
                Format.U32_LE,
                Format.U32_BE
        };

        int[] strides = new int[] {
                1, 2, 3, 4, 6, 8, 12, 16, 20, 24, 32
        };

        for (Format format : formats) {
            if (!formatCanHoldAll(format, spec.values)) {
                continue;
            }

            for (int strideIndex = 0; strideIndex < strides.length; strideIndex++) {
                int stride = strides[strideIndex];

                if (stride < format.width) {
                    continue;
                }

                int needed = (spec.values.length - 1) * stride + format.width;

                if (needed > data.length) {
                    continue;
                }

                for (int offset = 0; offset <= data.length - needed; offset++) {
                    if (matches(data, offset, format, stride, spec.values)) {
                        Hit hit = new Hit();
                        hit.language = language;
                        hit.relativeFile = relativeFile;
                        hit.offset = offset;
                        hit.format = format;
                        hit.stride = stride;
                        hit.spec = spec;

                        hits.add(hit);
                    }
                }
            }
        }
    }

    private static boolean matches(
            byte[] data,
            int offset,
            Format format,
            int stride,
            long[] values
    ) {
        for (int i = 0; i < values.length; i++) {
            long actual = readValue(data, offset + i * stride, format);

            if (actual != values[i]) {
                return false;
            }
        }

        return true;
    }

    private static boolean formatCanHoldAll(Format format, long[] values) {
        for (int i = 0; i < values.length; i++) {
            if (!formatCanHold(format, values[i])) {
                return false;
            }
        }

        return true;
    }

    private static boolean formatCanHold(Format format, long value) {
        if (value < 0) {
            return false;
        }

        if (format == Format.U8) {
            return value <= 0xFFL;
        }

        if (format == Format.U16_LE || format == Format.U16_BE) {
            return value <= 0xFFFFL;
        }

        if (format == Format.U24_LE || format == Format.U24_BE) {
            return value <= 0xFFFFFFL;
        }

        if (format == Format.U32_LE || format == Format.U32_BE) {
            return value <= 0xFFFFFFFFL;
        }

        return false;
    }

    private static long readValue(byte[] data, int offset, Format format) {
        if (format == Format.U8) {
            return data[offset] & 0xFFL;
        }

        if (format == Format.U16_LE) {
            return (data[offset] & 0xFFL)
                    | ((data[offset + 1] & 0xFFL) << 8);
        }

        if (format == Format.U16_BE) {
            return ((data[offset] & 0xFFL) << 8)
                    | (data[offset + 1] & 0xFFL);
        }

        if (format == Format.U24_LE) {
            return (data[offset] & 0xFFL)
                    | ((data[offset + 1] & 0xFFL) << 8)
                    | ((data[offset + 2] & 0xFFL) << 16);
        }

        if (format == Format.U24_BE) {
            return ((data[offset] & 0xFFL) << 16)
                    | ((data[offset + 1] & 0xFFL) << 8)
                    | (data[offset + 2] & 0xFFL);
        }

        if (format == Format.U32_LE) {
            return (data[offset] & 0xFFL)
                    | ((data[offset + 1] & 0xFFL) << 8)
                    | ((data[offset + 2] & 0xFFL) << 16)
                    | ((data[offset + 3] & 0xFFL) << 24);
        }

        if (format == Format.U32_BE) {
            return ((data[offset] & 0xFFL) << 24)
                    | ((data[offset + 1] & 0xFFL) << 16)
                    | ((data[offset + 2] & 0xFFL) << 8)
                    | (data[offset + 3] & 0xFFL);
        }

        throw new RuntimeException("Unknown format: " + format);
    }

    private static void printHitSummary(PrintWriter out, List<Hit> hits) {
        out.println("HIT SUMMARY");
        out.println("-----------");
        out.println("Total exact hits: " + hits.size());
        out.println();

        int enHits = 0;
        int jpHits = 0;

        for (Hit hit : hits) {
            if ("EN".equals(hit.language)) {
                enHits++;
            }

            if ("JP".equals(hit.language)) {
                jpHits++;
            }
        }

        out.println("EN hits: " + enHits);
        out.println("JP hits: " + jpHits);
        out.println();

        if (hits.size() == 0) {
            out.println("No exact table runs found.");
            out.println();
        }
    }

    private static void printTopHits(PrintWriter out, List<Hit> hits) {
        out.println("TOP EXACT HITS");
        out.println("--------------");

        if (hits.size() == 0) {
            out.println("No hits to print.");
            out.println();
            return;
        }

        int printed = 0;

        for (Hit hit : hits) {
            if (printed >= 200) {
                out.println("Stopped after 200 hits.");
                break;
            }

            out.println("Language:  " + hit.language);
            out.println("File:      " + hit.relativeFile);
            out.println("Offset:    " + hex(hit.offset));
            out.println("Spec:      " + hit.spec.name);
            out.println("Format:    " + hit.format);
            out.println("Stride:    " + hit.stride);
            out.println("Values:    " + valuesLine(hit.spec.values));
            out.println();

            printed++;
        }

        out.println();
    }

    private static void printLikelyPairs(PrintWriter out, List<Hit> hits) {
        out.println("POSSIBLE EN/JP PAIRS");
        out.println("--------------------");
        out.println("Pairs below have the same segment, value kind, transform, format, and stride.");
        out.println();

        int printed = 0;

        for (int i = 0; i < hits.size(); i++) {
            Hit a = hits.get(i);

            if (!"EN".equals(a.language)) {
                continue;
            }

            for (int j = 0; j < hits.size(); j++) {
                Hit b = hits.get(j);

                if (!"JP".equals(b.language)) {
                    continue;
                }

                if (!sameSearchShape(a, b)) {
                    continue;
                }

                out.println("PAIR");
                out.println("  Shape:     " + a.spec.segment
                        + " " + a.spec.valueKind
                        + " " + a.spec.transform
                        + " " + a.format
                        + " stride=" + a.stride);
                out.println("  EN:        " + a.relativeFile + " @ " + hex(a.offset));
                out.println("  JP:        " + b.relativeFile + " @ " + hex(b.offset));
                out.println();

                printed++;

                if (printed >= 80) {
                    out.println("Stopped after 80 possible pairs.");
                    out.println();
                    return;
                }
            }
        }

        if (printed == 0) {
            out.println("No EN/JP pairs found.");
        }

        out.println();
    }

    private static boolean sameSearchShape(Hit a, Hit b) {
        if (!a.spec.segment.equals(b.spec.segment)) {
            return false;
        }

        if (!a.spec.valueKind.equals(b.spec.valueKind)) {
            return false;
        }

        if (!a.spec.transform.equals(b.spec.transform)) {
            return false;
        }

        if (a.format != b.format) {
            return false;
        }

        return a.stride == b.stride;
    }

    private static String valuesLine(long[] values) {
        StringBuilder sb = new StringBuilder();

        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }

            sb.append(hexLong(values[i]));
        }

        return sb.toString();
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

    private static String rel(File root, File file) {
        String rootPath = root.getAbsolutePath().replace("\\", "/");
        String filePath = file.getAbsolutePath().replace("\\", "/");

        if (!rootPath.endsWith("/")) {
            rootPath = rootPath + "/";
        }

        if (filePath.startsWith(rootPath)) {
            return filePath.substring(rootPath.length());
        }

        return filePath;
    }

    private static String hex(int value) {
        return "0x" + Integer.toHexString(value).toUpperCase();
    }

    private static String hexLong(long value) {
        return "0x" + Long.toHexString(value).toUpperCase();
    }

    private enum Format {
        U8(1),
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

    private static class SequenceSpec {
        String language;
        String segment;
        String valueKind;
        String transform;
        String name;
        long[] values;
    }

    private static class Hit {
        String language;
        String relativeFile;
        int offset;
        Format format;
        int stride;
        SequenceSpec spec;

        int score() {
            int score = 0;

            score += spec.values.length * 10000;

            if (TARGET_REL_FILE.equalsIgnoreCase(relativeFile)) {
                score += 5000;
            }

            if ("LENGTH".equals(spec.valueKind)) {
                score += 2000;
            }

            if ("DIV4".equals(spec.transform)) {
                score += 1000;
            }

            if (format == Format.U16_LE || format == Format.U32_LE || format == Format.U8) {
                score += 500;
            }

            if (stride == format.width) {
                score += 250;
            }

            if (offset < 0x060000) {
                score += 100;
            }

            return score;
        }
    }
}