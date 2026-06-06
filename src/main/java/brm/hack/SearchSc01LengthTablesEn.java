package brm.hack;

import java.io.File;
import java.io.FileInputStream;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;

import brm.Conf;

public class SearchSc01LengthTablesEn {

    private static final String TARGET_SCRIPT_FILE = "SC01/006/0.4";

    /*
     * Use the long straight conversation section only.
     *
     * Start:
     *   0x60890 = first line we expanded.
     *
     * Stop before:
     *   0x6159C = first choice block.
     */
    private static final int START_ADDRESS = 0x60890;
    private static final int END_ADDRESS_EXCLUSIVE = 0x6159C;

    /*
     * Balanced expansion test:
     *
     * 0x60890 old length 0x50 became 0x82.
     * 0x60AA4 old length 0xFC had shortened text plus padding.
     */
    private static final int EXPANDED_ADDRESS = 0x60890;
    private static final int SHRINK_ADDRESS = 0x60AA4;
    private static final int DELTA = 0x32;

    private static final int CONTEXT_BYTES = 32;

    private static final int MIN_STRIDED_RUN = 5;
    private static final int MAX_EXACT_HITS_TO_PRINT = 200;
    private static final int MAX_STRIDED_RUNS_TO_PRINT = 200;

    public static void main(String[] args) throws Exception {
        File excel = new File(Conf.desktop + "brm-en.xlsx");
        File sc01Dir = new File(Conf.desktop + "brmen/SC01/");
        File report = new File(Conf.desktop + "sc01-length-table-search-en.txt");

        if (!excel.exists()) {
            throw new RuntimeException("Excel file not found: " + excel.getAbsolutePath());
        }

        if (!sc01Dir.exists()) {
            throw new RuntimeException("SC01 directory not found: " + sc01Dir.getAbsolutePath());
        }

        List<Block> blocks = readTargetConversationBlocks(excel);

        if (blocks.isEmpty()) {
            throw new RuntimeException("No target blocks found in " + TARGET_SCRIPT_FILE);
        }

        List<File> files = new ArrayList<File>();
        collectFiles(sc01Dir, files);

        Collections.sort(files, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return rel(a).compareTo(rel(b));
            }
        });

        PrintWriter out = new PrintWriter(report, "UTF-8");

        out.println("Brave Fencer Musashi English SC01 Length Table Search");
        out.println("====================================================");
        out.println();
        out.println("This report does not modify anything.");
        out.println();
        out.println("Target script file:");
        out.println("  " + TARGET_SCRIPT_FILE);
        out.println();
        out.println("Conversation range:");
        out.println("  from " + hex(START_ADDRESS, 6));
        out.println("  through before " + hex(END_ADDRESS_EXCLUSIVE, 6));
        out.println();
        out.println("Files scanned:");
        out.println("  " + files.size());
        out.println();

        printBlockList(out, blocks);

        searchExactFullSequences(out, files, blocks);
        searchWindowSequences(out, files, blocks);
        searchStridedLengthRuns(out, files, blocks);

        out.println();
        out.println("INTERPRETATION");
        out.println("--------------");
        out.println("The best hit would be a length sequence matching this conversation, especially in SC01/006/0.4.");
        out.println();
        out.println("If the active length table is found, the likely patch for the balanced test is:");
        out.println("  expanded block " + hex(EXPANDED_ADDRESS, 6) + ": 0x50 -> 0x82");
        out.println("  shrink block   " + hex(SHRINK_ADDRESS, 6) + ": 0xFC -> 0xCA if the table stores effective stepping length");
        out.println();
        out.println("Do not patch anything yet. This is still a report/search step.");
        out.println();

        out.close();

        System.out.println("SC01 length table search written to:");
        System.out.println(report.getAbsolutePath());
    }

    private static List<Block> readTargetConversationBlocks(File excel) throws Exception {
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
            String addressText = getCellText(row, 1).trim(); // B
            String lenText = getCellText(row, 2).trim(); // C

            if (!fileName.isEmpty() && !addressText.isEmpty()) {
                finishBlock(current, blocks);

                current = new Block();
                current.fileName = fileName.replace("\\", "/");
                current.addressText = addressText;
                current.address = parseHexAddress(addressText);
                current.startExcelRow = r + 1;
            }

            if (current == null) {
                continue;
            }

            current.endExcelRow = r + 1;

            if (!lenText.isEmpty()) {
                current.length = parseDecimalOrHexLength(lenText);
            }
        }

        finishBlock(current, blocks);

        in.close();

        Collections.sort(blocks, new Comparator<Block>() {
            @Override
            public int compare(Block a, Block b) {
                return a.address - b.address;
            }
        });

        return blocks;
    }

    private static void finishBlock(Block block, List<Block> blocks) {
        if (block == null) {
            return;
        }

        if (!TARGET_SCRIPT_FILE.equalsIgnoreCase(block.fileName)) {
            return;
        }

        if (block.address < START_ADDRESS || block.address >= END_ADDRESS_EXCLUSIVE) {
            return;
        }

        if (block.length < 0) {
            return;
        }

        blocks.add(block);
    }

    private static void printBlockList(PrintWriter out, List<Block> blocks) {
        out.println("TARGET BLOCK LENGTHS");
        out.println("--------------------");

        for (int i = 0; i < blocks.size(); i++) {
            Block b = blocks.get(i);

            out.println(
                    padLeft(String.valueOf(i), 2)
                            + " address " + hex(b.address, 6)
                            + " len dec " + b.length
                            + " hex " + hex(b.length, 4)
                            + " rows " + b.startExcelRow + "-" + b.endExcelRow
                            + specialNote(b)
            );
        }

        out.println();

        out.println("Length sequence, decimal:");
        out.println("  " + lengthSequenceDecimal(blocks));
        out.println();

        out.println("Length sequence, hex:");
        out.println("  " + lengthSequenceHex(blocks));
        out.println();
    }

    private static String specialNote(Block b) {
        if (b.address == EXPANDED_ADDRESS) {
            return "  EXPANDED TEST BLOCK";
        }

        if (b.address == SHRINK_ADDRESS) {
            return "  SHRINK TEST BLOCK";
        }

        return "";
    }

    private static void searchExactFullSequences(
            PrintWriter out,
            List<File> files,
            List<Block> blocks
    ) throws Exception {
        out.println();
        out.println("EXACT FULL LENGTH SEQUENCE HITS");
        out.println("-------------------------------");
        out.println("Searches the entire pre-choice conversation length sequence.");
        out.println();

        List<PatternSet> patterns = new ArrayList<PatternSet>();

        patterns.add(new PatternSet("packed UINT16 lengths", packed16Lengths(blocks, 0)));
        patterns.add(new PatternSet("packed UINT32 lengths", packed32Lengths(blocks, 0)));
        patterns.add(new PatternSet("packed UINT16 lengths minus 1", packed16Lengths(blocks, -1)));
        patterns.add(new PatternSet("packed UINT16 length divided by 4", packed16LengthWords(blocks)));

        if (allLengthsFitByte(blocks, 0)) {
            patterns.add(new PatternSet("packed UINT8 lengths", packed8Lengths(blocks, 0)));
        } else {
            patterns.add(new PatternSet("packed UINT8 low-byte lengths", packed8LowByteLengths(blocks, 0)));
        }

        if (allLengthsFitByte(blocks, -1)) {
            patterns.add(new PatternSet("packed UINT8 lengths minus 1", packed8Lengths(blocks, -1)));
        } else {
            patterns.add(new PatternSet("packed UINT8 low-byte lengths minus 1", packed8LowByteLengths(blocks, -1)));
        }

        patterns.add(new PatternSet("packed UINT8 length divided by 4", packed8LengthWords(blocks)));

        for (int p = 0; p < patterns.size(); p++) {
            PatternSet pattern = patterns.get(p);

            out.println();
            out.println("Pattern: " + pattern.name);
            out.println("Bytes:   " + bytesToHex(pattern.bytes));
            out.println();

            printPatternHits(out, files, pattern.bytes, MAX_EXACT_HITS_TO_PRINT);
        }

        out.println();
    }

    private static void searchWindowSequences(
            PrintWriter out,
            List<File> files,
            List<Block> blocks
    ) throws Exception {
        out.println();
        out.println("WINDOWED LENGTH SEQUENCE HITS");
        out.println("-----------------------------");
        out.println("Searches smaller chunks of the conversation in case the table is split.");
        out.println();

        int[] windowSizes = new int[] {12, 10, 8, 6, 5};

        for (int w = 0; w < windowSizes.length; w++) {
            int window = windowSizes[w];

            if (blocks.size() < window) {
                continue;
            }

            out.println();
            out.println("WINDOW SIZE " + window);
            out.println("-------------");

            for (int start = 0; start <= blocks.size() - window; start++) {
                List<Block> sub = blocks.subList(start, start + window);

                byte[] pattern16 = packed16Lengths(sub, 0);
                int hits16 = countPatternHits(files, pattern16);

                byte[] patternWords = packed16LengthWords(sub);
                int hitsWords = countPatternHits(files, patternWords);

                byte[] patternMinus1 = packed16Lengths(sub, -1);
                int hitsMinus1 = countPatternHits(files, patternMinus1);

                if (hits16 == 0 && hitsWords == 0 && hitsMinus1 == 0) {
                    continue;
                }

                out.println();
                out.println("Window blocks " + start + "-" + (start + window - 1));
                out.println("Addresses " + hex(sub.get(0).address, 6)
                        + " through " + hex(sub.get(sub.size() - 1).address, 6));

                if (hits16 > 0) {
                    out.println("  UINT16 length hits: " + hits16);
                    printPatternHits(out, files, pattern16, 30);
                }

                if (hitsWords > 0) {
                    out.println("  UINT16 length/4 hits: " + hitsWords);
                    printPatternHits(out, files, patternWords, 30);
                }

                if (hitsMinus1 > 0) {
                    out.println("  UINT16 length-1 hits: " + hitsMinus1);
                    printPatternHits(out, files, patternMinus1, 30);
                }
            }
        }

        out.println();
    }

    private static void searchStridedLengthRuns(
            PrintWriter out,
            List<File> files,
            List<Block> blocks
    ) throws Exception {
        out.println();
        out.println("STRIDED LENGTH RUNS");
        out.println("-------------------");
        out.println("Searches for the length sequence with one value every 1, 2, 4, 8, 12, or 16 bytes.");
        out.println("This catches tables where length is one field inside a larger record.");
        out.println();

        List<Run> runs = new ArrayList<Run>();

        SearchMode[] modes = new SearchMode[] {
                new SearchMode("UINT16 length", 2, 0, false, false),
                new SearchMode("UINT16 length-1", 2, -1, false, false),
                new SearchMode("UINT16 length/4", 2, 0, true, false),
                new SearchMode("UINT8 length or low byte", 1, 0, false, true),
                new SearchMode("UINT8 length-1 or low byte", 1, -1, false, true),
                new SearchMode("UINT8 length/4", 1, 0, true, true)
        };

        int[] strides = new int[] {1, 2, 4, 8, 12, 16};

        for (int m = 0; m < modes.length; m++) {
            SearchMode mode = modes[m];

            for (int f = 0; f < files.size(); f++) {
                File file = files.get(f);
                byte[] data = readAll(file);

                for (int s = 0; s < strides.length; s++) {
                    int stride = strides[s];

                    if (stride < mode.bytesPerValue) {
                        continue;
                    }

                    findRunsForMode(runs, file, data, blocks, mode, stride);
                }
            }
        }

        Collections.sort(runs, new Comparator<Run>() {
            @Override
            public int compare(Run a, Run b) {
                if (a.matchCount != b.matchCount) {
                    return b.matchCount - a.matchCount;
                }

                int c = a.relativeFile.compareTo(b.relativeFile);

                if (c != 0) {
                    return c;
                }

                return a.fileOffset - b.fileOffset;
            }
        });

        int printed = 0;

        for (int i = 0; i < runs.size(); i++) {
            Run run = runs.get(i);

            if (printed >= MAX_STRIDED_RUNS_TO_PRINT) {
                out.println("Stopped after " + MAX_STRIDED_RUNS_TO_PRINT + " strided runs.");
                break;
            }

            printRun(out, run, blocks);
            printed++;
        }

        if (printed == 0) {
            out.println("No strided length runs found.");
        }

        out.println();
    }

    private static void findRunsForMode(
            List<Run> runs,
            File file,
            byte[] data,
            List<Block> blocks,
            SearchMode mode,
            int stride
    ) {
        for (int pos = 0; pos + mode.bytesPerValue <= data.length; pos++) {
            for (int startIndex = 0; startIndex < blocks.size(); startIndex++) {
                int count = 0;

                while (startIndex + count < blocks.size()) {
                    int readPos = pos + count * stride;

                    if (readPos + mode.bytesPerValue > data.length) {
                        break;
                    }

                    int actual = readValue(data, readPos, mode.bytesPerValue);
                    int expected = expectedValue(blocks.get(startIndex + count), mode);

                    if (mode.byteMode) {
                        expected = expected & 0xFF;
                    }

                    if (actual != expected) {
                        break;
                    }

                    count++;
                }

                if (count >= MIN_STRIDED_RUN) {
                    Run run = new Run();
                    run.file = file;
                    run.relativeFile = rel(file);
                    run.data = data;
                    run.fileOffset = pos;
                    run.startBlockIndex = startIndex;
                    run.matchCount = count;
                    run.mode = mode;
                    run.stride = stride;
                    runs.add(run);
                }
            }
        }
    }

    private static void printRun(PrintWriter out, Run run, List<Block> blocks) {
        out.println("Run in " + run.relativeFile
                + " at " + hex(run.fileOffset, 8)
                + " mode " + run.mode.name
                + " stride " + run.stride
                + " blocks " + run.startBlockIndex + "-"
                + (run.startBlockIndex + run.matchCount - 1)
                + " matches " + run.matchCount);

        out.println("Context:");
        out.println("  " + context(run.data, run.fileOffset, run.stride * run.matchCount));

        out.println("Entries:");

        for (int i = 0; i < run.matchCount; i++) {
            Block b = blocks.get(run.startBlockIndex + i);
            int expected = expectedValue(b, run.mode);

            if (run.mode.byteMode) {
                expected = expected & 0xFF;
            }

            out.println("  block " + (run.startBlockIndex + i)
                    + " address " + hex(b.address, 6)
                    + " length " + b.length
                    + " stored " + hex(expected, run.mode.byteMode ? 2 : 4)
                    + specialNote(b));
        }

        out.println();
    }

    private static int expectedValue(Block block, SearchMode mode) {
        int value = block.length + mode.add;

        if (mode.divideByFour) {
            value = value / 4;
        }

        return value;
    }

    private static int readValue(byte[] data, int offset, int bytesPerValue) {
        if (bytesPerValue == 1) {
            return data[offset] & 0xFF;
        }

        int b0 = data[offset] & 0xFF;
        int b1 = data[offset + 1] & 0xFF;

        return b0 | (b1 << 8);
    }

    private static int countPatternHits(List<File> files, byte[] pattern) throws Exception {
        int count = 0;

        for (int i = 0; i < files.size(); i++) {
            byte[] data = readAll(files.get(i));
            count += findAll(data, pattern).size();
        }

        return count;
    }

    private static void printPatternHits(
            PrintWriter out,
            List<File> files,
            byte[] pattern,
            int maxToPrint
    ) throws Exception {
        int printed = 0;
        boolean capped = false;

        for (int i = 0; i < files.size(); i++) {
            File file = files.get(i);
            byte[] data = readAll(file);
            List<Integer> hits = findAll(data, pattern);

            for (int h = 0; h < hits.size(); h++) {
                if (printed >= maxToPrint) {
                    capped = true;
                    break;
                }

                int hit = hits.get(h).intValue();

                out.println("  " + rel(file) + " @ " + hex(hit, 8));
                out.println("    " + context(data, hit, pattern.length));

                printed++;
            }

            if (capped) {
                break;
            }
        }

        if (printed == 0) {
            out.println("  no hits");
        } else {
            out.println("  printed hits: " + printed + (capped ? " capped" : ""));
        }
    }

    private static byte[] packed16Lengths(List<Block> blocks, int add) {
        byte[] out = new byte[blocks.size() * 2];

        int p = 0;

        for (int i = 0; i < blocks.size(); i++) {
            int value = blocks.get(i).length + add;
            byte[] b = uint16LE(value);

            out[p++] = b[0];
            out[p++] = b[1];
        }

        return out;
    }

    private static byte[] packed32Lengths(List<Block> blocks, int add) {
        byte[] out = new byte[blocks.size() * 4];

        int p = 0;

        for (int i = 0; i < blocks.size(); i++) {
            int value = blocks.get(i).length + add;
            byte[] b = int32LE(value);

            out[p++] = b[0];
            out[p++] = b[1];
            out[p++] = b[2];
            out[p++] = b[3];
        }

        return out;
    }

    private static byte[] packed16LengthWords(List<Block> blocks) {
        byte[] out = new byte[blocks.size() * 2];

        int p = 0;

        for (int i = 0; i < blocks.size(); i++) {
            int value = blocks.get(i).length / 4;
            byte[] b = uint16LE(value);

            out[p++] = b[0];
            out[p++] = b[1];
        }

        return out;
    }

    private static byte[] packed8Lengths(List<Block> blocks, int add) {
        byte[] out = new byte[blocks.size()];

        for (int i = 0; i < blocks.size(); i++) {
            out[i] = (byte) ((blocks.get(i).length + add) & 0xFF);
        }

        return out;
    }

    private static byte[] packed8LowByteLengths(List<Block> blocks, int add) {
        return packed8Lengths(blocks, add);
    }

    private static byte[] packed8LengthWords(List<Block> blocks) {
        byte[] out = new byte[blocks.size()];

        for (int i = 0; i < blocks.size(); i++) {
            out[i] = (byte) ((blocks.get(i).length / 4) & 0xFF);
        }

        return out;
    }

    private static boolean allLengthsFitByte(List<Block> blocks, int add) {
        for (int i = 0; i < blocks.size(); i++) {
            int value = blocks.get(i).length + add;

            if (value < 0 || value > 255) {
                return false;
            }
        }

        return true;
    }

    private static String lengthSequenceDecimal(List<Block> blocks) {
        StringBuilder sb = new StringBuilder();

        for (int i = 0; i < blocks.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }

            sb.append(blocks.get(i).length);
        }

        return sb.toString();
    }

    private static String lengthSequenceHex(List<Block> blocks) {
        StringBuilder sb = new StringBuilder();

        for (int i = 0; i < blocks.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }

            sb.append(hex(blocks.get(i).length, 4));
        }

        return sb.toString();
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

    private static void collectFiles(File dir, List<File> files) {
        File[] children = dir.listFiles();

        if (children == null) {
            return;
        }

        for (int i = 0; i < children.length; i++) {
            File child = children[i];

            if (child.isDirectory()) {
                collectFiles(child, files);
            } else {
                String name = child.getName().toLowerCase();

                if (name.endsWith(".bak")) {
                    continue;
                }

                files.add(child);
            }
        }
    }

    private static List<Integer> findAll(byte[] data, byte[] pattern) {
        List<Integer> hits = new ArrayList<Integer>();

        for (int i = 0; i <= data.length - pattern.length; i++) {
            boolean match = true;

            for (int j = 0; j < pattern.length; j++) {
                if (data[i + j] != pattern[j]) {
                    match = false;
                    break;
                }
            }

            if (match) {
                hits.add(Integer.valueOf(i));
            }
        }

        return hits;
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

    private static byte[] uint16LE(int value) {
        return new byte[] {
                (byte) (value & 0xFF),
                (byte) ((value >> 8) & 0xFF)
        };
    }

    private static byte[] int32LE(int value) {
        return new byte[] {
                (byte) (value & 0xFF),
                (byte) ((value >> 8) & 0xFF),
                (byte) ((value >> 16) & 0xFF),
                (byte) ((value >> 24) & 0xFF)
        };
    }

    private static String context(byte[] data, int hit, int len) {
        int start = Math.max(0, hit - CONTEXT_BYTES);
        int end = Math.min(data.length, hit + len + CONTEXT_BYTES);

        StringBuilder sb = new StringBuilder();

        for (int i = start; i < end; i++) {
            if (i > start) {
                sb.append(' ');
            }

            if (i == hit) {
                sb.append('[');
            }

            sb.append(String.format("%02X", data[i] & 0xFF));

            if (i == hit + len - 1) {
                sb.append(']');
            }
        }

        return sb.toString();
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();

        for (int i = 0; i < bytes.length; i++) {
            if (i > 0) {
                sb.append(' ');
            }

            sb.append(String.format("%02X", bytes[i] & 0xFF));
        }

        return sb.toString();
    }

    private static String rel(File file) {
        String full = normalize(file);
        String root = normalize(new File(Conf.desktop + "brmen/"));

        if (full.startsWith(root)) {
            return full.substring(root.length());
        }

        return full;
    }

    private static String normalize(File file) {
        return file.getAbsolutePath().replace("\\", "/");
    }

    private static String hex(int value, int digits) {
        String s = Integer.toHexString(value).toUpperCase();

        while (s.length() < digits) {
            s = "0" + s;
        }

        return "0x" + s;
    }

    private static String padLeft(String text, int width) {
        while (text.length() < width) {
            text = " " + text;
        }

        return text;
    }

    private static class Block {
        String fileName;
        String addressText;
        int address;
        int startExcelRow;
        int endExcelRow;
        int length = -1;
    }

    private static class PatternSet {
        String name;
        byte[] bytes;

        PatternSet(String name, byte[] bytes) {
            this.name = name;
            this.bytes = bytes;
        }
    }

    private static class SearchMode {
        String name;
        int bytesPerValue;
        int add;
        boolean divideByFour;
        boolean byteMode;

        SearchMode(
                String name,
                int bytesPerValue,
                int add,
                boolean divideByFour,
                boolean byteMode
        ) {
            this.name = name;
            this.bytesPerValue = bytesPerValue;
            this.add = add;
            this.divideByFour = divideByFour;
            this.byteMode = byteMode;
        }
    }

    private static class Run {
        File file;
        String relativeFile;
        byte[] data;
        int fileOffset;
        int startBlockIndex;
        int matchCount;
        SearchMode mode;
        int stride;
    }
}