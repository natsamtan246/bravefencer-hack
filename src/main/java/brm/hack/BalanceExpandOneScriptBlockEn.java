package brm.hack;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;

import brm.Conf;
import brm.dump.Ctrl;

public class BalanceExpandOneScriptBlockEn {

    /*
     * Expand this block.
     */
    private static final String TARGET_FILE = "SC01/006/0.4";
    private static final int TARGET_ADDRESS = 0x60890;

    public static void main(String[] args) throws Exception {
        String splitdir = Conf.desktop + "brmen/";
        File excel = new File(Conf.desktop + "brm-en.xlsx");

        Encoding enc = new EncodingEn();

        List<Block> blocks = readScriptBlocks(excel, enc);

        Block expand = null;

        for (Block block : blocks) {
            if (block.fileName.equalsIgnoreCase(TARGET_FILE)
                    && block.address == TARGET_ADDRESS) {
                expand = block;
                break;
            }
        }

        if (expand == null) {
            throw new RuntimeException(
                    "Could not find expand target: "
                            + TARGET_FILE + " @ " + hex(TARGET_ADDRESS)
            );
        }

        if (!expand.hasEdit) {
            throw new RuntimeException(
                    "Expand target found, but column F has no edit."
            );
        }

        if (expand.delta <= 0) {
            throw new RuntimeException(
                    "Expand target does not overflow. Delta was " + signed(expand.delta)
            );
        }

        Block shrink = null;

        for (Block block : blocks) {
            if (!block.fileName.equalsIgnoreCase(TARGET_FILE)) {
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

            if (savedBytes >= expand.delta) {
                shrink = block;
                break;
            }
        }

        if (shrink == null) {
            throw new RuntimeException(
                    "No later edited block in " + TARGET_FILE
                            + " saves enough bytes. Need at least "
                            + expand.delta + " bytes saved. "
                            + "Make a later line much shorter in column F and run this again."
            );
        }

        File targetFile = new File(splitdir, TARGET_FILE.replace("\\", "/"));

        if (!targetFile.exists()) {
            throw new RuntimeException("Split file not found: " + targetFile.getAbsolutePath());
        }

        byte[] originalFileBytes = readAll(targetFile);

        validateBlockInsideFile(expand, originalFileBytes.length);
        validateBlockInsideFile(shrink, originalFileBytes.length);

        if (shrink.address < expand.address + expand.originalLen) {
            throw new RuntimeException(
                    "Shrink block overlaps expand block. Pick a later shrink block."
            );
        }

        int rawInsertDelta = expand.delta;

        /*
         * Important alignment test:
         *
         * The failed +50 test shifted later text blocks to addresses ending in ...12,
         * ...5E, ...AA, etc. Those are not 4-byte aligned.
         *
         * Round the inserted area up to a multiple of 4 so every later block remains
         * aligned.
         */
        int insertDelta = roundUpToMultiple(rawInsertDelta, 4);
        int expandedAreaLen = expand.originalLen + insertDelta;
        int expansionPadding = expandedAreaLen - expand.newBytes.length;

        int shrinkSavings = -shrink.delta;
        int extraPad = shrinkSavings - insertDelta;

        if (extraPad < 0) {
            throw new RuntimeException(
                    "Shrink block does not save enough bytes after alignment padding. "
                            + "Need " + insertDelta
                            + " but shrink only saves " + shrinkSavings
            );
        }

        byte[] patchedFileBytes = new byte[originalFileBytes.length];

        /*
         * Original:
         *
         * [before expand]
         * [expand original area]
         * [middle bytes]
         * [shrink original area]
         * [after shrink]
         *
         * Patched:
         *
         * [before expand]
         * [expanded text]
         * [middle bytes shifted forward by insertDelta]
         * [shortened shrink text shifted forward by insertDelta]
         * [zero padding if shrink saved more than needed]
         * [after shrink, back at original offset]
         */

        // 1. Copy everything before expanded block.
        System.arraycopy(
                originalFileBytes,
                0,
                patchedFileBytes,
                0,
                expand.address
        );

        // 2. Write expanded text at original address.
        System.arraycopy(
                expand.newBytes,
                0,
                patchedFileBytes,
                expand.address,
                expand.newBytes.length
        );

        // 3. Copy middle section, shifted forward.
        int middleSrcStart = expand.address + expand.originalLen;
        int middleSrcEnd = shrink.address;
        int middleLen = middleSrcEnd - middleSrcStart;
        int middleDstStart = expand.address + expandedAreaLen;

        System.arraycopy(
                originalFileBytes,
                middleSrcStart,
                patchedFileBytes,
                middleDstStart,
                middleLen
        );

        // 4. Write shortened block at its shifted position.
        int shrinkDstStart = shrink.address + insertDelta;

        System.arraycopy(
                shrink.newBytes,
                0,
                patchedFileBytes,
                shrinkDstStart,
                shrink.newBytes.length
        );

        // 5. Zero-fill any extra saved space before the original post-shrink area.
        int padStart = shrinkDstStart + shrink.newBytes.length;
        int padEnd = shrink.address + shrink.originalLen;

        for (int i = padStart; i < padEnd; i++) {
            patchedFileBytes[i] = 0x00;
        }

        // 6. Copy everything after the shrink block back to its original position.
        int afterShrinkSrcStart = shrink.address + shrink.originalLen;
        int afterShrinkLen = originalFileBytes.length - afterShrinkSrcStart;

        System.arraycopy(
                originalFileBytes,
                afterShrinkSrcStart,
                patchedFileBytes,
                afterShrinkSrcStart,
                afterShrinkLen
        );

        File backupDir = new File(Conf.desktop + "brmen_backups/");
        backupDir.mkdirs();

        File backup = new File(
                backupDir,
                TARGET_FILE.replace("/", "_").replace("\\", "_")
                        + ".before_balance_expand_test.bak"
        );

        if (!backup.exists()) {
            writeAll(backup, originalFileBytes);
            System.out.println("Backup written:");
            System.out.println(backup.getAbsolutePath());
        } else {
            System.out.println("Backup already exists, leaving it alone:");
            System.out.println(backup.getAbsolutePath());
        }

        //patchLikelyTextCallReferencesForMovedText(patchedFileBytes);

        writeAll(targetFile, patchedFileBytes);

        System.out.println();
        System.out.println("Balanced expansion patch complete.");
        System.out.println();

        System.out.println("EXPANDED BLOCK");
        System.out.println("File:         " + expand.fileName);
        System.out.println("Address:      " + hex(expand.address));
        System.out.println("Excel rows:   " + expand.startExcelRow + "-" + expand.endExcelRow);
        System.out.println("Original len: " + expand.originalLen);
        System.out.println("New len:      " + expand.newLen);
        System.out.println("Delta:        " + signed(expand.delta));
        System.out.println("Aligned area: " + expandedAreaLen);
        System.out.println("Raw delta:    " + signed(rawInsertDelta));
        System.out.println("Aligned delta:" + signed(insertDelta));
        System.out.println("Align padding after terminator: " + expansionPadding + " bytes");
        System.out.println();

        System.out.println("SHRUNK BLOCK");
        System.out.println("File:         " + shrink.fileName);
        System.out.println("Address:      " + hex(shrink.address));
        System.out.println("Excel rows:   " + shrink.startExcelRow + "-" + shrink.endExcelRow);
        System.out.println("Original len: " + shrink.originalLen);
        System.out.println("New len:      " + shrink.newLen);
        System.out.println("Delta:        " + signed(shrink.delta));
        System.out.println("Extra zero padding inside shrink area: " + extraPad + " bytes");
        System.out.println();

        System.out.println("File size stayed the same:");
        System.out.println("Original file size: " + originalFileBytes.length);
        System.out.println("Patched file size:  " + patchedFileBytes.length);
        System.out.println();

        String cdName = TARGET_FILE.substring(0, TARGET_FILE.indexOf('/'));

        System.out.println("Rebuilding " + cdName + ".CD...");
        CdRebuilder.rebuildOne(splitdir, Conf.outdir, cdName);

        System.out.println();
        System.out.println("Balanced expansion rebuild complete.");
        System.out.println("Replace this file in CDMage:");
        System.out.println(Conf.outdir + cdName + ".CD");
        System.out.println();
        System.out.println("Do NOT run HackEn for this test.");
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

            String fileName = getCellText(row, 0).trim(); // A
            String address = getCellText(row, 1).trim();  // B
            String lenText = getCellText(row, 2).trim();  // C
            String ctrl = getCellText(row, 3);            // D
            String original = getCellText(row, 4);        // E
            String edit = getCellText(row, 5);            // F

            if (!fileName.isEmpty() && !address.isEmpty()) {
                finishBlock(current, enc, blocks);

                current = new Block();
                current.fileName = fileName.replace("\\", "/");
                current.address = parseHexAddress(address);
                current.addressText = address;
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

        if (!block.hasEdit) {
            return;
        }

        block.newBytes = serialize(block.text.toString(), enc);
        block.newLen = block.newBytes.length;
        block.delta = block.newLen - block.originalLen;

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
                    "Block is outside file: "
                            + block.fileName
                            + " address=" + hex(block.address)
                            + " len=" + block.originalLen
                            + " fileSize=" + fileSize
            );
        }
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

    private static String hex(int value) {
        return "0x" + Integer.toHexString(value).toUpperCase();
    }

    private static String signed(int value) {
        if (value >= 0) {
            return "+" + value;
        }

        return String.valueOf(value);
    }
    private static void patchRamPointerTableForMovedRange(byte[] data) {
        /*
         * Experimental pointer-table fix.
         *
         * The RAM pointer search found a likely table around 0x0005C5CC:
         *
         *   C0 09 11 80
         *   FC 09 11 80
         *   34 0A 11 80
         *   68 0A 11 80
         *
         * These are little-endian RAM pointers:
         *
         *   0x801109C0
         *   0x801109FC
         *   0x80110A34
         *   0x80110A68
         *
         * Since the expanded text inserted +0x32 bytes, anything originally
         * from local offset 0x08E0 through before 0x0AA4 moved forward by +0x32.
         */
        final int tableStart = 0x0005C5A0;
        final int tableEndExclusive = 0x0005C620;

        final int ramBase = 0x80110000;
        final int movedStartLocal = 0x08E0;
        final int movedEndLocalExclusive = 0x0AA4;
        final int delta = 0x32;

        int patchedCount = 0;

        for (int pos = tableStart; pos + 3 < data.length && pos < tableEndExclusive; pos += 4) {
            int pointer = readInt32LE(data, pos);
            int local = pointer - ramBase;

            if (local >= movedStartLocal && local < movedEndLocalExclusive) {
                int newPointer = pointer + delta;

                writeInt32LE(data, pos, newPointer);

                System.out.println(
                        "Patched RAM pointer at file "
                                + hex(pos)
                                + ": "
                                + hex(pointer)
                                + " -> "
                                + hex(newPointer)
                );

                patchedCount++;
            }
        }

        System.out.println("RAM pointer table patches applied: " + patchedCount);
    }

    private static int readInt32LE(byte[] data, int offset) {
        int b0 = data[offset] & 0xFF;
        int b1 = data[offset + 1] & 0xFF;
        int b2 = data[offset + 2] & 0xFF;
        int b3 = data[offset + 3] & 0xFF;

        return b0 | (b1 << 8) | (b2 << 16) | (b3 << 24);
    }

    private static void writeInt32LE(byte[] data, int offset, int value) {
        data[offset] = (byte) (value & 0xFF);
        data[offset + 1] = (byte) ((value >> 8) & 0xFF);
        data[offset + 2] = (byte) ((value >> 16) & 0xFF);
        data[offset + 3] = (byte) ((value >> 24) & 0xFF);
    }
    private static void patchMipsImmediateReferencesForMovedText(byte[] data) {
        /*
         * Experimental MIPS immediate fix.
         *
         * The MIPS search found four strong code references in SC01/006/0.4:
         *
         *   0x00021864: E0 08 06 24 = addiu a2, zero, 0x08E0
         *   0x00021874: E0 08 05 24 = addiu a1, zero, 0x08E0
         *   0x00021890: E0 08 02 24 = addiu v0, zero, 0x08E0
         *   0x000218A4: E0 08 04 24 = addiu a0, zero, 0x08E0
         *
         * After inserting +0x32 bytes before 0x08E0, the first moved block
         * becomes:
         *
         *   0x08E0 + 0x32 = 0x0912
         *
         * So we patch only those four exact instructions.
         */
        patchWordExact(data, 0x00021864, 0x240608E0, 0x24060912);
        patchWordExact(data, 0x00021874, 0x240508E0, 0x24050912);
        patchWordExact(data, 0x00021890, 0x240208E0, 0x24020912);
        patchWordExact(data, 0x000218A4, 0x240408E0, 0x24040912);
    }

    private static void patchWordExact(byte[] data, int offset, int expectedOldWord, int newWord) {
        int actualOldWord = readInt32LE(data, offset);

        if (actualOldWord != expectedOldWord) {
            throw new RuntimeException(
                    "MIPS patch safety check failed at "
                            + hex(offset)
                            + ". Expected "
                            + hex(expectedOldWord)
                            + " but found "
                            + hex(actualOldWord)
            );
        }

        writeInt32LE(data, offset, newWord);

        System.out.println(
                "Patched MIPS immediate at "
                        + hex(offset)
                        + ": "
                        + hex(expectedOldWord)
                        + " -> "
                        + hex(newWord)
        );
    }
    private static void patchLikelyTextCallReferencesForMovedText(byte[] data) {
        /*
         * Experimental text-call immediate fix.
         *
         * These are stronger candidates than the 0x08E0 block-start references,
         * because they appear near likely text-display calls.
         *
         * Each immediate is inside the moved original range:
         *
         *   0x08E0 through before 0x0AA4
         *
         * So each gets +0x32.
         */

        // addiu a0, zero, 0x08F2
        // likely text call near jal 0x000B532
        patchWordExact(data, 0x00007AAC, 0x240408F2, 0x24040924);

        // addiu a0, zero, 0x0A74
        // likely text call near jal 0x000B532
        patchWordExact(data, 0x00007B20, 0x24040A74, 0x24040AA6);

        // jal 0x0051CC9 delay slot:
        // addiu a0, zero, 0x0A1B
        patchWordExact(data, 0x00028744, 0x24040A1B, 0x24040A4D);

        // jal 0x0051CC9 delay slot:
        // addiu a0, zero, 0x09DA
        patchWordExact(data, 0x000370D0, 0x240409DA, 0x24040A0C);

        // jal 0x0051CC9 delay slot:
        // addiu a0, zero, 0x0989
        patchWordExact(data, 0x00037CF8, 0x24040989, 0x240409BB);

        // jal 0x0051CC9 delay slot:
        // addiu a0, zero, 0x08E1
        patchWordExact(data, 0x00039098, 0x240408E1, 0x24040913);

        // addiu a0, zero, 0x0A05
        // likely argument before jal 0x0051CC9
        patchWordExact(data, 0x00054058, 0x24040A05, 0x24040A37);
    }
    private static int roundUpToMultiple(int value, int multiple) {
        int remainder = value % multiple;

        if (remainder == 0) {
            return value;
        }

        return value + (multiple - remainder);
    }

    private static class Block {
        String fileName;
        String addressText;
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