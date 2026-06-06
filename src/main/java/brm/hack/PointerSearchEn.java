package brm.hack;

import java.io.File;
import java.io.FileInputStream;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

import brm.Conf;

public class PointerSearchEn {

    private static final int CONTEXT_BYTES = 16;

    /*
     * Defaults from the current overflow test:
     *
     * Address: 60890
     * Original length: 80 decimal, which is 0x50
     * Next block starts at 0x60890 + 0x50 = 0x608E0
     *
     * You can change these later for other lines.
     */
    private static final long DEFAULT_ADDRESS = 0x60890L;
    private static final long DEFAULT_ORIGINAL_LEN = 80L;

    public static void main(String[] args) throws Exception {
        String splitdir = Conf.desktop + "brmen/";
        File report = new File(Conf.desktop + "pointer-search-en.txt");

        long address = DEFAULT_ADDRESS;
        long originalLen = DEFAULT_ORIGINAL_LEN;

        if (args.length >= 1) {
            address = parseHexOrDecimal(args[0]);
        }

        if (args.length >= 2) {
            originalLen = parseHexOrDecimal(args[1]);
        }

        long nextAddress = address + originalLen;

        List<File> files = new ArrayList<File>();
        collectFiles(new File(splitdir), files);

        PrintWriter out = new PrintWriter(report, "UTF-8");

        out.println("Brave Fencer Musashi English Pointer Search");
        out.println("===========================================");
        out.println();
        out.println("This report does not modify anything.");
        out.println();
        out.println("Split folder: " + splitdir);
        out.println("Target address:     " + hex(address, 8));
        out.println("Original length:    " + originalLen + " decimal / " + hex(originalLen, 4));
        out.println("Next block address: " + hex(nextAddress, 8));
        out.println();

        out.println("Searching for target address patterns...");
        out.println("----------------------------------------");
        searchValue(out, files, "TARGET", address);

        out.println();
        out.println("Searching for next-block address patterns...");
        out.println("--------------------------------------------");
        searchValue(out, files, "NEXT", nextAddress);

        out.println();
        out.println("Notes");
        out.println("-----");
        out.println("- 32-bit LE is the most common PS1-style pointer byte order.");
        out.println("- 24-bit patterns are useful because script data may store file offsets in 3 bytes.");
        out.println("- LOW16 matches are noisy. Do not trust them unless they appear in a suspicious table.");
        out.println("- WORD patterns test address / 4, in case the script stores word offsets.");
        out.println("- If TARGET and NEXT are near each other in the same table, that is very important.");
        out.println();

        out.close();

        System.out.println("Pointer search report written to:");
        System.out.println(report.getAbsolutePath());
    }

    private static void searchValue(PrintWriter out, List<File> files, String label, long value) throws Exception {
        List<SearchPattern> patterns = buildPatterns(value);

        for (SearchPattern pattern : patterns) {
            int totalHits = 0;

            out.println();
            out.println(label + " " + hex(value, 8) + " as " + pattern.name + " = " + bytesToHex(pattern.bytes));

            for (File file : files) {
                byte[] data = readAll(file);
                List<Integer> hits = findAll(data, pattern.bytes);

                if (!hits.isEmpty()) {
                    totalHits += hits.size();

                    for (Integer hit : hits) {
                        out.println("  " + relativePath(file) + " @ " + hex(hit.longValue(), 8));
                        out.println("    " + context(data, hit.intValue(), pattern.bytes.length));
                    }
                }
            }

            if (totalHits == 0) {
                out.println("  no hits");
            } else {
                out.println("  total hits: " + totalHits);
            }
        }
    }

    private static List<SearchPattern> buildPatterns(long value) {
        List<SearchPattern> patterns = new ArrayList<SearchPattern>();

        patterns.add(new SearchPattern("32-bit little-endian", little(value, 4)));
        patterns.add(new SearchPattern("32-bit big-endian", big(value, 4)));

        patterns.add(new SearchPattern("24-bit little-endian", little(value, 3)));
        patterns.add(new SearchPattern("24-bit big-endian", big(value, 3)));

        long low16 = value & 0xFFFFL;
        patterns.add(new SearchPattern("LOW16 little-endian", little(low16, 2)));
        patterns.add(new SearchPattern("LOW16 big-endian", big(low16, 2)));

        if ((value % 4L) == 0L) {
            long wordValue = value / 4L;

            patterns.add(new SearchPattern("WORD32 little-endian address/4", little(wordValue, 4)));
            patterns.add(new SearchPattern("WORD32 big-endian address/4", big(wordValue, 4)));

            patterns.add(new SearchPattern("WORD24 little-endian address/4", little(wordValue, 3)));
            patterns.add(new SearchPattern("WORD24 big-endian address/4", big(wordValue, 3)));
        }

        return patterns;
    }

    private static byte[] little(long value, int count) {
        byte[] b = new byte[count];

        for (int i = 0; i < count; i++) {
            b[i] = (byte) ((value >> (8 * i)) & 0xFF);
        }

        return b;
    }

    private static byte[] big(long value, int count) {
        byte[] b = new byte[count];

        for (int i = 0; i < count; i++) {
            int shift = 8 * (count - 1 - i);
            b[i] = (byte) ((value >> shift) & 0xFF);
        }

        return b;
    }

    private static List<Integer> findAll(byte[] data, byte[] pattern) {
        List<Integer> hits = new ArrayList<Integer>();

        if (pattern.length == 0 || data.length < pattern.length) {
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
                hits.add(Integer.valueOf(i));
            }
        }

        return hits;
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

    private static void collectFiles(File file, List<File> files) {
        if (file == null || !file.exists()) {
            return;
        }

        if (file.isFile()) {
            files.add(file);
            return;
        }

        File[] children = file.listFiles();

        if (children == null) {
            return;
        }

        for (File child : children) {
            collectFiles(child, files);
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

    private static String relativePath(File file) {
        String base = new File(Conf.desktop + "brmen/").getAbsolutePath();
        String full = file.getAbsolutePath();

        if (full.startsWith(base)) {
            String rel = full.substring(base.length());

            while (rel.startsWith("\\") || rel.startsWith("/")) {
                rel = rel.substring(1);
            }

            return rel.replace("\\", "/");
        }

        return full;
    }

    private static String hex(long value, int digits) {
        String s = Long.toHexString(value).toUpperCase();

        while (s.length() < digits) {
            s = "0" + s;
        }

        return "0x" + s;
    }

    private static long parseHexOrDecimal(String text) {
        String s = text.trim();

        if (s.startsWith("0x") || s.startsWith("0X")) {
            return Long.parseLong(s.substring(2), 16);
        }

        /*
         * For this project, addresses copied from Excel are usually hex-looking.
         * If the string contains A-F, parse as hex.
         */
        if (s.matches(".*[a-fA-F].*")) {
            return Long.parseLong(s, 16);
        }

        /*
         * Plain numbers are treated as hex too, because Excel addresses like 60890
         * are addresses, not decimal counts.
         */
        return Long.parseLong(s, 16);
    }

    private static class SearchPattern {
        String name;
        byte[] bytes;

        SearchPattern(String name, byte[] bytes) {
            this.name = name;
            this.bytes = bytes;
        }
    }
}