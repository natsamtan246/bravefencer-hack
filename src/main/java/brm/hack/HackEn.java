package brm.hack;

import java.io.File;

import brm.Conf;
import brm.ScriptConfigLoader;

public class HackEn {
    public static void main(String[] args) throws Exception {
        String splitdir = Conf.desktop + "brmen/";
        File excel = new File(Conf.desktop + "brm-en.xlsx");
        ScriptConfigLoader scriptConfig = new ScriptConfigLoader("en", splitdir);
        Encoding enc = new EncodingEn();

        new MainImporterEn().importFrom(excel, splitdir, scriptConfig.main, enc);

        new AllScriptsImporterEn().importFrom(excel, splitdir, enc);

        ErrMsg.checkErr();

        /*
         * For first SCRIPTS test, rebuild only SC01.
         * Do not full rebuild all SCxx yet.
         */
        CdRebuilder.rebuildOne(splitdir, Conf.outdir, "SC01");

        System.out.println("English SCRIPTS import/rebuild complete.");
    }
}