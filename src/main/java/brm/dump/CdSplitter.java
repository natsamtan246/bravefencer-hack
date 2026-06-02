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
				printRelaxedTableCandidates(cdfile, cd);

				cdfile.close();

				throw new RuntimeException(
						cd + ".CD: could not find a valid archive header. " +
								"This file may use a different format."
				);
			}

			System.out.printf(
					"%s mode=%s headerOffset=%08X tableOffset=%08X subfilecount=%d fileLength=%d%n",
					cd,
					header.hasCountHeader ? "count-header" : "table-only",
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
	private static class CdArchiveHeader {
		long headerOffset;
		long tableOffset;
		int subfileCount;
		boolean hasCountHeader;

		CdArchiveHeader(
				long headerOffset,
				long tableOffset,
				int subfileCount,
				boolean hasCountHeader
		) {
			this.headerOffset = headerOffset;
			this.tableOffset = tableOffset;
			this.subfileCount = subfileCount;
			this.hasCountHeader = hasCountHeader;
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
			CdArchiveHeader tableOnly = tryEntryTableAt(cdfile, cd, offset);

			if (tableOnly != null) {
				System.out.printf(
						"[FOUND] %s table-only archive at %08X, count=%d%n",
						cd,
						tableOnly.tableOffset,
						tableOnly.subfileCount
				);

				return tableOnly;
			}
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

		long trailingBytes = fileLength - lastEnd;

		if (trailingBytes < 0 || trailingBytes > Conf.LOGIC_BLOCK) {
			return null;
		}

		return new CdArchiveHeader(headerOffset, tableOffset, subfileCount, true);
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

				return new CdArchiveHeader(
						tableOffset,
						tableOffset,
						validEntries,
						false
				);
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
