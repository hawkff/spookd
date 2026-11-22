package app.morphe.extension.instagram.patches.dm.vault;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;

/** A plain file read in place for the viewer; callers read it on background threads. */
public final class PlainMedia implements ViewerMedia {
    private final RandomAccessFile file;
    private final long length;

    public PlainMedia(File path) throws IOException {
        file = new RandomAccessFile(path, "r");
        try {
            length = file.length();
        } catch (IOException e) {
            file.close();
            throw e;
        }
    }

    @Override
    public long length() {
        return length;
    }

    @Override
    public synchronized int readAt(long pos, byte[] buf, int off, int size) throws IOException {
        if (pos < 0 || off < 0 || size < 0 || size > buf.length - off) {
            throw new IndexOutOfBoundsException();
        }
        if (pos >= length) return -1;
        int count = (int) Math.min(size, length - pos);
        file.seek(pos);
        file.readFully(buf, off, count);
        return count;
    }

    @Override
    public void close() throws IOException {
        file.close();
    }
}
