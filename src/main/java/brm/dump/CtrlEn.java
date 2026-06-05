package brm.dump;

import java.util.ArrayList;
import java.util.Arrays;

import common.Util;

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
		 * Special printable glyphs.
		 *
		 * These are not really text-control commands like [020201].
		 * They are two-byte font symbols. We decode them to readable
		 * square-bracket tags so they are easy to edit in Excel.
		 */
		String specialGlyph = decodeSpecialGlyph(word, len);

		if (specialGlyph != null) {
			return specialGlyph;
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

	@Override
	public byte[] encode(String word) {
		/*
		 * Convert readable symbol tags into their original two-byte glyphs.
		 *
		 * This only affects exact tags like [fire].
		 * It does not change normal numbers, normal text, or raw controls.
		 */
		byte[] specialGlyph = encodeSpecialGlyph(word);

		if (specialGlyph != null) {
			return specialGlyph;
		}

		return super.encode(word);
	}

	private String decodeSpecialGlyph(byte[] word, int len) {
		if (len != 2) {
			return null;
		}

		String hex = toHex(word, 0, len).toUpperCase();

		if ("8081".equals(hex)) {
			return "[moon]";
		}

		if ("8283".equals(hex)) {
			return "[fire]";
		}

		if ("8485".equals(hex)) {
			return "[water]";
		}

		if ("8687".equals(hex)) {
			return "[wind]";
		}

		if ("8889".equals(hex)) {
			return "[sky]";
		}

		if ("8A8B".equals(hex)) {
			return "[earth]";
		}

		if ("8C8D".equals(hex)) {
			return "[sun]";
		}

		if ("9091".equals(hex)) {
			return "[circle]";
		}

		if ("9293".equals(hex)) {
			return "[triangle]";
		}

		if ("9495".equals(hex)) {
			return "[square]";
		}

		if ("9697".equals(hex)) {
			return "[cross]";
		}

		return null;
	}

	private byte[] encodeSpecialGlyph(String word) {
		if ("[moon]".equalsIgnoreCase(word)) {
			return Util.decodeHex("8081");
		}

		if ("[fire]".equalsIgnoreCase(word)) {
			return Util.decodeHex("8283");
		}

		if ("[water]".equalsIgnoreCase(word)) {
			return Util.decodeHex("8485");
		}

		if ("[wind]".equalsIgnoreCase(word)) {
			return Util.decodeHex("8687");
		}

		if ("[sky]".equalsIgnoreCase(word)) {
			return Util.decodeHex("8889");
		}

		if ("[earth]".equalsIgnoreCase(word)) {
			return Util.decodeHex("8A8B");
		}

		if ("[sun]".equalsIgnoreCase(word)) {
			return Util.decodeHex("8C8D");
		}

		if ("[circle]".equalsIgnoreCase(word)) {
			return Util.decodeHex("9091");
		}

		if ("[triangle]".equalsIgnoreCase(word)) {
			return Util.decodeHex("9293");
		}

		if ("[square]".equalsIgnoreCase(word)) {
			return Util.decodeHex("9495");
		}

		if ("[cross]".equalsIgnoreCase(word) || "[xbutton]".equalsIgnoreCase(word)) {
			return Util.decodeHex("9697");
		}

		return null;
	}
}