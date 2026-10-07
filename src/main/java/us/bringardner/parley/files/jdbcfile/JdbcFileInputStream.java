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
 * ~version~V001.01.47-V000.01.27-V000.01.04-V000.00.01-V000.00.00-
 */
/*
 * Created on Dec 7, 2004
 *
 */
package us.bringardner.parley.files.jdbcfile;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * @author Tony Bringardner
 *
 */
public class JdbcFileInputStream extends InputStream {


	private JdbcFileSource file;
	private byte [] data ;
	private int pos=-1;
	private ResultSet rs;
	private int currentChunk;
	private boolean eof = false;



	/**
	 * @param startingPosition 
	 * @throws FileNotFoundException
	 * @throws IOException
	 * 
	 */
	public JdbcFileInputStream(JdbcFileSource file, long startingPosition) throws  IOException {
		super();
		this.file = file;
		if( !file.exists() || !file.isFile()){
			throw new FileNotFoundException("Jdbc file "+file.getCanonicalPath()+") does not exists or is not a file.");
		}

		currentChunk = 1;
		if( startingPosition> 0 ) {
			skip(startingPosition);
		}
	}

	/* (non-Javadoc)
	 * @see java.io.InputStream#read()
	 */
	public int read() throws IOException {
		int ret = -1;

		if( !eof ) {
			if(pos < 0 || pos >= data.length){
				loadNextChunk();
				ret = read();
			} else {
				ret = data[pos++];
				ret = ret & 0xff;
			}
		}

		return ret;
	}
	
	
	private void loadNextChunk() throws IOException {

		data = file.getChunk(currentChunk++);
		pos = 0;
		if( data == null ){
			eof = true;
		}
	}

	public void close() throws IOException {
		if( rs != null ){
			try {
				rs.close();
				file.setLastAccessTime(System.currentTimeMillis());
			} catch (SQLException e) {
				e.printStackTrace();
				throw new IOException("Error closing result set. e="+e);
			}
		}
	}

}
