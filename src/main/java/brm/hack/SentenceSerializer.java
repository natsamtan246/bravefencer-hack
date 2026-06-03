package brm.hack;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;

import brm.dump.Ctrl;
import brm.dump.SentenceSplitter;
import brm.dump.SentenceSplitter.Callback;

public class SentenceSerializer {

	Encoding enc1;

	public SentenceSerializer(Encoding enc1) {
		this.enc1 = enc1;
	}

	public byte[] toBytes(String sentence) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();

		new SentenceSplitter().splitToWords(sentence, new Callback() {
			@Override
			public void onReadWord(boolean isCtrl, String word) {
				if (isCtrl) {
					byte[] bytes = Ctrl.encode(word);
					out.write(bytes, 0, bytes.length);
				} else {
					byte[] bytes = enc1.getCode(word);
					out.write(bytes, 0, bytes.length);
				}
			}
		});

		out.write(0); // end marker
		return out.toByteArray();
	}

	public byte[] toBytes(Sentence sentence) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();

		new SentenceSplitter().splitToWords(sentence.sentence, new Callback() {
			@Override
			public void onReadWord(boolean isCtrl, String word) {
				if (isCtrl) {
					byte[] bytes = Ctrl.encode(word);
					out.write(bytes, 0, bytes.length);
				} else {
					byte[] bytes = enc1.getCode(word);
					out.write(bytes, 0, bytes.length);
				}
			}
		});

		out.write(0); // end marker

		byte[] raw = out.toByteArray();

		int exceed = raw.length - sentence.len;
		if (exceed > 0) {
			throw new UnsupportedOperationException(String.format(
					"SCRIPTS文本超出%d字节 : %s",
					exceed,
					sentence.sentence
			));
		}

		/*
		 * Same behavior as before:
		 * return exactly sentence.len bytes.
		 * If raw is shorter, Arrays.copyOf pads with zeroes.
		 */
		return Arrays.copyOf(raw, sentence.len);
	}
}