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

public class SearchSc01RecordTablesEn {

    private static final String TARGET_SCRIPT_FILE = "SC01/006/0.4";

    private static final int START_ADDRESS = 0x060890;
    private static final int END_ADDRESS_EXCLUSIVE = 0x06159C;

    private static final int EXPANDED_ADDRESS = 0x060890;
    private static final int SHRINK_ADDRESS = 0x060AA4;
    private static final int DELTA = 0x32;

    private static final int RAM_BASE = 0x80110000;
    private static final int CONTEXT_BYTES = 32;
    private static final int MAX_HITS_TO_PRINT = 120;

    public static void main(String[] args) throws Exception {
        File excel = new File(Conf.desktop + "brm-en.xlsx");
        File sc01Dir = new File(Conf.desktop + "brmen/SC01/");
        File report = new File(Conf.desktop + "sc01-record-table-search-en.txt");

        List<Block> blocks = readBlocks(excel);

        if (blocks.isEmpty()) {
            throw new RuntimeException("No blocks found for " + TARGET_SCRIPT_FILE);
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

        out.println("Brave Fencer Musashi English SC01 Record Table Search");
        out.println("====================================================");
        out.println();
        out.println("This report does not modify anything.");
        out.println();
        out.println("Target script file: " + TARGET_SCRIPT_FILE);
        out.println("Range: " + hex(START_ADDRESS, 6) + " through before " + hex(END_ADDRESS_EXCLUSIVE, 6));
        out.println("Files scanned: " + files.size());
        out.println();

        printBlocks(out, blocks);

        Format[] formats = new Format[] {
                new Format("LOCAL16_START + UINT16_LENGTH", Format.LOCAL16_START_UINT16_LENGTH),
                new Format("UINT16_LENGTH + LOCAL16_START", Format.UINT16_LENGTH_LOCAL16_START),
                new Format("LOCAL16_START + LOCAL16_END", Format.LOCAL16_START_LOCAL16_END),
                new Format("LOCAL16_END + LOCAL16_START", Format.LOCAL16_END_LOCAL16_START),
                new Format("LOCAL16_START + UINT16_LENGTH_DIV4", Format.LOCAL16_START_UINT16_LENGTH_DIV4),
                new Format("UINT16_LENGTH_DIV4 + LOCAL16_START", Format.UINT16_LENGTH_DIV4_LOCAL16_START),
                new Format("FILE32_START + UINT32_LENGTH", Format.FILE32_START_UINT32_LENGTH),
                new Format("FILE32_START + FILE32_END", Format.FILE32_START_FILE32_END),
                new Format("RAM32_START + UINT32_LENGTH", Format.RAM32_START_UINT32_LENGTH),
                new Format("RAM32_START + RAM32_END", Format.RAM32_START_RAM32_END)
        };

        searchFullPatterns(out, files, blocks, formats);
        searchWindowPatterns(out, files, blocks, formats);

        out.println();
        out.println("PATCH INTERPRETATION");
        out.println("--------------------");
        out.println("If this finds a real table, the balanced expansion patch would likely need:");
        out.println("  expanded block start unchanged: 0x0890");
        out.println("  expanded block length:          0x0050 -> 0x0082");
        out.println("  expanded block end:             0x08E0 -> 0x0912");
        out.println();
        out.println("For moved blocks before the shrink block:");
        out.println("  start += 0x32");
        out.println("  end   += 0x32");
        out.println();
        out.println("For the shrink block:");
        out.println("  start 0x0AA4 -> 0x0AD6");
        out.println("  end usually stays 0x0BA0 if the padded area is still reserved.");
        out.println();
        out.println("Do not patch yet. First upload this report.");
        out.close();

        System.out.println("SC01 record table search written to:");
        System.out.println(report.getAbsolutePath());
    }

    private static void searchFullPatterns(
            PrintWriter out,
            List<File> files,
            List<Block> blocks,
            Format[] formats
    ) throws Exception {
        out.println();
        out.println("EXACT FULL RECORD TABLE HITS");
        out.println("----------------------------");
        out.println("Searches the full pre-choice conversation as records.");
        out.println();

        for (int i = 0; i < formats.length; i++) {
            Format format = formats[i];
            byte[] pattern = buildPattern(blocks, 0, blocks.size(), format.kind);

            out.println();
            out.println("Pattern: " + format.name);
            out.println("Bytes:   " + bytesToHex(pattern));
            out.println();

            printHits(out, files, pattern, MAX_HITS_TO_PRINT);
        }
    }

    private static void searchWindowPatterns(
            PrintWriter out,
            List<File> files,
            List<Block> blocks,
            Format[] formats
    ) throws Exception {
        out.println();
        out.println("WINDOWED RECORD TABLE HITS");
        out.println("--------------------------");
        out.println("Searches smaller chunks in case the table is split or has branches.");
        out.println();

        int[] windows = new int[] {12, 10, 8, 6, 5, 4};

        for (int w = 0; w < windows.length; w++) {
            int window = windows[w];

            if (blocks.size() < window) {
                continue;
            }

            out.println();
            out.println("WINDOW SIZE " + window);
            out.println("-------------");

            for (int start = 0; start <= blocks.size() - window; start++) {
                for (int f = 0; f < formats.length; f++) {
                    Format format = formats[f];
                    byte[] pattern = buildPattern(blocks, start, window, format.kind);
                    int hitCount = countHits(files, pattern);

                    if (hitCount == 0) {
                        continue;
                    }

                    Block first = blocks.get(start);
                    Block last = blocks.get(start + window - 1);

                    out.println();
                    out.println("Window blocks " + start + "-" + (start + window - 1));
                    out.println("Addresses " + hex(first.address, 6) + " through " + hex(last.address, 6));
                    out.println("Format: " + format.name);
                    out.println("Hits: " + hitCount);
                    out.println("Bytes: " + bytesToHex(pattern));
                    printHits(out, files, pattern, 40);
                }
            }
        }
    }

    private static byte[] buildPattern(List<Block> blocks, int start, int count, int kind) {
        ByteBuilder out = new ByteBuilder();

        for (int i = start; i < start + count; i++) {
            Block b = blocks.get(i);

            int localStart = b.address & 0xFFFF;
            int localEnd = (b.address + b.length) & 0xFFFF;
            int fileStart = b.address;
            int fileEnd = b.address + b.length;
            int ramStart = RAM_BASE + localStart;
            int ramEnd = RAM_BASE + localEnd;

            switch (kind) {
                case Format.LOCAL16_START_UINT16_LENGTH:
                    out.add(uint16LE(localStart));
                    out.add(uint16LE(b.length));
                    break;

                case Format.UINT16_LENGTH_LOCAL16_START:
                    out.add(uint16LE(b.length));
                    out.add(uint16LE(localStart));
                    break;

                case Format.LOCAL16_START_LOCAL16_END:
                    out.add(uint16LE(localStart));
                    out.add(uint16LE(localEnd));
                    break;

                case Format.LOCAL16_END_LOCAL16_START:
                    out.add(uint16LE(localEnd));
                    out.add(uint16LE(localStart));
                    break;

                case Format.LOCAL16_START_UINT16_LENGTH_DIV4:
                    out.add(uint16LE(localStart));
                    out.add(uint16LE(b.length / 4));
                    break;

                case Format.UINT16_LENGTH_DIV4_LOCAL16_START:
                    out.add(uint16LE(b.length / 4));
                    out.add(uint16LE(localStart));
                    break;

                case Format.FILE32_START_UINT32_LENGTH:
                    out.add(int32LE(fileStart));
                    out.add(int32LE(b.length));
                    break;

                case Format.FILE32_START_FILE32_END:
                    out.add(int32LE(fileStart));
                    out.add(int32LE(fileEnd));
                    break;

                case Format.RAM32_START_UINT32_LENGTH:
                    out.add(int32LE(ramStart));
                    out.add(int32LE(b.length));
                    break;

                case Format.RAM32_START_RAM32_END:
                    out.add(int32LE(ramStart));
                    out.add(int32LE(ramEnd));
                    break;

                default:
                    throw new RuntimeException("Unknown format: " + kind);
            }
        }

        return out.toByteArray();
    }

    private static List<Block> readBlocks(File excel) throws Exception {
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
            String addressText = getCellText(row, 1).trim();
            String lenText = getCellText(row, 2).trim();

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

    private static void printBlocks(PrintWriter out, List<Block> blocks) {
        out.println("BLOCKS");
        out.println("------");

        for (int i = 0; i < blocks.size(); i++) {
            Block b = blocks.get(i);

            out.println(
                    padLeft(String.valueOf(i), 2)
                            + " start " + hex(b.address, 6)
                            + " local " + hex(b.address & 0xFFFF, 4)
                            + " len " + hex(b.length, 4)
                            + " end " + hex(b.address + b.length, 6)
                            + " localEnd " + hex((b.address + b.length) & 0xFFFF, 4)
                            + " rows " + b.startExcelRow + "-" + b.endExcelRow
                            + note(b)
            );
        }

        out.println();
    }

    private static String note(Block b) {
        if (b.address == EXPANDED_ADDRESS) {
            return " EXPANDED";
        }

        if (b.address == SHRINK_ADDRESS) {
            return " SHRINK";
        }

        return "";
    }

    private static int countHits(List<File> files, byte[] pattern) throws Exception {
        int count = 0;

        for (int i = 0; i < files.size(); i++) {
            byte[] data = readAll(files.get(i));
            count += findAll(data, pattern).size();
        }

        return count;
    }

    private static void printHits(
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

    private static class Format {
        static final int LOCAL16_START_UINT16_LENGTH = 1;
        static final int UINT16_LENGTH_LOCAL16_START = 2;
        static final int LOCAL16_START_LOCAL16_END = 3;
        static final int LOCAL16_END_LOCAL16_START = 4;
        static final int LOCAL16_START_UINT16_LENGTH_DIV4 = 5;
        static final int UINT16_LENGTH_DIV4_LOCAL16_START = 6;
        static final int FILE32_START_UINT32_LENGTH = 7;
        static final int FILE32_START_FILE32_END = 8;
        static final int RAM32_START_UINT32_LENGTH = 9;
        static final int RAM32_START_RAM32_END = 10;

        String name;
        int kind;

        Format(String name, int kind) {
            this.name = name;
            this.kind = kind;
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