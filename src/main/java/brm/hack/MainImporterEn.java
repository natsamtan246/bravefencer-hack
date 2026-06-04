package brm.hack;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;

import brm.Script;
import common.ExcelParser;
import common.ExcelParser.RowCallback;

public class MainImporterEn {

    private static final int COL_ADDR = 1;
    private static final int COL_LEN  = 2;
    private static final int COL_CTRL = 3;
    private static final int COL_ORIG = 4;
    private static final int COL_EDIT = 5;

    private static class Patch {
        int addr;
        int len;
        byte[] bytes;

        Patch(int addr, int len, byte[] bytes) {
            this.addr = addr;
            this.len = len;
            this.bytes = bytes;
        }
    }

    private Integer currentAddr = null;
    private Integer currentLen = null;
    private StringBuilder currentSentence = new StringBuilder();
    private boolean currentHasEdit = false;

    private final List<Patch> patches = new ArrayList<Patch>();

    private SentenceSerializer serializer;
    private Script script;

    public void preflight(File excel, String splitDir, Script script, Encoding enc1)
            throws IOException {

        this.script = script;
        this.serializer = new SentenceSerializer(enc1);

        File mainFile = new File(splitDir + script.file);

        if (!mainFile.exists()) {
            ErrMsg.add("Missing MAIN file: " + mainFile.getAbsolutePath());
            return;
        }

        new ExcelParser(excel).parse("MAIN", 2, new RowCallback() {
            @Override
            public void doInRow(List<String> strs, int rowNum) {
                String addrCell = getCell(strs, COL_ADDR).trim();
                String lenCell = getCell(strs, COL_LEN).trim();

                boolean hasAddr = !isEmpty(addrCell);
                boolean hasLen = !isEmpty(lenCell);

                /*
                 * Address starts a new sentence block.
                 * Length can appear later in the block.
                 */
                if (hasAddr) {
                    flushCurrentSentence();

                    currentAddr = Integer.parseInt(addrCell, 16);
                    currentLen = null;
                    currentSentence = new StringBuilder();
                    currentHasEdit = false;
                }

                if (currentAddr == null) {
                    return;
                }

                appendRowText(strs);

                if (hasLen) {
                    currentLen = Integer.parseInt(lenCell);
                }
            }
        });

        flushCurrentSentence();
    }

    public void write(String splitDir) throws IOException {
        if (patches.isEmpty()) {
            return;
        }

        File mainFile = new File(splitDir + script.file);
        RandomAccessFile file = null;

        try {
            file = new RandomAccessFile(mainFile, "rw");

            for (Patch patch : patches) {
                file.seek(patch.addr);
                file.write(patch.bytes);

                System.out.printf(
                        "[MainImporterEn] patched MAIN %08X len=%d%n",
                        patch.addr,
                        patch.len
                );
            }
        } finally {
            if (file != null) {
                file.close();
            }
        }
    }

    public boolean patchedAny() {
        return !patches.isEmpty();
    }

    private void appendRowText(List<String> strs) {
        String ctrls = getCell(strs, COL_CTRL);
        String original = getCell(strs, COL_ORIG);
        String edit = getCell(strs, COL_EDIT);

        if (!isEmpty(edit)) {
            currentHasEdit = true;
        }

        String visibleText = !isEmpty(edit) ? edit : original;

        currentSentence.append(ctrls).append(visibleText);
    }

    private void flushCurrentSentence() {
        if (currentAddr == null) {
            return;
        }

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
            patches.add(new Patch(sentence.addr, sentence.len, bytes));
        } catch (UnsupportedOperationException ex) {
            ErrMsg.add(String.format(
                    "MAIN import failed at %08X len=%d : %s",
                    sentence.addr,
                    sentence.len,
                    ex.getMessage()
            ));
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