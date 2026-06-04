package brm.hack;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import brm.Conf;
import common.ExcelParser;
import common.ExcelParser.RowCallback;

public class ControlCodeReportEn {

    private static class TagInfo {
        int count = 0;
        List<String> examples = new ArrayList<String>();
    }

    private static final int MAX_EXAMPLES = 8;

    public static void main(String[] args) throws Exception {
        File excel = new File(Conf.desktop + "brm-en.xlsx");
        File report = new File(Conf.desktop + "control-code-report-en.txt");

        Map<String, TagInfo> tags = new LinkedHashMap<String, TagInfo>();

        scanSheet(excel, "MAIN", tags);
        scanSheet(excel, "SCRIPTS", tags);

        PrintWriter out = new PrintWriter(
                new FileOutputStream(report),
                true
        );

        try {
            out.println("English Control Code Report");
            out.println("===========================");
            out.println();
            out.println("Workbook: " + excel.getAbsolutePath());
            out.println();

            out.println("Summary by tag");
            out.println("--------------");

            for (Map.Entry<String, TagInfo> entry : tags.entrySet()) {
                String tag = entry.getKey();
                TagInfo info = entry.getValue();

                out.printf("%-20s %8d%n", tag, info.count);
            }

            out.println();
            out.println("Details");
            out.println("-------");

            for (Map.Entry<String, TagInfo> entry : tags.entrySet()) {
                String tag = entry.getKey();
                TagInfo info = entry.getValue();

                out.println();
                out.println(tag + "  count=" + info.count);
                out.println("kind=" + classify(tag));

                for (String example : info.examples) {
                    out.println("  " + example);
                }
            }

        } finally {
            out.close();
        }

        System.out.println("Control code report saved to:");
        System.out.println(report.getAbsolutePath());
    }

    private static void scanSheet(
            File excel,
            String sheet,
            Map<String, TagInfo> tags
    ) {
        new ExcelParser(excel).parse(sheet, 2, new RowCallback() {
            @Override
            public void doInRow(List<String> strs, int rowNum) {
                /*
                 * Scan all cells. Controls are usually in D,
                 * but colors like [c2] can appear inside text cells.
                 */
                for (int col = 0; col < strs.size(); col++) {
                    String cell = strs.get(col);

                    if (cell == null || cell.length() == 0) {
                        continue;
                    }

                    scanCell(tags, sheet, rowNum + 1, col + 1, cell);
                }
            }
        });
    }

    private static void scanCell(
            Map<String, TagInfo> tags,
            String sheet,
            int row,
            int col,
            String text
    ) {
        int pos = 0;

        while (pos < text.length()) {
            int open = text.indexOf('[', pos);

            if (open < 0) {
                break;
            }

            int close = text.indexOf(']', open + 1);

            if (close < 0) {
                break;
            }

            String tag = text.substring(open, close + 1);

            addTag(
                    tags,
                    tag,
                    String.format(
                            "%s R%d C%d: %s",
                            sheet,
                            row,
                            col,
                            preview(text)
                    )
            );

            pos = close + 1;
        }
    }

    private static void addTag(
            Map<String, TagInfo> tags,
            String tag,
            String example
    ) {
        TagInfo info = tags.get(tag);

        if (info == null) {
            info = new TagInfo();
            tags.put(tag, info);
        }

        info.count++;

        if (info.examples.size() < MAX_EXAMPLES) {
            info.examples.add(example);
        }
    }

    private static String preview(String text) {
        text = text.replace("\r", " ").replace("\n", " ");

        if (text.length() <= 120) {
            return text;
        }

        return text.substring(0, 117) + "...";
    }

    private static String classify(String tag) {
        String inner = tag.substring(1, tag.length() - 1);

        if (inner.equals("br")) {
            return "line break / 0A";
        }

        if (inner.equals("new")) {
            return "new box/page / 08";
        }

        if (inner.equals("wt")) {
            return "wait / 07";
        }

        if (inner.equals("sel")) {
            return "selection marker / 17";
        }

        if (inner.startsWith("box")) {
            return "box control / 09 xx";
        }

        if (inner.length() >= 2 &&
                inner.charAt(0) == 'c' &&
                isHex(inner.substring(1))) {
            return "color / palette control 01 xx";
        }

        if (isHex(inner)) {
            int byteLen = inner.length() / 2;

            if (byteLen == 1) {
                return "raw 1-byte control";
            }

            return "raw " + byteLen + "-byte control";
        }

        return "unknown bracket tag";
    }

    private static boolean isHex(String s) {
        if (s.length() == 0 || (s.length() % 2) != 0) {
            /*
             * Allow one-nibble color tags like [c1].
             */
            if (s.length() == 1) {
                return isHexChar(s.charAt(0));
            }

            return false;
        }

        for (int i = 0; i < s.length(); i++) {
            if (!isHexChar(s.charAt(i))) {
                return false;
            }
        }

        return true;
    }

    private static boolean isHexChar(char c) {
        return (c >= '0' && c <= '9') ||
                (c >= 'A' && c <= 'F') ||
                (c >= 'a' && c <= 'f');
    }
}