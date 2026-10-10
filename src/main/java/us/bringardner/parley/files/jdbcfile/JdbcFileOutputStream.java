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


/**
 * @author Tony Bringardner
 *
 */
public class JdbcFileOutputStream extends OutputStream {


	/*
	 *  Chunk size of 10 k
	 * 
	 * Most of the chunk sizes will be determines by the sender through the write functions 
	 */
	/**
	 * @deprecated no effect: a stream's buffer is the factory's
	 * {@link JdbcFileSourceFactory#getBufferSize()}. (It used to allocate a buffer of this size
	 * that the constructor then replaced.)
	 */
	@Deprecated
	public static int chunkSize = (1024*100);
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
		super();

		this.file = file;
		if( !file.exists() ){
			file.createNewFile();
		} else if( !file.isFile()) {
			throw new IOException(file.getCanonicalPath()+" is not a file.");
		} else if( ! append ){
			file.truncate();			
		}
		int size = chunkSize > 0 ? chunkSize : ((JdbcFileSourceFactory)file.getFileSourceFactory()).getChunk_size();
		data = new byte[size];
	}


	/* (non-Javadoc)
	 * @see java.io.OutputStream#write(int)
	 */
	public void write(int b) throws IOException {

		data[pointer++]= (byte)b;
		
		if(pointer >= data.length){
			flush();
		}
		
	}
	
	public void flush() throws IOException {

		if( !isClosed && pointer > 0 ){
			byte [] tmp = data;
			if( pointer < data.length ){
				tmp = new byte[pointer];
				System.arraycopy(data,0,tmp,0,pointer);
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
