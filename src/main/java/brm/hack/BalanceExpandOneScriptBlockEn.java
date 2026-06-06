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

    private static final String TARGET_FILE = "SC01/006/0.4";
    private static final int TARGET_ADDRESS = 0x60890;

    /*
     * Keep this as the +4 / +8 small diagnostic.
     */
    private static final int MAX_DIAGNOSTIC_DELTA = 8;

    public static void main(String[] args) throws Exception {
        String splitdir = Conf.desktop + "brmen/";
        File excel = new File(Conf.desktop + "brm-en.xlsx");

        Encoding enc = new EncodingEn();

        List<Block> blocks = readScriptBlocks(excel, enc);

        Block expand = findExpandBlock(blocks);

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

        if (expand.delta > MAX_DIAGNOSTIC_DELTA) {
            throw new RuntimeException(
                    "This is the SMALL expansion diagnostic only.\n"
                            + "Your current edit is too large: " + signed(expand.delta) + " bytes.\n"
                            + "Shorten the edited text in Excel until this block is only +4 or +8 bytes.\n"
                            + "Target block: " + TARGET_FILE + " @ " + hex(TARGET_ADDRESS)
            );
        }

        if ((expand.delta % 4) != 0) {
            throw new RuntimeException(
                    "For this diagnostic, make the expansion a multiple of 4 bytes.\n"
                            + "Current delta: " + signed(expand.delta) + "\n"
                            + "Try to make it +4 or +8 bytes."
            );
        }

        int insertDelta = expand.delta;

        Block shrink = findShrinkBlock(blocks, expand, insertDelta);

        if (shrink == null) {
            throw new RuntimeException(
                    "No later edited block in " + TARGET_FILE
                            + " saves enough bytes. Need at least "
                            + insertDelta + " bytes saved. "
                            + "Make a later line shorter in column F and run this again."
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

        int shrinkSavings = -shrink.delta;
        int extraPad = shrinkSavings - insertDelta;

        if (extraPad < 0) {
            throw new RuntimeException(
                    "Shrink block does not save enough bytes. "
                            + "Need " + insertDelta
                            + " but shrink only saves " + shrinkSavings
            );
        }

        byte[] patchedFileBytes = new byte[originalFileBytes.length];

        /*
         * Balanced same-size expansion:
         *
         * [expanded block grows by +4/+8]
         * [middle bytes shift forward]
         * [later shrink block shifts forward]
         * [extra saved bytes become zero padding]
         * [after-shrink region returns to original position]
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
        int middleDstStart = expand.address + expand.newBytes.length;

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
                        + ".before_narrow_halfword_test.bak"
        );

        if (!backup.exists()) {
            writeAll(backup, originalFileBytes);
            System.out.println("Backup written:");
            System.out.println(backup.getAbsolutePath());
        } else {
            System.out.println("Backup already exists, leaving it alone:");
            System.out.println(backup.getAbsolutePath());
        }

        /*
         * Current diagnostic:
         *
         * The broad halfword-table patch changed graphics because it patched
         * thousands of values across many files.
         *
         * This version patches ONLY the suspicious table-looking cluster inside
         * SC01/006/0.4, and ONLY values before the shrink block start.
         *
         * It does not patch SC01/000, SC01/005, SC01/009, etc.
         */
        patchNarrowHalfwordTableInTargetFile(patchedFileBytes, insertDelta);

        writeAll(targetFile, patchedFileBytes);

        System.out.println();
        System.out.println("Narrow halfword-table diagnostic complete.");
        System.out.println();

        System.out.println("EXPANDED BLOCK");
        System.out.println("File:         " + expand.fileName);
        System.out.println("Address:      " + hex(expand.address));
        System.out.println("Excel rows:   " + expand.startExcelRow + "-" + expand.endExcelRow);
        System.out.println("Original len: " + expand.originalLen);
        System.out.println("New len:      " + expand.newLen);
        System.out.println("Delta:        " + signed(expand.delta));
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

        System.out.println("No RAM pointer patches were applied.");
        System.out.println("No MIPS immediate patches were applied.");
        System.out.println("Only a narrow SC01/006/0.4 halfword-table range was patched.");
        System.out.println("No alignment padding was added.");
        System.out.println();

        String cdName = TARGET_FILE.substring(0, TARGET_FILE.indexOf('/'));

        System.out.println("Rebuilding " + cdName + ".CD...");
        CdRebuilder.rebuildOne(splitdir, Conf.outdir, cdName);

        System.out.println();
        System.out.println("Narrow halfword-table rebuild complete.");
        System.out.println("Replace this file in CDMage:");
        System.out.println(Conf.outdir + cdName + ".CD");
        System.out.println();
        System.out.println("Do NOT run HackEn for this test.");
    }

    private static void patchNarrowHalfwordTableInTargetFile(byte[] data, int delta) {
        /*
         * Suspicious SC01/006/0.4 halfword cluster from the broad test:
         *
         *   0x65FF4: 0x08E0
         *   0x6600C: 0x08F0
         *   0x660B4: 0x0900
         *   0x660E4: 0x0920
         *   0x66160: 0x08E0
         *   0x661E8: 0x0970
         *   etc.
         *
         * Only patch this local cluster.
         *
         * Also, only patch values from:
         *
         *   0x08E0 through before 0x0AA4
         *
         * because 0x0AA4 is the shrink block start. Patching 0x0AA4-0x0B9F
         * caused too much collateral damage in the broad test.
         */
        int[][] ranges = new int[][] {
                {0x00065FE0, 0x00066240}
        };

        final int shiftedStartLocal = 0x08E0;
        final int shiftedEndLocalExclusive = 0x0AA4;

        int patchedCount = 0;

        for (int r = 0; r < ranges.length; r++) {
            int start = ranges[r][0];
            int end = ranges[r][1];

            if (start >= data.length) {
                continue;
            }

            if (end > data.length) {
                end = data.length;
            }

            for (int pos = start; pos + 1 < end; pos += 2) {
                int value = readUInt16LE(data, pos);

                if (value < shiftedStartLocal || value >= shiftedEndLocalExclusive) {
                    continue;
                }

                int newValue = value + delta;

                writeUInt16LE(data, pos, newValue);

                System.out.println(
                        "Patched narrow halfword offset in "
                                + TARGET_FILE
                                + " at "
                                + hex(pos)
                                + ": "
                                + hex4(value)
                                + " -> "
                                + hex4(newValue)
                );

                patchedCount++;
            }
        }

        System.out.println("Narrow halfword-table patches applied: " + patchedCount);
    }

    private static int readUInt16LE(byte[] data, int offset) {
        int b0 = data[offset] & 0xFF;
        int b1 = data[offset + 1] & 0xFF;

        return b0 | (b1 << 8);
    }

    private static void writeUInt16LE(byte[] data, int offset, int value) {
        data[offset] = (byte) (value & 0xFF);
        data[offset + 1] = (byte) ((value >> 8) & 0xFF);
    }

    private static String hex4(int value) {
        String s = Integer.toHexString(value & 0xFFFF).toUpperCase();

        while (s.length() < 4) {
            s = "0" + s;
        }

        return "0x" + s;
    }

    private static Block findExpandBlock(List<Block> blocks) {
        for (Block block : blocks) {
            if (block.fileName.equalsIgnoreCase(TARGET_FILE)
                    && block.address == TARGET_ADDRESS) {
                return block;
            }
        }

        return null;
    }

    private static Block findShrinkBlock(List<Block> blocks, Block expand, int neededBytes) {
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