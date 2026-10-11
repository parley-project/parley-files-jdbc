package us.bringardner.parley.files.jdbcfile;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.ISeekableInputStream;

/**
 * A read-only view of a JDBC file that can be moved around, as a RandomAccessFile opened with
 * mode "r": seeking never changes the file (a seek past the end leaves the pointer there and
 * the next read returns -1), and bytes are 0..255.
 * <p>
 * It keeps one input stream, positioned at the pointer, so reading on from where the last read
 * ended doesn't start again from the beginning of the file; a seek drops it and the next read
 * opens another at the new place.
 */
public class JdbcFileSourceSeekableInputStream extends InputStream implements ISeekableInputStream {

	private final JdbcFileSource file;
	private long pointer = 0;
	private boolean closed = false;
	/** the stream the last read used, and where it is now; null if there isn't one at the pointer */
	private InputStream current;
	private long currentAt = -1;

	JdbcFileSourceSeekableInputStream(JdbcFileSource file) {
		this.file = file;
	}

	/**
	 * @param chunkSize not used: this stream doesn't write (a seek past the end used to grow the
	 * file in chunks of this size). Kept so the options of getSeekableInputStream still fit.
	 */
	JdbcFileSourceSeekableInputStream(JdbcFileSource file, int chunkSize) {
		this(file);
	}

	@Override
	public long length() throws IOException {
		return file.length(true);
	}

	@Override
	public void seek(long whereTo) throws IOException {
		if( whereTo < 0 ) {
			throw new IOException("Negative seek offset");
		}
		pointer = whereTo;
	}

	private final byte[] one = new byte[1];

	@Override
	public int read() throws IOException {
		return read(one, 0, 1) == 1 ? one[0] & 0xff : -1;
	}

	@Override
	public int read(byte[] data, int off, int len) throws IOException {
		Objects.checkFromIndexSize(off, len, data.length);
		if( closed ) {
			return -1;
		}
		if( len == 0 ) {
			return 0;
		}
		if( current == null || currentAt != pointer ) {
			dropCurrent();
			current = file.getInputStream(pointer);
			currentAt = pointer;
		}
		int n = current.read(data, off, len);
		if( n > 0 ) {
			pointer += n;
			currentAt += n;
		}
		return n;
	}

	@Override
	public int read(byte[] data) throws IOException {
		return read(data, 0, data.length);
	}

	private void dropCurrent() throws IOException {
		InputStream c = current;
		current = null;
		currentAt = -1;
		if( c != null ) {
			c.close();
		}
	}

	@Override
	public void close() throws IOException {
		closed = true;
		dropCurrent();
	}

	@Override
	public long getFilePointer() throws IOException {
		return pointer;
	}

	@Override
	public FileSource getFile() throws IOException {
		return file;
	}

	@Override
	public InputStream getInputStream() throws IOException {
		return this;
	}
}
