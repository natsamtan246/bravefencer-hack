package brm.hack;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;

import brm.Conf;
import brm.dump.Ctrl;

public class TestBoundaryReferenceBucketsEn {

    private static final String TARGET_FILE = "SC01/006/0.4";
    private static final int TARGET_ADDRESS = 0x60890;
    private static final int LOCAL_BASE = 0x60000;

    private static final int MAX_DIAGNOSTIC_DELTA = 8;

    public static void main(String[] args) throws Exception {
        String splitdir = Conf.desktop + "brmen/";
        File excel = new File(Conf.desktop + "brm-en.xlsx");

        File targetFile = new File(splitdir, TARGET_FILE.replace("\\", "/"));

        if (!targetFile.exists()) {
            throw new RuntimeException("Missing target split file: " + targetFile.getAbsolutePath());
        }

        Encoding enc = new EncodingEn();

        List<Block> blocks = readScriptBlocks(excel, enc);

        Block expand = findExpandBlock(blocks);

        if (expand == null) {
            throw new RuntimeException("Could not find expand target " + TARGET_FILE + " @ " + hex(TARGET_ADDRESS));
        }

        if (!expand.hasEdit) {
            throw new RuntimeException("Expand target has no column F edit.");
        }

        if (expand.delta <= 0) {
            throw new RuntimeException("Expand target is not longer. Delta=" + signed(expand.delta));
        }

        if (expand.delta > MAX_DIAGNOSTIC_DELTA) {
            throw new RuntimeException(
                    "This is still a small +4/+8 diagnostic.\n"
                            + "Current delta is " + signed(expand.delta) + ".\n"
                            + "Shorten the edit until it is +4 or +8."
            );
        }

        if ((expand.delta % 2) != 0) {
            throw new RuntimeException("Delta must be even for this boundary-reference test. Current delta=" + signed(expand.delta));
        }

        int insertDelta = expand.delta;

        Block shrink = findShrinkBlock(blocks, expand, insertDelta);

        if (shrink == null) {
            throw new RuntimeException("No later shrink block saves enough bytes.");
        }

        byte[] originalFileBytes = readAll(targetFile);

        validateBlockInsideFile(expand, originalFileBytes.length);
        validateBlockInsideFile(shrink, originalFileBytes.length);

        byte[] balancedPatchedNoBridge = buildBalancedPatch(originalFileBytes, expand, shrink, insertDelta);

        List<Integer> shiftedOldStarts = collectShiftedOldStarts(blocks, expand, shrink);

        File outputDir = new File(Conf.desktop + "boundary_reference_bucket_tests/");
        outputDir.mkdirs();

        File report = new File(outputDir, "boundary-reference-bucket-test-report.txt");
        PrintWriter out = new PrintWriter(report, "UTF-8");

        out.println("Boundary Reference Bucket Test");
        out.println("==============================");
        out.println();
        out.println("This test creates multiple SC01.CD variants.");
        out.println("It does NOT use old-start bridge bytes.");
        out.println("It expands one block, shifts the middle blocks, shrinks one later block,");
        out.println("then tries patching candidate boundary-reference buckets one at a time.");
        out.println();

        out.println("EXPAND");
        out.println("  File:         " + expand.fileName);
        out.println("  Address:      " + hex(expand.address));
        out.println("  Rows:         " + expand.startExcelRow + "-" + expand.endExcelRow);
        out.println("  Original len: " + expand.originalLen);
        out.println("  New len:      " + expand.newLen);
        out.println("  Delta:        " + signed(expand.delta));
        out.println();

        out.println("SHRINK");
        out.println("  File:         " + shrink.fileName);
        out.println("  Address:      " + hex(shrink.address));
        out.println("  Shifted addr: " + hex(shrink.address + insertDelta));
        out.println("  Rows:         " + shrink.startExcelRow + "-" + shrink.endExcelRow);
        out.println("  Original len: " + shrink.originalLen);
        out.println("  New len:      " + shrink.newLen);
        out.println("  Delta:        " + signed(shrink.delta));
        out.println();

        out.println("SHIFTED OLD STARTS TO PATCH IF A BUCKET IS REAL");
        for (Integer oldStart : shiftedOldStarts) {
            out.println("  " + hex(oldStart) + " -> " + hex(oldStart + insertDelta)
                    + " local " + hex(oldStart - LOCAL_BASE)
                    + " -> " + hex(oldStart - LOCAL_BASE + insertDelta));
        }
        out.println();

        Candidate[] candidates = new Candidate[] {
                new Candidate("00_no_pointer_patch_no_bridge", new int[][] {}),
                new Candidate("01_bucket_65E00_65FFF", new int[][] {{0x65E00, 0x65FFF}}),
                new Candidate("02_bucket_66000_661FF", new int[][] {{0x66000, 0x661FF}}),
                new Candidate("03_bucket_66200_663FF", new int[][] {{0x66200, 0x663FF}}),
                new Candidate("04_bucket_66600_667FF", new int[][] {{0x66600, 0x667FF}}),
                new Candidate("05_all_four_buckets", new int[][] {
                        {0x65E00, 0x65FFF},
                        {0x66000, 0x661FF},
                        {0x66200, 0x663FF},
                        {0x66600, 0x667FF}
                })
        };

        String cdName = TARGET_FILE.substring(0, TARGET_FILE.indexOf('/'));

        File cdOut = new File(Conf.outdir + cdName + ".CD");

        for (int i = 0; i < candidates.length; i++) {
            Candidate candidate = candidates[i];

            byte[] variant = copyOf(balancedPatchedNoBridge);

            out.println("VARIANT " + candidate.name);
            out.println("----------------------------------------");

            int patchCount = 0;

            for (int r = 0; r < candidate.ranges.length; r++) {
                int rangeStart = candidate.ranges[r][0];
                int rangeEnd = candidate.ranges[r][1];

                patchCount += patchBoundaryReferencesInRange(
                        variant,
                        shiftedOldStarts,
                        insertDelta,
                        rangeStart,
                        rangeEnd,
                        out
                );
            }

            out.println("Total patches for variant: " + patchCount);
            out.println();

            writeAll(targetFile, variant);

            System.out.println("Rebuilding variant: " + candidate.name);
            CdRebuilder.rebuildOne(splitdir, Conf.outdir, cdName);

            if (!cdOut.exists()) {
                out.close();
                throw new RuntimeException("Expected rebuilt CD not found: " + cdOut.getAbsolutePath());
            }

            File variantCd = new File(outputDir, candidate.name + "_SC01.CD");
            writeAll(variantCd, readAll(cdOut));

            out.println("Wrote variant CD:");
            out.println("  " + variantCd.getAbsolutePath());
            out.println();
        }

        writeAll(targetFile, originalFileBytes);

        out.println("DONE");
        out.println("----");
        out.println("The original split file was restored at the end.");
        out.println();
        out.println("Test these CD files one at a time in CDMage:");
        out.println("  " + outputDir.getAbsolutePath());
        out.println();
        out.println("Expected results:");
        out.println("  00_no_pointer_patch_no_bridge should still skip boxes.");
        out.println("  If a bucket is real, that variant should show all boxes and preserve voice timing.");
        out.println("  If a bucket is false, it may skip boxes or cause visual glitches.");
        out.println();

        out.close();

        System.out.println();
        System.out.println("Boundary-reference bucket variants created.");
        System.out.println("Folder:");
        System.out.println(outputDir.getAbsolutePath());
        System.out.println();
        System.out.println("Report:");
        System.out.println(report.getAbsolutePath());
        System.out.println();
        System.out.println("The original split file was restored.");
        System.out.println("Do NOT run HackEn for this test.");
    }

    private static int patchBoundaryReferencesInRange(
            byte[] data,
            List<Integer> oldStarts,
            int delta,
            int rangeStart,
            int rangeEnd,
            PrintWriter out
    ) {
        int patches = 0;

        if (rangeStart < 0) {
            rangeStart = 0;
        }

        if (rangeEnd >= data.length) {
            rangeEnd = data.length - 1;
        }

        boolean[] touched = new boolean[data.length];

        for (int offset = rangeStart; offset <= rangeEnd - 1; offset++) {
            if (touched[offset] || touched[offset + 1]) {
                continue;
            }

            PatchMatch le = findU16Match(readU16LE(data, offset), oldStarts, delta);

            if (le != null) {
                writeU16LE(data, offset, le.newValue);
                touched[offset] = true;
                touched[offset + 1] = true;

                out.println("  PATCH LE @ " + hex(offset)
                        + " " + le.description
                        + " " + hex(le.oldValue)
                        + " -> " + hex(le.newValue));

                patches++;
                continue;
            }

            PatchMatch be = findU16Match(readU16BE(data, offset), oldStarts, delta);

            if (be != null) {
                writeU16BE(data, offset, be.newValue);
                touched[offset] = true;
                touched[offset + 1] = true;

                out.println("  PATCH BE @ " + hex(offset)
                        + " " + be.description
                        + " " + hex(be.oldValue)
                        + " -> " + hex(be.newValue));

                patches++;
            }
        }

        return patches;
    }

    private static PatchMatch findU16Match(int value, List<Integer> oldStarts, int delta) {
        for (Integer oldStartObj : oldStarts) {
            int oldStart = oldStartObj.intValue();
            int local = oldStart - LOCAL_BASE;

            if (value == local) {
                PatchMatch m = new PatchMatch();
                m.oldValue = value;
                m.newValue = local + delta;
                m.description = "LOCAL_RAW for " + hex(oldStart);
                return m;
            }

            if ((local % 2) == 0 && (delta % 2) == 0) {
                int div2 = local / 2;

                if (value == div2) {
                    PatchMatch m = new PatchMatch();
                    m.oldValue = value;
                    m.newValue = div2 + (delta / 2);
                    m.description = "LOCAL_DIV2 for " + hex(oldStart);
                    return m;
                }
            }

            if ((local % 4) == 0 && (delta % 4) == 0) {
                int div4 = local / 4;

                if (value == div4) {
                    PatchMatch m = new PatchMatch();
                    m.oldValue = value;
                    m.newValue = div4 + (delta / 4);
                    m.description = "LOCAL_DIV4 for " + hex(oldStart);
                    return m;
                }
            }
        }

        return null;
    }

    private static List<Integer> collectShiftedOldStarts(List<Block> blocks, Block expand, Block shrink) {
        List<Integer> starts = new ArrayList<Integer>();

        for (Block block : blocks) {
            if (!TARGET_FILE.equalsIgnoreCase(block.fileName)) {
                continue;
            }

            if (block.address <= expand.address) {
                continue;
            }

            if (block.address > shrink.address) {
                continue;
            }

            starts.add(Integer.valueOf(block.address));
        }

        return starts;
    }

    private static byte[] buildBalancedPatch(byte[] originalFileBytes, Block expand, Block shrink, int insertDelta) {
        int shrinkSavings = -shrink.delta;

        if (shrinkSavings < insertDelta) {
            throw new RuntimeException("Shrink block does not save enough bytes.");
        }

        byte[] patchedFileBytes = new byte[originalFileBytes.length];

        System.arraycopy(
                originalFileBytes,
                0,
                patchedFileBytes,
                0,
                expand.address
        );

        System.arraycopy(
                expand.newBytes,
                0,
                patchedFileBytes,
                expand.address,
                expand.newBytes.length
        );

        int middleSrcStart = expand.address + expand.originalLen;
        int middleSrcEnd = shrink.address;
        int middleLen = middleSrcEnd - middleSrcStart;
        int middleDstStart = expand.address + expand.newBytes.length;

        System.arraycopy(
                originalFileBytes,
                middleSrcStart,
                patchedFileBytes,
                middleDstStart,
                middleLen
        );

        int shrinkDstStart = shrink.address + insertDelta;

        System.arraycopy(
                shrink.newBytes,
                0,
                patchedFileBytes,
                shrinkDstStart,
                shrink.newBytes.length
        );

        int padStart = shrinkDstStart + shrink.newBytes.length;
        int padEnd = shrink.address + shrink.originalLen;

        for (int i = padStart; i < padEnd; i++) {
            patchedFileBytes[i] = 0x00;
        }

        int afterShrinkSrcStart = shrink.address + shrink.originalLen;
        int afterShrinkLen = originalFileBytes.length - afterShrinkSrcStart;

        System.arraycopy(
                originalFileBytes,
                afterShrinkSrcStart,
                patchedFileBytes,
                afterShrinkSrcStart,
                afterShrinkLen
        );

        return patchedFileBytes;
    }

    private static Block findExpandBlock(List<Block> blocks) {
        for (Block block : blocks) {
            if (TARGET_FILE.equalsIgnoreCase(block.fileName)
                    && block.address == TARGET_ADDRESS) {
                return block;
            }
        }

        return null;
    }

    private static Block findShrinkBlock(List<Block> blocks, Block expand, int neededBytes) {
        for (Block block : blocks) {
            if (!TARGET_FILE.equalsIgnoreCase(block.fileName)) {
                continue;
            }

            if (!block.hasEdit) {
                continue;
            }

            if (block.address <= expand.address) {
                continue;
            }

            if (block.delta >= 0) {
                continue;
            }

            int savedBytes = -block.delta;

            if (savedBytes >= neededBytes) {
                return block;
            }
        }

        return null;
    }

    private static List<Block> readScriptBlocks(File excel, Encoding enc) throws Exception {
        List<Block> blocks = new ArrayList<Block>();

        FileInputStream in = new FileInputStream(excel);
        Workbook wb = WorkbookFactory.create(in);
        Sheet sheet = wb.getSheet("SCRIPTS");

        if (sheet == null) {
            in.close();
            throw new RuntimeException("SCRIPTS sheet not found.");
        }

        Block current = null;

        for (int r = 1; r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);

            String fileName = getCellText(row, 0).trim();
            String address = getCellText(row, 1).trim();
            String lenText = getCellText(row, 2).trim();
            String ctrl = getCellText(row, 3);
            String original = getCellText(row, 4);
            String edit = getCellText(row, 5);

            if (!fileName.isEmpty() && !address.isEmpty()) {
                finishBlock(current, enc, blocks);

                current = new Block();
                current.fileName = fileName.replace("\\", "/");
                current.address = parseHexAddress(address);
                current.startExcelRow = r + 1;
            }

            if (current == null) {
                continue;
            }

            current.endExcelRow = r + 1;

            if (!lenText.isEmpty()) {
                current.originalLen = parseDecimalOrHexLength(lenText);
            }

            current.text.append(ctrl);

            if (!edit.isEmpty()) {
                current.text.append(edit);
                current.hasEdit = true;
            } else {
                current.text.append(original);
            }
        }

        finishBlock(current, enc, blocks);

        in.close();

        return blocks;
    }

    private static void finishBlock(Block block, Encoding enc, List<Block> blocks) {
        if (block == null) {
            return;
        }

        if (block.originalLen < 0) {
            return;
        }

        if (block.hasEdit) {
            block.newBytes = serialize(block.text.toString(), enc);
            block.newLen = block.newBytes.length;
            block.delta = block.newLen - block.originalLen;
        } else {
            block.newBytes = null;
            block.newLen = block.originalLen;
            block.delta = 0;
        }

        blocks.add(block);
    }

    private static byte[] serialize(String text, Encoding enc) {
        ByteBuilder out = new ByteBuilder();

        int i = 0;

        while (i < text.length()) {
            char ch = text.charAt(i);

            if (ch == '[') {
                int close = text.indexOf(']', i);

                if (close >= 0) {
                    String token = text.substring(i, close + 1);
                    out.add(Ctrl.encode(token));
                    i = close + 1;
                    continue;
                }
            }

            int codePoint = text.codePointAt(i);
            String oneChar = new String(Character.toChars(codePoint));

            out.add(enc.getCode(oneChar));
            i += Character.charCount(codePoint);
        }

        out.add(new byte[] {0x00});

        return out.toByteArray();
    }

    private static void validateBlockInsideFile(Block block, int fileSize) {
        if (block.address < 0 || block.address + block.originalLen > fileSize) {
            throw new RuntimeException(
                    "Block outside file: "
                            + block.fileName
                            + " address="
                            + hex(block.address)
                            + " len="
                            + block.originalLen
                            + " fileSize="
                            + fileSize
            );
        }
    }

    private static int readU16LE(byte[] data, int offset) {
        return (data[offset] & 0xFF)
                | ((data[offset + 1] & 0xFF) << 8);
    }

    private static int readU16BE(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 8)
                | (data[offset + 1] & 0xFF);
    }

    private static void writeU16LE(byte[] data, int offset, int value) {
        data[offset] = (byte) (value & 0xFF);
        data[offset + 1] = (byte) ((value >> 8) & 0xFF);
    }

    private static void writeU16BE(byte[] data, int offset, int value) {
        data[offset] = (byte) ((value >> 8) & 0xFF);
        data[offset + 1] = (byte) (value & 0xFF);
    }

    private static String getCellText(Row row, int cellIndex) {
        if (row == null) {
            return "";
        }

        Cell cell = row.getCell(cellIndex);

        if (cell == null) {
            return "";
        }

        switch (cell.getCellType()) {
            case Cell.CELL_TYPE_STRING:
                return cell.getStringCellValue();

            case Cell.CELL_TYPE_NUMERIC:
                double num = cell.getNumericCellValue();

                if (num == Math.floor(num)) {
                    return String.valueOf((long) num);
                }

                return String.valueOf(num);

            case Cell.CELL_TYPE_BOOLEAN:
                return String.valueOf(cell.getBooleanCellValue());

            case Cell.CELL_TYPE_FORMULA:
                try {
                    return cell.getStringCellValue();
                } catch (Exception ex) {
                    try {
                        double formulaNum = cell.getNumericCellValue();

                        if (formulaNum == Math.floor(formulaNum)) {
                            return String.valueOf((long) formulaNum);
                        }

                        return String.valueOf(formulaNum);
                    } catch (Exception ex2) {
                        return "";
                    }
                }

            default:
                return "";
        }
    }

    private static int parseHexAddress(String text) {
        String s = text.trim();

        if (s.startsWith("0x") || s.startsWith("0X")) {
            return Integer.parseInt(s.substring(2), 16);
        }

        return Integer.parseInt(s, 16);
    }

    private static int parseDecimalOrHexLength(String text) {
        String s = text.trim();

        if (s.startsWith("0x") || s.startsWith("0X")) {
            return Integer.parseInt(s.substring(2), 16);
        }

        return Integer.parseInt(s);
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

    private static String hex(int value) {
        return "0x" + Integer.toHexString(value).toUpperCase();
    }

    private static String signed(int value) {
        if (value >= 0) {
            return "+" + value;
        }

        return String.valueOf(value);
    }

    private static class Candidate {
        String name;
        int[][] ranges;

        Candidate(String name, int[][] ranges) {
            this.name = name;
            this.ranges = ranges;
        }
    }

    private static class PatchMatch {
        int oldValue;
        int newValue;
        String description;
    }

    private static class Block {
        String fileName;
        int address;
        int startExcelRow;
        int endExcelRow;
        int originalLen = -1;
        boolean hasEdit = false;
        StringBuilder text = new StringBuilder();

        byte[] newBytes;
        int newLen;
        int delta;
    }

    private static class ByteBuilder {
        private byte[] data = new byte[256];
        private int size = 0;

        void add(byte[] bytes) {
            ensure(size + bytes.length);

            for (int i = 0; i < bytes.length; i++) {
                data[size++] = bytes[i];
            }
        }

        byte[] toByteArray() {
            byte[] ret = new byte[size];
            System.arraycopy(data, 0, ret, 0, size);
            return ret;
        }

        private void ensure(int needed) {
            if (needed <= data.length) {
                return;
            }

            int newLen = data.length;

            while (newLen < needed) {
                newLen *= 2;
            }

            byte[] next = new byte[newLen];
            System.arraycopy(data, 0, next, 0, size);
            data = next;
        }
    }
}