package brm.dump;

import java.util.ArrayList;
import java.util.Arrays;

public class CtrlEn extends Ctrl {

	public CtrlEn(){
		b1 = new ArrayList<>();
		b1.addAll(Arrays.asList(
				(byte)0x00,
				(byte)0x05,
				(byte)0x07,
				(byte)0x08,
				(byte)0x0A,
				(byte)0x0C,
				(byte)0x0D,
				(byte)0x0E,
				(byte)0x0F,
				(byte)0x15,
				(byte)0x16,
				(byte)0x17,
				(byte)0x18,
				(byte)0x19,
				(byte)0x1E
		));

		for(int i = 0x20; i <= 0x7A; i++){
			b1.add((byte)(i & 0xFF));
		}

		b2 = new ArrayList<>();
		b2.addAll(Arrays.asList(
				(byte)0x01,
				(byte)0x03,
				(byte)0x04,
				(byte)0x09
		));

		for(int i = 0x80; i <= 0x96; i += 2){
			b2.add((byte)(i & 0xFF));
		}

		b3 = Arrays.asList(
				(byte)0x02,
				(byte)0x10,
				(byte)0x11,
				(byte)0x14
		);

		b4 = Arrays.asList(
				(byte)0x0B,
				(byte)0x12
		);

		b5 = Arrays.asList();
		b6 = Arrays.asList();

		/*
		 * English scripts use additional high-byte glyphs.
		 *
		 * The original CtrlEn only covered ASCII-ish bytes up to 0x7A,
		 * so bytes like B1/E4 crashed as "unsupported".
		 *
		 * Add remaining high bytes as one-byte glyphs unless they are
		 * already known multi-byte control starters.
		 */
		for(int i = 0x7B; i <= 0xFF; i++){
			byte b = (byte)(i & 0xFF);

			if(!b1.contains(b)
					&& !b2.contains(b)
					&& !b3.contains(b)
					&& !b4.contains(b)
					&& !b5.contains(b)
					&& !b6.contains(b)) {

				b1.add(b);
			}
		}
	}

	@Override
	public String decode(byte[] word, int len) {
		if((word[0] & 0xFF) == 0x0A) {
			return " ";
		}

		return ""; // no need to display english ctrl code
	}
}