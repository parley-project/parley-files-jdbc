/**
 * <PRE>
 * 
 * Copyright Tony Bringarder 1998, 2025 
 * 
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *       <A href="http://www.apache.org/licenses/LICENSE-2.0">http://www.apache.org/licenses/LICENSE-2.0</A>
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 *  </PRE>
 *   
 *   
 *	@author Tony Bringardner   
 *
 *
 * ~version~V001.01.47-V001.01.38-V000.01.32-V000.01.27-V000.00.01-V000.00.00-
 */
/*
 * Created on Dec 7, 2004
 *
 */
package us.bringardner.parley.files.jdbcfile;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Objects;


/**
 * @author Tony Bringardner
 *
 */
public class JdbcFileOutputStream extends OutputStream implements us.bringardner.parley.files.Syncable {


	/** The most a buffer holds, which is the size of the rows this stream stores. */
	private final int chunk;
	/** The first buffer is small: most files are far smaller than a chunk (100 K by default). */
	private static final int FIRST_BUFFER = 8 * 1024;

	/*
	 * Most of the chunk sizes will be determines by the sender through the write functions 
	 */
	private JdbcFileSource file;
	private byte [] data;
	private int pointer = 0;	
	private boolean isClosed=false;

	
	public JdbcFileOutputStream(JdbcFileSource file, boolean append) throws IOException{
		this(file, append, 0);
	}

	/**
	 * @param chunkSize the size of the rows this stream stores: its buffer is filled, then
	 * stored as one row. 0 for the factory's chunk size.
	 */
	public JdbcFileOutputStream(JdbcFileSource file, boolean append, int chunkSize) throws IOException{
		this(file, append, chunkSize, false);
	}

	/**
	 * @param created the file was made for this stream just now: it is empty and a file, so none of that
	 * is looked up again and nothing is truncated (each of those can be a statement)
	 */
	JdbcFileOutputStream(JdbcFileSource file, boolean append, int chunkSize, boolean created) throws IOException{
		super();

		this.file = file;
		if( created ) {
			// (nothing to check or empty)
		} else if( !file.exists() ){
			file.createNewFile();
		} else if( !file.isFile()) {
			throw new IOException(file.getCanonicalPath()+" is not a file.");
		} else if( ! append ){
			file.truncate();			
		}
		chunk = chunkSize > 0 ? chunkSize : ((JdbcFileSourceFactory)file.getFileSourceFactory()).getChunk_size();
		data = new byte[Math.min(chunk, FIRST_BUFFER)];
	}

	private void ensureOpen() throws IOException {
		if( isClosed ) {
			throw new IOException("Stream closed");
		}
	}

	/** Room for needed bytes in the buffer (never more than a chunk): it doubles up to the chunk size */
	private void ensureCapacity(int needed) {
		if( needed > data.length ) {
			data = Arrays.copyOf(data, Math.min(chunk, Math.max(needed, data.length * 2)));
		}
	}

	/* (non-Javadoc)
	 * @see java.io.OutputStream#write(int)
	 */
	public void write(int b) throws IOException {
		ensureOpen();
		if( pointer >= chunk ) {
			flush();   // (a full chunk that could not be stored the last time)
		}
		ensureCapacity(pointer + 1);
		data[pointer++]= (byte)b;
		
		if(pointer >= chunk){
			flush();
		}
		
	}

	/**
	 * Copies in blocks (OutputStream's own loop is a call for each byte), storing a row each time the
	 * buffer holds a chunk.
	 */
	@Override
	public void write(byte[] b, int off, int len) throws IOException {
		ensureOpen();
		Objects.checkFromIndexSize(off, len, b.length);
		while( len > 0 ) {
			if( pointer >= chunk ) {
				flush();   // (also a chunk that could not be stored the last time)
			}
			int n = Math.min(len, chunk - pointer);
			ensureCapacity(pointer + n);
			System.arraycopy(b, off, data, pointer, n);
			pointer += n;
			off += n;
			len -= n;
			if( pointer >= chunk ) {
				flush();
			}
		}
	}
	
	/**
	 * Writes what is held (see {@link #flush()}). Each write is committed, so that is as durable as
	 * the database makes it.
	 */
	@Override
	public void sync() throws IOException {
		flush();
	}

	public void flush() throws IOException {

		if( !isClosed && pointer > 0 ){
			byte [] tmp = data;
			if( pointer < data.length ){
				tmp = Arrays.copyOf(data, pointer);
			}
			file.appendData(tmp.length,tmp);
			pointer =  0;
		}
	}

	
	public void close() throws IOException {
		if( isClosed) {
			return;
		}

		flush();
		isClosed = true;

	}

	

}
