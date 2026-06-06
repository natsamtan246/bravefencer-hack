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

public class LocalPointerSequenceSearchEn {

    private static final String TARGET_FILE = "SC01/006/0.4";

    /*
     * From the balanced expansion test:
     *
     * Expanded block:
     *   0x60890 original length 0x50
     *
     * First block after expanded original area:
     *   0x60890 + 0x50 = 0x608E0
     *
     * Shrink block:
     *   0x60AA4
     *
     * Everything from 0x608E0 through before 0x60AA4 was shifted by +0x32.
     */
    private static final int MOVED_START_ADDRESS = 0x608E0;
    private static final int MOVED_END_ADDRESS_EXCLUSIVE = 0x60AA4;
    private static final int INSERT_DELTA = 0x32;

    private static final int MIN_RUN = 3;
    private static final int CONTEXT_BYTES = 24;
    private static final int MAX_GENERAL_RUNS_TO_PRINT = 120;

    public static void main(String[] args) throws Exception {
        String splitdir = Conf.desktop + "brmen/";
        File excel = new File(Conf.desktop + "brm-en.xlsx");
        File targetFile = new File(splitdir, TARGET_FILE.replace("\\", "/"));
        File report = new File(Conf.desktop + "local-pointer-sequence-search-en.txt");

        if (!targetFile.exists()) {
            throw new RuntimeException("Target split file not found: " + targetFile.getAbsolutePath());
        }

        List<Block> blocks = readTargetBlocks(excel);
        byte[] data = readAll(targetFile);

        Map<Integer, List<Block>> localOffsetToBlocks = new LinkedHashMap<Integer, List<Block>>();

        for (Block block : blocks) {
            int localOffset = block.address & 0xFFFF;

            List<Block> list = localOffsetToBlocks.get(Integer.valueOf(localOffset));

            if (list == null) {
                list = new ArrayList<Block>();
                localOffsetToBlocks.put(Integer.valueOf(localOffset), list);
            }

            list.add(block);
        }

        List<Run> runs = findRuns(data, localOffsetToBlocks);

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

        System.out.println("Local pointer sequence report written to:");
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

    private static List<Run> findRuns(byte[] data, Map<Integer, List<Block>> localOffsetToBlocks) {
        List<Run> runs = new ArrayList<Run>();

        int[] strides = new int[] {2, 4, 8};

        for (int s = 0; s < strides.length; s++) {
            int stride = strides[s];

            for (int start = 0; start <= data.length - 2; start++) {
                /*
                 * Only keep maximal runs for this stride.
                 * If the previous slot was also a known offset, then this is
                 * just the same run starting one entry later.
                 */
                int prev = start - stride;

                if (prev >= 0 && prev + 1 < data.length) {
                    int prevValue = readUInt16LE(data, prev);

                    if (localOffsetToBlocks.containsKey(Integer.valueOf(prevValue))) {
                        continue;
                    }
                }

                Run run = new Run();
                run.startOffset = start;
                run.stride = stride;

                int pos = start;

                while (pos + 1 < data.length) {
                    int value = readUInt16LE(data, pos);
                    List<Block> matchedBlocks = localOffsetToBlocks.get(Integer.valueOf(value));

                    if (matchedBlocks == null) {
                        break;
                    }

                    Entry entry = new Entry();
                    entry.fileOffset = pos;
                    entry.localOffset = value;
                    entry.blocks = matchedBlocks;
                    entry.inMovedRange = isLocalOffsetInMovedRange(value);

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

        out.println("Brave Fencer Musashi English Local Pointer Sequence Search");
        out.println("==========================================================");
        out.println();
        out.println("This report does not modify anything.");
        out.println();
        out.println("Target file: " + TARGET_FILE);
        out.println("Known script blocks in target file: " + blocks.size());
        out.println("Moved original range:");
        out.println("  Full addresses: " + hex(MOVED_START_ADDRESS, 6)
                + " through before " + hex(MOVED_END_ADDRESS_EXCLUSIVE, 6));
        out.println("  Local offsets:  " + hex(MOVED_START_ADDRESS & 0xFFFF, 4)
                + " through before " + hex(MOVED_END_ADDRESS_EXCLUSIVE & 0xFFFF, 4));
        out.println("  Insert delta:   +" + INSERT_DELTA + " decimal / +" + hexNoPrefix(INSERT_DELTA, 4));
        out.println();

        out.println("KNOWN BLOCKS AROUND MOVED RANGE");
        out.println("-------------------------------");

        for (Block block : blocks) {
            if (block.address >= MOVED_START_ADDRESS - 0x100
                    && block.address <= MOVED_END_ADDRESS_EXCLUSIVE + 0x100) {
                out.println(
                        "Address " + hex(block.address, 6)
                                + " local " + hex(block.address & 0xFFFF, 4)
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
            out.println("No runs touched the moved range.");
        }

        out.println();

        out.println("GENERAL RUNS");
        out.println("------------");
        out.println("These are other possible offset tables. They may include false positives.");
        out.println();

        int printed = 0;

        for (Run run : runs) {
            if (run.touchesMovedRange) {
                continue;
            }

            if (printed >= MAX_GENERAL_RUNS_TO_PRINT) {
                out.println("Stopped after " + MAX_GENERAL_RUNS_TO_PRINT + " general runs.");
                break;
            }

            printed++;
            printRun(out, data, run);
        }

        if (printed == 0) {
            out.println("No general runs found.");
        }

        out.println();
        out.println("NOTES");
        out.println("-----");
        out.println("- This searches for little-endian 16-bit local offsets.");
        out.println("- A single match is not meaningful.");
        out.println("- A sequence/run of 3 or more known script block offsets is suspicious.");
        out.println("- Stride 2 means packed 16-bit offsets.");
        out.println("- Stride 4 means one pointer every 4 bytes, common for small table entries.");
        out.println("- Stride 8 means one pointer every 8 bytes, in case entries have larger records.");
        out.println("- High-priority runs are the ones containing offsets in the range that broke after shifting.");
        out.println("- If we find the correct table, the next test is to add 0x32 to offsets from 0x08E0 through before 0x0AA4.");
        out.println();

        out.close();
    }

    private static void printRun(PrintWriter out, byte[] data, Run run) {
        out.println("Possible run at file offset " + hex(run.startOffset, 8)
                + " stride " + run.stride
                + " matches " + run.entries.size()
                + (run.touchesMovedRange ? "  <-- touches moved range" : ""));

        out.println("Context:");
        out.println("  " + context(data, run.startOffset, run.stride * run.entries.size()));

        out.println("Entries:");

        for (Entry entry : run.entries) {
            Block block = entry.blocks.get(0);

            out.println("  file " + hex(entry.fileOffset, 8)
                    + " value " + hex(entry.localOffset, 4)
                    + " -> address " + hex(block.address, 6)
                    + " rows " + block.startExcelRow + "-" + block.endExcelRow
                    + " len " + block.originalLen
                    + (entry.inMovedRange ? "  MOVED" : ""));
        }

        out.println();
    }

    private static boolean isLocalOffsetInMovedRange(int localOffset) {
        int movedStart = MOVED_START_ADDRESS & 0xFFFF;
        int movedEnd = MOVED_END_ADDRESS_EXCLUSIVE & 0xFFFF;

        return localOffset >= movedStart && localOffset < movedEnd;
    }

    private static int readUInt16LE(byte[] data, int offset) {
        int lo = data[offset] & 0xFF;
        int hi = data[offset + 1] & 0xFF;

        return lo | (hi << 8);
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

        /*
         * Address cells like 60890 are hex addresses.
         */
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
        int localOffset;
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