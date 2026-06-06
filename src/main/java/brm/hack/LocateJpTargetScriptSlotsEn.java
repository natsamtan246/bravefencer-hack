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
import brm.dump.Ctrl;

public class LocateJpTargetScriptSlotsEn {

    private static final String EN_XLSX = "brm-en.xlsx";
    private static final String JP_XLSX = "brm-jp.xlsx";

    private static final String TARGET_FILE = "SC01/006/0.4";
    private static final int TARGET_ADDRESS = 0x60890;

    public static void main(String[] args) throws Exception {
        File enExcel = new File(Conf.desktop + EN_XLSX);
        File jpExcel = new File(Conf.desktop + JP_XLSX);

        File enSplitFile = new File(Conf.desktop + "brmen/" + TARGET_FILE);
        File jpSplitFile = new File(Conf.desktop + "brmjp/" + TARGET_FILE);

        File report = new File(Conf.desktop + "jp-target-slot-locator-report.txt");

        PrintWriter out = new PrintWriter(report, "UTF-8");

        out.println("Brave Fencer Musashi JP Target Slot Locator");
        out.println("===========================================");
        out.println();
        out.println("This report does not modify anything.");
        out.println();
        out.println("Purpose:");
        out.println("  Use the JP workbook's English mirror text to match JP rows to EN");
        out.println("  address-based blocks, then search the JP split binary for the");
        out.println("  leading control-code bytes of those JP rows.");
        out.println();
        out.println("Why:");
        out.println("  The JP spreadsheet has no address/length columns, but the JP split");
        out.println("  binary exists. This report tries to recover the real JP binary");
        out.println("  positions for the same scene area.");
        out.println();

        out.println("INPUTS");
        out.println("------");
        out.println("EN workbook:      " + enExcel.getAbsolutePath() + " exists=" + enExcel.exists());
        out.println("JP workbook:      " + jpExcel.getAbsolutePath() + " exists=" + jpExcel.exists());
        out.println("EN split target:  " + enSplitFile.getAbsolutePath() + " exists=" + enSplitFile.exists());
        out.println("JP split target:  " + jpSplitFile.getAbsolutePath() + " exists=" + jpSplitFile.exists());
        out.println();

        if (!enExcel.exists()) {
            out.close();
            throw new RuntimeException("Missing EN workbook: " + enExcel.getAbsolutePath());
        }

        if (!jpExcel.exists()) {
            out.close();
            throw new RuntimeException("Missing JP workbook: " + jpExcel.getAbsolutePath());
        }

        if (!enSplitFile.exists()) {
            out.close();
            throw new RuntimeException("Missing EN split target: " + enSplitFile.getAbsolutePath());
        }

        if (!jpSplitFile.exists()) {
            out.close();
            throw new RuntimeException("Missing JP split target: " + jpSplitFile.getAbsolutePath());
        }

        byte[] enBytes = readAll(enSplitFile);
        byte[] jpBytes = readAll(jpSplitFile);

        out.println("BINARY SUMMARY");
        out.println("--------------");
        out.println("EN target size: " + enBytes.length);
        out.println("JP target size: " + jpBytes.length);
        out.println("Size difference JP - EN: " + signed(jpBytes.length - enBytes.length));
        out.println("EN SHA-1: " + sha1(enBytes));
        out.println("JP SHA-1: " + sha1(jpBytes));
        out.println();

        List<EnBlock> enBlocks = readEnAddressBlocks(enExcel);
        List<JpBlock> jpBlocks = readJpIndexBlocks(jpExcel);

        Map<String, List<JpBlock>> jpByMirror = new LinkedHashMap<String, List<JpBlock>>();

        for (JpBlock jp : jpBlocks) {
            String norm = normalize(jp.englishMirror.toString());

            if (norm.length() == 0) {
                continue;
            }

            List<JpBlock> list = jpByMirror.get(norm);

            if (list == null) {
                list = new ArrayList<JpBlock>();
                jpByMirror.put(norm, list);
            }

            list.add(jp);
        }

        List<EnBlock> targetEnBlocks = getTargetEnWindow(enBlocks);

        List<TargetPair> pairs = new ArrayList<TargetPair>();

        for (EnBlock en : targetEnBlocks) {
            TargetPair pair = new TargetPair();
            pair.en = en;

            String norm = normalize(en.fullText.toString());
            List<JpBlock> matches = jpByMirror.get(norm);

            if (matches != null && matches.size() > 0) {
                pair.jpMatches = matches;
                pair.jp = matches.get(0);
                pair.leadingControlBytes = extractLeadingControlBytes(pair.jp.fullText.toString());
                pair.hits = findAll(jpBytes, pair.leadingControlBytes);
            } else {
                pair.jpMatches = new ArrayList<JpBlock>();
                pair.leadingControlBytes = new byte[0];
                pair.hits = new ArrayList<Integer>();
            }

            pairs.add(pair);
        }

        out.println("TARGET EN WINDOW");
        out.println("----------------");
        out.println("Target EN file: " + TARGET_FILE);
        out.println("Target EN address: " + hex6(TARGET_ADDRESS));
        out.println();

        for (int i = 0; i < pairs.size(); i++) {
            TargetPair pair = pairs.get(i);

            out.println("EN window #" + i);
            out.println("  EN address: " + hex6(pair.en.address));
            out.println("  EN len:     " + pair.en.originalLen);
            out.println("  EN rows:    " + pair.en.startExcelRow + "-" + pair.en.endExcelRow);
            out.println("  EN text:    \"" + cleanSample(pair.en.sample.toString(), 130) + "\"");

            if (pair.jp == null) {
                out.println("  JP mirror:  NO MATCH");
            } else {
                out.println("  JP mirror:  index " + pair.jp.index
                        + " rows " + pair.jp.startExcelRow + "-" + pair.jp.endExcelRow
                        + " matches=" + pair.jpMatches.size());
                out.println("  JP text:    \"" + cleanSample(pair.jp.sample.toString(), 130) + "\"");
                out.println("  Prefix len: " + pair.leadingControlBytes.length);
                out.println("  Prefix hex: " + hexBytes(pair.leadingControlBytes));
                out.println("  JP hits:    " + pair.hits.size());
                out.println("  Hit list:   " + limitedHitList(pair.hits, 20));
            }

            out.println();
        }

        RunCandidate bestRun = chooseBestSequentialRun(pairs);

        out.println("BEST SEQUENTIAL JP BINARY RUN");
        out.println("-----------------------------");

        if (bestRun == null || bestRun.foundCount == 0) {
            out.println("No usable sequential JP run found.");
            out.println();
        } else {
            out.println("Found count: " + bestRun.foundCount + " / " + pairs.size());
            out.println("Span:        " + hex(bestRun.span()));
            out.println();

            out.println("Index | EN address/len | JP index | selected JP offset | JP slot delta to next | notes");
            out.println();

            for (int i = 0; i < pairs.size(); i++) {
                TargetPair pair = pairs.get(i);
                Integer selected = bestRun.selectedOffsets.get(pair);

                out.print(padLeft(String.valueOf(i), 5));
                out.print(" | ");
                out.print(hex6(pair.en.address));
                out.print(" len=");
                out.print(pair.en.originalLen);
                out.print(" | ");

                if (pair.jp == null) {
                    out.print("no JP mirror");
                } else {
                    out.print(pair.jp.index);
                }

                out.print(" | ");

                if (selected == null) {
                    out.print("not selected");
                } else {
                    out.print(hex(selected));
                }

                out.print(" | ");

                Integer nextSelected = findNextSelected(bestRun, pairs, i);

                if (selected != null && nextSelected != null) {
                    out.print(hex(nextSelected - selected));
                    out.print(" / ");
                    out.print(nextSelected - selected);
                } else {
                    out.print("unknown");
                }

                out.print(" | ");

                if (selected != null) {
                    out.print("selected");
                } else if (pair.jp == null) {
                    out.print("no mirror match");
                } else if (pair.hits.size() == 0) {
                    out.print("prefix not found in JP target");
                } else {
                    out.print("hits exist but not in chosen run");
                }

                out.println();
            }

            out.println();
        }

        out.println("JP RUN DETAIL");
        out.println("-------------");

        if (bestRun != null && bestRun.foundCount > 0) {
            for (int i = 0; i < pairs.size(); i++) {
                TargetPair pair = pairs.get(i);
                Integer selected = bestRun.selectedOffsets.get(pair);

                if (selected == null) {
                    continue;
                }

                out.println("EN " + hex6(pair.en.address)
                        + " len=" + pair.en.originalLen
                        + " -> JP index " + pair.jp.index
                        + " at " + hex(selected));
                out.println("  JP bytes at selected offset:");
                out.println("  " + hexContext(jpBytes, selected, 32, 96));
                out.println();
            }
        } else {
            out.println("No JP run detail available.");
            out.println();
        }

        out.println("INTERPRETATION");
        out.println("--------------");
        out.println("If the selected JP offsets form a clean sequence, compare the JP slot");
        out.println("deltas to the EN lengths.");
        out.println();
        out.println("If JP deltas differ from EN lengths, then JP and EN did not preserve");
        out.println("the same fixed binary slots in this scene.");
        out.println();
        out.println("If JP deltas equal EN lengths, then the JP binary likely uses the same");
        out.println("slot starts/lengths, and the different file size comes from elsewhere.");
        out.println();
        out.println("Either way, this gets us closer than the spreadsheet-only comparison.");
        out.println();

        out.close();

        System.out.println("JP target slot locator report written to:");
        System.out.println(report.getAbsolutePath());
    }

    private static List<EnBlock> getTargetEnWindow(List<EnBlock> allBlocks) {
        List<EnBlock> targetFileBlocks = new ArrayList<EnBlock>();

        for (EnBlock block : allBlocks) {
            if (TARGET_FILE.equalsIgnoreCase(block.fileName)) {
                targetFileBlocks.add(block);
            }
        }

        Collections.sort(targetFileBlocks, new Comparator<EnBlock>() {
            @Override
            public int compare(EnBlock a, EnBlock b) {
                if (a.address != b.address) {
                    return a.address - b.address;
                }

                return a.startExcelRow - b.startExcelRow;
            }
        });

        int bestIndex = 0;
        int bestDistance = Integer.MAX_VALUE;

        for (int i = 0; i < targetFileBlocks.size(); i++) {
            int distance = Math.abs(targetFileBlocks.get(i).address - TARGET_ADDRESS);

            if (distance < bestDistance) {
                bestDistance = distance;
                bestIndex = i;
            }
        }

        /*
         * Include the two boxes before the experiment area and enough boxes
         * after it to cover the whole local scene conversation.
         */
        int start = bestIndex - 2;

        if (start < 0) {
            start = 0;
        }

        int end = start + 24;

        if (end > targetFileBlocks.size()) {
            end = targetFileBlocks.size();
        }

        List<EnBlock> window = new ArrayList<EnBlock>();

        for (int i = start; i < end; i++) {
            window.add(targetFileBlocks.get(i));
        }

        return window;
    }

    private static RunCandidate chooseBestSequentialRun(List<TargetPair> pairs) {
        List<TargetPair> searchablePairs = new ArrayList<TargetPair>();

        for (TargetPair pair : pairs) {
            if (pair.jp != null && pair.hits.size() > 0) {
                searchablePairs.add(pair);
            }
        }

        if (searchablePairs.size() == 0) {
            return null;
        }

        TargetPair first = searchablePairs.get(0);

        RunCandidate best = null;

        for (Integer startHit : first.hits) {
            RunCandidate candidate = new RunCandidate();

            int lastOffset = startHit;
            candidate.selectedOffsets.put(first, startHit);
            candidate.foundCount = 1;
            candidate.firstOffset = startHit;
            candidate.lastOffset = startHit;

            for (int i = 1; i < searchablePairs.size(); i++) {
                TargetPair pair = searchablePairs.get(i);

                Integer chosen = chooseNextHit(pair.hits, lastOffset);

                if (chosen == null) {
                    continue;
                }

                candidate.selectedOffsets.put(pair, chosen);
                candidate.foundCount++;
                lastOffset = chosen;
                candidate.lastOffset = chosen;
            }

            if (best == null || candidateBetter(candidate, best)) {
                best = candidate;
            }
        }

        return best;
    }

    private static Integer chooseNextHit(List<Integer> hits, int previous) {
        /*
         * The target dialogue area should be local. Do not jump absurdly far.
         */
        int maxGap = 0x10000;

        Integer best = null;

        for (Integer hit : hits) {
            if (hit <= previous) {
                continue;
            }

            int gap = hit - previous;

            if (gap > maxGap) {
                continue;
            }

            if (best == null || hit < best) {
                best = hit;
            }
        }

        return best;
    }

    private static boolean candidateBetter(RunCandidate a, RunCandidate b) {
        if (a.foundCount != b.foundCount) {
            return a.foundCount > b.foundCount;
        }

        if (a.span() != b.span()) {
            return a.span() < b.span();
        }

        return a.firstOffset < b.firstOffset;
    }

    private static Integer findNextSelected(RunCandidate run, List<TargetPair> pairs, int index) {
        for (int i = index + 1; i < pairs.size(); i++) {
            Integer selected = run.selectedOffsets.get(pairs.get(i));

            if (selected != null) {
                return selected;
            }
        }

        return null;
    }

    private static byte[] extractLeadingControlBytes(String text) {
        ByteBuilder builder = new ByteBuilder();

        int i = 0;

        while (i < text.length()) {
            char ch = text.charAt(i);

            if (ch != '[') {
                break;
            }

            int close = text.indexOf(']', i);

            if (close < 0) {
                break;
            }

            String token = text.substring(i, close + 1);

            try {
                byte[] encoded = Ctrl.encode(token);
                builder.add(encoded);
            } catch (Exception ex) {
                break;
            }

            i = close + 1;
        }

        return builder.toByteArray();
    }

    private static List<Integer> findAll(byte[] data, byte[] pattern) {
        List<Integer> hits = new ArrayList<Integer>();

        if (pattern == null || pattern.length == 0) {
            return hits;
        }

        if (pattern.length > data.length) {
            return hits;
        }

        for (int i = 0; i <= data.length - pattern.length; i++) {
            boolean match = true;

            for (int j = 0; j < pattern.length; j++) {
                if (data[i + j] != pattern[j]) {
                    match = false;
                    break;
                }
            }

            if (match) {
                hits.add(i);
            }
        }

        return hits;
    }

    private static List<EnBlock> readEnAddressBlocks(File excel) throws Exception {
        List<EnBlock> blocks = new ArrayList<EnBlock>();

        FileInputStream in = new FileInputStream(excel);
        Workbook wb = WorkbookFactory.create(in);
        Sheet sheet = wb.getSheet("SCRIPTS");

        if (sheet == null) {
            in.close();
            throw new RuntimeException("EN workbook has no SCRIPTS sheet.");
        }

        EnBlock current = null;

        for (int r = 1; r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);

            String fileName = getCellText(row, 0).trim();
            String address = getCellText(row, 1).trim();
            String lenText = getCellText(row, 2).trim();
            String ctrl = getCellText(row, 3);
            String original = getCellText(row, 4);

            if (!fileName.isEmpty() && !address.isEmpty()) {
                finishEnBlock(current, blocks);

                current = new EnBlock();
                current.fileName = normalizeFileName(fileName);
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

            current.fullText.append(ctrl);
            current.fullText.append(original);

            if (current.sample.length() < 500) {
                current.sample.append(ctrl);
                current.sample.append(original);
            }
        }

        finishEnBlock(current, blocks);

        in.close();

        return blocks;
    }

    private static void finishEnBlock(EnBlock block, List<EnBlock> blocks) {
        if (block == null) {
            return;
        }

        if (block.originalLen < 0) {
            return;
        }

        blocks.add(block);
    }

    private static List<JpBlock> readJpIndexBlocks(File excel) throws Exception {
        List<JpBlock> blocks = new ArrayList<JpBlock>();

        FileInputStream in = new FileInputStream(excel);
        Workbook wb = WorkbookFactory.create(in);
        Sheet sheet = wb.getSheet("SCRIPTS");

        if (sheet == null) {
            in.close();
            throw new RuntimeException("JP workbook has no SCRIPTS sheet.");
        }

        JpBlock current = null;

        for (int r = 1; r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);

            String index = getCellText(row, 0).trim();
            String ctrl = getCellText(row, 1);
            String original = getCellText(row, 2);
            String englishMirror = getCellText(row, 4);

            if (!index.isEmpty()) {
                finishJpBlock(current, blocks);

                current = new JpBlock();
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

            if (!englishMirror.isEmpty()) {
                current.englishMirror.append(englishMirror);
            }
        }

        finishJpBlock(current, blocks);

        in.close();

        return blocks;
    }

    private static void finishJpBlock(JpBlock block, List<JpBlock> blocks) {
        if (block == null) {
            return;
        }

        blocks.add(block);
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

    private static String hexContext(byte[] data, int center, int before, int after) {
        int start = center - before;
        int end = center + after;

        if (start < 0) {
            start = 0;
        }

        if (end >= data.length) {
            end = data.length - 1;
        }

        StringBuilder sb = new StringBuilder();

        for (int pos = start; pos <= end; pos++) {
            if (pos == center) {
                sb.append("[");
            }

            sb.append(String.format("%02X", data[pos] & 0xFF));

            if (pos == center) {
                sb.append("]");
            }

            if (pos < end) {
                sb.append(" ");
            }
        }

        return sb.toString();
    }

    private static String limitedHitList(List<Integer> hits, int max) {
        if (hits == null || hits.size() == 0) {
            return "none";
        }

        StringBuilder sb = new StringBuilder();

        for (int i = 0; i < hits.size() && i < max; i++) {
            if (i > 0) {
                sb.append(", ");
            }

            sb.append(hex(hits.get(i)));
        }

        if (hits.size() > max) {
            sb.append(", ... ");
            sb.append(hits.size() - max);
            sb.append(" more");
        }

        return sb.toString();
    }

    private static String hexBytes(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return "";
        }

        StringBuilder sb = new StringBuilder();

        for (int i = 0; i < bytes.length; i++) {
            if (i > 0) {
                sb.append(" ");
            }

            sb.append(String.format("%02X", bytes[i] & 0xFF));
        }

        return sb.toString();
    }

    private static String padLeft(String s, int len) {
        String ret = s;

        while (ret.length() < len) {
            ret = " " + ret;
        }

        return ret;
    }

    private static String signed(int value) {
        if (value >= 0) {
            return "+" + value;
        }

        return String.valueOf(value);
    }

    private static String hex(int value) {
        return "0x" + Integer.toHexString(value).toUpperCase();
    }

    private static String hex6(int value) {
        String s = Integer.toHexString(value & 0xFFFFFF).toUpperCase();

        while (s.length() < 6) {
            s = "0" + s;
        }

        return "0x" + s;
    }

    private static class EnBlock {
        String fileName;
        int address;
        int originalLen = -1;
        int startExcelRow;
        int endExcelRow;
        StringBuilder fullText = new StringBuilder();
        StringBuilder sample = new StringBuilder();
    }

    private static class JpBlock {
        String index;
        int startExcelRow;
        int endExcelRow;
        StringBuilder fullText = new StringBuilder();
        StringBuilder sample = new StringBuilder();
        StringBuilder englishMirror = new StringBuilder();
    }

    private static class TargetPair {
        EnBlock en;
        JpBlock jp;
        List<JpBlock> jpMatches;
        byte[] leadingControlBytes;
        List<Integer> hits;
    }

    private static class RunCandidate {
        Map<TargetPair, Integer> selectedOffsets = new LinkedHashMap<TargetPair, Integer>();
        int foundCount;
        int firstOffset;
        int lastOffset;

        int span() {
            return lastOffset - firstOffset;
        }
    }

    private static class ByteBuilder {
        private byte[] data = new byte[64];
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