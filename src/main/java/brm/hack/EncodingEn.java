package brm.hack;

import java.util.LinkedHashMap;
import java.util.Map;

import common.RscLoader;
import common.Util;

public class EncodingEn extends Encoding {

    private Map<String, byte[]> charCode = new LinkedHashMap<String, byte[]>();

    public EncodingEn() {
        /*
         * Reverse the English dump table:
         * charset1_en is code -> character.
         * Import needs character -> code.
         */
        RscLoader.load("charset1_en", "gbk", new RscLoader.Callback() {
            @Override
            public void doInline(String line) {
                if (line == null || line.trim().isEmpty()) {
                    return;
                }

                String[] arr = line.split("=", 2);

                if (arr.length < 2) {
                    return;
                }

                String hex = arr[0].trim();
                String ch = arr[1];

                charCode.put(ch, Util.decodeHex(hex));
            }
        });

        /*
         * Optional aliases. These help if Excel or copy/paste changes symbols.
         * Keep the aliases only if the target character exists in the table.
         */
        addAliasIfPossible("¡ð", "○");
        addAliasIfPossible("¡õ", "△");
        addAliasIfPossible("¡÷", "□");
        addAliasIfPossible("¡Á", "×");
    }

    @Override
    public byte[] getCode(String char_) {
        byte[] code = charCode.get(char_);

        if (code != null) {
            return code;
        }

        throw new UnsupportedOperationException(
                "English character cannot be encoded: [" + char_ + "]"
        );
    }

    private void addAliasIfPossible(String alias, String existing) {
        if (!charCode.containsKey(alias) && charCode.containsKey(existing)) {
            charCode.put(alias, charCode.get(existing));
        }

        if (!charCode.containsKey(existing) && charCode.containsKey(alias)) {
            charCode.put(existing, charCode.get(alias));
        }
    }
}