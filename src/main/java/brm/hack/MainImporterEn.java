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
     * English MAIN sheet columns:
     *
     * B = start address
     * C = original length
     * D = control codes
     * E = original English text
     * F = edited/replacement text
     *
     * Blank F means: do not touch the original file.
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
                    String addrCell = getCell(strs, COL_ADDR);

                    /*
                     * A non-empty address starts a new MAIN sentence block.
                     */
                    if (!isEmpty(addrCell)) {
                        flushCurrentSentence();

                        currentAddr = Integer.parseInt(addrCell.trim(), 16);

                        String lenCell = getCell(strs, COL_LEN);
                        if (isEmpty(lenCell)) {
                            ErrMsg.add(String.format(
                                    "MAIN row %d has address but no length",
                                    rowNum + 1
                            ));
                            currentLen = null;
                        } else {
                            currentLen = Integer.parseInt(lenCell.trim());
                        }

                        currentSentence = new StringBuilder();
                        currentHasEdit = false;
                    }

                    if (currentAddr == null) {
                        return;
                    }

                    String ctrls = getCell(strs, COL_CTRL);
                    String original = getCell(strs, COL_ORIG);
                    String edit = getCell(strs, COL_EDIT);

                    /*
                     * If any row inside this sentence block has an edit,
                     * rewrite the whole sentence block.
                     *
                     * Unedited continuation rows fall back to original English text.
                     */
                    if (!isEmpty(edit)) {
                        currentHasEdit = true;
                    }

                    String visibleText = !isEmpty(edit) ? edit : original;
                    currentSentence.append(ctrls).append(visibleText);
                }
            });

            flushCurrentSentence();
        } finally {
            file.close();
        }
    }

    private void flushCurrentSentence() {
        if (currentAddr == null || currentLen == null) {
            return;
        }

        /*
         * No edit in column F means leave original bytes untouched.
         */
        if (!currentHasEdit) {
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
            ErrMsg.add(ex.getMessage());
        } catch (IOException ex) {
            ErrMsg.add("Failed writing MAIN at "
                    + Integer.toHexString(sentence.addr)
                    + ": " + ex.getMessage());
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