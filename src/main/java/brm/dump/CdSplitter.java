package brm.dump;

import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import brm.Conf;
import brm.PacHeader;
import common.Util;

public class CdSplitter {
	public static void main(String[] args) throws IOException {

		//testWholeFileUncompress(
		//		"EN SC03",
			//	new File(Conf.endir + "SC03.CD")
		//);

		//testWholeFileUncompress(
		//		"EN SC04",
		//		new File(Conf.endir + "SC04.CD")
		//);


		//printHeaderBytes(
				//"JP SC04",
				//new File(Conf.jpdir + "SC04.CD")
		//);

		//printHeaderBytes(
				//"EN SC04",
				//new File(Conf.endir + "SC04.CD")
		//);
		
		//split Japanese ROM
		//CdSplitter splitter=new CdSplitter(Conf.desktop+"brmjp\\");
		//splitter.split(Conf.jpdir);
		
		//split English ROM
CdSplitter splitter=new CdSplitter(Conf.desktop+"brmen\\");
splitter.split(Conf.endir);
	}
	
	private String splitDir;
	
	public static CdSplitter newInstance(){
		String splitDir = System.getProperty("java.io.tmpdir");
		if(!splitDir.endsWith(File.separator))
			splitDir+=File.separator;
		splitDir+="brm"+File.separator;
		return new CdSplitter(splitDir);
	}
	
	public CdSplitter(String splitDir) {
		this.splitDir = splitDir;
		File dir=new File(splitDir);
		Util.mkdirs(dir);
		File[] children=dir.listFiles();
		if(children!=null && children.length>0){
			throw new RuntimeException(splitDir+" is not empty!!");
		}
	}
	private boolean isValidSubCdEntry(
			long fileLength,
			int entrance,
			int size
	) {
		if (entrance < 0) {
			return false;
		}

		if (size <= 0) {
			return false;
		}

		if (entrance >= fileLength) {
			return false;
		}

		if ((long) entrance + (long) size > fileLength) {
			return false;
		}

		if (entrance % Conf.LOGIC_BLOCK != 0) {
			return false;
		}

		return true;
	}

	private static void testWholeFileUncompress(String label, File input) throws IOException {
		File out = new File(input.getParentFile(), input.getName() + ".unpacked_test");

		System.out.println("Trying whole-file uncompress: " + label);
		System.out.println("input=" + input.getAbsolutePath());
		System.out.println("output=" + out.getAbsolutePath());

		BufferedOutputStream fos =
				new BufferedOutputStream(new FileOutputStream(out));

		RandomAccessFile raf = null;

		try {
			raf = new RandomAccessFile(input, "r");

			byte[] all = new byte[(int) raf.length()];
			raf.readFully(all);

			Uncompresser.uncompress(
					new ByteArrayInputStream(all),
					fos
			);

		} finally {
			fos.flush();
			fos.close();

			if (raf != null) {
				raf.close();
			}
		}

		printHeaderBytes(label + " unpacked", out);
	}
	private void saveRawCd(
			RandomAccessFile cdfile,
			File outFile
	) throws IOException {

		BufferedOutputStream fos =
				new BufferedOutputStream(
						new FileOutputStream(outFile)
				);

		try {
			cdfile.seek(0);

			byte[] buf = new byte[0x8000];

			int read;

			while ((read = cdfile.read(buf)) != -1) {
				fos.write(buf, 0, read);
			}

		} finally {
			fos.flush();
			fos.close();
		}
	}
	
	public void split(String cddir) throws IOException {
		long s=System.currentTimeMillis();
		for(String cd:Conf.CDS){
			System.out.println("splitting "+cd+" ....");
			List<File> subcds = new ArrayList<>();
			RandomAccessFile cdfile = new RandomAccessFile(cddir+cd+".CD", "r");
			Map<Integer,Integer> entrance_size = new LinkedHashMap<>();
			long fileLength = cdfile.length();

			CdArchiveHeader header = findArchiveHeader(cdfile, cd);

			if (header == null) {

				int detectedShift = findDominantPacShift(cdfile, cd);

				if (detectedShift >= 0) {
					boolean shiftedPacOk =
							splitShiftedPacLists(cdfile, cd, detectedShift);

					if (shiftedPacOk) {
						cdfile.close();
						continue;
					}
				}

				if (detectedShift != 0x3C6) {
					boolean shiftedPacOk =
							splitShiftedPacLists(cdfile, cd, 0x3C6);

					if (shiftedPacOk) {
						cdfile.close();
						continue;
					}
				}

				if (detectedShift != 0x4DA) {
					boolean shiftedPacOk =
							splitShiftedPacLists(cdfile, cd, 0x4DA);

					if (shiftedPacOk) {
						cdfile.close();
						continue;
					}
				}

				CdArchiveHeader partial = findBestPartialSectorTable(cdfile, cd);

				if (partial != null) {
					header = partial;
				}
			}

			if (header == null) {
				printRelaxedTableCandidates(cdfile, cd);
				printByteOffsetTableCandidates(cdfile, cd);
				printPacCandidates(cdfile, cd);

				printMagicCandidatesAnywhere(cdfile, cd, PacHeader.MAGIC, "PAC");
				printMagicCandidatesAnywhere(cdfile, cd, Conf.SQV_MAGIC, "SQV");

				printHeaderBytes("JP " + cd, new File(Conf.jpdir + cd + ".CD"));
				printHeaderBytes("EN " + cd, new File(Conf.endir + cd + ".CD"));

				cdfile.close();

				throw new RuntimeException(
						cd + ".CD: could not find a valid archive header. " +
								"This file may use a different format."
				);
			}

			System.out.printf(
					"%s mode=%s headerOffset=%08X tableOffset=%08X subfilecount=%d fileLength=%d%n",
					cd,
					header.hasCountHeader
							? "count-header"
							: (header.partialTable ? "partial-table" : "table-only"),
					header.headerOffset,
					header.tableOffset,
					header.subfileCount,
					fileLength
			);

			cdfile.seek(header.tableOffset);

			for(int i = 0; i < header.subfileCount; i++) {

				long tablePos = cdfile.getFilePointer();

				int rawEntrance = cdfile.readInt();
				int rawSize = cdfile.readInt();

				int entrance =
						Util.hilo(rawEntrance) * Conf.LOGIC_BLOCK;

				int size =
						Util.hilo(rawSize);

				System.out.printf(
						"%s entry %d tablePos=%08X rawEntrance=%08X rawSize=%08X entrance=%08X size=%08X%n",
						cd,
						i,
						tablePos,
						rawEntrance,
						rawSize,
						entrance,
						size
				);

				if (!isValidSubCdEntry(fileLength, entrance, size)) {
					cdfile.close();

					throw new RuntimeException(
							cd + ".CD invalid entry " + i +
									" entrance=" + Integer.toHexString(entrance) +
									" size=" + Integer.toHexString(size)
					);
				}

				entrance_size.put(entrance, size);
			}
			
			new File(splitDir+cd).mkdirs();
			int index=0;
			for(Entry<Integer,Integer> e:entrance_size.entrySet()){
				int entrace = e.getKey();
				cdfile.seek(entrace);
				subcds.add(saveSubCd(cdfile, splitDir+cd+File.separator, index, e.getKey(), e.getValue()));
				index++;
			}
			cdfile.close();
			
			//subcd means linkedPacList or sqv or other unknown format
			for(File subcd:subcds){
				RandomAccessFile fis = new RandomAccessFile(subcd,"r");

				byte[] magic = new byte[4];
				fis.read(magic);
				fis.close();

				if(Arrays.equals(magic, PacHeader.MAGIC)){
					splitPac(subcd);
					subcd.delete();
				} else if(Arrays.equals(magic, Conf.SQV_MAGIC)){
					String i = subcd.getName().split("-")[0];
					subcd.renameTo(new File(subcd.getParent()+File.separator+i+".sqv"));
				}
			}
		}
		
		System.out.println("split finished. cost(sec):"+(System.currentTimeMillis()-s)/1000);
	}
	private boolean hasMagicAt(
			RandomAccessFile file,
			long offset,
			byte[] magic
	) throws IOException {

		if (offset < 0 || offset + magic.length > file.length()) {
			return false;
		}

		file.seek(offset);

		for (int i = 0; i < magic.length; i++) {
			if (file.readUnsignedByte() != (magic[i] & 0xFF)) {
				return false;
			}
		}

		return true;
	}
	private boolean splitShiftedPacLists(
			RandomAccessFile cdfile,
			String cd,
			int shift
	) throws IOException {

		long fileLength = cdfile.length();

		File cdDir = new File(splitDir + cd);
		cdDir.mkdirs();

		int listIndex = 0;
		int foundLists = 0;

		System.out.printf(
				"[INFO] Trying shifted PAC-list splitter for %s shift=%03X%n",
				cd,
				shift
		);

		for (long offset = shift; offset + 4 < fileLength; offset += Conf.LOGIC_BLOCK) {

			if (!hasMagicAt(cdfile, offset, PacHeader.MAGIC)) {
				continue;
			}

			long pacAddr = offset;
			int innerIndex = 0;
			boolean endPac = false;
			boolean failed = false;

			File outDir = new File(
					cdDir,
					String.format("%03d", listIndex)
			);

			outDir.mkdirs();

			System.out.printf(
					"[SHIFT-PAC] %s list=%03d offset=%08X%n",
					cd,
					listIndex,
					offset
			);

			try {
				while (!endPac) {

					if (pacAddr + Conf.LOGIC_BLOCK > fileLength) {
						System.out.printf(
								"[WARN] %s list=%03d hit EOF before PAC header at %08X%n",
								cd,
								listIndex,
								pacAddr
						);
						failed = true;
						break;
					}

					cdfile.seek(pacAddr);

					if (!hasMagicAt(cdfile, pacAddr, PacHeader.MAGIC)) {
						System.out.printf(
								"[WARN] %s list=%03d expected PAC magic at %08X, stopping list%n",
								cd,
								listIndex,
								pacAddr
						);
						failed = true;
						break;
					}

					cdfile.seek(pacAddr);

					PacHeader h = PacHeader.read(cdfile);

					if (h.len <= 0 || pacAddr + h.len > fileLength + Conf.LOGIC_BLOCK) {
						System.out.printf(
								"[WARN] %s list=%03d bad PAC len=%08X at %08X, stopping list%n",
								cd,
								listIndex,
								h.len,
								pacAddr
						);
						failed = true;
						break;
					}

					byte[] buf = new byte[h.len - Conf.LOGIC_BLOCK];
					cdfile.readFully(buf);

					File outFile = new File(
							outDir,
							innerIndex + "." + h.pacType
					);

					BufferedOutputStream fos =
							new BufferedOutputStream(
									new FileOutputStream(outFile)
							);

					try {
						if (h.pacType == 4) {
							Uncompresser.uncompress(
									new ByteArrayInputStream(buf),
									fos
							);
						} else {
							fos.write(buf);
						}
					} finally {
						fos.flush();
						fos.close();
					}

					endPac = h.endFlag == 1;

					int roundedLen = Util.get0x800Multiple(h.len);
					pacAddr += roundedLen;
					innerIndex++;
				}

			} catch (RuntimeException ex) {
				failed = true;

				System.out.printf(
						"[WARN] %s list=%03d failed at offset=%08X inner=%d: %s%n",
						cd,
						listIndex,
						pacAddr,
						innerIndex,
						ex.getMessage()
				);
			}

			if (!failed && innerIndex > 0) {
				foundLists++;
				listIndex++;

				offset = pacAddr - Conf.LOGIC_BLOCK;
			} else {
				System.out.printf(
						"[INFO] Skipping bad shifted PAC candidate %s list=%03d offset=%08X%n",
						cd,
						listIndex,
						offset
				);

				delete(outDir);
			}
		}

		System.out.printf(
				"[INFO] Shifted PAC-list splitter found %d lists for %s%n",
				foundLists,
				cd
		);

		return foundLists > 0;
	}
	private void printPacCandidates(
			RandomAccessFile cdfile,
			String cd
	) throws IOException {

		long fileLength = cdfile.length();
		int printed = 0;

		System.out.println("[DEBUG] Searching sector-aligned PAC candidates for " + cd);

		for (long offset = 0; offset + 4 < fileLength; offset += Conf.LOGIC_BLOCK) {

			cdfile.seek(offset);

			byte[] magic = new byte[4];
			cdfile.readFully(magic);

			if (Arrays.equals(magic, PacHeader.MAGIC)) {

				System.out.printf(
						"[PAC?] %s offset=%08X%n",
						cd,
						offset
				);

				try {
					cdfile.seek(offset);
					PacHeader h = PacHeader.read(cdfile);

					System.out.printf(
							"       len=%08X type=%d endFlag=%d%n",
							h.len,
							h.pacType,
							h.endFlag
					);

				} catch (Exception ex) {
					System.out.println("       PacHeader.read failed: " + ex.getMessage());
				}

				printed++;

				if (printed >= 80) {
					System.out.println("[DEBUG] Stopping after 80 PAC candidates.");
					return;
				}
			}
		}

		if (printed == 0) {
			System.out.println("[DEBUG] No sector-aligned PAC candidates found for " + cd);
		}
	}
	private static class CdArchiveHeader {
		long headerOffset;
		long tableOffset;
		int subfileCount;
		boolean hasCountHeader;
		boolean partialTable;

		CdArchiveHeader(
				long headerOffset,
				long tableOffset,
				int subfileCount,
				boolean hasCountHeader,
				boolean partialTable
		) {
			this.headerOffset = headerOffset;
			this.tableOffset = tableOffset;
			this.subfileCount = subfileCount;
			this.hasCountHeader = hasCountHeader;
			this.partialTable = partialTable;
		}
	}
	private boolean isValidByteOffsetEntry(
			long fileLength,
			int entrance,
			int size
	) {
		if (entrance < 0) {
			return false;
		}

		if (size <= 0) {
			return false;
		}

		if (entrance >= fileLength) {
			return false;
		}

		if ((long) entrance + (long) size > fileLength) {
			return false;
		}

		return true;
	}
	private void printByteOffsetTableCandidates(
			RandomAccessFile cdfile,
			String cd
	) throws IOException {

		long fileLength = cdfile.length();
		long scanLimit = fileLength - 8;
		int printed = 0;

		System.out.println("[DEBUG] Searching BYTE-offset table candidates for " + cd);

		for (long offset = 0; offset <= scanLimit; offset += 4) {

			cdfile.seek(offset);

			int previousEntrance = -1;
			int validEntries = 0;
			long lastEnd = -1;
			int firstEntrance = -1;
			int firstSize = -1;

			for (int i = 0; i < 500; i++) {

				if (cdfile.getFilePointer() + 8 > fileLength) {
					break;
				}

				int rawEntrance = cdfile.readInt();
				int rawSize = cdfile.readInt();

				int entrance = Util.hilo(rawEntrance);
				int size = Util.hilo(rawSize);

				if (!isValidByteOffsetEntry(fileLength, entrance, size)) {
					break;
				}

				if (previousEntrance >= 0 && entrance <= previousEntrance) {
					break;
				}

				if (validEntries == 0) {
					firstEntrance = entrance;
					firstSize = size;
				}

				previousEntrance = entrance;
				lastEnd = (long) entrance + (long) size;
				validEntries++;
			}

			if (validEntries >= 5) {
				long trailing = fileLength - lastEnd;

				System.out.printf(
						"[BYTE-CANDIDATE] %s offset=%08X count=%d first=%08X firstSize=%08X lastEnd=%08X trailing=%08X%n",
						cd,
						offset,
						validEntries,
						firstEntrance,
						firstSize,
						lastEnd,
						trailing
				);

				printed++;

				if (printed >= 60) {
					System.out.println("[DEBUG] Stopping after 60 byte-offset candidates.");
					return;
				}
			}
		}

		System.out.println("[DEBUG] No BYTE-offset candidates found for " + cd);
	}
	private void printMagicCandidatesAnywhere(
			RandomAccessFile cdfile,
			String cd,
			byte[] magic,
			String label
	) throws IOException {

		long fileLength = cdfile.length();

		if (fileLength > Integer.MAX_VALUE) {
			System.out.println("[DEBUG] " + cd + " too large for full magic scan");
			return;
		}

		cdfile.seek(0);

		byte[] all = new byte[(int) fileLength];
		cdfile.readFully(all);

		int printed = 0;

		System.out.println("[DEBUG] Searching " + label + " magic anywhere for " + cd);

		for (int i = 0; i <= all.length - magic.length; i++) {
			boolean match = true;

			for (int j = 0; j < magic.length; j++) {
				if (all[i + j] != magic[j]) {
					match = false;
					break;
				}
			}

			if (match) {
				System.out.printf(
						"[MAGIC-%s] %s offset=%08X aligned0x800=%s%n",
						label,
						cd,
						i,
						(i % Conf.LOGIC_BLOCK == 0)
				);

				printed++;

				if (printed >= 80) {
					System.out.println("[DEBUG] Stopping after 80 " + label + " magic hits.");
					return;
				}
			}
		}

		if (printed == 0) {
			System.out.println("[DEBUG] No " + label + " magic found anywhere for " + cd);
		}
	}
	private CdArchiveHeader findArchiveHeader(RandomAccessFile cdfile, String cd) throws IOException {
		long fileLength = cdfile.length();

		// First try normal JP-style count header at 0.
		CdArchiveHeader normal = tryArchiveHeaderAt(cdfile, cd, 0);
		if (normal != null) {
			return normal;
		}

		System.out.println("[INFO] Normal header failed for " + cd + ". Scanning for shifted header/table...");

		long scanLimit = fileLength - 8;

		for (long offset = 0; offset <= scanLimit; offset += 4) {

			if ((offset & 0xFFFFF) == 0) {
				System.out.printf("[SCAN] %s offset=%08X / %08X%n", cd, offset, scanLimit);
			}

			// Mode 1: shifted count-style header.
			CdArchiveHeader found = tryArchiveHeaderAt(cdfile, cd, offset);

			if (found != null) {
				System.out.printf(
						"[FOUND] %s count-header archive at %08X, table=%08X, count=%d%n",
						cd,
						found.headerOffset,
						found.tableOffset,
						found.subfileCount
				);

				return found;
			}

			// Mode 2: raw entry table with no count header.
			//CdArchiveHeader tableOnly = tryEntryTableAt(cdfile, cd, offset);

			//if (tableOnly != null) {
				//System.out.printf(
						//"[FOUND] %s table-only archive at %08X, count=%d%n",
						//cd,
						//tableOnly.tableOffset,
						//tableOnly.subfileCount
				//);

				//return tableOnly;
			//}
		}

		return null;
	}
	private CdArchiveHeader tryArchiveHeaderAt(
			RandomAccessFile cdfile,
			String cd,
			long headerOffset
	) throws IOException {

		long fileLength = cdfile.length();
		long lastEnd = -1;
		int firstEntrance = -1;

		if (headerOffset < 0 || headerOffset + 8 >= fileLength) {
			return null;
		}

		cdfile.seek(headerOffset);

		int rawCount = cdfile.readInt();
		int rawReserved = cdfile.readInt();

		int subfileCount = Util.hilo(rawCount);

		// JP files look like:
		// A4 00 00 00 00 00 00 00
		// meaning count as little-endian int, then zero.
		if (subfileCount <= 0 || subfileCount > 1000) {
			return null;
		}

		if (rawReserved != 0) {
			return null;
		}

		long tableOffset = headerOffset + 8;
		long tableEnd = tableOffset + ((long) subfileCount * 8L);

		if (tableEnd > fileLength) {
			return null;
		}

		int previousEntrance = -1;
		int validEntries = 0;

		cdfile.seek(tableOffset);

		for (int i = 0; i < subfileCount; i++) {
			int rawEntrance = cdfile.readInt();
			int rawSize = cdfile.readInt();

			int entrance = Util.hilo(rawEntrance) * Conf.LOGIC_BLOCK;
			int size = Util.hilo(rawSize);

			if (i == 0) {
				firstEntrance = entrance;
			}

			if (!isValidSubCdEntry(fileLength, entrance, size)) {
				return null;
			}

			if (previousEntrance >= 0 && entrance <= previousEntrance) {
				return null;
			}

			previousEntrance = entrance;
			lastEnd = (long) entrance + (long) size;
			validEntries++;
		}

		if (validEntries != subfileCount) {
			return null;
		}

		if (firstEntrance < 0) {
			return null;
		}

		if (tableEnd > firstEntrance) {
			return null;
		}

		long trailingBytes = fileLength - lastEnd;

		if (trailingBytes < 0 || trailingBytes > Conf.LOGIC_BLOCK) {
			return null;
		}

		return new CdArchiveHeader(headerOffset, tableOffset, subfileCount, true, false);

	}
	private int findDominantPacShift(
			RandomAccessFile cdfile,
			String cd
	) throws IOException {

		long fileLength = cdfile.length();

		if (fileLength > Integer.MAX_VALUE) {
			return -1;
		}

		cdfile.seek(0);

		byte[] all = new byte[(int) fileLength];
		cdfile.readFully(all);

		int[] counts = new int[Conf.LOGIC_BLOCK];

		for (int i = 0; i <= all.length - PacHeader.MAGIC.length; i++) {
			boolean match = true;

			for (int j = 0; j < PacHeader.MAGIC.length; j++) {
				if (all[i + j] != PacHeader.MAGIC[j]) {
					match = false;
					break;
				}
			}

			if (match) {
				counts[i % Conf.LOGIC_BLOCK]++;
			}
		}

		int bestShift = -1;
		int bestCount = 0;

		for (int i = 0; i < counts.length; i++) {
			if (counts[i] > bestCount) {
				bestCount = counts[i];
				bestShift = i;
			}
		}

		if (bestCount > 0) {
			System.out.printf(
					"[INFO] Dominant PAC shift for %s is %03X with %d hits%n",
					cd,
					bestShift,
					bestCount
			);

			return bestShift;
		}

		System.out.println("[INFO] No PAC shift detected for " + cd);
		return -1;
	}
	private CdArchiveHeader tryEntryTableAt(
			RandomAccessFile cdfile,
			String cd,
			long tableOffset
	) throws IOException {

		long fileLength = cdfile.length();

		if (tableOffset < 0 || tableOffset + 16 >= fileLength) {
			return null;
		}

		cdfile.seek(tableOffset);

		int previousEntrance = -1;
		int validEntries = 0;
		long lastEnd = -1;

		for (int i = 0; i < 1000; i++) {

			if (cdfile.getFilePointer() + 8 > fileLength) {
				break;
			}

			int rawEntrance = cdfile.readInt();
			int rawSize = cdfile.readInt();

			int entrance =
					Util.hilo(rawEntrance) * Conf.LOGIC_BLOCK;

			int size =
					Util.hilo(rawSize);

			if (!isValidSubCdEntry(fileLength, entrance, size)) {
				break;
			}

			if (previousEntrance >= 0 && entrance <= previousEntrance) {
				break;
			}

			previousEntrance = entrance;
			lastEnd = (long) entrance + (long) size;
			validEntries++;

			long trailingBytes = fileLength - lastEnd;

			// If this entry ends at EOF/padding and we have enough entries,
			// this is probably the complete table.
			if (validEntries >= 8 &&
					trailingBytes >= 0 &&
					trailingBytes <= Conf.LOGIC_BLOCK) {

				return new CdArchiveHeader(tableOffset, tableOffset, validEntries, false, false);
			}
		}

		return null;
	}
	private void printRelaxedTableCandidates(
			RandomAccessFile cdfile,
			String cd
	) throws IOException {

		long fileLength = cdfile.length();
		long scanLimit = fileLength - 8;
		int printed = 0;

		System.out.println("[DEBUG] Searching relaxed table candidates for " + cd);

		for (long offset = 0; offset <= scanLimit; offset += 4) {

			cdfile.seek(offset);

			int previousEntrance = -1;
			int validEntries = 0;
			long lastEnd = -1;
			int firstEntrance = -1;
			int firstSize = -1;

			for (int i = 0; i < 300; i++) {

				if (cdfile.getFilePointer() + 8 > fileLength) {
					break;
				}

				int rawEntrance = cdfile.readInt();
				int rawSize = cdfile.readInt();

				int entrance = Util.hilo(rawEntrance) * Conf.LOGIC_BLOCK;
				int size = Util.hilo(rawSize);

				if (!isValidSubCdEntry(fileLength, entrance, size)) {
					break;
				}

				if (previousEntrance >= 0 && entrance <= previousEntrance) {
					break;
				}

				if (validEntries == 0) {
					firstEntrance = entrance;
					firstSize = size;
				}

				previousEntrance = entrance;
				lastEnd = (long) entrance + (long) size;
				validEntries++;
			}

			if (validEntries >= 5) {
				long trailing = fileLength - lastEnd;

				System.out.printf(
						"[CANDIDATE] %s offset=%08X count=%d first=%08X firstSize=%08X lastEnd=%08X trailing=%08X%n",
						cd,
						offset,
						validEntries,
						firstEntrance,
						firstSize,
						lastEnd,
						trailing
				);

				printed++;

				if (printed >= 40) {
					System.out.println("[DEBUG] Stopping after 40 candidates.");
					return;
				}
			}
		}

		System.out.println("[DEBUG] No relaxed candidates found for " + cd);
	}
	private CdArchiveHeader findBestPartialSectorTable(
			RandomAccessFile cdfile,
			String cd
	) throws IOException {

		long fileLength = cdfile.length();
		long scanLimit = fileLength - 8;

		CdArchiveHeader best = null;
		int bestCount = 0;
		long bestLastEnd = -1;

		System.out.println("[INFO] Searching partial sector table for " + cd);

		for (long offset = 0; offset <= scanLimit; offset += 4) {

			cdfile.seek(offset);

			int previousEntrance = -1;
			int validEntries = 0;
			long lastEnd = -1;
			int firstEntrance = -1;

			for (int i = 0; i < 300; i++) {

				if (cdfile.getFilePointer() + 8 > fileLength) {
					break;
				}

				int rawEntrance = cdfile.readInt();
				int rawSize = cdfile.readInt();

				int entrance =
						Util.hilo(rawEntrance) * Conf.LOGIC_BLOCK;

				int size =
						Util.hilo(rawSize);

				if (!isValidSubCdEntry(fileLength, entrance, size)) {
					break;
				}

				if (previousEntrance >= 0 && entrance <= previousEntrance) {
					break;
				}

				if (validEntries == 0) {
					firstEntrance = entrance;
				}

				previousEntrance = entrance;
				lastEnd = (long) entrance + (long) size;
				validEntries++;
			}

			if (validEntries >= 5 && firstEntrance == Conf.LOGIC_BLOCK) {

				if (validEntries > bestCount ||
						(validEntries == bestCount && lastEnd > bestLastEnd)) {

					bestCount = validEntries;
					bestLastEnd = lastEnd;

					best = new CdArchiveHeader(
							offset,
							offset,
							validEntries,
							false,
							true
					);
				}
			}
		}

		if (best != null) {
			System.out.printf(
					"[FOUND] %s partial sector table at %08X, count=%d%n",
					cd,
					best.tableOffset,
					best.subfileCount
			);
		}

		return best;
	}
	
	private File saveSubCd(RandomAccessFile cdfile, String dir, int index, int entrance, int size) throws IOException{
		File subfile = new File(dir+String.format("%03d-%08X", index, entrance));
		BufferedOutputStream fos = new BufferedOutputStream(new FileOutputStream(subfile));
		if (size < 0) {
			throw new RuntimeException(
					"Negative subCD size: "
							+ size
							+ " entrance="
							+ Integer.toHexString(entrance)
			);
		}

		byte[] buf = new byte[size];
		cdfile.read(buf);
		fos.write(buf);
		fos.flush();
		fos.close();
		return subfile;
	}
	
	private void splitPac(File linkedPacList) throws IOException{
		RandomAccessFile linkedPacListStream = new RandomAccessFile(linkedPacList, "r");
		String indexInCd = linkedPacList.getName().split("-")[0];
		File dir = new File(linkedPacList.getParent()+File.separator+indexInCd);
		dir.mkdir();
		boolean endPac=false;
		int pacAddr = 0, index=0;
		BufferedOutputStream oheader= new BufferedOutputStream(new FileOutputStream(new File(dir,"headers.bin")));
		
		while(!endPac){
			linkedPacListStream.seek(pacAddr);
			PacHeader h = PacHeader.read(linkedPacListStream);
			oheader.write(h.toBytes());
			endPac = h.endFlag==1;
			
			byte[] buf = new byte[h.len-Conf.LOGIC_BLOCK];
			linkedPacListStream.read(buf);
			BufferedOutputStream fos=new BufferedOutputStream(new FileOutputStream(dir.getAbsolutePath()+File.separator+index+"."+h.pacType));
			if(h.pacType==4){	//4=compressed file
				Uncompresser.uncompress(new ByteArrayInputStream(buf), fos);
			} else {
				fos.write(buf);
			}
			fos.flush();
			fos.close();
			
			h.len=Util.get0x800Multiple(h.len);
			pacAddr += h.len;
			index++;
		}
		linkedPacListStream.close();
		oheader.flush();
		oheader.close();
	}

	private static void printHeaderBytes(String label, File file) throws IOException {
		RandomAccessFile raf = new RandomAccessFile(file, "r");

		byte[] buf = new byte[0x80];
		int read = raf.read(buf);
		raf.close();

		System.out.println("==== " + label + " ====");
		System.out.println(file.getAbsolutePath());
		System.out.println("length=" + file.length());

		for (int i = 0; i < read; i++) {
			if (i % 16 == 0) {
				System.out.printf("%08X: ", i);
			}

			System.out.printf("%02X ", buf[i] & 0xFF);

			if (i % 16 == 15) {
				System.out.println();
			}
		}

		System.out.println();
	}
	
	/**
	 * 
	 * @param file e.g. MAIN/001/0.1
	 * @return
	 */
	public File getFile(String file){
		return new File(splitDir+file);
	}
	
	public String getSplitDir() {
		return splitDir;
	}

	public void dispose(){
		delete(new File(splitDir));
	}
	
	private void delete(File f){
		if(f.isDirectory()) {
			for(File _f:f.listFiles()){
				delete(_f);
			}
		}
		f.delete();
	}

}
