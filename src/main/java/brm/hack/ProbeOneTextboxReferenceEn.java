package brm.hack;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import brm.Conf;

public class ProbeOneTextboxReferenceEn {

    /*
     * Report + variant generator.
     *
     * This does NOT expand text.
     * This does NOT use bridge bytes.
     * This does NOT read Excel.
     *
     * It takes a clean original SC01/006/0.4 and patches exactly ONE possible
     * reference per variant:
     *
     *   old target: 0x0608E0
     *     Steward Ribson line:
     *     "[05][0B81D342][020202][18]<Steward Ribson>..."
     *
     *   new target: 0x06092C
     *     next Musashi line:
     *     "[05][0B819742][020601][18]<Musashi>..."
     *
     * If a variant patches a real live reference, the Ribson line should
     * visibly change into the next Musashi line, skip, or otherwise behave
     * differently.
     *
     * If a variant only causes graphics glitches, that hit is probably not
     * the textbox reference.
     */

    private static final String TARGET_FILE = "SC01/006/0.4";
    private static final String CD_NAME = "SC01";

    private static final int LOCAL_BASE = 0x060000;

    private static final int OLD_FULL = 0x0608E0;
    private static final int NEW_FULL = 0x06092C;

    private static final int MAX_VARIANTS = 40;

    public static void main(String[] args) throws Exception {
        String splitdir = Conf.desktop + "brmen/";
        File targetFile = new File(splitdir, TARGET_FILE.replace("\\", "/"));

        if (!targetFile.exists()) {
            throw new RuntimeException("Missing target split file: " + targetFile.getAbsolutePath());
        }

        byte[] original = readAll(targetFile);

        File outputDir = new File(Conf.desktop + "single_reference_probe_tests/");
        outputDir.mkdirs();

        File report = new File(outputDir, "single-reference-probe-report.txt");
        PrintWriter out = new PrintWriter(report, "UTF-8");

        out.println("Single Textbox Reference Probe");
        out.println("==============================");
        out.println();
        out.println("This test creates multiple SC01.CD variants.");
        out.println("Each variant patches exactly ONE possible reference.");
        out.println();
        out.println("It does NOT expand text.");
        out.println("It does NOT use bridge bytes.");
        out.println("It does NOT use Excel edits.");
        out.println();
        out.println("Clean target file:");
        out.println("  " + targetFile.getAbsolutePath());
        out.println();
        out.println("Probe change:");
        out.println("  OLD_FULL:   " + hex(OLD_FULL));
        out.println("  NEW_FULL:   " + hex(NEW_FULL));
        out.println("  OLD_LOCAL:  " + hex(OLD_FULL - LOCAL_BASE));
        out.println("  NEW_LOCAL:  " + hex(NEW_FULL - LOCAL_BASE));
        out.println();

        List<PatchFormat> formats = buildPatchFormats();
        List<Range> ranges = buildRanges(original.length);

        List<Hit> hits = findHits(original, formats, ranges);

        out.println("SEARCH SUMMARY");
        out.println("--------------");
        out.println("Formats searched: " + formats.size());
        out.println("Priority ranges:  " + ranges.size());
        out.println("Unique hits:      " + hits.size());
        out.println("Max variants:     " + MAX_VARIANTS);
        out.println();

        out.println("PATCH FORMATS");
        out.println("-------------");
        for (PatchFormat format : formats) {
            out.println(
                    "  "
                            + format.name
                            + " old="
                            + hexLong(format.oldValue)
                            + " new="
                            + hexLong(format.newValue)
                            + " width="
                            + format.width
            );
        }
        out.println();

        out.println("PRIORITY RANGES");
        out.println("---------------");
        for (Range range : ranges) {
            out.println(
                    "  "
                            + range.name
                            + " "
                            + hex(range.start)
                            + "-"
                            + hex(range.end)
            );
        }
        out.println();

        out.println("ALL HITS, SORTED BY TEST PRIORITY");
        out.println("---------------------------------");

        for (int i = 0; i < hits.size(); i++) {
            Hit hit = hits.get(i);

            out.println(
                    padLeft(String.valueOf(i + 1), 3)
                            + " offset="
                            + hex(hit.offset)
                            + " format="
                            + hit.format.name
                            + " range="
                            + hit.range.name
                            + " old="
                            + hexLong(hit.format.oldValue)
                            + " new="
                            + hexLong(hit.format.newValue)
            );
        }

        out.println();

        int variantCount = Math.min(MAX_VARIANTS, hits.size());

        out.println("VARIANTS WRITTEN");
        out.println("----------------");

        File cdOut = new File(Conf.outdir + CD_NAME + ".CD");

        for (int i = 0; i < variantCount; i++) {
            Hit hit = hits.get(i);

            byte[] variant = copyOf(original);
            writeValue(variant, hit.offset, hit.format, hit.format.newValue);

            writeAll(targetFile, variant);

            System.out.println("Rebuilding reference probe variant " + (i + 1) + " / " + variantCount);
            System.out.println("  offset " + hex(hit.offset) + " " + hit.format.name);

            CdRebuilder.rebuildOne(splitdir, Conf.outdir, CD_NAME);

            if (!cdOut.exists()) {
                out.close();
                writeAll(targetFile, original);
                throw new RuntimeException("Expected rebuilt CD not found: " + cdOut.getAbsolutePath());
            }

            String variantName =
                    padLeftZero(String.valueOf(i + 1), 2)
                            + "_probe_"
                            + hit.format.name
                            + "_at_"
                            + hexNoPrefix(hit.offset)
                            + "_SC01.CD";

            File variantCd = new File(outputDir, variantName);
            writeAll(variantCd, readAll(cdOut));

            out.println(
                    padLeft(String.valueOf(i + 1), 3)
                            + " "
                            + variantName
                            + " | offset="
                            + hex(hit.offset)
                            + " | format="
                            + hit.format.name
                            + " | range="
                            + hit.range.name
            );
        }

        writeAll(targetFile, original);

        out.println();
        out.println("DONE");
        out.println("----");
        out.println("Original split file was restored.");
        out.println();
        out.println("Test goal:");
        out.println("  Watch the original cutscene around the Steward Ribson line.");
        out.println();
        out.println("Normal line:");
        out.println("  Steward Ribson: Sir Musashi! Thou hath awakeneth.");
        out.println();
        out.println("A real live reference should make that moment visibly change,");
        out.println("probably into the next Musashi/Geezer line or a clear skip.");
        out.println();
        out.println("A graphics glitch alone means that variant probably hit unrelated data.");
        out.println("No visible change means that variant is not the live textbox reference.");
        out.println();

        out.close();

        System.out.println();
        System.out.println("Single-reference probe variants created.");
        System.out.println("Folder:");
        System.out.println(outputDir.getAbsolutePath());
        System.out.println();
        System.out.println("Report:");
        System.out.println(report.getAbsolutePath());
        System.out.println();
        System.out.println("Original split file restored.");
        System.out.println("Do NOT run HackEn for this test.");
    }

    private static List<PatchFormat> buildPatchFormats() {
        int oldLocal = OLD_FULL - LOCAL_BASE;
        int newLocal = NEW_FULL - LOCAL_BASE;

        List<PatchFormat> formats = new ArrayList<PatchFormat>();

        addFormat(formats, "LOCAL_RAW_U16_LE", Format.U16_LE, oldLocal, newLocal, 10);
        addFormat(formats, "LOCAL_RAW_U16_BE", Format.U16_BE, oldLocal, newLocal, 11);

        addFormat(formats, "LOCAL_DIV2_U16_LE", Format.U16_LE, oldLocal / 2, newLocal / 2, 20);
        addFormat(formats, "LOCAL_DIV2_U16_BE", Format.U16_BE, oldLocal / 2, newLocal / 2, 21);

        addFormat(formats, "LOCAL_DIV4_U16_LE", Format.U16_LE, oldLocal / 4, newLocal / 4, 30);
        addFormat(formats, "LOCAL_DIV4_U16_BE", Format.U16_BE, oldLocal / 4, newLocal / 4, 31);

        addFormat(formats, "FULL_U24_LE", Format.U24_LE, OLD_FULL, NEW_FULL, 40);
        addFormat(formats, "FULL_U24_BE", Format.U24_BE, OLD_FULL, NEW_FULL, 41);

        addFormat(formats, "FULL_U32_LE", Format.U32_LE, OLD_FULL, NEW_FULL, 50);
        addFormat(formats, "FULL_U32_BE", Format.U32_BE, OLD_FULL, NEW_FULL, 51);

        addFormat(formats, "RAM_8011_LOCAL_U32_LE", Format.U32_LE, 0x80110000L + oldLocal, 0x80110000L + newLocal, 60);
        addFormat(formats, "RAM_8011_LOCAL_U32_BE", Format.U32_BE, 0x80110000L + oldLocal, 0x80110000L + newLocal, 61);

        /*
         * MIPS addiu/ori immediate-looking instruction:
         *
         *   0x240408E0 -> 0x2404092C
         *
         * PS1 is little-endian, so this is searched as U32_LE.
         */
        addFormat(formats, "MIPS_2404_IMM_U32_LE", Format.U32_LE, 0x240408E0L, 0x2404092CL, 70);

        return formats;
    }

    private static void addFormat(
            List<PatchFormat> formats,
            String name,
            Format format,
            long oldValue,
            long newValue,
            int priority
    ) {
        if (!formatCanHold(format, oldValue) || !formatCanHold(format, newValue)) {
            return;
        }

        PatchFormat patchFormat = new PatchFormat();
        patchFormat.name = name;
        patchFormat.format = format;
        patchFormat.width = format.width;
        patchFormat.oldValue = oldValue;
        patchFormat.newValue = newValue;
        patchFormat.priority = priority;

        formats.add(patchFormat);
    }

    private static List<Range> buildRanges(int fileLength) {
        List<Range> ranges = new ArrayList<Range>();

        /*
         * First priority:
         * These are the noisy target-file cluster areas from the previous report.
         * We are patching ONE hit at a time now, not the whole bucket.
         */
        addRange(ranges, "target_cluster_65E00_667FF", 0x65E00, 0x667FF, fileLength, 0);

        /*
         * Earlier searches had suspicious table-looking material around 0x5Cxxx.
         */
        addRange(ranges, "earlier_table_area_5C000_5C8FF", 0x5C000, 0x5C8FF, fileLength, 1);

        /*
         * Earlier MIPS immediate candidates were around here.
         */
        addRange(ranges, "earlier_mips_area_21800_21900", 0x21800, 0x21900, fileLength, 2);

        /*
         * Then everything before the actual text area.
         */
        addRange(ranges, "whole_file_before_text", 0x00000, 0x5FFFF, fileLength, 3);

        /*
         * Then everything else.
         */
        addRange(ranges, "whole_file_after_text", 0x60000, fileLength - 1, fileLength, 4);

        return ranges;
    }

    private static void addRange(
            List<Range> ranges,
            String name,
            int start,
            int end,
            int fileLength,
            int priority
    ) {
        if (start < 0) {
            start = 0;
        }

        if (end >= fileLength) {
            end = fileLength - 1;
        }

        if (start > end) {
            return;
        }

        Range range = new Range();
        range.name = name;
        range.start = start;
        range.end = end;
        range.priority = priority;

        ranges.add(range);
    }

    private static List<Hit> findHits(byte[] data, List<PatchFormat> formats, List<Range> ranges) {
        List<Hit> hits = new ArrayList<Hit>();
        Set<String> seen = new LinkedHashSet<String>();

        for (Range range : ranges) {
            for (PatchFormat format : formats) {
                int last = range.end - format.width + 1;

                if (last < range.start) {
                    continue;
                }

                for (int offset = range.start; offset <= last; offset++) {
                    long actual = readValue(data, offset, format.format);

                    if (actual != format.oldValue) {
                        continue;
                    }

                    String key = format.name + "@" + offset;

                    if (seen.contains(key)) {
                        continue;
                    }

                    seen.add(key);

                    Hit hit = new Hit();
                    hit.offset = offset;
                    hit.format = format;
                    hit.range = range;

                    hits.add(hit);
                }
            }
        }

        Collections.sort(hits, new Comparator<Hit>() {
            @Override
            public int compare(Hit a, Hit b) {
                if (a.range.priority != b.range.priority) {
                    return a.range.priority - b.range.priority;
                }

                if (a.format.priority != b.format.priority) {
                    return a.format.priority - b.format.priority;
                }

                return a.offset - b.offset;
            }
        });

        return hits;
    }

    private static long readValue(byte[] data, int offset, Format format) {
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

    private static void writeValue(byte[] data, int offset, PatchFormat patchFormat, long value) {
        Format format = patchFormat.format;

        if (format == Format.U16_LE) {
            data[offset] = (byte) (value & 0xFF);
            data[offset + 1] = (byte) ((value >> 8) & 0xFF);
            return;
        }

        if (format == Format.U16_BE) {
            data[offset] = (byte) ((value >> 8) & 0xFF);
            data[offset + 1] = (byte) (value & 0xFF);
            return;
        }

        if (format == Format.U24_LE) {
            data[offset] = (byte) (value & 0xFF);
            data[offset + 1] = (byte) ((value >> 8) & 0xFF);
            data[offset + 2] = (byte) ((value >> 16) & 0xFF);
            return;
        }

        if (format == Format.U24_BE) {
            data[offset] = (byte) ((value >> 16) & 0xFF);
            data[offset + 1] = (byte) ((value >> 8) & 0xFF);
            data[offset + 2] = (byte) (value & 0xFF);
            return;
        }

        if (format == Format.U32_LE) {
            data[offset] = (byte) (value & 0xFF);
            data[offset + 1] = (byte) ((value >> 8) & 0xFF);
            data[offset + 2] = (byte) ((value >> 16) & 0xFF);
            data[offset + 3] = (byte) ((value >> 24) & 0xFF);
            return;
        }

        if (format == Format.U32_BE) {
            data[offset] = (byte) ((value >> 24) & 0xFF);
            data[offset + 1] = (byte) ((value >> 16) & 0xFF);
            data[offset + 2] = (byte) ((value >> 8) & 0xFF);
            data[offset + 3] = (byte) (value & 0xFF);
            return;
        }

        throw new RuntimeException("Unknown format: " + format);
    }

    private static boolean formatCanHold(Format format, long value) {
        if (value < 0) {
            return false;
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

    private static void writeAll(File file, byte[] data) throws Exception {
        FileOutputStream out = new FileOutputStream(file);
        out.write(data);
        out.close();
    }

    private static byte[] copyOf(byte[] data) {
        byte[] copy = new byte[data.length];
        System.arraycopy(data, 0, copy, 0, data.length);
        return copy;
    }

    private static String padLeft(String s, int len) {
        String ret = s;

        while (ret.length() < len) {
            ret = " " + ret;
        }

        return ret;
    }

    private static String padLeftZero(String s, int len) {
        String ret = s;

        while (ret.length() < len) {
            ret = "0" + ret;
        }

        return ret;
    }

    private static String hex(int value) {
        return "0x" + Integer.toHexString(value).toUpperCase();
    }

    private static String hexNoPrefix(int value) {
        return Integer.toHexString(value).toUpperCase();
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

    private static class PatchFormat {
        String name;
        Format format;
        int width;
        long oldValue;
        long newValue;
        int priority;
    }

    private static class Range {
        String name;
        int start;
        int end;
        int priority;
    }

    private static class Hit {
        int offset;
        PatchFormat format;
        Range range;
    }
}