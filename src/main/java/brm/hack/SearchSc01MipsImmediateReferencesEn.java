package brm.hack;

import java.io.File;
import java.io.FileInputStream;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import brm.Conf;

public class SearchSc01MipsImmediateReferencesEn {

    /*
     * Balanced expansion test:
     *
     * Expanded text starts at local 0x0890.
     * Following blocks from local 0x08E0 through before 0x0AA4 moved by +0x32.
     */
    private static final int MOVED_START_LOCAL = 0x08E0;
    private static final int MOVED_END_LOCAL_EXCLUSIVE = 0x0AA4;
    private static final int DELTA = 0x32;

    private static final int MAX_HITS_TO_PRINT = 300;
    private static final int CONTEXT_WORDS = 6;

    private static final Target[] TARGETS = new Target[] {
            new Target(0x0890, "expanded block start 0x060890"),
            new Target(0x08E0, "first moved block 0x0608E0"),
            new Target(0x092C, "moved block 0x06092C"),
            new Target(0x0978, "moved block 0x060978"),
            new Target(0x09C4, "moved block 0x0609C4"),
            new Target(0x09FC, "moved block 0x0609FC"),
            new Target(0x0AA4, "shrink block start 0x060AA4"),
            new Target(0x0BA0, "first aligned-after-shrink block 0x060BA0")
    };

    private static final String[] REG_NAMES = new String[] {
            "zero", "at", "v0", "v1",
            "a0", "a1", "a2", "a3",
            "t0", "t1", "t2", "t3",
            "t4", "t5", "t6", "t7",
            "s0", "s1", "s2", "s3",
            "s4", "s5", "s6", "s7",
            "t8", "t9", "k0", "k1",
            "gp", "sp", "fp", "ra"
    };

    public static void main(String[] args) throws Exception {
        File sc01Dir = new File(Conf.desktop + "brmen/SC01/");
        File report = new File(Conf.desktop + "sc01-mips-immediate-search-en.txt");

        if (!sc01Dir.exists()) {
            throw new RuntimeException("SC01 directory not found: " + sc01Dir.getAbsolutePath());
        }

        List<File> files = new ArrayList<File>();
        collectFiles(sc01Dir, files);

        Collections.sort(files, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return rel(a).compareTo(rel(b));
            }
        });

        List<Hit> hits = new ArrayList<Hit>();

        for (int i = 0; i < files.size(); i++) {
            File file = files.get(i);
            byte[] data = readAll(file);

            /*
             * MIPS instructions should be 4-byte aligned.
             * Search aligned words only to reduce false positives.
             */
            for (int pos = 0; pos + 3 < data.length; pos += 4) {
                int word = readInt32LE(data, pos);
                int imm = word & 0xFFFF;

                boolean exactTarget = findTarget(imm) != null;
                boolean inMovedRange =
                        imm >= MOVED_START_LOCAL && imm < MOVED_END_LOCAL_EXCLUSIVE;

                if (!exactTarget && !inMovedRange) {
                    continue;
                }

                Hit hit = new Hit();
                hit.file = file;
                hit.relativeFile = rel(file);
                hit.data = data;
                hit.fileOffset = pos;
                hit.word = word;
                hit.imm = imm;
                hit.exactTarget = exactTarget;
                hit.inMovedRange = inMovedRange;
                hit.target = findTarget(imm);

                hits.add(hit);
            }
        }

        Collections.sort(hits, new Comparator<Hit>() {
            @Override
            public int compare(Hit a, Hit b) {
                int sa = a.score();
                int sb = b.score();

                if (sa != sb) {
                    return sb - sa;
                }

                int c = a.relativeFile.compareTo(b.relativeFile);

                if (c != 0) {
                    return c;
                }

                return a.fileOffset - b.fileOffset;
            }
        });

        PrintWriter out = new PrintWriter(report, "UTF-8");

        out.println("Brave Fencer Musashi English SC01 MIPS Immediate Search");
        out.println("======================================================");
        out.println();
        out.println("This report does not modify anything.");
        out.println();
        out.println("Search root:");
        out.println("  " + sc01Dir.getAbsolutePath());
        out.println();
        out.println("Moved local range:");
        out.println("  0x08E0 through before 0x0AA4");
        out.println("  delta +0x32");
        out.println();
        out.println("What this searches:");
        out.println("  4-byte-aligned MIPS instructions whose immediate value equals");
        out.println("  one of the nearby text offsets or falls inside the moved range.");
        out.println();

        printTargets(out);
        printLikelyLoadImmediateHits(out, hits);
        printSc01006Hits(out, hits);
        printAllTopHits(out, hits);

        out.println();
        out.println("PATCH INTERPRETATION");
        out.println("--------------------");
        out.println("The best patch candidates are instructions like:");
        out.println("  addiu a?, zero, 0x08E0");
        out.println("  ori   a?, zero, 0x08E0");
        out.println();
        out.println("For the balanced expansion test, immediates inside 0x08E0 through before 0x0AA4");
        out.println("would probably need +0x32.");
        out.println();
        out.println("Examples:");
        out.println("  0x08E0 -> 0x0912");
        out.println("  0x092C -> 0x095E");
        out.println("  0x0978 -> 0x09AA");
        out.println("  0x09C4 -> 0x09F6");
        out.println("  0x09FC -> 0x0A2E");
        out.println();
        out.println("Do not patch yet. Upload this report first.");

        out.close();

        System.out.println("SC01 MIPS immediate search written to:");
        System.out.println(report.getAbsolutePath());
    }

    private static void printTargets(PrintWriter out) {
        out.println("TARGET IMMEDIATES");
        out.println("-----------------");

        for (int i = 0; i < TARGETS.length; i++) {
            Target t = TARGETS[i];

            out.println("  " + hex(t.value, 4) + " " + t.note
                    + patchNote(t.value));
        }

        out.println();
    }

    private static String patchNote(int value) {
        if (value >= MOVED_START_LOCAL && value < MOVED_END_LOCAL_EXCLUSIVE) {
            return " would become " + hex(value + DELTA, 4);
        }

        return "";
    }

    private static void printLikelyLoadImmediateHits(PrintWriter out, List<Hit> hits) {
        out.println();
        out.println("LIKELY LOAD-IMMEDIATE HITS");
        out.println("--------------------------");
        out.println("These are the most interesting: addiu/ori with source register zero.");
        out.println();

        int printed = 0;

        for (int i = 0; i < hits.size(); i++) {
            Hit hit = hits.get(i);

            if (!hit.isLikelyLoadImmediate()) {
                continue;
            }

            printHit(out, hit);
            printed++;

            if (printed >= MAX_HITS_TO_PRINT) {
                out.println("Stopped after " + MAX_HITS_TO_PRINT + " hits.");
                break;
            }
        }

        if (printed == 0) {
            out.println("No likely load-immediate hits found.");
            out.println();
        }
    }

    private static void printSc01006Hits(PrintWriter out, List<Hit> hits) {
        out.println();
        out.println("SC01/006/0.4 HITS");
        out.println("-----------------");
        out.println("These are important because your edited text is in SC01/006/0.4.");
        out.println();

        int printed = 0;

        for (int i = 0; i < hits.size(); i++) {
            Hit hit = hits.get(i);

            if (!"/SC01/006/0.4".equalsIgnoreCase(hit.relativeFile)
                    && !"SC01/006/0.4".equalsIgnoreCase(hit.relativeFile)) {
                continue;
            }

            printHit(out, hit);
            printed++;

            if (printed >= MAX_HITS_TO_PRINT) {
                out.println("Stopped after " + MAX_HITS_TO_PRINT + " hits.");
                break;
            }
        }

        if (printed == 0) {
            out.println("No SC01/006/0.4 hits found.");
            out.println();
        }
    }

    private static void printAllTopHits(PrintWriter out, List<Hit> hits) {
        out.println();
        out.println("TOP HITS OVERALL");
        out.println("----------------");
        out.println("Sorted by likely usefulness.");
        out.println();

        int printed = 0;

        for (int i = 0; i < hits.size(); i++) {
            Hit hit = hits.get(i);

            printHit(out, hit);
            printed++;

            if (printed >= MAX_HITS_TO_PRINT) {
                out.println("Stopped after " + MAX_HITS_TO_PRINT + " hits.");
                break;
            }
        }

        if (printed == 0) {
            out.println("No hits found.");
            out.println();
        }
    }

    private static void printHit(PrintWriter out, Hit hit) {
        int newImm = hit.imm + DELTA;

        out.println("File: " + hit.relativeFile);
        out.println("Offset: " + hex(hit.fileOffset, 8));
        out.println("Word:   " + hex(hit.word, 8)
                + " bytes " + bytesToHex(int32LE(hit.word)));
        out.println("Insn:   " + disasm(hit.word));
        out.println("Imm:    " + hex(hit.imm, 4)
                + (hit.target != null ? " " + hit.target.note : "")
                + patchNote(hit.imm));
        out.println("Score:  " + hit.score());

        if (hit.inMovedRange) {
            int patchedWord = (hit.word & 0xFFFF0000) | (newImm & 0xFFFF);

            out.println("Patch candidate:");
            out.println("  word " + hex(hit.word, 8)
                    + " -> " + hex(patchedWord, 8));
            out.println("  bytes " + bytesToHex(int32LE(hit.word))
                    + " -> " + bytesToHex(int32LE(patchedWord)));
        }

        out.println("Nearby instructions:");
        out.println(disasmContext(hit.data, hit.fileOffset));

        out.println();
    }

    private static String disasmContext(byte[] data, int hitOffset) {
        StringBuilder sb = new StringBuilder();

        int start = hitOffset - CONTEXT_WORDS * 4;
        int end = hitOffset + CONTEXT_WORDS * 4;

        if (start < 0) {
            start = 0;
        }

        if (end + 3 >= data.length) {
            end = data.length - 4;
        }

        for (int pos = start; pos <= end; pos += 4) {
            int word = readInt32LE(data, pos);

            sb.append("  ");

            if (pos == hitOffset) {
                sb.append("=> ");
            } else {
                sb.append("   ");
            }

            sb.append(hex(pos, 8));
            sb.append("  ");
            sb.append(hex(word, 8));
            sb.append("  ");
            sb.append(disasm(word));
            sb.append("\n");
        }

        return sb.toString();
    }

    private static String disasm(int word) {
        int op = (word >>> 26) & 0x3F;
        int rs = (word >>> 21) & 0x1F;
        int rt = (word >>> 16) & 0x1F;
        int rd = (word >>> 11) & 0x1F;
        int funct = word & 0x3F;
        int imm = word & 0xFFFF;
        short simm = (short) imm;

        if (word == 0) {
            return "nop";
        }

        if (op == 0) {
            if (funct == 0x21) {
                return "addu " + reg(rd) + ", " + reg(rs) + ", " + reg(rt);
            }

            if (funct == 0x23) {
                return "subu " + reg(rd) + ", " + reg(rs) + ", " + reg(rt);
            }

            if (funct == 0x08) {
                return "jr " + reg(rs);
            }

            return "SPECIAL funct=" + hex(funct, 2)
                    + " rs=" + reg(rs)
                    + " rt=" + reg(rt)
                    + " rd=" + reg(rd);
        }

        switch (op) {
            case 0x02:
                return "j " + hex(word & 0x03FFFFFF, 7);

            case 0x03:
                return "jal " + hex(word & 0x03FFFFFF, 7);

            case 0x04:
                return "beq " + reg(rs) + ", " + reg(rt) + ", " + hex(imm, 4);

            case 0x05:
                return "bne " + reg(rs) + ", " + reg(rt) + ", " + hex(imm, 4);

            case 0x08:
                return "addi " + reg(rt) + ", " + reg(rs) + ", " + simmText(simm);

            case 0x09:
                return "addiu " + reg(rt) + ", " + reg(rs) + ", " + simmText(simm);

            case 0x0A:
                return "slti " + reg(rt) + ", " + reg(rs) + ", " + simmText(simm);

            case 0x0B:
                return "sltiu " + reg(rt) + ", " + reg(rs) + ", " + simmText(simm);

            case 0x0C:
                return "andi " + reg(rt) + ", " + reg(rs) + ", " + hex(imm, 4);

            case 0x0D:
                return "ori " + reg(rt) + ", " + reg(rs) + ", " + hex(imm, 4);

            case 0x0E:
                return "xori " + reg(rt) + ", " + reg(rs) + ", " + hex(imm, 4);

            case 0x0F:
                return "lui " + reg(rt) + ", " + hex(imm, 4);

            case 0x20:
                return "lb " + reg(rt) + ", " + simmText(simm) + "(" + reg(rs) + ")";

            case 0x21:
                return "lh " + reg(rt) + ", " + simmText(simm) + "(" + reg(rs) + ")";

            case 0x23:
                return "lw " + reg(rt) + ", " + simmText(simm) + "(" + reg(rs) + ")";

            case 0x24:
                return "lbu " + reg(rt) + ", " + simmText(simm) + "(" + reg(rs) + ")";

            case 0x25:
                return "lhu " + reg(rt) + ", " + simmText(simm) + "(" + reg(rs) + ")";

            case 0x28:
                return "sb " + reg(rt) + ", " + simmText(simm) + "(" + reg(rs) + ")";

            case 0x29:
                return "sh " + reg(rt) + ", " + simmText(simm) + "(" + reg(rs) + ")";

            case 0x2B:
                return "sw " + reg(rt) + ", " + simmText(simm) + "(" + reg(rs) + ")";

            default:
                return "op=" + hex(op, 2)
                        + " rs=" + reg(rs)
                        + " rt=" + reg(rt)
                        + " imm=" + hex(imm, 4);
        }
    }

    private static String simmText(short value) {
        int unsigned = value & 0xFFFF;

        if (value < 0) {
            return String.valueOf(value) + " / " + hex(unsigned, 4);
        }

        return hex(unsigned, 4);
    }

    private static String reg(int index) {
        return REG_NAMES[index];
    }

    private static Target findTarget(int value) {
        for (int i = 0; i < TARGETS.length; i++) {
            if (TARGETS[i].value == value) {
                return TARGETS[i];
            }
        }

        return null;
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

    private static int readInt32LE(byte[] data, int offset) {
        int b0 = data[offset] & 0xFF;
        int b1 = data[offset + 1] & 0xFF;
        int b2 = data[offset + 2] & 0xFF;
        int b3 = data[offset + 3] & 0xFF;

        return b0 | (b1 << 8) | (b2 << 16) | (b3 << 24);
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

    private static class Target {
        int value;
        String note;

        Target(int value, String note) {
            this.value = value;
            this.note = note;
        }
    }

    private static class Hit {
        File file;
        String relativeFile;
        byte[] data;
        int fileOffset;
        int word;
        int imm;
        boolean exactTarget;
        boolean inMovedRange;
        Target target;

        boolean isInTargetFile() {
            return "/SC01/006/0.4".equalsIgnoreCase(relativeFile)
                    || "SC01/006/0.4".equalsIgnoreCase(relativeFile);
        }

        boolean isLikelyLoadImmediate() {
            int op = (word >>> 26) & 0x3F;
            int rs = (word >>> 21) & 0x1F;

            return (op == 0x09 || op == 0x0D) && rs == 0;
        }

        int score() {
            int score = 0;

            if (isInTargetFile()) {
                score += 100;
            }

            if (isLikelyLoadImmediate()) {
                score += 100;
            }

            int op = (word >>> 26) & 0x3F;
            int rs = (word >>> 21) & 0x1F;

            if (op == 0x09) {
                score += 40;
            }

            if (op == 0x0D) {
                score += 35;
            }

            if (rs == 0) {
                score += 20;
            }

            if (exactTarget) {
                score += 20;
            }

            if (inMovedRange) {
                score += 20;
            }

            /*
             * Many PS1 files have executable code around this area.
             */
            if (fileOffset >= 0x20000 && fileOffset < 0x30000) {
                score += 15;
            }

            return score;
        }
    }
}