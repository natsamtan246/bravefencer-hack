package brm.hack;

import java.io.File;
import java.io.FileInputStream;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

import brm.Conf;

public class SearchRealRuntimeTextPointersEn {

    /*
     * Searches for the real runtime text pointers discovered in no$psx.
     *
     * Runtime loaded base discovered:
     *
     *   SC01/006/0.4 file offset 0x0608E0 -> RAM 0x80188A38
     *
     * Therefore:
     *
     *   loadedBase = 0x80188A38 - 0x0608E0 = 0x80128158
     *
     * We now search for full little-endian RAM pointers like:
     *
     *   E8 89 18 80 = 0x801889E8
     *   38 8A 18 80 = 0x80188A38
     *   84 8A 18 80 = 0x80188A84
     *
     * This is different from the older searches that assumed 0x80110000-ish.
     */

    private static final String TARGET_FILE = "SC01/006/0.4";

    private static final int RUNTIME_BASE = 0x80128158;

    private static final int[] TEXT_OFFSETS = new int[] {
            0x060890,
            0x0608E0,
            0x06092C,
            0x060978,
            0x0609C4,
            0x0609FC,
            0x060AA4,
            0x060BA0,
            0x060C7C
    };

    private static final String[] TEXT_NAMES = new String[] {
            "first Musashi line / expanded block",
            "Steward Ribson line",
            "next Musashi/Geezer line",
            "following line 1",
            "following line 2",
            "following line 3",
            "shrink block",
            "first after-shrink block",
            "later known large block"
    };

    public static void main(String[] args) throws Exception {
        String splitDir = Conf.desktop + "brmen/";
        File root = new File(splitDir);

        if (!root.exists()) {
            throw new RuntimeException("Missing split dir: " + root.getAbsolutePath());
        }

        File reportFile = new File(Conf.desktop + "real-runtime-text-pointer-search-en.txt");
        PrintWriter out = new PrintWriter(reportFile, "UTF-8");

        out.println("Real Runtime Text Pointer Search - EN");
        out.println("====================================");
        out.println();
        out.println("Split dir:");
        out.println("  " + root.getAbsolutePath());
        out.println();
        out.println("Target file:");
        out.println("  " + TARGET_FILE);
        out.println();
        out.println("Runtime base:");
        out.println("  " + hex(RUNTIME_BASE));
        out.println();
        out.println("Reason:");
        out.println("  no$psx showed file offset 0x0608E0 loaded at 0x80188A38.");
        out.println("  Therefore loaded base is 0x80128158.");
        out.println();

        List<File> files = new ArrayList<File>();
        collectFiles(root, files);

        out.println("Files searched:");
        out.println("  " + files.size());
        out.println();

        out.println("Pointer targets:");
        out.println("----------------");

        for (int i = 0; i < TEXT_OFFSETS.length; i++) {
            int fileOffset = TEXT_OFFSETS[i];
            int runtimePtr = RUNTIME_BASE + fileOffset;

            out.println(
                    "  "
                            + padRight(TEXT_NAMES[i], 34)
                            + " fileOffset="
                            + hex(fileOffset)
                            + " runtimePtr="
                            + hex(runtimePtr)
                            + " bytesLE="
                            + bytesToHex(le32(runtimePtr))
            );
        }

        out.println();
        out.println("Exact full RAM pointer hits:");
        out.println("----------------------------");

        int totalFullHits = 0;

        for (File file : files) {
            byte[] data = readAll(file);
            String relative = relativize(root, file);

            for (int i = 0; i < TEXT_OFFSETS.length; i++) {
                int fileOffset = TEXT_OFFSETS[i];
                int runtimePtr = RUNTIME_BASE + fileOffset;

                List<Integer> hits = findAll(data, le32(runtimePtr));

                for (Integer hit : hits) {
                    totalFullHits++;

                    out.println(
                            relative
                                    + " @ "
                                    + hex(hit.intValue())
                                    + " -> "
                                    + TEXT_NAMES[i]
                                    + " fileOffset="
                                    + hex(fileOffset)
                                    + " runtimePtr="
                                    + hex(runtimePtr)
                    );

                    out.println("    context:");
                    out.println("    " + contextHex(data, hit.intValue(), 32));
                }
            }
        }

        out.println();
        out.println("Total full RAM pointer hits:");
        out.println("  " + totalFullHits);
        out.println();

        out.println("Target-file compact table:");
        out.println("--------------------------");

        File target = new File(root, TARGET_FILE.replace("/", File.separator));

        if (target.exists()) {
            byte[] data = readAll(target);

            for (int i = 0; i < TEXT_OFFSETS.length; i++) {
                int fileOffset = TEXT_OFFSETS[i];
                int runtimePtr = RUNTIME_BASE + fileOffset;
                List<Integer> hits = findAll(data, le32(runtimePtr));

                out.println(
                        TEXT_NAMES[i]
                                + " runtimePtr="
                                + hex(runtimePtr)
                                + " hitCount="
                                + hits.size()
                );

                for (Integer hit : hits) {
                    out.println("  hit @ " + hex(hit.intValue()));
                    out.println("    " + contextHex(data, hit.intValue(), 32));
                }
            }

            out.println();
            out.println("First 256 bytes of target file:");
            out.println("-------------------------------");
            out.println(contextHex(data, 0, 256));
        } else {
            out.println("Target file missing:");
            out.println("  " + target.getAbsolutePath());
        }

        out.println();
        out.println("Also searching local file offsets as little-endian U32:");
        out.println("-------------------------------------------------------");

        int totalLocalHits = 0;

        for (File file : files) {
            byte[] data = readAll(file);
            String relative = relativize(root, file);

            for (int i = 0; i < TEXT_OFFSETS.length; i++) {
                int fileOffset = TEXT_OFFSETS[i];

                List<Integer> hits = findAll(data, le32(fileOffset));

                for (Integer hit : hits) {
                    totalLocalHits++;

                    out.println(
                            relative
                                    + " @ "
                                    + hex(hit.intValue())
                                    + " -> "
                                    + TEXT_NAMES[i]
                                    + " localOffset="
                                    + hex(fileOffset)
                    );

                    out.println("    context:");
                    out.println("    " + contextHex(data, hit.intValue(), 32));
                }
            }
        }

        out.println();
        out.println("Total local-offset U32 hits:");
        out.println("  " + totalLocalHits);
        out.println();

        out.println("Done.");
        out.close();

        System.out.println("Wrote report:");
        System.out.println(reportFile.getAbsolutePath());
    }

    private static void collectFiles(File file, List<File> files) {
        if (file.isFile()) {
            files.add(file);
            return;
        }

        File[] children = file.listFiles();

        if (children == null) {
            return;
        }

        for (int i = 0; i < children.length; i++) {
            collectFiles(children[i], files);
        }
    }

    private static List<Integer> findAll(byte[] data, byte[] needle) {
        List<Integer> hits = new ArrayList<Integer>();

        if (needle.length == 0 || data.length < needle.length) {
            return hits;
        }

        for (int i = 0; i <= data.length - needle.length; i++) {
            boolean match = true;

            for (int j = 0; j < needle.length; j++) {
                if (data[i + j] != needle[j]) {
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

    private static byte[] le32(int value) {
        byte[] data = new byte[4];

        data[0] = (byte) (value & 0xFF);
        data[1] = (byte) ((value >> 8) & 0xFF);
        data[2] = (byte) ((value >> 16) & 0xFF);
        data[3] = (byte) ((value >> 24) & 0xFF);

        return data;
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

    private static String contextHex(byte[] data, int center, int radius) {
        int start = center - radius;
        int end = center + radius;

        if (start < 0) {
            start = 0;
        }

        if (end > data.length) {
            end = data.length;
        }

        StringBuilder sb = new StringBuilder();

        for (int pos = start; pos < end; pos += 16) {
            sb.append(hex(pos));
            sb.append(": ");

            for (int i = 0; i < 16; i++) {
                int p = pos + i;

                if (p < end) {
                    sb.append(byteHex(data[p]));
                    sb.append(' ');
                } else {
                    sb.append("   ");
                }
            }

            sb.append(" | ");

            for (int i = 0; i < 16; i++) {
                int p = pos + i;

                if (p < end) {
                    int b = data[p] & 0xFF;

                    if (b >= 32 && b <= 126) {
                        sb.append((char) b);
                    } else {
                        sb.append('.');
                    }
                }
            }

            sb.append('\n');
        }

        return sb.toString();
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

    private static String padRight(String s, int len) {
        String ret = s;

        while (ret.length() < len) {
            ret = ret + " ";
        }

        return ret;
    }

    private static String relativize(File root, File file) {
        String rootPath = root.getAbsolutePath();
        String filePath = file.getAbsolutePath();

        if (filePath.startsWith(rootPath)) {
            String relative = filePath.substring(rootPath.length());

            while (relative.startsWith(File.separator)) {
                relative = relative.substring(1);
            }

            return relative.replace(File.separatorChar, '/');
        }

        return filePath;
    }
}