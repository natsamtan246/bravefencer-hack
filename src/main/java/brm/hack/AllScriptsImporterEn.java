package brm.hack;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import common.ExcelParser;
import common.ExcelParser.RowCallback;

public class AllScriptsImporterEn {

    /*
     * English SCRIPTS sheet columns:
     *
     * A = script file
     * B = start address
     * C = original length
     * D = control codes, usually blank for English
     * E = original English text
     * F = edited/replacement text
     */
    private static final int COL_SCRIPT = 0;
    private static final int COL_ADDR   = 1;
    private static final int COL_LEN    = 2;
    private static final int COL_CTRL   = 3;
    private static final int COL_EDIT   = 5;

    public void importFrom(File excel, String splitDir, Encoding enc1) {
        Map<String, List<Sentence>> scriptSentences =
                new LinkedHashMap<String, List<Sentence>>();

        new ExcelParser(excel).parse("SCRIPTS", 2, new RowCallback() {
            @Override
            public void doInRow(List<String> strs, int rowNum) {
                String script = getCell(strs, COL_SCRIPT);
                String addrCell = getCell(strs, COL_ADDR);
                String lenCell = getCell(strs, COL_LEN);
                String ctrls = getCell(strs, COL_CTRL);
                String edit = getCell(strs, COL_EDIT);

                /*
                 * Blank edit column means: do not touch this row.
                 */
                if (isEmpty(edit)) {
                    return;
                }

                if (isEmpty(script) || isEmpty(addrCell) || isEmpty(lenCell)) {
                    ErrMsg.add(String.format(
                            "SCRIPTS row %d has edit text but missing script/address/length",
                            rowNum + 1
                    ));
                    return;
                }

                int addr = Integer.parseInt(addrCell.trim(), 16);
                int len = Integer.parseInt(lenCell.trim());

                String replacement = ctrls + edit;

                Sentence sentence = new Sentence(
                        replacement,
                        script,
                        len,
                        addr
                );

                List<Sentence> list = scriptSentences.get(script);
                if (list == null) {
                    list = new ArrayList<Sentence>();
                    scriptSentences.put(script, list);
                }

                list.add(sentence);
            }
        });

        writeSentences(splitDir, enc1, scriptSentences);
    }

    private void writeSentences(
            String splitDir,
            Encoding enc1,
            Map<String, List<Sentence>> scriptSentences
    ) {
        SentenceSerializer serializer = new SentenceSerializer(enc1);

        for (Map.Entry<String, List<Sentence>> entry : scriptSentences.entrySet()) {
            String scriptName = entry.getKey();
            File scriptFile = new File(splitDir + scriptName);

            if (!scriptFile.exists()) {
                ErrMsg.add("Missing script file: " + scriptFile.getAbsolutePath());
                continue;
            }

            RandomAccessFile file = null;

            try {
                System.out.println("[AllScriptsImporterEn] writing " + scriptName);
                file = new RandomAccessFile(scriptFile, "rw");

                for (Sentence sentence : entry.getValue()) {
                    try {
                        byte[] bytes = serializer.toBytes(sentence);
                        file.seek(sentence.addr);
                        file.write(bytes);
                    } catch (UnsupportedOperationException ex) {
                        ErrMsg.add(ex.getMessage());
                    }
                }
            } catch (IOException ex) {
                ErrMsg.add("Failed writing " + scriptFile.getAbsolutePath()
                        + ": " + ex.getMessage());
            } finally {
                if (file != null) {
                    try {
                        file.close();
                    } catch (IOException ignored) {
                    }
                }
            }
        }
    }

    private String getCell(List<String> strs, int cell) {
        if (strs.size() > cell && strs.get(cell) != null) {
            return strs.get(cell).trim();
        }

        return "";
    }

    private boolean isEmpty(String s) {
        return s == null || s.trim().isEmpty();
    }
}