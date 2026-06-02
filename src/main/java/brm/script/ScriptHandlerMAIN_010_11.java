package brm.script;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.List;
import java.util.Map;

import brm.Script;
import brm.dump.Charset1;
import brm.dump.Charset2;
import brm.dump.Ctrl;
import brm.dump.EnglishConf;
import brm.hack.Encoding;
import brm.hack.ErrMsg;
import brm.hack.FontGen;
import brm.hack.SentenceSerializer;
import brm.hack.RelocationMap;
import brm.hack.ScriptAllocator;
import brm.hack.PointerPatcher;
import brm.script.Exporter.Callback;
import common.ExcelParser;
import common.ExcelParser.RowCallback;
import common.Util;
import common.WordsCount;

/**
 * 里面的字符全部为单字节编码+Fxxx编码
 */
public class ScriptHandlerMAIN_010_11{
	
	String splitDir;
	Script script;
	private int fontOffset,start,end;
	public ScriptHandlerMAIN_010_11(String splitDir, Script script) {
		this.splitDir = splitDir;
		this.script = script;
		this.fontOffset = Integer.parseInt(script.addr[0],16);
		this.start = Integer.parseInt(script.addr[1],16);
		this.end = Integer.parseInt(script.addr[2],16);
	}

	public void export(
			Callback cb,
			Ctrl ctrl,
			Charset1 charset1,
			Map<String,String> englishTexts
	) throws IOException {

		RandomAccessFile file = null;

		try {

			file = new RandomAccessFile(
					this.splitDir + script.file,
					"r"
			);

			new ScriptReader(
					new Charset2(),
					new EnglishConf(
							englishTexts,
							script.englishFile
					)
			).readTextArea(
					file,
					script.file,
					ctrl,
					charset1,
					cb,
					start,
					end,
					fontOffset,
					0
			);

		} finally {

			if (file != null) {
				file.close();
			}

		}
	}
	
	private Integer lastAddr;
	private Integer lastSentenceLen;
	private StringBuilder sentence = new StringBuilder();
	private ScriptAllocator allocator;

	public void setAllocator(ScriptAllocator allocator) {
    this.allocator = allocator;
}
	
	WordsCount wordsCount = new WordsCount();
	boolean occurErrorSentence=false;

	public void import_(File excel, Encoding enc1) throws IOException{
		SentenceSerializer sParser = new SentenceSerializer(enc1);
		RandomAccessFile file = null;

		try {
			file = new RandomAccessFile(
					this.splitDir + script.file,
					"rw"
			);

			final RandomAccessFile f = file;

			if (this.allocator == null) {
				long safeStart = file.length();
				this.allocator =
						new ScriptAllocator(file, safeStart);
			}

			new ExcelParser(excel).parse(
					"MAIN",
					2,
					new RowCallback() {
						@Override
						public void doInRow(
								List<String> strs,
								int rowNum
						) {

							String addrCell = strs.get(1);

							if (Util.isNotEmpty(addrCell)) {

								if (lastAddr != null) {
									rewriteSentence(
											f,
											sParser
									);
								}

								sentence =
										new StringBuilder();

								lastAddr =
										Integer.parseInt(
												addrCell,
												16
										);
							}

							String ctrls =
									getCell(strs,3);

							String chinese =
									getCell(strs,5);

							sentence
									.append(ctrls)
									.append(chinese);

							String lenCell =
									getCell(strs,2);

							if (Util.isNotEmpty(lenCell)) {
								lastSentenceLen =
										Integer.parseInt(
												lenCell
										);
							}
						}
					}
			);

			rewriteSentence(file,sParser);

			PointerPatcher.patch(
					f,
					start,
					end
			);

			writeNewFont(f,enc1);

		} finally {
			if (file != null) {
				file.close();
			}
		}
	}
	private void rewriteSentence(RandomAccessFile file, SentenceSerializer sParser){
		try {
			byte[] bs = sParser.toBytes(sentence.toString());
			int exceed = bs.length - lastSentenceLen;

			// =========================
			// OPTION A: relocation mode (only when needed OR always safe)
			// =========================
			if (allocator != null && exceed > 0) {
				int newAddr = allocator.write(bs);
				RelocationMap.put(lastAddr, newAddr);
				return;
			}

			// =========================
			// fallback: original behavior
			// =========================
			if (exceed <= 0) {

				file.seek(lastAddr);
				file.write(bs);

				for (int i = bs.length;
				     i < lastSentenceLen;
				     i++) {
					file.writeByte(0);
				}

			} else {
				ErrMsg.add(String.format(
						"MAIN文本超出%d字节 : %s",
						exceed,
						sentence.toString()
				));
			}

		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}
	
 	private void writeNewFont(RandomAccessFile file, Encoding enc1){
		try {
			file.seek(fontOffset);
			file.write(new FontGen().gen(enc1.getSingleByteChars()));
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}
	
	private String getCell(List<String> strs, int cell){
		String ret="";
		if(strs.size()>cell && strs.get(cell)!=null) {
			ret = strs.get(cell);
		}
		return ret;
	}
	
}