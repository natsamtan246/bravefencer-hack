package brm.hack;

import java.io.File;
import java.util.Set;

import brm.Conf;
import brm.ScriptConfigLoader;

public class HackEn {
    public static void main(String[] args) throws Exception {
        String splitdir = Conf.desktop + "brmen/";
        File excel = new File(Conf.desktop + "brm-en.xlsx");

        ScriptConfigLoader scriptConfig =
                new ScriptConfigLoader("en", splitdir);

        Encoding enc = new EncodingEn();

        MainImporterEn mainImporter = new MainImporterEn();
        AllScriptsImporterEn scriptsImporter = new AllScriptsImporterEn();

        /*
         * Preflight only.
         * These calls read the spreadsheet and serialize edits,
         * but do not write to brmen yet.
         */
        mainImporter.preflight(excel, splitdir, scriptConfig.main, enc);
        scriptsImporter.preflight(excel, splitdir, enc);

        /*
         * If anything is too long or invalid, stop before touching files.
         */
        ErrMsg.checkErr();

        /*
         * Now it is safe to write patches.
         */
        mainImporter.write(splitdir);
        scriptsImporter.write(splitdir);

        if (mainImporter.patchedAny()) {
            CdRebuilder.rebuildOne(splitdir, Conf.outdir, "MAIN");
        }

        Set<String> touchedCds = scriptsImporter.getTouchedCdNames();

        for (String cdName : touchedCds) {
            CdRebuilder.rebuildOne(splitdir, Conf.outdir, cdName);
        }

        if (!mainImporter.patchedAny() && touchedCds.isEmpty()) {
            System.out.println("No English edits found. Nothing rebuilt.");
        } else {
            System.out.println("English import/rebuild complete.");
        }
    }
}