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

        int insertDelta = expand.delta;
        int shrinkSavings = -shrink.delta;
        int extraPad = shrinkSavings - insertDelta;

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