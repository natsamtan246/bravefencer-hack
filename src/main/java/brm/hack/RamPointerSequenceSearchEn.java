package brm.hack;

import java.io.File;
import java.io.FileInputStream;
import java.io.PrintWriter;
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

public class RamPointerSequenceSearchEn {

    private static final String TARGET_FILE = "SC01/006/0.4";

    private static final int MOVED_START_ADDRESS = 0x608E0;
    private static final int MOVED_END_ADDRESS_EXCLUSIVE = 0x60AA4;
    private static final int INSERT_DELTA = 0x32;

    /*
     * Based on values like EC 17 11 80, which is 0x801117EC.
     *
     * For file address 0x0617EC:
     * local low16 = 0x17EC
     * RAM pointer = 0x80110000 + 0x17EC = 0x801117EC
     */
    private static final int RAM_BASE = 0x80110000;

    private static final int MIN_RUN = 2;
    private static final int CONTEXT_BYTES = 32;

    public static void main(String[] args) throws Exception {
        String splitdir = Conf.desktop + "brmen/";
        File excel = new File(Conf.desktop + "brm-en.xlsx");
        File targetFile = new File(splitdir, TARGET_FILE.replace("\\", "/"));
        File report = new File(Conf.desktop + "ram-pointer-sequence-search-en.txt");

        if (!targetFile.exists()) {
            throw new RuntimeException("Target split file not found: " + targetFile.getAbsolutePath());
        }

        List<Block> blocks = readTargetBlocks(excel);
        byte[] data = readAll(targetFile);

        Map<Integer, List<Block>> pointerToBlocks = new LinkedHashMap<Integer, List<Block>>();

        for (Block block : blocks) {
            int local = block.address & 0xFFFF;
            int pointer = RAM_BASE + local;

            List<Block> list = pointerToBlocks.get(Integer.valueOf(pointer));

            if (list == null) {
                list = new ArrayList<Block>();
                pointerToBlocks.put(Integer.valueOf(pointer), list);
            }

            list.add(block);
        }

        List<Run> runs = findRuns(data, pointerToBlocks);

        Collections.sort(runs, new Comparator<Run>() {
            @Override
            public int compare(Run a, Run b) {
                if (a.touchesMovedRange != b.touchesMovedRange) {
                    return a.touchesMovedRange ? -1 : 1;
                }

                if (a.entries.size() != b.entries.size()) {
                    return b.entries.size() - a.entries.size();
                }

                return a.startOffset - b.startOffset;
            }
        });

        writeReport(report, data, blocks, runs);

        System.out.println("RAM pointer sequence report written to:");
        System.out.println(report.getAbsolutePath());
    }

    private static List<Block> readTargetBlocks(File excel) throws Exception {
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

            if (!fileName.isEmpty() && !address.isEmpty()) {
                finishBlock(current, blocks);

                current = new Block();
                current.fileName = fileName.replace("\\", "/");
                current.addressText = address;
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

        if (!TARGET_FILE.equalsIgnoreCase(block.fileName)) {
            return;
        }

        blocks.add(block);
    }

    private static List<Run> findRuns(byte[] data, Map<Integer, List<Block>> pointerToBlocks) {
        List<Run> runs = new ArrayList<Run>();

        int[] strides = new int[] {4, 8, 12, 16};

        for (int s = 0; s < strides.length; s++) {
            int stride = strides[s];

            for (int start = 0; start <= data.length - 4; start++) {
                int prev = start - stride;

                if (prev >= 0 && prev + 3 < data.length) {
                    int prevValue = readInt32LE(data, prev);

                    if (pointerToBlocks.containsKey(Integer.valueOf(prevValue))) {
                        continue;
                    }
                }

                Run run = new Run();
                run.startOffset = start;
                run.stride = stride;

                int pos = start;

                while (pos + 3 < data.length) {
                    int value = readInt32LE(data, pos);
                    List<Block> matchedBlocks = pointerToBlocks.get(Integer.valueOf(value));

                    if (matchedBlocks == null) {
                        break;
                    }

                    Entry entry = new Entry();
                    entry.fileOffset = pos;
                    entry.pointer = value;
                    entry.blocks = matchedBlocks;

                    Block block = matchedBlocks.get(0);
                    entry.inMovedRange = block.address >= MOVED_START_ADDRESS
                            && block.address < MOVED_END_ADDRESS_EXCLUSIVE;

                    if (entry.inMovedRange) {
                        run.touchesMovedRange = true;
                    }

                    run.entries.add(entry);
                    pos += stride;
                }

                if (run.entries.size() >= MIN_RUN) {
                    runs.add(run);
                }
            }
        }

        return runs;
    }

    private static void writeReport(
            File report,
            byte[] data,
            List<Block> blocks,
            List<Run> runs
    ) throws Exception {
        PrintWriter out = new PrintWriter(report, "UTF-8");

        out.println("Brave Fencer Musashi English RAM Pointer Sequence Search");
        out.println("========================================================");
        out.println();
        out.println("This report does not modify anything.");
        out.println();
        out.println("Target file: " + TARGET_FILE);
        out.println("Known script blocks in target file: " + blocks.size());
        out.println("RAM base: " + hex(RAM_BASE, 8));
        out.println();
        out.println("Moved original range:");
        out.println("  Full addresses: " + hex(MOVED_START_ADDRESS, 6)
                + " through before " + hex(MOVED_END_ADDRESS_EXCLUSIVE, 6));
        out.println("  RAM pointers:    " + hex(RAM_BASE + (MOVED_START_ADDRESS & 0xFFFF), 8)
                + " through before " + hex(RAM_BASE + (MOVED_END_ADDRESS_EXCLUSIVE & 0xFFFF), 8));
        out.println("  Insert delta:    +" + INSERT_DELTA + " decimal / +" + hexNoPrefix(INSERT_DELTA, 4));
        out.println();

        out.println("KNOWN BLOCKS AROUND MOVED RANGE");
        out.println("-------------------------------");

        for (Block block : blocks) {
            if (block.address >= MOVED_START_ADDRESS - 0x100
                    && block.address <= MOVED_END_ADDRESS_EXCLUSIVE + 0x100) {
                int pointer = RAM_BASE + (block.address & 0xFFFF);

                out.println(
                        "Address " + hex(block.address, 6)
                                + " pointer " + hex(pointer, 8)
                                + " bytes " + pointerBytes(pointer)
                                + " rows " + block.startExcelRow + "-" + block.endExcelRow
                                + " len " + block.originalLen
                );
            }
        }

        out.println();

        out.println("HIGH-PRIORITY RUNS TOUCHING MOVED RANGE");
        out.println("---------------------------------------");

        int highCount = 0;

        for (Run run : runs) {
            if (!run.touchesMovedRange) {
                continue;
            }

            highCount++;
            printRun(out, data, run);
        }

        if (highCount == 0) {
            out.println("No RAM pointer runs touched the moved range.");
        }

        out.println();

        out.println("GENERAL RUNS");
        out.println("------------");
        out.println("These are other possible RAM pointer tables.");
        out.println();

        int printed = 0;

        for (Run run : runs) {
            if (run.touchesMovedRange) {
                continue;
            }

            if (printed >= 120) {
                out.println("Stopped after 120 general runs.");
                break;
            }

            printed++;
            printRun(out, data, run);
        }

        if (printed == 0) {
            out.println("No general RAM pointer runs found.");
        }

        out.println();
        out.println("DIRECT MOVED POINTER HITS");
        out.println("-------------------------");
        out.println("This section searches each moved block pointer individually.");

        for (Block block : blocks) {
            if (block.address < MOVED_START_ADDRESS
                    || block.address >= MOVED_END_ADDRESS_EXCLUSIVE) {
                continue;
            }

            int pointer = RAM_BASE + (block.address & 0xFFFF);
            byte[] pattern = pointerToBytes(pointer);
            List<Integer> hits = findAll(data, pattern);

            out.println();
            out.println("Block " + hex(block.address, 6)
                    + " pointer " + hex(pointer, 8)
                    + " bytes " + bytesToHex(pattern)
                    + " rows " + block.startExcelRow + "-" + block.endExcelRow
                    + " hits " + hits.size());

            for (Integer hit : hits) {
                out.println("  hit at " + hex(hit.intValue(), 8));
                out.println("    " + context(data, hit.intValue(), pattern.length));
            }
        }

        out.println();
        out.println("NOTES");
        out.println("-----");
        out.println("- This searches for 32-bit little-endian RAM pointers like E0 08 11 80.");
        out.println("- Assumed formula: pointer = 0x80110000 + (address & 0xFFFF).");
        out.println("- If direct moved pointer hits exist, the next test is to patch those pointers by +0x32.");
        out.println("- If there are no moved hits, the missing references may be command-local, calculated, or held in another file.");
        out.println();

        out.close();
    }

    private static void printRun(PrintWriter out, byte[] data, Run run) {
        out.println("Possible RAM pointer run at file offset " + hex(run.startOffset, 8)
                + " stride " + run.stride
                + " matches " + run.entries.size()
                + (run.touchesMovedRange ? "  <-- touches moved range" : ""));

        out.println("Context:");
        out.println("  " + context(data, run.startOffset, run.stride * run.entries.size()));

        out.println("Entries:");

        for (Entry entry : run.entries) {
            Block block = entry.blocks.get(0);

            out.println("  file " + hex(entry.fileOffset, 8)
                    + " pointer " + hex(entry.pointer, 8)
                    + " bytes " + pointerBytes(entry.pointer)
                    + " -> address " + hex(block.address, 6)
                    + " rows " + block.startExcelRow + "-" + block.endExcelRow
                    + " len " + block.originalLen
                    + (entry.inMovedRange ? "  MOVED" : ""));
        }

        out.println();
    }

    private static int readInt32LE(byte[] data, int offset) {
        int b0 = data[offset] & 0xFF;
        int b1 = data[offset + 1] & 0xFF;
        int b2 = data[offset + 2] & 0xFF;
        int b3 = data[offset + 3] & 0xFF;

        return b0 | (b1 << 8) | (b2 << 16) | (b3 << 24);
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

    private static byte[] pointerToBytes(int pointer) {
        return new byte[] {
                (byte) (pointer & 0xFF),
                (byte) ((pointer >> 8) & 0xFF),
                (byte) ((pointer >> 16) & 0xFF),
                (byte) ((pointer >> 24) & 0xFF)
        };
    }

    private static String pointerBytes(int pointer) {
        return bytesToHex(pointerToBytes(pointer));
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

    private static String context(byte[] data, int hit, int len) {
        int start = Math.max(0, hit - CONTEXT_BYTES);
        int end = Math.min(data.length, hit + len + CONTEXT_BYTES);

        StringBuilder sb = new StringBuilder();

        for (int i = start; i < end; i++) {
            if (i > start) {
                sb.append(' ');
            }

            if (i == hit) {
                sb.append("[");
            }

            sb.append(String.format("%02X", data[i] & 0xFF));

            if (i == hit + len - 1) {
                sb.append("]");
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

    private static String hex(int value, int digits) {
        String s = Integer.toHexString(value).toUpperCase();

        while (s.length() < digits) {
            s = "0" + s;
        }

        return "0x" + s;
    }

    private static String hexNoPrefix(int value, int digits) {
        String s = Integer.toHexString(value).toUpperCase();

        while (s.length() < digits) {
            s = "0" + s;
        }

        return s;
    }

    private static class Block {
        String fileName;
        String addressText;
        int address;
        int startExcelRow;
        int endExcelRow;
        int originalLen = -1;
    }

    private static class Entry {
        int fileOffset;
        int pointer;
        List<Block> blocks;
        boolean inMovedRange;
    }

    private static class Run {
        int startOffset;
        int stride;
        boolean touchesMovedRange;
        List<Entry> entries = new ArrayList<Entry>();
    }
}