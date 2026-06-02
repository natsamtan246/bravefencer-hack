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

		printHeaderBytes(
				"JP SC03",
				new File(Conf.jpdir + "SC03.CD")
		);

		printHeaderBytes(
				"EN SC03",
				new File(Conf.endir + "SC03.CD")
		);
		
		//split Japanese ROM
		//CdSplitter splitter=new CdSplitter(Conf.desktop+"brmjp\\");
		//splitter.split(Conf.jpdir);
		
		//split English ROM
//CdSplitter splitter=new CdSplitter(Conf.desktop+"brmen\\");
//splitter.split(Conf.endir);
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
	
	public void split(String cddir) throws IOException {
		long s=System.currentTimeMillis();
		for(String cd:Conf.CDS){
			System.out.println("splitting "+cd+" ....");
			List<File> subcds = new ArrayList<>();
			RandomAccessFile cdfile = new RandomAccessFile(cddir+cd+".CD", "r");
			Map<Integer,Integer> entrance_size = new LinkedHashMap<>();
			int subfilecount = cdfile.readUnsignedByte();
			long fileLength = cdfile.length();

			System.out.printf(
					"%s subfilecount=%d fileLength=%d%n",
					cd,
					subfilecount,
					fileLength
			);

			cdfile.seek(8);

			boolean validHeader = true;

			for(int i = 0; i < subfilecount; i++) {

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
					validHeader = false;

					System.out.printf(
							"[WARN] Invalid %s header at entry %d. entrance=%08X size=%08X fileLength=%d%n",
							cd,
							i,
							entrance,
							size,
							fileLength
					);

					break;
				}

				entrance_size.put(entrance, size);
			}

			if (!validHeader) {
				throw new RuntimeException(
						cd + ".CD does not match the expected JP-style CD archive header. " +
								"Need special English SCxx handling."
				);
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
				byte[] header = new byte[4];
				fis.read(header);
				fis.close();
				if(Arrays.equals(header, PacHeader.MAGIC)){
					splitPac(subcd);
					subcd.delete();
				} else if(Arrays.equals(header, Conf.SQV_MAGIC)){
					String i = subcd.getName().split("-")[0];
					subcd.renameTo(new File(subcd.getParent()+File.separator+i+".sqv"));
				}
			}
		}
		
		System.out.println("split finished. cost(sec):"+(System.currentTimeMillis()-s)/1000);
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
