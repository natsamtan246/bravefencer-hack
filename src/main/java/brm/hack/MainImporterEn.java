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
     * C = original length, sometimes blank after control-preserving dump
     * D = control-code segment
     * E = original English text segment
     * F = edited/replacement text segment
     *
     * Any non-empty address starts a new sentence.
     * Blank-address rows continue the current sentence.
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

                    /*
                     * Any non-empty address starts a new sentence block.
                     */
                    if (hasAddr) {
                        flushCurrentSentence();

                        currentAddr = Integer.parseInt(addrCell, 16);

                        if (!isEmpty(lenCell)) {
                            currentLen = Integer.parseInt(lenCell);
                        } else {
                            currentLen = measureSentenceLength(currentAddr);
                            System.out.printf(
                                    "[MainImporterEn] measured MAIN %08X len=%d%n",
                                    currentAddr,
                                    currentLen
                            );
                        }

                        currentSentence = new StringBuilder();
                        currentHasEdit = false;

                        appendRowText(strs);
                        return;
                    }

                    /*
                     * Blank address = continuation row of current sentence.
                     */
                    if (currentAddr == null || currentLen == null) {
                        return;
                    }

                    appendRowText(strs);
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
         * Preserve byte order:
         * control segment first, then same-row text segment.
         */
        currentSentence.append(ctrls).append(visibleText);
    }

    private void flushCurrentSentence() {
        if (currentAddr == null || currentLen == null) {
            return;
        }

        /*
         * No edit in this sentence block means leave original bytes untouched.
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

    /*
     * Some control-preserving MAIN rows have address but no length.
     * In that case, derive the fixed slot size from the original file:
     * read from the address until the 00 end marker, inclusive.
     */
    private int measureSentenceLength(int addr) {
        try {
            long oldPos = file.getFilePointer();

            file.seek(addr);

            int count = 0;

            while (true) {
                int b = file.read();

                if (b < 0) {
                    throw new RuntimeException(String.format(
                            "Reached EOF while measuring MAIN sentence at %08X",
                            addr
                    ));
                }

                count++;

                if (b == 0x00) {
                    break;
                }

                if (count > 8192) {
                    throw new RuntimeException(String.format(
                            "MAIN sentence at %08X is too long or missing end marker",
                            addr
                    ));
                }
            }

            file.seek(oldPos);
            return count;
        } catch (IOException ex) {
            throw new RuntimeException(ex);
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