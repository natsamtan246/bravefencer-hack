package brm.hack;

import java.io.File;

import brm.Conf;
import brm.ScriptConfigLoader;
import brm.script.ScriptHandlerMAIN_010_11;

public class HackEn {

    public static void main(String[] args) throws Exception {
        /*
         * English split folder created by CdSplitter.
         * Example:
         * C:\Users\Administrator\Desktop\brmen\
         */
        String splitdir = Conf.desktop + "brmen/";

        /*
         * Your edited English spreadsheet.
         * For first test, use the dumped brm-en.xlsx.
         */
        File excel = new File(Conf.desktop + "brm-en.xlsx");

        /*
         * English script config.
         */
        ScriptConfigLoader scriptConfig = new ScriptConfigLoader("en", splitdir);

        /*
         * Encoding object used by the existing inserters.
         */
        Encoding enc = new Encoding();

        /*
         * Import MAIN sheet.
         */
        new ScriptHandlerMAIN_010_11(splitdir, scriptConfig.main)
                .import_(excel, enc);

        /*
         * Import SCRIPTS sheet.
         * This uses brm-en.xlsx.xml next to the spreadsheet.
         */
        new AllScriptsImporter()
                .importFrom(excel, splitdir, scriptConfig, enc);

        /*
         * Save encoding table for debugging.
         */
        enc.saveAsTbl(Conf.outdir + "english-main.tbl");

        /*
         * Stop if text exceeded fixed space.
         */
        ErrMsg.checkErr();

        /*
         * Rebuild MAIN.CD / SC01.CD / etc. into Conf.outdir.
         */
        CdRebuilder.rebuild(splitdir, Conf.outdir);

        System.out.println("English import/rebuild complete.");
    }
}