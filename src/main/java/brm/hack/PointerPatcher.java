package brm.hack;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.Map;

public class PointerPatcher {

    public static void patch(
            RandomAccessFile file,
            long start,
            long end
    ) throws IOException {

        for (long pos = start; pos <= end - 4; pos += 4) {

            file.seek(pos);

            int ptr = Integer.reverseBytes(file.readInt());

            Integer relocated =
                    RelocationMap.get(ptr);

            if (relocated != null) {

                file.seek(pos);

                file.writeInt(
                        Integer.reverseBytes(relocated)
                );

                System.out.printf(
                        "patched %08X -> %08X @ %08X\n",
                        ptr,
                        relocated,
                        pos
                );
            }
        }
    }
}