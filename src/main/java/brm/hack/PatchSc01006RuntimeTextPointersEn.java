package brm.hack;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;

import brm.Conf;

public class PatchSc01006RuntimeTextPointersEn {

    /*
     * Patches the real runtime textbox pointer table found by no$psx + search report.
     *
     * Target:
     *   C:\Users\Administrator\Desktop\brmen\SC01\006\0.4
     *
     * Runtime base:
     *   0x80128158
     *
     * Found table offsets in SC01/006/0.4:
     *   0x93E04 -> 0x801889E8  first Musashi / expanded block
     *   0x93EBC -> 0x80188A38  Steward Ribson line
     *   0x93EDC -> 0x80188A84  next Musashi/Geezer line
     *   0x93EF4 -> 0x80188AD0  following line 1
     *   0x93F0C -> 0x80188B1C  following line 2
     *   0x93F24 -> 0x80188B54  following line 3
     *   0x93F3C -> 0x80188BFC  shrink block
     *   0x93F5C -> 0x80188CF8  first after-shrink block
     *   0x93F8C -> 0x80188DD4  later known block
     *
     * For the real balanced expansion:
     *   expanded block at 0x60890 grows by +0x32 bytes
     *   middle blocks through the shrink block shift forward by +0x32
     *   first after-shrink block returns to its old address
     *
     * Run this AFTER the text expansion/repacking step has shifted the data,
     * but BEFORE rebuilding the ISO.
     */

    private static final String TARGET_RELATIVE = "SC01/006/0.4";

    private static final int RUNTIME_BASE = 0x80128158;

    /*
     * Change this to 0x04 only if you are testing the tiny +4 build.
     * For the real current expansion, keep it at 0x32.
     */
    private static final int DELTA = 0x32;

    private static final Entry[] ENTRIES = new Entry[] {
            /*
             * First block starts at the same place. It grew, but its start pointer
             * does not move.
             */
            new Entry("first Musashi / expanded block", 0x93E04, 0x060890, 0x060890, false),

            /*
             * These are between the expanded block and the shortened block.
             * Their physical starts moved forward by DELTA.
             */
            new Entry("Steward Ribson line",            0x93EBC, 0x0608E0, 0x0608E0 + DELTA, true),
            new Entry("next Musashi/Geezer line",      0x93EDC, 0x06092C, 0x06092C + DELTA, true),
            new Entry("following line 1",              0x93EF4, 0x060978, 0x060978 + DELTA, true),
            new Entry("following line 2",              0x93F0C, 0x0609C4, 0x0609C4 + DELTA, true),
            new Entry("following line 3",              0x93F24, 0x0609FC, 0x0609FC + DELTA, true),

            /*
             * The shortened block itself also starts later, because all earlier
             * data was shifted forward into it.
             */
            new Entry("shrink block",                  0x93F3C, 0x060AA4, 0x060AA4 + DELTA, true),

            /*
             * After the shrink block, the total file layout is balanced back to
             * the old positions.
             */
            new Entry("first after-shrink block",      0x93F5C, 0x060BA0, 0x060BA0, false),
            new Entry("later known large block",       0x93F8C, 0x060C7C, 0x060C7C, false)
    };

    public static void main(String[] args) throws Exception {
        File splitRoot = new File(Conf.desktop + "brmen/");
        File target = new File(splitRoot, TARGET_RELATIVE.replace("/", File.separator));

        if (!target.exists()) {
            throw new RuntimeException("Missing target file: " + target.getAbsolutePath());
        }

        File report = new File(Conf.desktop + "real-runtime-text-pointer-patch-en.txt");

        PrintWriter out = new PrintWriter(report, "UTF-8");

        out.println("Patch SC01/006 Runtime Text Pointers - EN");
        out.println("=========================================");
        out.println();
        out.println("Target:");
        out.println("  " + target.getAbsolutePath());
        out.println();
        out.println("Runtime base:");
        out.println("  " + hex(RUNTIME_BASE));
        out.println();
        out.println("Delta:");
        out.println("  " + hex(DELTA) + " / " + DELTA + " decimal");
        out.println();

        byte[] data = readAll(target);

        out.println("File size:");
        out.println("  " + data.length + " bytes");
        out.println();

        backup(target, data, out);

        boolean fatalMismatch = false;

        out.println("Verification before patch:");
        out.println("--------------------------");

        for (int i = 0; i < ENTRIES.length; i++) {
            Entry e = ENTRIES[i];

            int oldPtr = RUNTIME_BASE + e.oldFileOffset;
            int newPtr = RUNTIME_BASE + e.newFileOffset;
            int current = readLe32(data, e.tableOffset);

            out.println(e.name);
            out.println("  table offset: " + hex(e.tableOffset));
            out.println("  old file offset: " + hex(e.oldFileOffset));
            out.println("  new file offset: " + hex(e.newFileOffset));
            out.println("  expected old ptr: " + hex(oldPtr) + " bytes " + bytesToHex(le32(oldPtr)));
            out.println("  expected new ptr: " + hex(newPtr) + " bytes " + bytesToHex(le32(newPtr)));
            out.println("  current ptr:      " + hex(current) + " bytes " + bytesToHex(le32(current)));

            if (current == oldPtr) {
                if (e.patch) {
                    out.println("  status: old value found; will patch");
                } else {
                    out.println("  status: old value found; unchanged entry");
                }
            } else if (current == newPtr) {
                if (e.patch) {
                    out.println("  status: already patched");
                } else {
                    out.println("  status: unchanged entry already correct");
                }
            } else {
                out.println("  status: MISMATCH - not old or expected new");
                fatalMismatch = true;
            }

            out.println();
        }

        if (fatalMismatch) {
            out.println("Aborting because at least one table entry did not match expected old/new values.");
            out.println("This protects the file from being patched at the wrong offset or in the wrong build state.");
            out.close();

            System.out.println("Patch aborted. See report:");
            System.out.println(report.getAbsolutePath());

            return;
        }

        out.println("Applying patch:");
        out.println("---------------");

        for (int i = 0; i < ENTRIES.length; i++) {
            Entry e = ENTRIES[i];

            int oldPtr = RUNTIME_BASE + e.oldFileOffset;
            int newPtr = RUNTIME_BASE + e.newFileOffset;
            int current = readLe32(data, e.tableOffset);

            if (!e.patch) {
                out.println(e.name + " @ " + hex(e.tableOffset) + " unchanged at " + hex(current));
                continue;
            }

            if (current == newPtr) {
                out.println(e.name + " @ " + hex(e.tableOffset) + " already patched to " + hex(newPtr));
                continue;
            }

            if (current != oldPtr) {
                out.println(e.name + " @ " + hex(e.tableOffset) + " skipped due to unexpected current value " + hex(current));
                continue;
            }

            writeLe32(data, e.tableOffset, newPtr);

            out.println(
                    e.name
                            + " @ "
                            + hex(e.tableOffset)
                            + " patched "
                            + hex(oldPtr)
                            + " -> "
                            + hex(newPtr)
            );
        }

        writeAll(target, data);

        out.println();
        out.println("Verification after patch:");
        out.println("-------------------------");

        byte[] verify = readAll(target);

        for (int i = 0; i < ENTRIES.length; i++) {
            Entry e = ENTRIES[i];

            int expected = RUNTIME_BASE + e.newFileOffset;
            int current = readLe32(verify, e.tableOffset);

            out.println(
                    e.name
                            + " @ "
                            + hex(e.tableOffset)
                            + " current="
                            + hex(current)
                            + " expected="
                            + hex(expected)
                            + " "
                            + (current == expected ? "OK" : "BAD")
            );
        }

        out.println();
        out.println("Done.");
        out.println();
        out.println("Next:");
        out.println("  1. Rebuild the ISO from this split directory.");
        out.println("  2. Test the non-bridge expanded build.");
        out.println("  3. Watch whether the skipped textboxes and voice timing are both fixed.");
        out.close();

        System.out.println("Patched target:");
        System.out.println(target.getAbsolutePath());
        System.out.println();
        System.out.println("Wrote report:");
        System.out.println(report.getAbsolutePath());
    }

    private static void backup(File target, byte[] data, PrintWriter out) throws Exception {
        File backupDir = new File(Conf.desktop + "brmen_backups/");

        if (!backupDir.exists()) {
            backupDir.mkdirs();
        }

        String timestamp = new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date());

        File backup = new File(
                backupDir,
                "SC01_006_0.4.before-real-runtime-pointer-patch." + timestamp + ".bak"
        );

        writeAll(backup, data);

        out.println("Backup:");
        out.println("  " + backup.getAbsolutePath());
        out.println();
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

    private static int readLe32(byte[] data, int offset) {
        int b0 = data[offset] & 0xFF;
        int b1 = data[offset + 1] & 0xFF;
        int b2 = data[offset + 2] & 0xFF;
        int b3 = data[offset + 3] & 0xFF;

        return b0 | (b1 << 8) | (b2 << 16) | (b3 << 24);
    }

    private static void writeLe32(byte[] data, int offset, int value) {
        data[offset] = (byte) (value & 0xFF);
        data[offset + 1] = (byte) ((value >> 8) & 0xFF);
        data[offset + 2] = (byte) ((value >> 16) & 0xFF);
        data[offset + 3] = (byte) ((value >> 24) & 0xFF);
    }

    private static byte[] le32(int value) {
        byte[] ret = new byte[4];

        ret[0] = (byte) (value & 0xFF);
        ret[1] = (byte) ((value >> 8) & 0xFF);
        ret[2] = (byte) ((value >> 16) & 0xFF);
        ret[3] = (byte) ((value >> 24) & 0xFF);

        return ret;
    }

    private static String bytesToHex(byte[] data) {
        StringBuilder sb = new StringBuilder();

        for (int i = 0; i < data.length; i++) {
            if (i > 0) {
                sb.append(' ');
            }

            sb.append(byteHex(data[i]));
        }

        return sb.toString();
    }

    private static String byteHex(byte b) {
        int value = b & 0xFF;
        String s = Integer.toHexString(value).toUpperCase();

        if (s.length() < 2) {
            return "0" + s;
        }

        return s;
    }

    private static String hex(int value) {
        return "0x" + Integer.toHexString(value).toUpperCase();
    }

    private static class Entry {
        String name;
        int tableOffset;
        int oldFileOffset;
        int newFileOffset;
        boolean patch;

        Entry(String name, int tableOffset, int oldFileOffset, int newFileOffset, boolean patch) {
            this.name = name;
            this.tableOffset = tableOffset;
            this.oldFileOffset = oldFileOffset;
            this.newFileOffset = newFileOffset;
            this.patch = patch;
        }
    }
}