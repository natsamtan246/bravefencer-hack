package brm.hack;

import java.io.IOException;
import java.io.RandomAccessFile;

public class ScriptAllocator {

    private final RandomAccessFile file;
    private long cursor;

    public ScriptAllocator(RandomAccessFile file, long startOffset) {
        this.file = file;
        this.cursor = startOffset;
    }

    public int write(byte[] data) throws IOException {
        long addr = cursor;

        file.seek(cursor);
        file.write(data);

        cursor += data.length;

        return (int) addr;
    }
}