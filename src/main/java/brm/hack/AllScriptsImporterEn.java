package brm.hack;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import common.ExcelParser;
import common.ExcelParser.RowCallback;

public class AllScriptsImporterEn {

    /*
     * English SCRIPTS sheet layout:
     *
     * A = script file
     * B = start address
     * C = original length
     * D = control codes
     * E = original English text
     * F = edited/replacement text
     *
     * Rows with A/B/C filled start a new sentence block.
     * Rows with A/B/C blank continue the previous sentence block.
     */
    private static final int COL_SCRIPT = 0;
    private static final int COL_ADDR   = 1;
    private static final int COL_LEN    = 2;
    private static final int COL_CTRL   = 3;
    private static final int COL_ORIG   = 4;
    private static final int COL_EDIT   = 5;

    private String currentScript = null;
    private Integer currentAddr = null;
    private Integer currentLen = null;
    private StringBuilder currentSentence = new StringBuilder();
    private boolean currentHasEdit = false;

    private final Map<String, Map<Integer, Sentence>> scriptSentences =
            new LinkedHashMap<String, Map<Integer, Sentence>>();

    public void importFrom(File excel, String splitDir, Encoding enc1) {
        new ExcelParser(excel).parse("SCRIPTS", 2, new RowCallback() {
            @Override
            public void doInRow(List<String> strs, int rowNum) {
                String scriptCell = getCell(strs, COL_SCRIPT);
                String addrCell = getCell(strs, COL_ADDR);
                String lenCell = getCell(strs, COL_LEN);

                /*
                 * Non-empty script/address/length starts a new sentence block.
                 */
                if (!isEmpty(scriptCell) || !isEmpty(addrCell) || !isEmpty(lenCell)) {
                    flushCurrentSentence();

                    if (isEmpty(scriptCell) || isEmpty(addrCell) || isEmpty(lenCell)) {
                        ErrMsg.add(String.format(
                                "SCRIPTS row %d starts a sentence but is missing script/address/length",
                                rowNum + 1
                        ));

                        currentScript = null;
                        currentAddr = null;
                        currentLen = null;
                        currentSentence = new StringBuilder();
                        currentHasEdit = false;
                        return;
                    }

                    currentScript = scriptCell;
                    currentAddr = Integer.parseInt(addrCell.trim(), 16);
                    currentLen = Integer.parseInt(lenCell.trim());
                    currentSentence = new StringBuilder();
                    currentHasEdit = false;
                }

                /*
                 * Ignore leading garbage/blank rows before first sentence.
                 */
                if (currentScript == null || currentAddr == null || currentLen == null) {
                    return;
                }

                /*
                 * Preserve byte order by appending each row's controls,
                 * then that same row's text/edit segment.
                 */
                String ctrls = getCell(strs, COL_CTRL);
                String original = getCell(strs, COL_ORIG);
                String edit = getCell(strs, COL_EDIT);

                if (!isEmpty(edit)) {
                    currentHasEdit = true;
                }

                String visibleText = !isEmpty(edit) ? edit : original;
                currentSentence.append(ctrls).append(visibleText);
            }
        });

        flushCurrentSentence();

        writeSentences(splitDir, enc1);
    }

    private void flushCurrentSentence() {
        if (currentScript == null || currentAddr == null || currentLen == null) {
            return;
        }

        /*
         * No edit anywhere inside this sentence block means leave original bytes untouched.
         */
        if (!currentHasEdit) {
            return;
        }

        Sentence sentence = new Sentence(
                currentSentence.toString(),
                currentScript,
                currentLen,
                currentAddr
        );

        Map<Integer, Sentence> byAddr = scriptSentences.get(currentScript);
        if (byAddr == null) {
            byAddr = new LinkedHashMap<Integer, Sentence>();
            scriptSentences.put(currentScript, byAddr);
        }

        /*
         * Avoid writing the same script/address twice if the sheet has duplicates.
         * Last edited occurrence wins.
         */
        byAddr.put(currentAddr, sentence);
    }

    private void writeSentences(String splitDir, Encoding enc1) {
        SentenceSerializer serializer = new SentenceSerializer(enc1);

        for (Map.Entry<String, Map<Integer, Sentence>> entry : scriptSentences.entrySet()) {
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

                for (Sentence sentence : entry.getValue().values()) {
                    try {
                        byte[] bytes = serializer.toBytes(sentence);
                        file.seek(sentence.addr);
                        file.write(bytes);

                        System.out.printf(
                                "[AllScriptsImporterEn] patched %s %08X len=%d%n",
                                sentence.script,
                                sentence.addr,
                                sentence.len
                        );
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