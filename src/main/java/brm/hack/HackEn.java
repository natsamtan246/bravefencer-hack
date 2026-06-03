package brm.hack;

import java.io.File;

import brm.Conf;
import brm.ScriptConfigLoader;

public class HackEn {

    public static void main(String[] args) throws Exception {
        String splitdir = Conf.desktop + "brmen/";
        File excel = new File(Conf.desktop + "brm-en.xlsx");

        ScriptConfigLoader scriptConfig =
                new ScriptConfigLoader("en", splitdir);

        Encoding enc = new EncodingEn();

        new MainImporterEn()
                .importFrom(excel, splitdir, scriptConfig.main, enc);

        /*
         * Leave SCRIPTS disabled for the first boot test.
         */
        // new AllScriptsImporterEn()
        //         .importFrom(excel, splitdir, enc);

        ErrMsg.checkErr();

        /*
         * For now, rebuild only MAIN.CD.
         * Do not recompress all SCxx archives yet.
         */
        CdRebuilder.rebuildOne(splitdir, Conf.outdir, "MAIN");

        System.out.println("English MAIN import/rebuild complete.");
    }
}