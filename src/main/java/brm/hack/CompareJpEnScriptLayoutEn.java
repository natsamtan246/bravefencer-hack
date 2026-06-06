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
    private static final int TARGET_ADDRESS = 0x60890;

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
        out.println("  Compare what can be compared between the EN and JP workbooks.");
        out.println();
        out.println("Important:");
        out.println("  The EN workbook usually has file/address/length columns.");
        out.println("  The JP workbook may only have sentence index/control/text columns.");
        out.println("  If JP has no address/length columns, this report cannot prove JP slot");
        out.println("  addresses directly from the spreadsheet alone.");
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

        WorkbookData en = readWorkbook(enExcel, "EN", out);
        WorkbookData jp = readWorkbook(jpExcel, "JP", out);

        printDetectedLayouts(out, en, jp);
        printEnAddressSummary(out, en);
        printJpIndexSummary(out, jp);

        if (en.addressBlocks.size() > 0 && jp.addressBlocks.size() > 0) {
            printAddressLayoutCompare(out, en, jp);
            printTargetAddressCompare(out, en, jp, TARGET_FILE);
        } else {
            out.println("ADDRESS-LAYOUT COMPARE");
            out.println("----------------------");
            out.println("Skipped direct address/length comparison because one workbook does not");
            out.println("have file/address/length columns.");
            out.println();
            out.println("EN address blocks: " + en.addressBlocks.size());
            out.println("JP address blocks: " + jp.addressBlocks.size());
            out.println();
        }

        printMirrorMatchCompare(out, en, jp);
        printTargetEnBlocksWithJpMirrorMatches(out, en, jp);
        printOptionalBinaryCompare(out);

        out.close();

        System.out.println("JP/EN script layout compare written to:");
        System.out.println(report.getAbsolutePath());
    }

    private static WorkbookData readWorkbook(File excel, String label, PrintWriter out) throws Exception {
        FileInputStream in = new FileInputStream(excel);
        Workbook wb = WorkbookFactory.create(in);

        Sheet sheet = wb.getSheet("SCRIPTS");

        if (sheet == null) {
            out.println(label + " workbook sheets:");
            for (int i = 0; i < wb.getNumberOfSheets(); i++) {
                out.println("  " + wb.getSheetName(i));
            }
            out.println();

            in.close();

            throw new RuntimeException(label + " workbook has no SCRIPTS sheet: " + excel.getAbsolutePath());
        }

        WorkbookData data = new WorkbookData();
        data.label = label;
        data.excel = excel;

        data.header0 = getCellText(sheet.getRow(0), 0);
        data.header1 = getCellText(sheet.getRow(0), 1);
        data.header2 = getCellText(sheet.getRow(0), 2);
        data.header3 = getCellText(sheet.getRow(0), 3);
        data.header4 = getCellText(sheet.getRow(0), 4);

        data.layout = detectLayout(sheet);

        if (data.layout == Layout.ADDRESS_COLUMNS) {
            data.addressBlocks = readAddressBlocks(sheet);

            for (AddressBlock block : data.addressBlocks) {
                data.addressByKey.put(block.key(), block);

                List<AddressBlock> byFile = data.addressByFile.get(block.fileName);

                if (byFile == null) {
                    byFile = new ArrayList<AddressBlock>();
                    data.addressByFile.put(block.fileName, byFile);
                }

                byFile.add(block);

                String norm = normalize(block.fullText.toString());
                List<AddressBlock> byText = data.addressByNormalizedText.get(norm);

                if (byText == null) {
                    byText = new ArrayList<AddressBlock>();
                    data.addressByNormalizedText.put(norm, byText);
                }

                byText.add(block);
            }

            for (List<AddressBlock> list : data.addressByFile.values()) {
                Collections.sort(list, new Comparator<AddressBlock>() {
                    @Override
                    public int compare(AddressBlock a, AddressBlock b) {
                        if (a.address != b.address) {
                            return a.address - b.address;
                        }

                        return a.startExcelRow - b.startExcelRow;
                    }
                });
            }
        } else {
            data.indexBlocks = readIndexBlocks(sheet);

            for (IndexBlock block : data.indexBlocks) {
                if (block.englishMirror.length() > 0) {
                    String norm = normalize(block.englishMirror.toString());
                    List<IndexBlock> byText = data.indexByNormalizedEnglishMirror.get(norm);

                    if (byText == null) {
                        byText = new ArrayList<IndexBlock>();
                        data.indexByNormalizedEnglishMirror.put(norm, byText);
                    }

                    byText.add(block);
                }
            }
        }

        in.close();

        return data;
    }

    private static Layout detectLayout(Sheet sheet) {
        String h0 = getCellText(sheet.getRow(0), 0);
        String h1 = getCellText(sheet.getRow(0), 1);
        String h2 = getCellText(sheet.getRow(0), 2);

        if (containsAny(h0, "脚本", "script", "file")
                && containsAny(h1, "地址", "address")
                && containsAny(h2, "长度", "length", "len")) {
            return Layout.ADDRESS_COLUMNS;
        }

        if (containsAny(h0, "语句", "编号", "sentence", "index")
                && containsAny(h1, "控制", "control")) {
            return Layout.INDEX_COLUMNS;
        }

        Row row2 = sheet.getRow(1);

        String c0 = getCellText(row2, 0).trim();
        String c1 = getCellText(row2, 1).trim();
        String c2 = getCellText(row2, 2).trim();

        if (looksLikeFileName(c0) && looksLikeHexAddress(c1)) {
            return Layout.ADDRESS_COLUMNS;
        }

        if (looksLikeSentenceIndex(c0) && c1.startsWith("[")) {
            return Layout.INDEX_COLUMNS;
        }

        if (c1.startsWith("[") && !looksLikeHexAddress(c1) && c2.length() > 0) {
            return Layout.INDEX_COLUMNS;
        }

        return Layout.UNKNOWN;
    }

    private static List<AddressBlock> readAddressBlocks(Sheet sheet) {
        List<AddressBlock> blocks = new ArrayList<AddressBlock>();

        AddressBlock current = null;

        for (int r = 1; r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);

            String fileName = getCellText(row, 0).trim();
            String address = getCellText(row, 1).trim();
            String lenText = getCellText(row, 2).trim();
            String ctrl = getCellText(row, 3);
            String original = getCellText(row, 4);

            if (!fileName.isEmpty() && !address.isEmpty()) {
                finishAddressBlock(current, blocks);

                if (!looksLikeHexAddress(address)) {
                    current = null;
                    continue;
                }

                current = new AddressBlock();
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
                current.lenText = lenText;
                current.originalLen = parseDecimalOrHexLength(lenText);
            }

            current.fullText.append(ctrl);
            current.fullText.append(original);

            if (current.sample.length() < 500) {
                current.sample.append(ctrl);
                current.sample.append(original);
            }
        }

        finishAddressBlock(current, blocks);

        return blocks;
    }

    private static void finishAddressBlock(AddressBlock block, List<AddressBlock> blocks) {
        if (block == null) {
            return;
        }

        if (block.originalLen < 0) {
            return;
        }

        blocks.add(block);
    }

    private static List<IndexBlock> readIndexBlocks(Sheet sheet) {
        List<IndexBlock> blocks = new ArrayList<IndexBlock>();

        IndexBlock current = null;

        for (int r = 1; r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);

            String index = getCellText(row, 0).trim();
            String ctrl = getCellText(row, 1);
            String original = getCellText(row, 2);
            String chinese = getCellText(row, 3);
            String englishMirror = getCellText(row, 4);

            if (!index.isEmpty()) {
                finishIndexBlock(current, blocks);

                current = new IndexBlock();
                current.index = index;
                current.startExcelRow = r + 1;
            }

            if (current == null) {
                continue;
            }

            current.endExcelRow = r + 1;

            current.fullText.append(ctrl);
            current.fullText.append(original);

            if (current.sample.length() < 500) {
                current.sample.append(ctrl);
                current.sample.append(original);
            }

            if (!chinese.isEmpty() && current.chineseSample.length() < 500) {
                current.chineseSample.append(chinese);
            }

            if (!englishMirror.isEmpty()) {
                current.englishMirror.append(englishMirror);
            }
        }

        finishIndexBlock(current, blocks);

        return blocks;
    }

    private static void finishIndexBlock(IndexBlock block, List<IndexBlock> blocks) {
        if (block == null) {
            return;
        }

        blocks.add(block);
    }

    private static void printDetectedLayouts(PrintWriter out, WorkbookData en, WorkbookData jp) {
        out.println("DETECTED LAYOUTS");
        out.println("----------------");

        printLayout(out, en);
        out.println();
        printLayout(out, jp);
        out.println();
    }

    private static void printLayout(PrintWriter out, WorkbookData data) {
        out.println(data.label + ":");
        out.println("  File: " + data.excel.getAbsolutePath());
        out.println("  Layout: " + data.layout);
        out.println("  Header A: " + data.header0);
        out.println("  Header B: " + data.header1);
        out.println("  Header C: " + data.header2);
        out.println("  Header D: " + data.header3);
        out.println("  Header E: " + data.header4);
        out.println("  Address blocks parsed: " + data.addressBlocks.size());
        out.println("  Index blocks parsed:   " + data.indexBlocks.size());
    }

    private static void printEnAddressSummary(PrintWriter out, WorkbookData en) {
        out.println("EN ADDRESS SUMMARY");
        out.println("------------------");

        if (en.addressBlocks.size() == 0) {
            out.println("No EN address blocks were parsed.");
            out.println();
            return;
        }

        out.println("Total EN address blocks: " + en.addressBlocks.size());
        out.println("Total EN script files:   " + en.addressByFile.size());
        out.println();

        List<String> files = new ArrayList<String>(en.addressByFile.keySet());
        Collections.sort(files);

        out.println("First 40 EN script files:");
        for (int i = 0; i < files.size() && i < 40; i++) {
            String file = files.get(i);
            out.println("  " + file + " blocks=" + en.addressByFile.get(file).size());
        }

        if (files.size() > 40) {
            out.println("  ... stopped after 40 files");
        }

        out.println();
    }

    private static void printJpIndexSummary(PrintWriter out, WorkbookData jp) {
        out.println("JP INDEX SUMMARY");
        out.println("----------------");

        if (jp.indexBlocks.size() == 0) {
            out.println("No JP index blocks were parsed.");
            out.println();
            return;
        }

        int mirrorCount = 0;

        for (IndexBlock block : jp.indexBlocks) {
            if (block.englishMirror.length() > 0) {
                mirrorCount++;
            }
        }

        out.println("Total JP index blocks: " + jp.indexBlocks.size());
        out.println("JP index blocks with English mirror text: " + mirrorCount);
        out.println();

        out.println("First 20 JP index blocks:");
        for (int i = 0; i < jp.indexBlocks.size() && i < 20; i++) {
            IndexBlock b = jp.indexBlocks.get(i);

            out.println(
                    "  #"
                            + i
                            + " index="
                            + b.index
                            + " rows="
                            + b.startExcelRow
                            + "-"
                            + b.endExcelRow
                            + " jp=\""
                            + cleanSample(b.sample.toString(), 100)
                            + "\""
                            + " mirror=\""
                            + cleanSample(b.englishMirror.toString(), 100)
                            + "\""
            );
        }

        out.println();
    }

    private static void printAddressLayoutCompare(PrintWriter out, WorkbookData en, WorkbookData jp) {
        out.println("ADDRESS-LAYOUT COMPARE");
        out.println("----------------------");
        out.println("Both workbooks have address blocks, so direct file+address comparison is possible.");
        out.println();

        int commonKeys = 0;
        int sameLength = 0;
        int differentLength = 0;
        int enOnly = 0;

        for (String key : en.addressByKey.keySet()) {
            AddressBlock eb = en.addressByKey.get(key);
            AddressBlock jb = jp.addressByKey.get(key);

            if (jb == null) {
                enOnly++;
                continue;
            }

            commonKeys++;

            if (eb.originalLen == jb.originalLen) {
                sameLength++;
            } else {
                differentLength++;
            }
        }

        int jpOnly = 0;

        for (String key : jp.addressByKey.keySet()) {
            if (!en.addressByKey.containsKey(key)) {
                jpOnly++;
            }
        }

        out.println("Common file+address blocks: " + commonKeys);
        out.println("Common blocks with same length: " + sameLength);
        out.println("Common blocks with different length: " + differentLength);
        out.println("EN-only file+address blocks: " + enOnly);
        out.println("JP-only file+address blocks: " + jpOnly);

        if (commonKeys > 0) {
            out.println("Same-length percent: " + String.format("%.2f", sameLength * 100.0 / commonKeys) + "%");
        }

        out.println();
    }

    private static void printTargetAddressCompare(PrintWriter out, WorkbookData en, WorkbookData jp, String targetFile) {
        out.println("TARGET FILE DIRECT ADDRESS COMPARE");
        out.println("----------------------------------");
        out.println("Target: " + targetFile);
        out.println();

        List<AddressBlock> enList = en.addressByFile.get(targetFile);
        List<AddressBlock> jpList = jp.addressByFile.get(targetFile);

        if (enList == null) {
            out.println("EN target file not present.");
            out.println();
            return;
        }

        if (jpList == null) {
            out.println("JP target file not present.");
            out.println();
            return;
        }

        out.println("EN target blocks: " + enList.size());
        out.println("JP target blocks: " + jpList.size());
        out.println();

        int max = Math.max(enList.size(), jpList.size());

        out.println("Index | EN addr/len | JP addr/len | address same | length same");
        out.println();

        for (int i = 0; i < max; i++) {
            AddressBlock eb = i < enList.size() ? enList.get(i) : null;
            AddressBlock jb = i < jpList.size() ? jpList.get(i) : null;

            boolean addrSame = eb != null && jb != null && eb.address == jb.address;
            boolean lenSame = eb != null && jb != null && eb.originalLen == jb.originalLen;

            out.print(padLeft(String.valueOf(i), 5));
            out.print(" | ");

            if (eb == null) {
                out.print("EN missing");
            } else {
                out.print(hex6(eb.address) + " len=" + eb.originalLen);
            }

            out.print(" | ");

            if (jb == null) {
                out.print("JP missing");
            } else {
                out.print(hex6(jb.address) + " len=" + jb.originalLen);
            }

            out.print(" | ");
            out.print(addrSame ? "YES" : "NO ");

            out.print(" | ");
            out.println(lenSame ? "YES" : "NO ");
        }

        out.println();
    }

    private static void printMirrorMatchCompare(PrintWriter out, WorkbookData en, WorkbookData jp) {
        out.println("JP ENGLISH-MIRROR MATCH COMPARE");
        out.println("-------------------------------");
        out.println("This is the useful fallback when JP has no address columns.");
        out.println();
        out.println("It compares JP column E English mirror text against full EN address-block text.");
        out.println("If they match, the JP row appears to correspond to that EN block.");
        out.println();

        if (en.addressBlocks.size() == 0) {
            out.println("No EN address blocks to match against.");
            out.println();
            return;
        }

        if (jp.indexBlocks.size() == 0) {
            out.println("No JP index blocks to match.");
            out.println();
            return;
        }

        int jpWithMirror = 0;
        int exactOne = 0;
        int exactMany = 0;
        int noMatch = 0;

        for (IndexBlock jb : jp.indexBlocks) {
            if (jb.englishMirror.length() == 0) {
                continue;
            }

            jpWithMirror++;

            String norm = normalize(jb.englishMirror.toString());
            List<AddressBlock> matches = en.addressByNormalizedText.get(norm);

            if (matches == null || matches.size() == 0) {
                noMatch++;
            } else if (matches.size() == 1) {
                exactOne++;
            } else {
                exactMany++;
            }
        }

        out.println("JP blocks with English mirror text: " + jpWithMirror);
        out.println("Mirror matched exactly one EN block: " + exactOne);
        out.println("Mirror matched multiple EN blocks:  " + exactMany);
        out.println("Mirror did not match EN block:      " + noMatch);

        if (jpWithMirror > 0) {
            out.println("Exact single-match percent: "
                    + String.format("%.2f", exactOne * 100.0 / jpWithMirror) + "%");
        }

        out.println();

        out.println("First 80 JP mirror matches:");
        out.println();

        int printed = 0;

        for (IndexBlock jb : jp.indexBlocks) {
            if (jb.englishMirror.length() == 0) {
                continue;
            }

            String norm = normalize(jb.englishMirror.toString());
            List<AddressBlock> matches = en.addressByNormalizedText.get(norm);

            if (matches == null || matches.size() == 0) {
                continue;
            }

            for (AddressBlock eb : matches) {
                out.println(
                        "JP index "
                                + jb.index
                                + " rows "
                                + jb.startExcelRow
                                + "-"
                                + jb.endExcelRow
                                + " -> EN "
                                + eb.fileName
                                + " "
                                + hex6(eb.address)
                                + " len="
                                + eb.originalLen
                                + " EN rows "
                                + eb.startExcelRow
                                + "-"
                                + eb.endExcelRow
                );

                printed++;

                if (printed >= 80) {
                    out.println("Stopped after 80 matches.");
                    out.println();
                    return;
                }
            }
        }

        if (printed == 0) {
            out.println("No mirror matches printed.");
        }

        out.println();
    }

    private static void printTargetEnBlocksWithJpMirrorMatches(PrintWriter out, WorkbookData en, WorkbookData jp) {
        out.println("TARGET EN BLOCKS WITH JP MIRROR MATCHES");
        out.println("---------------------------------------");
        out.println("Target EN file: " + TARGET_FILE);
        out.println("Target EN area: " + hex6(TARGET_ADDRESS));
        out.println();

        List<AddressBlock> targetBlocks = en.addressByFile.get(TARGET_FILE);

        if (targetBlocks == null) {
            out.println("EN target file not found in workbook.");
            out.println();
            return;
        }

        int bestIndex = 0;
        int bestDistance = Integer.MAX_VALUE;

        for (int i = 0; i < targetBlocks.size(); i++) {
            int distance = Math.abs(targetBlocks.get(i).address - TARGET_ADDRESS);

            if (distance < bestDistance) {
                bestDistance = distance;
                bestIndex = i;
            }
        }

        int start = bestIndex - 8;

        if (start < 0) {
            start = 0;
        }

        int end = start + 24;

        if (end > targetBlocks.size()) {
            end = targetBlocks.size();
        }

        out.println("EN blocks near experiment area:");
        out.println();

        for (int i = start; i < end; i++) {
            AddressBlock eb = targetBlocks.get(i);

            String norm = normalize(eb.fullText.toString());
            List<IndexBlock> jpMatches = jp.indexByNormalizedEnglishMirror.get(norm);

            out.println(
                    "EN #"
                            + i
                            + " "
                            + hex6(eb.address)
                            + " len="
                            + eb.originalLen
                            + " rows="
                            + eb.startExcelRow
                            + "-"
                            + eb.endExcelRow
                            + " text=\""
                            + cleanSample(eb.sample.toString(), 100)
                            + "\""
            );

            if (jpMatches == null || jpMatches.size() == 0) {
                out.println("  JP mirror match: none");
            } else {
                for (IndexBlock jb : jpMatches) {
                    out.println(
                            "  JP mirror match: index="
                                    + jb.index
                                    + " rows="
                                    + jb.startExcelRow
                                    + "-"
                                    + jb.endExcelRow
                                    + " jp=\""
                                    + cleanSample(jb.sample.toString(), 100)
                                    + "\""
                    );
                }
            }

            out.println();
        }

        out.println("Interpretation:");
        out.println("  If JP has only index rows, these matches show which JP line corresponds");
        out.println("  to each EN fixed slot. They do NOT prove the JP binary address/length.");
        out.println("  To compare real JP binary slots, we need the JP split files too.");
        out.println();
    }

    private static void printOptionalBinaryCompare(PrintWriter out) throws Exception {
        out.println("OPTIONAL SPLIT-FILE BINARY CHECK");
        out.println("--------------------------------");
        out.println("This only runs if both split folders exist.");
        out.println();

        File enDir = new File(Conf.desktop + "brmen/");
        File jpDir = new File(Conf.desktop + "brmjp/");

        out.println("EN split dir: " + enDir.getAbsolutePath() + " exists=" + enDir.exists());
        out.println("JP split dir: " + jpDir.getAbsolutePath() + " exists=" + jpDir.exists());
        out.println();

        if (!enDir.exists() || !jpDir.exists()) {
            out.println("Skipping binary compare because one split dir is missing.");
            out.println("Expected JP split folder: " + jpDir.getAbsolutePath());
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

    private static boolean containsAny(String text, String... needles) {
        String lower = text == null ? "" : text.toLowerCase();

        for (String needle : needles) {
            if (needle == null) {
                continue;
            }

            if (lower.contains(needle.toLowerCase())) {
                return true;
            }
        }

        return false;
    }

    private static boolean looksLikeFileName(String s) {
        String t = s == null ? "" : s.trim();

        return t.contains("/") || t.contains("\\");
    }

    private static boolean looksLikeSentenceIndex(String s) {
        String t = s == null ? "" : s.trim();

        if (t.length() == 0) {
            return false;
        }

        for (int i = 0; i < t.length(); i++) {
            char ch = t.charAt(i);

            if (!(ch >= '0' && ch <= '9') && ch != '.') {
                return false;
            }
        }

        return true;
    }

    private static boolean looksLikeHexAddress(String s) {
        String t = s == null ? "" : s.trim();

        if (t.startsWith("0x") || t.startsWith("0X")) {
            t = t.substring(2);
        }

        if (t.length() == 0) {
            return false;
        }

        for (int i = 0; i < t.length(); i++) {
            char ch = t.charAt(i);

            boolean ok =
                    (ch >= '0' && ch <= '9')
                            || (ch >= 'a' && ch <= 'f')
                            || (ch >= 'A' && ch <= 'F');

            if (!ok) {
                return false;
            }
        }

        return true;
    }

    private static String normalizeFileName(String s) {
        return s.trim().replace("\\", "/");
    }

    private static String normalize(String s) {
        if (s == null) {
            return "";
        }

        String t = s.replace("\r", "")
                .replace("\n", "")
                .replace("\t", "")
                .trim();

        while (t.contains("  ")) {
            t = t.replace("  ", " ");
        }

        return t;
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
        String cleaned = s == null ? "" : s;

        cleaned = cleaned.replace("\r", " ")
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

    private static String hex6(int value) {
        String s = Integer.toHexString(value & 0xFFFFFF).toUpperCase();

        while (s.length() < 6) {
            s = "0" + s;
        }

        return "0x" + s;
    }

    private enum Layout {
        ADDRESS_COLUMNS,
        INDEX_COLUMNS,
        UNKNOWN
    }

    private static class WorkbookData {
        String label;
        File excel;
        Layout layout;

        String header0;
        String header1;
        String header2;
        String header3;
        String header4;

        List<AddressBlock> addressBlocks = new ArrayList<AddressBlock>();
        List<IndexBlock> indexBlocks = new ArrayList<IndexBlock>();

        Map<String, AddressBlock> addressByKey = new LinkedHashMap<String, AddressBlock>();
        Map<String, List<AddressBlock>> addressByFile = new LinkedHashMap<String, List<AddressBlock>>();
        Map<String, List<AddressBlock>> addressByNormalizedText = new LinkedHashMap<String, List<AddressBlock>>();

        Map<String, List<IndexBlock>> indexByNormalizedEnglishMirror = new LinkedHashMap<String, List<IndexBlock>>();
    }

    private static class AddressBlock {
        String fileName;
        String addressText;
        String lenText;
        int address;
        int originalLen = -1;
        int startExcelRow;
        int endExcelRow;
        StringBuilder fullText = new StringBuilder();
        StringBuilder sample = new StringBuilder();

        String key() {
            return fileName + "@" + hex6(address);
        }
    }

    private static class IndexBlock {
        String index;
        int startExcelRow;
        int endExcelRow;
        StringBuilder fullText = new StringBuilder();
        StringBuilder sample = new StringBuilder();
        StringBuilder chineseSample = new StringBuilder();
        StringBuilder englishMirror = new StringBuilder();
    }
}