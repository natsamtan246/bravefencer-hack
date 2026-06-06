package brm.hack;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;

import brm.Conf;
import brm.dump.Ctrl;

public class ExpandOneScriptBlockEn {

    /*
     * One-off expansion test target from the overflow report.
     *
     * SCRIPTS
     * File: SC01/006/0.4
     * Address: 60890
     * Original length: 80
     */
    private static final String TARGET_FILE = "SC01/006/0.4";
    private static final String TARGET_ADDRESS = "60890";

    public static void main(String[] args) throws Exception {
        String splitdir = Conf.desktop + "brmen/";
        File excel = new File(Conf.desktop + "brm-en.xlsx");

        Encoding enc = new EncodingEn();

        TargetBlock block = findTargetBlock(excel);

        if (block == null) {
            throw new RuntimeException(
                    "Could not find target block in SCRIPTS sheet: "
                            + TARGET_FILE + " @ " + TARGET_ADDRESS
            );
        }

        if (!block.hasEdit) {
            throw new RuntimeException(
                    "Target block found, but column F has no edit. Add/edit column F first."
            );
        }

        if (block.originalLen < 0) {
            throw new RuntimeException(
                    "Target block found, but original length column C was not found."
            );
        }

        byte[] newBytes = serialize(block.text.toString(), enc);

        int address = parseHexAddress(block.addressText);
        int originalLen = block.originalLen;
        int newLen = newBytes.length;
        int delta = newLen - originalLen;

        File targetFile = new File(splitdir, TARGET_FILE.replace("\\", "/"));

        if (!targetFile.exists()) {
            throw new RuntimeException("Split file not found: " + targetFile.getAbsolutePath());
        }

        byte[] originalFileBytes = readAll(targetFile);

        if (address < 0 || address + originalLen > originalFileBytes.length) {
            throw new RuntimeException(
                    "Target address/length is outside file. Address="
                            + hex(address) + " len=" + originalLen
                            + " fileSize=" + originalFileBytes.length
            );
        }

        File backup = new File(targetFile.getAbsolutePath() + ".before_expand_test.bak");

        if (!backup.exists()) {
            writeAll(backup, originalFileBytes);
            System.out.println("Backup written:");
            System.out.println(backup.getAbsolutePath());
        } else {
            System.out.println("Backup already exists, leaving it alone:");
            System.out.println(backup.getAbsolutePath());
        }

        byte[] patchedFileBytes;

        if (delta <= 0) {
            /*
             * Same-size or shorter edit: normal safe replacement.
             */
            patchedFileBytes = new byte[originalFileBytes.length];

            System.arraycopy(originalFileBytes, 0, patchedFileBytes, 0, originalFileBytes.length);
            System.arraycopy(newBytes, 0, patchedFileBytes, address, newBytes.length);

            /*
             * Pad remaining original sentence area with 00.
             */
            for (int i = address + newBytes.length; i < address + originalLen; i++) {
                patchedFileBytes[i] = 0x00;
            }
        } else {
            /*
             * Expansion test:
             *
             * Before:
             * [before][old sentence area][rest of script]
             *
             * After:
             * [before][new longer sentence][rest of script shifted forward]
             */
            patchedFileBytes = new byte[originalFileBytes.length + delta];

            System.arraycopy(
                    originalFileBytes,
                    0,
                    patchedFileBytes,
                    0,
                    address
            );

            System.arraycopy(
                    newBytes,
                    0,
                    patchedFileBytes,
                    address,
                    newBytes.length
            );

            System.arraycopy(
                    originalFileBytes,
                    address + originalLen,
                    patchedFileBytes,
                    address + newBytes.length,
                    originalFileBytes.length - (address + originalLen)
            );
        }

        writeAll(targetFile, patchedFileBytes);

        System.out.println();
        System.out.println("Expanded one script block.");
        System.out.println("File:          " + TARGET_FILE);
        System.out.println("Address:       " + block.addressText);
        System.out.println("Original len:  " + originalLen);
        System.out.println("New len:       " + newLen);
        System.out.println("Delta:         " + signed(delta));
        System.out.println("Old file size: " + originalFileBytes.length);
        System.out.println("New file size: " + patchedFileBytes.length);

        String cdName = TARGET_FILE.substring(0, TARGET_FILE.indexOf('/'));

        System.out.println();
        System.out.println("Rebuilding " + cdName + ".CD...");
        CdRebuilder.rebuildOne(splitdir, Conf.outdir, cdName);

        System.out.println();
        System.out.println("Expansion test rebuild complete.");
        System.out.println("Replace this file in CDMage:");
        System.out.println(Conf.outdir + cdName + ".CD");
        System.out.println();
        System.out.println("Do NOT run HackEn for this test. HackEn will still reject the overflow.");
    }

    private static TargetBlock findTargetBlock(File excel) throws Exception {
        FileInputStream in = new FileInputStream(excel);
        Workbook wb = WorkbookFactory.create(in);
        Sheet sheet = wb.getSheet("SCRIPTS");

        if (sheet == null) {
            in.close();
            throw new RuntimeException("SCRIPTS sheet not found.");
        }

        TargetBlock current = null;

        for (int r = 1; r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);

            String fileName = getCellText(row, 0).trim(); // A
            String address = getCellText(row, 1).trim();  // B
            String lenText = getCellText(row, 2).trim();  // C
            String ctrl = getCellText(row, 3);            // D
            String original = getCellText(row, 4);        // E
            String edit = getCellText(row, 5);            // F

            if (!fileName.isEmpty() && !address.isEmpty()) {
                if (current != null && current.matchesTarget()) {
                    in.close();
                    return current;
                }

                current = new TargetBlock();
                current.fileName = fileName.replace("\\", "/");
                current.addressText = address;
            }

            if (current == null) {
                continue;
            }

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

        if (current != null && current.matchesTarget()) {
            in.close();
            return current;
        }

        in.close();
        return null;
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

        /*
         * End marker.
         */
        out.add(new byte[] {0x00});

        return out.toByteArray();
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

        /*
         * Address cells like 60890 are hex addresses, not decimal.
         */
        return Integer.parseInt(s, 16);
    }

    private static int parseDecimalOrHexLength(String text) {
        String s = text.trim();

        if (s.startsWith("0x") || s.startsWith("0X")) {
            return Integer.parseInt(s.substring(2), 16);
        }

        /*
         * Length column is normally decimal.
         */
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

    private static class TargetBlock {
        String fileName;
        String addressText;
        int originalLen = -1;
        boolean hasEdit = false;
        StringBuilder text = new StringBuilder();

        boolean matchesTarget() {
            return TARGET_FILE.equalsIgnoreCase(fileName)
                    && TARGET_ADDRESS.equalsIgnoreCase(addressText);
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