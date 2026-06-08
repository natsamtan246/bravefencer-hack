package brm.hack;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
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
     * Real expansion target:
     *
     * +50 decimal = 0x32 bytes.
     *
     * This is NOT the old +4/+8 bridge diagnostic.
     */
    private static final int EXPECTED_DELTA = 0x32;

    /*
     * The shrink block we have been using to balance the +50 expansion.
     * Keeping this strict prevents accidentally using the wrong shortened line.
     */
    private static final int EXPECTED_SHRINK_ADDRESS = 0x60AA4;

    /*
     * no$psx/debugger confirmed:
     *
     *   file offset 0x0608E0 -> RAM 0x80188A38
     *
     * Therefore:
     *
     *   runtime base = 0x80188A38 - 0x0608E0 = 0x80128158
     */
    private static final int RUNTIME_BASE = 0x80128158;

    private static final PointerPatch[] POINTER_PATCHES = new PointerPatch[] {
            /*
             * Expanded block start stays the same.
             */
            new PointerPatch("first Musashi / expanded block", 0x93E04, 0x060890, 0x060890, false),

            /*
             * These blocks physically move forward by +0x32.
             */
            new PointerPatch("Steward Ribson line",       0x93EBC, 0x0608E0, 0x0608E0 + EXPECTED_DELTA, true),
            new PointerPatch("next Musashi/Geezer line", 0x93EDC, 0x06092C, 0x06092C + EXPECTED_DELTA, true),
            new PointerPatch("following line 1",         0x93EF4, 0x060978, 0x060978 + EXPECTED_DELTA, true),
            new PointerPatch("following line 2",         0x93F0C, 0x0609C4, 0x0609C4 + EXPECTED_DELTA, true),
            new PointerPatch("following line 3",         0x93F24, 0x0609FC, 0x0609FC + EXPECTED_DELTA, true),
            new PointerPatch("shrink block",             0x93F3C, 0x060AA4, 0x060AA4 + EXPECTED_DELTA, true),

            /*
             * After the shortened block, the file layout is balanced back to
             * original positions.
             */
            new PointerPatch("first after-shrink block", 0x93F5C, 0x060BA0, 0x060BA0, false),
            new PointerPatch("later known large block",  0x93F8C, 0x060C7C, 0x060C7C, false)
    };

    public static void main(String[] args) throws Exception {
        String splitdir = Conf.desktop + "brmen/";
        File excel = new File(Conf.desktop + "brm-en.xlsx");

        File reportFile = new File(Conf.desktop + "balance-expand-plus-runtime-pointer-patch-en.txt");
        PrintWriter report = new PrintWriter(reportFile, "UTF-8");

        report.println("Balance Expand One Script Block EN - Real +50 Version");
        report.println("=====================================================");
        report.println();

        Encoding enc = new EncodingEn();

        List<Block> blocks = readScriptBlocks(excel, enc);

        Block expand = findExpandBlock(blocks);

        if (expand == null) {
            report.close();
            throw new RuntimeException(
                    "Could not find expand target: "
                            + TARGET_FILE + " @ " + hex(TARGET_ADDRESS)
            );
        }

        if (!expand.hasEdit) {
            report.close();
            throw new RuntimeException(
                    "Expand target found, but column F has no edit."
            );
        }

        if (expand.delta <= 0) {
            report.close();
            throw new RuntimeException(
                    "Expand target does not overflow. Delta was " + signed(expand.delta)
            );
        }

        if (expand.delta != EXPECTED_DELTA) {
            report.close();
            throw new RuntimeException(
                    "This is the real +50 version, but your current edit is "
                            + signed(expand.delta)
                            + " bytes.\n"
                            + "Expected exactly +50 / 0x32 bytes.\n"
                            + "Target block: "
                            + TARGET_FILE
                            + " @ "
                            + hex(TARGET_ADDRESS)
            );
        }

        if ((expand.delta % 2) != 0) {
            report.close();
            throw new RuntimeException(
                    "Expansion delta must be even for this text stream.\n"
                            + "Current delta: " + signed(expand.delta)
            );
        }

        int insertDelta = expand.delta;

        Block shrink = findShrinkBlock(blocks, expand, insertDelta);

        if (shrink == null) {
            report.close();
            throw new RuntimeException(
                    "No later edited block in " + TARGET_FILE
                            + " saves enough bytes. Need at least "
                            + insertDelta + " bytes saved."
            );
        }

        if (shrink.address != EXPECTED_SHRINK_ADDRESS) {
            report.close();
            throw new RuntimeException(
                    "Wrong shrink block selected.\n"
                            + "Expected shrink block at "
                            + hex(EXPECTED_SHRINK_ADDRESS)
                            + " but selected "
                            + hex(shrink.address)
                            + ".\n"
                            + "For this +50 test, shorten the known shrink block at 0x60AA4."
            );
        }

        File targetFile = new File(splitdir, TARGET_FILE.replace("/", File.separator));

        if (!targetFile.exists()) {
            report.close();
            throw new RuntimeException("Split file not found: " + targetFile.getAbsolutePath());
        }

        byte[] originalFileBytes = readAll(targetFile);

        validateBlockInsideFile(expand, originalFileBytes.length);
        validateBlockInsideFile(shrink, originalFileBytes.length);

        if (shrink.address < expand.address + expand.originalLen) {
            report.close();
            throw new RuntimeException(
                    "Shrink block overlaps expand block. Pick a later shrink block."
            );
        }

        int shrinkSavings = -shrink.delta;
        int extraPad = shrinkSavings - insertDelta;

        if (extraPad < 0) {
            report.close();
            throw new RuntimeException(
                    "Shrink block does not save enough bytes. "
                            + "Need " + insertDelta
                            + " but shrink only saves " + shrinkSavings
            );
        }

        report.println("Target split file:");
        report.println("  " + targetFile.getAbsolutePath());
        report.println();

        report.println("Expanded block:");
        report.println("  file:         " + expand.fileName);
        report.println("  address:      " + hex(expand.address));
        report.println("  Excel rows:   " + expand.startExcelRow + "-" + expand.endExcelRow);
        report.println("  original len: " + expand.originalLen);
        report.println("  new len:      " + expand.newLen);
        report.println("  delta:        " + signed(expand.delta) + " / " + hex(expand.delta));
        report.println();

        report.println("Shrink block:");
        report.println("  file:         " + shrink.fileName);
        report.println("  address:      " + hex(shrink.address));
        report.println("  shifted addr: " + hex(shrink.address + insertDelta));
        report.println("  Excel rows:   " + shrink.startExcelRow + "-" + shrink.endExcelRow);
        report.println("  original len: " + shrink.originalLen);
        report.println("  new len:      " + shrink.newLen);
        report.println("  delta:        " + signed(shrink.delta));
        report.println("  extra zero padding inside shrink area: " + extraPad + " bytes");
        report.println();

        backup(targetFile, originalFileBytes, report);

        byte[] patchedFileBytes = new byte[originalFileBytes.length];

        /*
         * Balanced same-size expansion:
         *
         * 1. Expanded text grows at 0x60890 by +0x32.
         * 2. Middle bytes shift forward by +0x32.
         * 3. Shrink block is written at 0x60AA4 + 0x32.
         * 4. Extra saved bytes become zero padding.
         * 5. Everything after the shrink block returns to original position.
         */

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

        report.println("Balanced layout written in memory.");
        report.println("  original file size: " + originalFileBytes.length);
        report.println("  patched file size:  " + patchedFileBytes.length);
        report.println();

        verifyAndApplyRuntimePointerPatches(patchedFileBytes, report);

        writeAll(targetFile, patchedFileBytes);

        report.println();
        report.println("Final verification after write:");
        report.println("-------------------------------");

        byte[] verifyBytes = readAll(targetFile);

        for (int i = 0; i < POINTER_PATCHES.length; i++) {
            PointerPatch patch = POINTER_PATCHES[i];

            int expected = RUNTIME_BASE + patch.newFileOffset;
            int current = readLe32(verifyBytes, patch.tableOffset);

            report.println(
                    patch.name
                            + " @ "
                            + hex(patch.tableOffset)
                            + " current="
                            + hex(current)
                            + " expected="
                            + hex(expected)
                            + " "
                            + (current == expected ? "OK" : "BAD")
            );
        }

        report.println();
        report.println("No old-start bridge bytes were applied.");
        report.println("No MIPS immediate patches were applied.");
        report.println("Runtime textbox pointer table was patched.");
        report.println();

        String cdName = TARGET_FILE.substring(0, TARGET_FILE.indexOf('/'));

        report.println("Rebuilding " + cdName + ".CD...");
        report.flush();

        System.out.println();
        System.out.println("Real +50 balanced expansion and runtime pointer patch complete.");
        System.out.println();
        System.out.println("Rebuilding " + cdName + ".CD...");

        CdRebuilder.rebuildOne(splitdir, Conf.outdir, cdName);

        report.println("Rebuild complete.");
        report.println();
        report.println("Replace this file in CDMage:");
        report.println("  " + Conf.outdir + cdName + ".CD");
        report.println();
        report.println("Do NOT run HackEn for this test.");
        report.println("Do NOT use the old bridge diagnostic for this test.");
        report.println("Done.");
        report.close();

        System.out.println();
        System.out.println("Wrote patched split file:");
        System.out.println(targetFile.getAbsolutePath());
        System.out.println();
        System.out.println("Wrote report:");
        System.out.println(reportFile.getAbsolutePath());
        System.out.println();
        System.out.println("Replace this file in CDMage:");
        System.out.println(Conf.outdir + cdName + ".CD");
        System.out.println();
        System.out.println("Do NOT run HackEn for this test.");
    }

    private static void verifyAndApplyRuntimePointerPatches(byte[] data, PrintWriter report) {
        boolean fatalMismatch = false;

        report.println("Runtime pointer table verification before patch:");
        report.println("-----------------------------------------------");

        for (int i = 0; i < POINTER_PATCHES.length; i++) {
            PointerPatch patch = POINTER_PATCHES[i];

            int oldPtr = RUNTIME_BASE + patch.oldFileOffset;
            int newPtr = RUNTIME_BASE + patch.newFileOffset;
            int current = readLe32(data, patch.tableOffset);

            report.println(patch.name);
            report.println("  table offset:     " + hex(patch.tableOffset));
            report.println("  old file offset:  " + hex(patch.oldFileOffset));
            report.println("  new file offset:  " + hex(patch.newFileOffset));
            report.println("  expected old ptr: " + hex(oldPtr) + " bytes " + bytesToHex(le32(oldPtr)));
            report.println("  expected new ptr: " + hex(newPtr) + " bytes " + bytesToHex(le32(newPtr)));
            report.println("  current ptr:      " + hex(current) + " bytes " + bytesToHex(le32(current)));

            if (current == oldPtr) {
                if (patch.shouldPatch) {
                    report.println("  status: old value found; will patch");
                } else {
                    report.println("  status: old value found; unchanged entry");
                }
            } else if (current == newPtr) {
                if (patch.shouldPatch) {
                    report.println("  status: already patched");
                } else {
                    report.println("  status: unchanged entry already correct");
                }
            } else {
                report.println("  status: MISMATCH - not old or expected new");
                fatalMismatch = true;
            }

            report.println();
        }

        if (fatalMismatch) {
            report.flush();

            throw new RuntimeException(
                    "Aborting because at least one runtime pointer table entry did not match expected old/new values.\n"
                            + "See report on Desktop: balance-expand-plus-runtime-pointer-patch-en.txt"
            );
        }

        report.println("Applying runtime pointer patches:");
        report.println("--------------------------------");

        for (int i = 0; i < POINTER_PATCHES.length; i++) {
            PointerPatch patch = POINTER_PATCHES[i];

            int oldPtr = RUNTIME_BASE + patch.oldFileOffset;
            int newPtr = RUNTIME_BASE + patch.newFileOffset;
            int current = readLe32(data, patch.tableOffset);

            if (!patch.shouldPatch) {
                report.println(patch.name + " @ " + hex(patch.tableOffset) + " unchanged at " + hex(current));
                continue;
            }

            if (current == newPtr) {
                report.println(patch.name + " @ " + hex(patch.tableOffset) + " already patched to " + hex(newPtr));
                continue;
            }

            if (current != oldPtr) {
                report.println(patch.name + " @ " + hex(patch.tableOffset) + " skipped due to unexpected current value " + hex(current));
                continue;
            }

            writeLe32(data, patch.tableOffset, newPtr);

            report.println(
                    patch.name
                            + " @ "
                            + hex(patch.tableOffset)
                            + " patched "
                            + hex(oldPtr)
                            + " -> "
                            + hex(newPtr)
            );
        }

        report.println();
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
                    "Block is outside file: "
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

    private static void backup(File target, byte[] data, PrintWriter report) throws Exception {
        File backupDir = new File(Conf.desktop + "brmen_backups/");

        if (!backupDir.exists()) {
            backupDir.mkdirs();
        }

        String timestamp = new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date());

        File backup = new File(
                backupDir,
                TARGET_FILE.replace("/", "_").replace("\\", "_")
                        + ".before_real_plus50_runtime_pointer_patch."
                        + timestamp
                        + ".bak"
        );

        writeAll(backup, data);

        report.println("Backup written:");
        report.println("  " + backup.getAbsolutePath());
        report.println();
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

    private static int readLe32(byte[] data, int offset) {
        int b0 = data[offset] & 0xFF;
        int b1 = data[offset + 1] & 0xFF;
        int b2 = data[offset + 2] & 0xFF;
        int b3 = data[offset + 3] & 0xFF;

        return b0 | (b1 << 8) | (b2 << 16) | (b3 << 24);
    }

    private static void writeLe32(byte[] data, int offset, int value) {
        data[offset] = (byte) (value & 0xFF);
        data[offset + 1] = (byte) ((value >> 8) & 0xFF);
        data[offset + 2] = (byte) ((value >> 16) & 0xFF);
        data[offset + 3] = (byte) ((value >> 24) & 0xFF);
    }

    private static byte[] le32(int value) {
        byte[] ret = new byte[4];

        ret[0] = (byte) (value & 0xFF);
        ret[1] = (byte) ((value >> 8) & 0xFF);
        ret[2] = (byte) ((value >> 16) & 0xFF);
        ret[3] = (byte) ((value >> 24) & 0xFF);

        return ret;
    }

    private static String bytesToHex(byte[] data) {
        StringBuilder sb = new StringBuilder();

        for (int i = 0; i < data.length; i++) {
            if (i > 0) {
                sb.append(' ');
            }

            sb.append(byteHex(data[i]));
        }

        return sb.toString();
    }

    private static String byteHex(byte b) {
        int value = b & 0xFF;
        String s = Integer.toHexString(value).toUpperCase();

        if (s.length() < 2) {
            return "0" + s;
        }

        return s;
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

    private static class PointerPatch {
        String name;
        int tableOffset;
        int oldFileOffset;
        int newFileOffset;
        boolean shouldPatch;

        PointerPatch(String name, int tableOffset, int oldFileOffset, int newFileOffset, boolean shouldPatch) {
            this.name = name;
            this.tableOffset = tableOffset;
            this.oldFileOffset = oldFileOffset;
            this.newFileOffset = newFileOffset;
            this.shouldPatch = shouldPatch;
        }
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