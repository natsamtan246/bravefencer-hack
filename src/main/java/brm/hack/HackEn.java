package brm.hack;

import java.io.File;

import brm.Conf;
import brm.ScriptConfigLoader;

public class HackEn {

    public static void main(String[] args) throws Exception {
        /*
         * This should be the clean split folder made from the unecm image.
         */
        String splitdir = Conf.desktop + "brmen/";

        /*
         * Your English workbook.
         * Column F is the edit/replacement column.
         */
        File excel = new File(Conf.desktop + "brm-en.xlsx");

        /*
         * English script config.
         */
        ScriptConfigLoader scriptConfig =
                new ScriptConfigLoader("en", splitdir);

        Encoding enc = new Encoding();

        /*
         * Patch only rows where column F is filled.
         */
        new MainImporterEn()
                .importFrom(excel, splitdir, scriptConfig.main, enc);

        new AllScriptsImporterEn()
                .importFrom(excel, splitdir, enc);

        /*
         * Stop here if any line exceeded its original byte slot.
         */
        ErrMsg.checkErr();

        /*
         * Rebuild MAIN.CD, SC01.CD, SC02.CD, etc.
         */
        CdRebuilder.rebuild(splitdir, Conf.outdir);

        System.out.println("English import/rebuild complete.");
    }
}