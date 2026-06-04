package brm.hack;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashSet;
import java.util.Set;

import common.ExcelParser;
import common.ExcelParser.RowCallback;

public class AllScriptsImporterEn {

    /*
     * English SCRIPTS sheet:
     *
     * A = script file, written on first row of sentence
     * B = start address, written on first row of sentence
     * C = length, often written on final row of sentence
     * D = control-code segment
     * E = original English text segment
     * F = edited/replacement text segment
     *
     * G exists in the sheet, but we are not using it right now.
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
    private final Set<String> touchedCdNames = new LinkedHashSet<String>();

    private final Map<String, Map<Integer, Sentence>> scriptSentences =
            new LinkedHashMap<String, Map<Integer, Sentence>>();

    public void importFrom(File excel, String splitDir, Encoding enc1) {
        new ExcelParser(excel).parse("SCRIPTS", 2, new RowCallback() {
            @Override
            public void doInRow(List<String> strs, int rowNum) {
                String scriptCell = getCell(strs, COL_SCRIPT).trim();
                String addrCell = getCell(strs, COL_ADDR).trim();
                String lenCell = getCell(strs, COL_LEN).trim();

                boolean hasScript = !isEmpty(scriptCell);
                boolean hasAddr = !isEmpty(addrCell);
                boolean hasLen = !isEmpty(lenCell);

                /*
                 * A real new SCRIPTS sentence starts when script + address exist.
                 * Length may be blank here because the dumper often writes length
                 * on the final row of the sentence block.
                 */
                if (hasScript && hasAddr) {
                    flushCurrentSentence();

                    currentScript = scriptCell;
                    currentAddr = Integer.parseInt(addrCell, 16);
                    currentLen = null;
                    currentSentence = new StringBuilder();
                    currentHasEdit = false;
                }

                /*
                 * Ignore rows before the first valid script/address block.
                 */
                if (currentScript == null || currentAddr == null) {
                    return;
                }

                appendRowText(strs);

                /*
                 * Length can appear on any row of the current block,
                 * usually the final row.
                 */
                if (hasLen) {
                    currentLen = Integer.parseInt(lenCell);
                }
            }
        });

        flushCurrentSentence();

        writeSentences(splitDir, enc1);
    }

    private void appendRowText(List<String> strs) {
        String ctrls = getCell(strs, COL_CTRL);
        String original = getCell(strs, COL_ORIG);
        String edit = getCell(strs, COL_EDIT);

        if (!isEmpty(edit)) {
            currentHasEdit = true;
        }

        String visibleText = !isEmpty(edit) ? edit : original;

        /*
         * Preserve exact order:
         * controls first, then same-row text segment.
         */
        currentSentence.append(ctrls).append(visibleText);
    }

    private void flushCurrentSentence() {
        if (currentScript == null || currentAddr == null) {
            return;
        }

        /*
         * No edit anywhere in this sentence block means leave original bytes untouched.
         */
        if (!currentHasEdit) {
            return;
        }

        if (currentLen == null) {
            ErrMsg.add(String.format(
                    "SCRIPTS import failed at %s %08X: edited sentence has no length cell. " +
                            "Check column C on the last row of this sentence block.",
                    currentScript,
                    currentAddr
            ));
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

        byAddr.put(currentAddr, sentence);
        touchedCdNames.add(getCdName(currentScript));
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
                        ErrMsg.add(String.format(
                                "SCRIPTS import failed at %s %08X len=%d : %s",
                                sentence.script,
                                sentence.addr,
                                sentence.len,
                                ex.getMessage()
                        ));
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
            return strs.get(cell);
        }

        return "";
    }

    private boolean isEmpty(String s) {
        return s == null || s.trim().isEmpty();
    }
    public Set<String> getTouchedCdNames() {
        return touchedCdNames;
    }

    private String getCdName(String scriptPath) {
        int slash = scriptPath.indexOf('/');
        if (slash < 0) {
            return scriptPath;
        }

        return scriptPath.substring(0, slash);
    }
}