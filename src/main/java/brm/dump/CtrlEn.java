package brm.dump;

import java.util.ArrayList;
import java.util.Arrays;

public class CtrlEn extends Ctrl {

	public CtrlEn(){
		b1 = new ArrayList<>();
		b1.addAll(Arrays.asList((byte)0x0,(byte)0x5,(byte)7,(byte)8,(byte)0xa,(byte)0xc,(byte)0xd,(byte)0xe,(byte)0xf,
				(byte)0x15,(byte)0x16,(byte)0x17,(byte)0x18,(byte)0x19,(byte)0x1e));
		for(int i=0x20;i<=0x7A;i++){
			b1.add((byte)(i&0xFF));
		}

		b2 = new ArrayList<>();
		b2.addAll(Arrays.asList((byte)0x1,(byte)3,(byte)0x4,(byte)0x9));
		for(int i=0x80;i<=0x96;i+=2){
			b2.add((byte)(i&0xFF));
		}

		b3 = Arrays.asList((byte)0x2,(byte)0x10,(byte)0x14,(byte)0x11);
		b4 = Arrays.asList((byte)0xB,(byte)0x12);
		b5 = Arrays.asList();
		b6 = Arrays.asList();
	}

	@Override
	public String decode(byte[] word, int len) {
		int first = word[0] & 0xFF;

		/*
		 * End marker. Do not display.
		 */
		if (first == 0x00) {
			return "";
		}

		if (first == 0x0A) {
			return "[br]";
		}

		/*
		 * Friendly tags already understood by Ctrl.encode().
		 */
		if (first == 0x01 && len >= 2) {
			return String.format("[c%X]", word[1] & 0xFF);
		}

		if (first == 0x07) {
			return "[wt]";
		}

		if (first == 0x08) {
			return "[new]";
		}

		if (first == 0x09 && len >= 2) {
			return String.format("[box%X]", word[1] & 0xFF);
		}

		if (first == 0x17) {
			return "[sel]";
		}

		/*
		 * Raw fallback.
		 *
		 * Examples:
		 * [13]
		 * [1C]
		 * [0B010203]
		 * [12010203]
		 *
		 * Ctrl.encode() can already turn these back into bytes.
		 */
		return "[" + toHex(word, 0, len) + "]";
	}
}