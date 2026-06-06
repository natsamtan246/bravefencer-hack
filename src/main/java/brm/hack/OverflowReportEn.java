package brm.hack;

import java.io.File;
import java.io.FileInputStream;
import java.io.PrintWriter;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;

import brm.Conf;
import brm.dump.Ctrl;

public class OverflowReportEn {

    private static final int BYTES_AFTER_COUNT = 32;

    public static void main(String[] args) throws Exception {
        String splitdir = Conf.desktop + "brmen/";
        File excel = new File(Conf.desktop + "brm-en.xlsx");
        File report = new File(Conf.desktop + "text-overflow-report-en.txt");

        Encoding enc = new EncodingEn();

        List<Result> results = new ArrayList<Result>();
        List<String> errors = new ArrayList<String>();

        FileInputStream in = new FileInputStream(excel);
        Workbook wb = WorkbookFactory.create(in);

        scanMain(wb.getSheet("MAIN"), enc, results, errors);
        scanScripts(wb.getSheet("SCRIPTS"), enc, results, errors);

        in.close();

        writeReport(report, splitdir, results, errors);

        System.out.println("Overflow research report written to:");
        System.out.println(report.getAbsolutePath());
    }

    private static void scanMain(
            Sheet sheet,
            Encoding enc,
            List<Result> results,
            List<String> errors
    ) {
        if (sheet == null) {
            errors.add("MAIN sheet not found.");
            return;
        }

        Block block = null;

        for (int r = 1; r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);

            String address = getCellText(row, 1).trim(); // B
            String lenText = getCellText(row, 2).trim(); // C
            String ctrl = getCellText(row, 3);           // D
            String original = getCellText(row, 4);       // E
            String edit = getCellText(row, 5);           // F

            if (!address.isEmpty()) {
                finishBlock(block, enc, results, errors);
                block = new Block();
                block.sheetName = "MAIN";
                block.fileName = "";
                block.addressText = address;
                block.startExcelRow = r + 1;
            }

            if (block == null) {
                continue;
            }

            block.endExcelRow = r + 1;

            if (!lenText.isEmpty()) {
                block.originalLen = parseFlexibleInt(lenText);
            }

            block.text.append(ctrl);

            if (!edit.isEmpty()) {
                block.text.append(edit);
                block.hasEdit = true;
            } else {
                block.text.append(original);
            }
        }

        finishBlock(block, enc, results, errors);
    }

    private static void scanScripts(
            Sheet sheet,
            Encoding enc,
            List<Result> results,
            List<String> errors
    ) {
        if (sheet == null) {
            errors.add("SCRIPTS sheet not found.");
            return;
        }

        Block block = null;

        for (int r = 1; r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);

            String fileName = getCellText(row, 0).trim(); // A
            String address = getCellText(row, 1).trim();  // B
            String lenText = getCellText(row, 2).trim();  // C
            String ctrl = getCellText(row, 3);            // D
            String original = getCellText(row, 4);        // E
            String edit = getCellText(row, 5);            // F

            if (!fileName.isEmpty() && !address.isEmpty()) {
                finishBlock(block, enc, results, errors);
                block = new Block();
                block.sheetName = "SCRIPTS";
                block.fileName = fileName;
                block.addressText = address;
                block.startExcelRow = r + 1;
            }

            if (block == null) {
                continue;
            }

            block.endExcelRow = r + 1;

            if (!lenText.isEmpty()) {
                block.originalLen = parseFlexibleInt(lenText);
            }

            block.text.append(ctrl);

            if (!edit.isEmpty()) {
                block.text.append(edit);
                block.hasEdit = true;
            } else {
                block.text.append(original);
            }
        }

        finishBlock(block, enc, results, errors);
    }

    private static void finishBlock(
            Block block,
            Encoding enc,
            List<Result> results,
            List<String> errors
    ) {
        if (block == null) {
            return;
        }

        /*
         * Match current importer behavior:
         * only report sentence blocks that have at least one nonblank F cell.
         */
        if (!block.hasEdit) {
            return;
        }

        if (block.originalLen < 0) {
            errors.add(block.location() + " has edit but no original length in column C.");
            return;
        }

        Result result = new Result();
        result.sheetName = block.sheetName;
        result.fileName = block.fileName;
        result.addressText = block.addressText;
        result.startExcelRow = block.startExcelRow;
        result.endExcelRow = block.endExcelRow;
        result.originalLen = block.originalLen;
        result.preview = preview(block.text.toString());

        try {
            result.newLen = encodedLength(block.text.toString(), enc);
            result.delta = result.newLen - result.originalLen;
        } catch (Exception ex) {
            result.encodeError = ex.getMessage();
        }

        results.add(result);
    }

    private static int encodedLength(String text, Encoding enc) {
        int total = 0;
        int i = 0;

        while (i < text.length()) {
            char ch = text.charAt(i);

            if (ch == '[') {
                int close = text.indexOf(']', i);

                if (close >= 0) {
                    String token = text.substring(i, close + 1);
                    total += Ctrl.encode(token).length;
                    i = close + 1;
                    continue;
                }
            }

            int codePoint = text.codePointAt(i);
            String oneChar = new String(Character.toChars(codePoint));

            total += enc.getCode(oneChar).length;
            i += Character.charCount(codePoint);
        }

        /*
         * SentenceSerializer appends the 00 terminator.
         */
        return total + 1;
    }

    private static void writeReport(
            File report,
            String splitdir,
            List<Result> results,
            List<String> errors
    ) throws Exception {
        int editedCount = 0;
        int overflowCount = 0;
        int closeCallCount = 0;
        int encodeErrorCount = 0;

        for (Result r : results) {
            editedCount++;

            if (r.encodeError != null) {
                encodeErrorCount++;
            } else if (r.delta > 0) {
                overflowCount++;
            } else if (r.originalLen - r.newLen <= 8) {
                closeCallCount++;
            }
        }

        PrintWriter out = new PrintWriter(report, "UTF-8");

        out.println("Brave Fencer Musashi English Text Overflow Research Report");
        out.println("==========================================================");
        out.println();
        out.println("This report does not modify brmen, the Excel file, or rebuilt CD files.");
        out.println();
        out.println("Edited sentence blocks checked: " + editedCount);
        out.println("Overflows found:              " + overflowCount);
        out.println("Close calls, <= 8 bytes free: " + closeCallCount);
        out.println("Encoding errors:              " + encodeErrorCount);
        out.println("Other errors:                 " + errors.size());
        out.println();

        if (!errors.isEmpty()) {
            out.println("OTHER ERRORS");
            out.println("------------");

            for (String error : errors) {
                out.println(error);
            }

            out.println();
        }

        if (encodeErrorCount > 0) {
            out.println("ENCODING ERRORS");
            out.println("---------------");

            for (Result r : results) {
                if (r.encodeError != null) {
                    printResultHeader(out, r);
                    out.println("Encode error: " + r.encodeError);
                    out.println("Preview: " + r.preview);
                    out.println();
                }
            }

            out.println();
        }

        if (overflowCount > 0) {
            out.println("OVERFLOWS");
            out.println("---------");

            for (Result r : results) {
                if (r.encodeError == null && r.delta > 0) {
                    printResultHeader(out, r);
                    out.println("Original length: " + r.originalLen);
                    out.println("New length:      " + r.newLen);
                    out.println("Overflow:        +" + r.delta + " bytes");
                    out.println("Bytes after original block: " + readBytesAfter(splitdir, r));
                    out.println("Preview: " + r.preview);
                    out.println();
                }
            }

            out.println();
        }

        if (closeCallCount > 0) {
            out.println("CLOSE CALLS");
            out.println("-----------");
            out.println("These fit, but have 8 bytes or less remaining.");
            out.println();

            for (Result r : results) {
                if (r.encodeError == null && r.delta <= 0 && r.originalLen - r.newLen <= 8) {
                    printResultHeader(out, r);
                    out.println("Original length: " + r.originalLen);
                    out.println("New length:      " + r.newLen);
                    out.println("Bytes free:      " + (r.originalLen - r.newLen));
                    out.println("Preview: " + r.preview);
                    out.println();
                }
            }

            out.println();
        }

        out.println("NOTES");
        out.println("-----");
        out.println("- New length includes the 00 sentence terminator.");
        out.println("- This report only checks blocks with at least one nonblank column F cell.");
        out.println("- This matches the current English importer behavior.");
        out.println("- MAIN bytes-after lookup is not available in this standalone report yet.");
        out.println("- SCRIPTS bytes-after lookup uses the script file path from column A.");
        out.println();

        out.close();
    }

    private static void printResultHeader(PrintWriter out, Result r) {
        out.println(r.sheetName);

        if (r.fileName != null && !r.fileName.isEmpty()) {
            out.println("File: " + r.fileName);
        }

        out.println("Address: " + r.addressText);
        out.println("Excel rows: " + r.startExcelRow + "-" + r.endExcelRow);
    }

    private static String readBytesAfter(String splitdir, Result r) {
        if (r.fileName == null || r.fileName.isEmpty()) {
            return "(not available for MAIN yet)";
        }

        try {
            int address = Integer.parseInt(r.addressText.replace("0x", "").replace("0X", ""), 16);
            File file = new File(splitdir, r.fileName.replace("\\", "/"));

            if (!file.exists()) {
                return "(split file not found: " + file.getAbsolutePath() + ")";
            }

            long pos = (long) address + (long) r.originalLen;

            if (pos < 0 || pos >= file.length()) {
                return "(address + length is outside file)";
            }

            RandomAccessFile raf = new RandomAccessFile(file, "r");
            raf.seek(pos);

            int count = (int) Math.min(BYTES_AFTER_COUNT, file.length() - pos);
            byte[] buf = new byte[count];

            raf.readFully(buf);
            raf.close();

            StringBuilder sb = new StringBuilder();

            for (int i = 0; i < buf.length; i++) {
                if (i > 0) {
                    sb.append(' ');
                }

                sb.append(String.format("%02X", buf[i]));
            }

            return sb.toString();
        } catch (Exception ex) {
            return "(could not read bytes after block: " + ex.getMessage() + ")";
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

    private static int parseFlexibleInt(String text) {
        String s = text.trim();

        if (s.startsWith("0x") || s.startsWith("0X")) {
            return Integer.parseInt(s.substring(2), 16);
        }

        /*
         * Length column is normally decimal.
         */
        return Integer.parseInt(s);
    }

    private static String preview(String text) {
        String s = text.replace("\r", "\\r").replace("\n", "\\n");

        if (s.length() <= 160) {
            return s;
        }

        return s.substring(0, 160) + "...";
    }

    private static class Block {
        String sheetName;
        String fileName;
        String addressText;
        int startExcelRow;
        int endExcelRow;
        int originalLen = -1;
        boolean hasEdit = false;
        StringBuilder text = new StringBuilder();

        String location() {
            StringBuilder sb = new StringBuilder();
            sb.append(sheetName);

            if (fileName != null && !fileName.isEmpty()) {
                sb.append(" ").append(fileName);
            }

            sb.append(" address ").append(addressText);
            sb.append(" rows ").append(startExcelRow).append("-").append(endExcelRow);

            return sb.toString();
        }
    }

    private static class Result {
        String sheetName;
        String fileName;
        String addressText;
        int startExcelRow;
        int endExcelRow;
        int originalLen;
        int newLen;
        int delta;
        String preview;
        String encodeError;
    }
}