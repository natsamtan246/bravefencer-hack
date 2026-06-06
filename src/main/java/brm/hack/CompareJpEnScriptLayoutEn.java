package brm.hack;

import java.io.File;
import java.io.FileInputStream;
import java.io.PrintWriter;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;

import brm.Conf;

public class CompareJpEnScriptLayoutEn {

    private static final String EN_XLSX = "brm-en.xlsx";
    private static final String JP_XLSX = "brm-jp.xlsx";

    private static final String TARGET_FILE = "SC01/006/0.4";

    public static void main(String[] args) throws Exception {
        File enExcel = new File(Conf.desktop + EN_XLSX);
        File jpExcel = new File(Conf.desktop + JP_XLSX);

        File report = new File(Conf.desktop + "jp-en-script-layout-compare.txt");

        PrintWriter out = new PrintWriter(report, "UTF-8");

        out.println("Brave Fencer Musashi JP/EN Script Layout Compare");
        out.println("================================================");
        out.println();
        out.println("This report does not modify anything.");
        out.println();
        out.println("Purpose:");
        out.println("  Check whether the English script kept the same fixed script-block");
        out.println("  layout as the Japanese script.");
        out.println();
        out.println("If JP and EN addresses/lengths match, that suggests the English team");
        out.println("translated inside fixed slots rather than freely moving text.");
        out.println();

        out.println("INPUTS");
        out.println("------");
        out.println("EN workbook: " + enExcel.getAbsolutePath() + " exists=" + enExcel.exists());
        out.println("JP workbook: " + jpExcel.getAbsolutePath() + " exists=" + jpExcel.exists());
        out.println();

        if (!enExcel.exists()) {
            out.close();
            throw new RuntimeException("Missing EN workbook: " + enExcel.getAbsolutePath());
        }

        if (!jpExcel.exists()) {
            out.close();
            throw new RuntimeException("Missing JP workbook: " + jpExcel.getAbsolutePath());
        }

        ScriptData en = readScriptsWorkbook(enExcel, "EN", out);
        ScriptData jp = readScriptsWorkbook(jpExcel, "JP", out);

        printGlobalSummary(out, en, jp);
        printTargetFileCompare(out, en, jp, TARGET_FILE);
        printCommonFileLayoutSummary(out, en, jp);
        printAddressKeyCompare(out, en, jp);
        printOptionalBinaryCompare(out);

        out.close();

        System.out.println("JP/EN script layout compare written to:");
        System.out.println(report.getAbsolutePath());
    }

    private static ScriptData readScriptsWorkbook(File excel, String label, PrintWriter out) throws Exception {
        FileInputStream in = new FileInputStream(excel);
        Workbook wb = WorkbookFactory.create(in);

        Sheet scripts = wb.getSheet("SCRIPTS");

        if (scripts == null) {
            out.println(label + " workbook sheets:");
            for (int i = 0; i < wb.getNumberOfSheets(); i++) {
                out.println("  " + wb.getSheetName(i));
            }
            out.println();

            in.close();

            throw new RuntimeException(
                    label + " workbook has no SCRIPTS sheet: " + excel.getAbsolutePath()
            );
        }

        ScriptData data = new ScriptData();
        data.label = label;
        data.excel = excel;
        data.blocks = readScriptBlocks(scripts);

        for (Block block : data.blocks) {
            data.byKey.put(block.key(), block);

            List<Block> list = data.byFile.get(block.fileName);
            if (list == null) {
                list = new ArrayList<Block>();
                data.byFile.put(block.fileName, list);
            }
            list.add(block);
        }

        for (List<Block> list : data.byFile.values()) {
            Collections.sort(list, new Comparator<Block>() {
                @Override
                public int compare(Block a, Block b) {
                    if (a.address != b.address) {
                        return a.address - b.address;
                    }

                    return a.startExcelRow - b.startExcelRow;
                }
            });
        }

        in.close();

        return data;
    }

    private static List<Block> readScriptBlocks(Sheet sheet) {
        List<Block> blocks = new ArrayList<Block>();

        Block current = null;

        for (int r = 1; r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);

            String fileName = getCellText(row, 0).trim();
            String address = getCellText(row, 1).trim();
            String lenText = getCellText(row, 2).trim();
            String ctrl = getCellText(row, 3);
            String original = getCellText(row, 4);

            if (!fileName.isEmpty() && !address.isEmpty()) {
                finishBlock(current, blocks);

                current = new Block();
                current.fileName = normalizeFileName(fileName);
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
                current.lenText = lenText;
            }

            if (!ctrl.isEmpty() || !original.isEmpty()) {
                if (current.textSample.length() < 300) {
                    current.textSample.append(ctrl);
                    current.textSample.append(original);
                }
            }
        }

        finishBlock(current, blocks);

        return blocks;
    }

    private static void finishBlock(Block block, List<Block> blocks) {
        if (block == null) {
            return;
        }

        if (block.originalLen < 0) {
            return;
        }

        blocks.add(block);
    }

    private static void printGlobalSummary(PrintWriter out, ScriptData en, ScriptData jp) {
        out.println("GLOBAL SUMMARY");
        out.println("--------------");
        out.println("EN script blocks: " + en.blocks.size());
        out.println("JP script blocks: " + jp.blocks.size());
        out.println("EN script files:  " + en.byFile.size());
        out.println("JP script files:  " + jp.byFile.size());
        out.println();

        int commonFiles = 0;
        int enOnlyFiles = 0;
        int jpOnlyFiles = 0;

        for (String file : en.byFile.keySet()) {
            if (jp.byFile.containsKey(file)) {
                commonFiles++;
            } else {
                enOnlyFiles++;
            }
        }

        for (String file : jp.byFile.keySet()) {
            if (!en.byFile.containsKey(file)) {
                jpOnlyFiles++;
            }
        }

        out.println("Common script files: " + commonFiles);
        out.println("EN-only script files: " + enOnlyFiles);
        out.println("JP-only script files: " + jpOnlyFiles);
        out.println();

        int commonKeys = 0;
        int sameLen = 0;
        int diffLen = 0;
        int enOnlyKeys = 0;

        for (Map.Entry<String, Block> entry : en.byKey.entrySet()) {
            Block jpBlock = jp.byKey.get(entry.getKey());

            if (jpBlock == null) {
                enOnlyKeys++;
                continue;
            }

            commonKeys++;

            if (entry.getValue().originalLen == jpBlock.originalLen) {
                sameLen++;
            } else {
                diffLen++;
            }
        }

        int jpOnlyKeys = 0;

        for (String key : jp.byKey.keySet()) {
            if (!en.byKey.containsKey(key)) {
                jpOnlyKeys++;
            }
        }

        out.println("Common file+address blocks: " + commonKeys);
        out.println("Common blocks with SAME length: " + sameLen);
        out.println("Common blocks with DIFFERENT length: " + diffLen);
        out.println("EN-only file+address blocks: " + enOnlyKeys);
        out.println("JP-only file+address blocks: " + jpOnlyKeys);
        out.println();

        if (commonKeys > 0) {
            double samePercent = (sameLen * 100.0) / commonKeys;
            out.println("Same-length percent among common file+address blocks: "
                    + String.format("%.2f", samePercent) + "%");
        }

        out.println();
    }

    private static void printTargetFileCompare(
            PrintWriter out,
            ScriptData en,
            ScriptData jp,
            String targetFile
    ) {
        out.println("TARGET FILE DETAILED COMPARE");
        out.println("----------------------------");
        out.println("Target: " + targetFile);
        out.println();

        List<Block> enList = en.byFile.get(targetFile);
        List<Block> jpList = jp.byFile.get(targetFile);

        if (enList == null) {
            out.println("EN does not contain target file.");
            out.println();
            return;
        }

        if (jpList == null) {
            out.println("JP does not contain target file.");
            out.println();
            out.println("This may mean JP file names differ, or the JP workbook was dumped differently.");
            out.println();
            return;
        }

        out.println("EN blocks in target: " + enList.size());
        out.println("JP blocks in target: " + jpList.size());
        out.println();

        int max = Math.max(enList.size(), jpList.size());

        out.println("Ordered block comparison:");
        out.println("Index | EN addr/len/rows | JP addr/len/rows | address same | length same | notes");
        out.println();

        int sameAddr = 0;
        int sameLenByIndex = 0;
        int sameBoth = 0;

        for (int i = 0; i < max; i++) {
            Block eb = i < enList.size() ? enList.get(i) : null;
            Block jb = i < jpList.size() ? jpList.get(i) : null;

            boolean addrSame = eb != null && jb != null && eb.address == jb.address;
            boolean lenSame = eb != null && jb != null && eb.originalLen == jb.originalLen;

            if (addrSame) {
                sameAddr++;
            }

            if (lenSame) {
                sameLenByIndex++;
            }

            if (addrSame && lenSame) {
                sameBoth++;
            }

            out.print(padLeft(String.valueOf(i), 5));
            out.print(" | ");

            if (eb == null) {
                out.print("EN missing");
            } else {
                out.print(hex6(eb.address));
                out.print(" len=");
                out.print(hex4(eb.originalLen));
                out.print("/");
                out.print(eb.originalLen);
                out.print(" rows=");
                out.print(eb.startExcelRow);
                out.print("-");
                out.print(eb.endExcelRow);
            }

            out.print(" | ");

            if (jb == null) {
                out.print("JP missing");
            } else {
                out.print(hex6(jb.address));
                out.print(" len=");
                out.print(hex4(jb.originalLen));
                out.print("/");
                out.print(jb.originalLen);
                out.print(" rows=");
                out.print(jb.startExcelRow);
                out.print("-");
                out.print(jb.endExcelRow);
            }

            out.print(" | ");
            out.print(addrSame ? "YES" : "NO ");

            out.print(" | ");
            out.print(lenSame ? "YES" : "NO ");

            out.print(" | ");

            if (eb != null && jb != null) {
                if (addrSame && lenSame) {
                    out.print("same slot");
                } else if (addrSame) {
                    out.print("same start, different length");
                } else if (lenSame) {
                    out.print("different start, same length");
                } else {
                    out.print("different");
                }
            }

            out.println();
        }

        out.println();
        out.println("Target summary:");
        out.println("Same address by ordered index: " + sameAddr + " / " + max);
        out.println("Same length by ordered index:  " + sameLenByIndex + " / " + max);
        out.println("Same address AND length:       " + sameBoth + " / " + max);
        out.println();

        out.println("Target text samples around current experiment area:");
        out.println();

        printBlocksNearAddress(out, "EN", enList, 0x60890, 18);
        out.println();
        printBlocksNearAddress(out, "JP", jpList, 0x60890, 18);

        out.println();
    }

    private static void printBlocksNearAddress(
            PrintWriter out,
            String label,
            List<Block> list,
            int address,
            int count
    ) {
        out.println(label + " blocks near " + hex6(address) + ":");

        int bestIndex = 0;
        int bestDistance = Integer.MAX_VALUE;

        for (int i = 0; i < list.size(); i++) {
            int distance = Math.abs(list.get(i).address - address);

            if (distance < bestDistance) {
                bestDistance = distance;
                bestIndex = i;
            }
        }

        int start = bestIndex - count / 2;

        if (start < 0) {
            start = 0;
        }

        int end = start + count;

        if (end > list.size()) {
            end = list.size();
        }

        for (int i = start; i < end; i++) {
            Block b = list.get(i);

            out.println(
                    "  #"
                            + i
                            + " "
                            + hex6(b.address)
                            + " len="
                            + hex4(b.originalLen)
                            + "/"
                            + b.originalLen
                            + " rows="
                            + b.startExcelRow
                            + "-"
                            + b.endExcelRow
                            + " text=\""
                            + cleanSample(b.textSample.toString(), 90)
                            + "\""
            );
        }
    }

    private static void printCommonFileLayoutSummary(PrintWriter out, ScriptData en, ScriptData jp) {
        out.println("COMMON FILE ORDERED-LAYOUT SUMMARY");
        out.println("----------------------------------");
        out.println("This checks each common file by ordered block index.");
        out.println();

        List<String> commonFiles = new ArrayList<String>();

        for (String file : en.byFile.keySet()) {
            if (jp.byFile.containsKey(file)) {
                commonFiles.add(file);
            }
        }

        Collections.sort(commonFiles);

        int identicalLayoutFiles = 0;
        int sameAddressFiles = 0;
        int checkedFiles = 0;

        List<FileSummary> summaries = new ArrayList<FileSummary>();

        for (String file : commonFiles) {
            List<Block> enList = en.byFile.get(file);
            List<Block> jpList = jp.byFile.get(file);

            FileSummary summary = compareFileOrdered(file, enList, jpList);
            summaries.add(summary);

            checkedFiles++;

            if (summary.sameCount && summary.sameAddressCount == summary.maxCount) {
                sameAddressFiles++;
            }

            if (summary.sameCount && summary.sameAddressCount == summary.maxCount
                    && summary.sameLengthCount == summary.maxCount) {
                identicalLayoutFiles++;
            }
        }

        out.println("Common files checked: " + checkedFiles);
        out.println("Files with identical ordered addresses: " + sameAddressFiles);
        out.println("Files with identical ordered addresses AND lengths: " + identicalLayoutFiles);
        out.println();

        Collections.sort(summaries, new Comparator<FileSummary>() {
            @Override
            public int compare(FileSummary a, FileSummary b) {
                int scoreDiff = b.score() - a.score();

                if (scoreDiff != 0) {
                    return scoreDiff;
                }

                return a.fileName.compareTo(b.fileName);
            }
        });

        out.println("Top same-layout candidates:");
        out.println("File | EN count | JP count | same addr | same len | same both");
        out.println();

        int printed = 0;

        for (FileSummary s : summaries) {
            if (printed >= 80) {
                out.println("Stopped after 80 files.");
                break;
            }

            out.println(
                    s.fileName
                            + " | "
                            + s.enCount
                            + " | "
                            + s.jpCount
                            + " | "
                            + s.sameAddressCount
                            + "/"
                            + s.maxCount
                            + " | "
                            + s.sameLengthCount
                            + "/"
                            + s.maxCount
                            + " | "
                            + s.sameBothCount
                            + "/"
                            + s.maxCount
            );

            printed++;
        }

        out.println();
    }

    private static FileSummary compareFileOrdered(
            String file,
            List<Block> enList,
            List<Block> jpList
    ) {
        FileSummary s = new FileSummary();
        s.fileName = file;
        s.enCount = enList.size();
        s.jpCount = jpList.size();
        s.maxCount = Math.max(enList.size(), jpList.size());
        s.sameCount = enList.size() == jpList.size();

        for (int i = 0; i < s.maxCount; i++) {
            Block eb = i < enList.size() ? enList.get(i) : null;
            Block jb = i < jpList.size() ? jpList.get(i) : null;

            if (eb == null || jb == null) {
                continue;
            }

            boolean addrSame = eb.address == jb.address;
            boolean lenSame = eb.originalLen == jb.originalLen;

            if (addrSame) {
                s.sameAddressCount++;
            }

            if (lenSame) {
                s.sameLengthCount++;
            }

            if (addrSame && lenSame) {
                s.sameBothCount++;
            }
        }

        return s;
    }

    private static void printAddressKeyCompare(PrintWriter out, ScriptData en, ScriptData jp) {
        out.println("DIFFERENT-LENGTH COMMON ADDRESS EXAMPLES");
        out.println("----------------------------------------");
        out.println("These are blocks with the same file+address but different slot length.");
        out.println();

        int printed = 0;

        List<String> keys = new ArrayList<String>(en.byKey.keySet());
        Collections.sort(keys);

        for (String key : keys) {
            Block eb = en.byKey.get(key);
            Block jb = jp.byKey.get(key);

            if (jb == null) {
                continue;
            }

            if (eb.originalLen == jb.originalLen) {
                continue;
            }

            out.println(
                    key
                            + " EN len="
                            + hex4(eb.originalLen)
                            + "/"
                            + eb.originalLen
                            + " JP len="
                            + hex4(jb.originalLen)
                            + "/"
                            + jb.originalLen
                            + " EN rows="
                            + eb.startExcelRow
                            + "-"
                            + eb.endExcelRow
                            + " JP rows="
                            + jb.startExcelRow
                            + "-"
                            + jb.endExcelRow
            );

            printed++;

            if (printed >= 120) {
                out.println("Stopped after 120 examples.");
                break;
            }
        }

        if (printed == 0) {
            out.println("No different-length common-address examples found.");
        }

        out.println();
    }

    private static void printOptionalBinaryCompare(PrintWriter out) throws Exception {
        out.println("OPTIONAL SPLIT-FILE BINARY CHECK");
        out.println("--------------------------------");
        out.println("This section only runs if both split folders exist.");
        out.println();

        File enDir = new File(Conf.desktop + "brmen/");
        File jpDir = new File(Conf.desktop + "brmjp/");

        out.println("EN split dir: " + enDir.getAbsolutePath() + " exists=" + enDir.exists());
        out.println("JP split dir: " + jpDir.getAbsolutePath() + " exists=" + jpDir.exists());
        out.println();

        if (!enDir.exists() || !jpDir.exists()) {
            out.println("Skipping binary compare because one split dir is missing.");
            out.println("Expected JP split folder name: " + jpDir.getAbsolutePath());
            out.println();
            return;
        }

        File enTarget = new File(enDir, TARGET_FILE);
        File jpTarget = new File(jpDir, TARGET_FILE);

        out.println("EN target split file: " + enTarget.getAbsolutePath() + " exists=" + enTarget.exists());
        out.println("JP target split file: " + jpTarget.getAbsolutePath() + " exists=" + jpTarget.exists());
        out.println();

        if (!enTarget.exists() || !jpTarget.exists()) {
            out.println("Skipping target binary compare because target file is missing.");
            out.println();
            return;
        }

        byte[] enBytes = readAll(enTarget);
        byte[] jpBytes = readAll(jpTarget);

        out.println("EN target size: " + enBytes.length);
        out.println("JP target size: " + jpBytes.length);
        out.println("EN SHA-1: " + sha1(enBytes));
        out.println("JP SHA-1: " + sha1(jpBytes));
        out.println();

        int min = Math.min(enBytes.length, jpBytes.length);
        int same = 0;

        for (int i = 0; i < min; i++) {
            if (enBytes[i] == jpBytes[i]) {
                same++;
            }
        }

        out.println("Same bytes over min size: " + same + " / " + min);

        if (min > 0) {
            out.println("Same-byte percent over min size: " + String.format("%.2f", same * 100.0 / min) + "%");
        }

        out.println();
    }

    private static String normalizeFileName(String s) {
        return s.trim().replace("\\", "/");
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

    private static String sha1(byte[] data) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-1");
        byte[] hash = digest.digest(data);

        StringBuilder sb = new StringBuilder();

        for (byte b : hash) {
            sb.append(String.format("%02x", b & 0xFF));
        }

        return sb.toString();
    }

    private static String cleanSample(String s, int maxLen) {
        String cleaned = s.replace("\r", " ")
                .replace("\n", " ")
                .replace("\t", " ");

        while (cleaned.contains("  ")) {
            cleaned = cleaned.replace("  ", " ");
        }

        if (cleaned.length() > maxLen) {
            return cleaned.substring(0, maxLen) + "...";
        }

        return cleaned;
    }

    private static String padLeft(String s, int len) {
        String ret = s;

        while (ret.length() < len) {
            ret = " " + ret;
        }

        return ret;
    }

    private static String hex4(int value) {
        String s = Integer.toHexString(value & 0xFFFF).toUpperCase();

        while (s.length() < 4) {
            s = "0" + s;
        }

        return "0x" + s;
    }

    private static String hex6(int value) {
        String s = Integer.toHexString(value & 0xFFFFFF).toUpperCase();

        while (s.length() < 6) {
            s = "0" + s;
        }

        return "0x" + s;
    }

    private static class ScriptData {
        String label;
        File excel;
        List<Block> blocks;
        Map<String, Block> byKey = new LinkedHashMap<String, Block>();
        Map<String, List<Block>> byFile = new LinkedHashMap<String, List<Block>>();
    }

    private static class Block {
        String fileName;
        String addressText;
        String lenText;
        int address;
        int originalLen = -1;
        int startExcelRow;
        int endExcelRow;
        StringBuilder textSample = new StringBuilder();

        String key() {
            return fileName + "@" + hex6(address);
        }
    }

    private static class FileSummary {
        String fileName;
        int enCount;
        int jpCount;
        int maxCount;
        boolean sameCount;
        int sameAddressCount;
        int sameLengthCount;
        int sameBothCount;

        int score() {
            return sameBothCount * 10 + sameAddressCount * 5 + sameLengthCount;
        }
    }
}