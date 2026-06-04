package brm.hack;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import common.ExcelParser;
import common.ExcelParser.RowCallback;

public class AllScriptsImporterEn {

    private static final int COL_SCRIPT = 0;
    private static final int COL_ADDR   = 1;
    private static final int COL_LEN    = 2;
    private static final int COL_CTRL   = 3;
    private static final int COL_ORIG   = 4;
    private static final int COL_EDIT   = 5;

    private static class Patch {
        String script;
        int addr;
        int len;
        byte[] bytes;

        Patch(String script, int addr, int len, byte[] bytes) {
            this.script = script;
            this.addr = addr;
            this.len = len;
            this.bytes = bytes;
        }
    }

    private String currentScript = null;
    private Integer currentAddr = null;
    private Integer currentLen = null;
    private StringBuilder currentSentence = new StringBuilder();
    private boolean currentHasEdit = false;

    private String splitDir;
    private SentenceSerializer serializer;

    private final Map<String, LinkedHashMap<Integer, Patch>> patchesByScript =
            new LinkedHashMap<String, LinkedHashMap<Integer, Patch>>();

    private final Set<String> touchedCdNames =
            new LinkedHashSet<String>();

    public void preflight(File excel, String splitDir, Encoding enc1) {
        this.splitDir = splitDir;
        this.serializer = new SentenceSerializer(enc1);

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
                 * Script + address starts a new sentence block.
                 * Length can appear later in the block.
                 */
                if (hasScript && hasAddr) {
                    flushCurrentSentence();

                    currentScript = scriptCell;
                    currentAddr = Integer.parseInt(addrCell, 16);
                    currentLen = null;
                    currentSentence = new StringBuilder();
                    currentHasEdit = false;
                }

                if (currentScript == null || currentAddr == null) {
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
        for (Map.Entry<String, LinkedHashMap<Integer, Patch>> entry : patchesByScript.entrySet()) {
            String scriptName = entry.getKey();
            File scriptFile = new File(splitDir + scriptName);

            RandomAccessFile file = null;

            try {
                System.out.println("[AllScriptsImporterEn] writing " + scriptName);
                file = new RandomAccessFile(scriptFile, "rw");

                for (Patch patch : entry.getValue().values()) {
                    file.seek(patch.addr);
                    file.write(patch.bytes);

                    System.out.printf(
                            "[AllScriptsImporterEn] patched %s %08X len=%d%n",
                            patch.script,
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
    }

    public Set<String> getTouchedCdNames() {
        return touchedCdNames;
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
        if (currentScript == null || currentAddr == null) {
            return;
        }

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

        File scriptFile = new File(splitDir + currentScript);

        if (!scriptFile.exists()) {
            ErrMsg.add("Missing script file: " + scriptFile.getAbsolutePath());
            return;
        }

        Sentence sentence = new Sentence(
                currentSentence.toString(),
                currentScript,
                currentLen,
                currentAddr
        );

        try {
            byte[] bytes = serializer.toBytes(sentence);

            LinkedHashMap<Integer, Patch> byAddr = patchesByScript.get(currentScript);

            if (byAddr == null) {
                byAddr = new LinkedHashMap<Integer, Patch>();
                patchesByScript.put(currentScript, byAddr);
            }

            byAddr.put(
                    currentAddr,
                    new Patch(currentScript, currentAddr, currentLen, bytes)
            );

            touchedCdNames.add(getCdName(currentScript));

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

    private String getCdName(String scriptPath) {
        int slash = scriptPath.indexOf('/');

        if (slash < 0) {
            return scriptPath;
        }

        return scriptPath.substring(0, slash);
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