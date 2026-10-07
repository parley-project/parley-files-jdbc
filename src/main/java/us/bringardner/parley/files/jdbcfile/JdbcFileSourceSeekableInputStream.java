package us.bringardner.parley.files.jdbcfile;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.ISeekableInputStream;

public class JdbcFileSourceSeekableInputStream extends InputStream implements ISeekableInputStream {


	private JdbcFileSource file;
	private long pointer = 0;
	private int bufferSize = 1024*5;
	private boolean closed = false;

	JdbcFileSourceSeekableInputStream(JdbcFileSource file) {
		this.file = file;	
	}

	@Override
	public long length() throws IOException {		
		return file.length(true);
	}

	@Override
	public void seek(long whereTo) throws IOException {
		long size = length();
		while( whereTo > size) {
			//  make it grow

			int expand = (int) (whereTo - size);
			if( expand >= bufferSize) {
				expand = bufferSize;
			}

			try(OutputStream out = file.getOutputStream(true)) {
				out.write(new byte[expand]);
			}

			size += expand;
		}

		pointer = whereTo;		

	}

	private byte [] dubmBuffer = new byte[1];

	/**
	 * Warning... this will be very slow.
	 * But, I don't think it will be used much and I'm too lazy to manage a runtime buffer :-(
	 */
	@Override
	public int read() throws IOException {
		if( !closed ) {
			if( read(dubmBuffer) == dubmBuffer.length) {
				return dubmBuffer[0];
			}
		}
		return -1;
	}

	@Override
	public int read(byte[] data, int start, int end) throws IOException {
		int ret = -1;
		if( !closed) {
			long skip = pointer+start;
			try(InputStream in = file.getInputStream(skip)) {
				if( (ret=in.read(data, start, end))>=0) {
					pointer+=ret;
				}
			}
		}
		return ret;
	}

	@Override
	public void close() throws IOException {
		closed = true;		
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

	@Override
	public int read(byte[] data) throws IOException {
		return read(data,0,data.length);
	}

}
