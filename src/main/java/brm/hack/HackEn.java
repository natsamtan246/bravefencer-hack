package brm.hack;

import java.io.File;
import java.util.Set;

import brm.Conf;
import brm.ScriptConfigLoader;

public class HackEn {
    public static void main(String[] args) throws Exception {
        String splitdir = Conf.desktop + "brmen/";
        File excel = new File(Conf.desktop + "brm-en.xlsx");

        ScriptConfigLoader scriptConfig = new ScriptConfigLoader("en", splitdir);
        Encoding enc = new EncodingEn();

        MainImporterEn mainImporter = new MainImporterEn();
        mainImporter.importFrom(excel, splitdir, scriptConfig.main, enc);

        AllScriptsImporterEn scriptsImporter = new AllScriptsImporterEn();
        scriptsImporter.importFrom(excel, splitdir, enc);

        ErrMsg.checkErr();

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