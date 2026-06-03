package brm.hack;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.List;

import brm.Script;
import common.ExcelParser;
import common.ExcelParser.RowCallback;

public class MainImporterEn {

    /*
     * English MAIN sheet:
     *
     * B = start address, written on first row of sentence
     * C = length, often written on final row of sentence
     * D = control-code segment
     * E = original English text segment
     * F = edited/replacement text segment
     */
    private static final int COL_ADDR = 1;
    private static final int COL_LEN  = 2;
    private static final int COL_CTRL = 3;
    private static final int COL_ORIG = 4;
    private static final int COL_EDIT = 5;

    private Integer currentAddr = null;
    private Integer currentLen = null;
    private StringBuilder currentSentence = new StringBuilder();
    private boolean currentHasEdit = false;

    private RandomAccessFile file;
    private SentenceSerializer serializer;
    private Script script;

    public void importFrom(File excel, String splitDir, Script script, Encoding enc1)
            throws IOException {

        this.script = script;
        this.serializer = new SentenceSerializer(enc1);

        File mainFile = new File(splitDir + script.file);

        if (!mainFile.exists()) {
            throw new RuntimeException("Missing MAIN file: " + mainFile.getAbsolutePath());
        }

        file = new RandomAccessFile(mainFile, "rw");

        try {
            new ExcelParser(excel).parse("MAIN", 2, new RowCallback() {
                @Override
                public void doInRow(List<String> strs, int rowNum) {
                    String addrCell = getCell(strs, COL_ADDR).trim();
                    String lenCell = getCell(strs, COL_LEN).trim();

                    boolean hasAddr = !isEmpty(addrCell);
                    boolean hasLen = !isEmpty(lenCell);

                    /*
                     * Address starts a new sentence block.
                     * Length may be blank here because the dumper writes length
                     * at sentenceEnd(), often on the final row of the sentence.
                     */
                    if (hasAddr) {
                        flushCurrentSentence();

                        currentAddr = Integer.parseInt(addrCell, 16);
                        currentLen = null;
                        currentSentence = new StringBuilder();
                        currentHasEdit = false;
                    }

                    /*
                     * Ignore rows before first sentence.
                     */
                    if (currentAddr == null) {
                        return;
                    }

                    /*
                     * Preserve row order.
                     */
                    appendRowText(strs);

                    /*
                     * Length can appear on any row of the current sentence,
                     * usually the final row.
                     */
                    if (hasLen) {
                        currentLen = Integer.parseInt(lenCell);
                    }
                }
            });

            flushCurrentSentence();
        } finally {
            file.close();
        }
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
         * Control segment first, then same-row text segment.
         */
        currentSentence.append(ctrls).append(visibleText);
    }

    private void flushCurrentSentence() {
        if (currentAddr == null) {
            return;
        }

        /*
         * No edit in this sentence block means leave original bytes untouched.
         */
        if (!currentHasEdit) {
            return;
        }

        if (currentLen == null) {
            ErrMsg.add(String.format(
                    "MAIN import failed at %08X: edited sentence has no length cell. " +
                            "Check column C on the last row of this sentence block.",
                    currentAddr
            ));
            return;
        }

        Sentence sentence = new Sentence(
                currentSentence.toString(),
                script.file,
                currentLen,
                currentAddr
        );

        try {
            byte[] bytes = serializer.toBytes(sentence);
            file.seek(sentence.addr);
            file.write(bytes);

            System.out.printf(
                    "[MainImporterEn] patched MAIN %08X len=%d%n",
                    sentence.addr,
                    sentence.len
            );
        } catch (UnsupportedOperationException ex) {
            ErrMsg.add(String.format(
                    "MAIN import failed at %08X len=%d : %s",
                    sentence.addr,
                    sentence.len,
                    ex.getMessage()
            ));
        } catch (IOException ex) {
            ErrMsg.add("Failed writing MAIN at "
                    + Integer.toHexString(sentence.addr)
                    + ": " + ex.getMessage());
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
}