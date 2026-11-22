package app.morphe.extension.instagram.patches.dm.vault;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;

/** Random-access plaintext the viewer decodes and plays; callers read it on background threads. */
public interface ViewerMedia extends Closeable {
    /** Plaintext length. */
    long length();

    /**
     * Reads up to {@code size} bytes at {@code pos}. Returns the count read, or -1 when
     * {@code pos} is at or past the end.
     */
    int readAt(long pos, byte[] buf, int off, int size) throws IOException;

    /** Sequential reader over {@link #readAt}. */
    default InputStream openStream() {
        return new InputStream() {
            private final byte[] one = new byte[1];
            private long pos;

            @Override
            public int read() throws IOException {
                return read(one, 0, 1) == 1 ? one[0] & 0xFF : -1;
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                if (len == 0) return 0;
                int count = readAt(pos, b, off, len);
                if (count > 0) pos += count;
                return count;
            }
        };
    }
}
