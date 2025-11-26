package us.bringardner.io.filesource.jdbcfile;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import us.bringardner.io.filesource.IRandomAccessIoController;

/**
 * This class does nothing but read and write data.
 */
public class JdbcRandomAccessIoController implements IRandomAccessIoController {

	private class Chunk {
		boolean isNew=false;
		boolean isDirty=false;
		byte [] data;
		long chunk_number;
		long start;
		long end;
		int size;

		public Chunk() {

		}

		public Chunk(long start,long cn,byte [] b) {
			this.start = start;
			chunk_number = cn;
			data = b;
		}

		public boolean contains(long pos) {			
			long max = (start+data.length);
			return pos >= start && pos < max;
		}	

	}

	private JdbcFileSourceFactory factory ;
	private JdbcFileSource file;
	private Connection con;

	private long lastWritePosition;

	private long lastReadPosition;
	private int maxWriteOffset=-1;
	private Chunk currentChunk;


	public JdbcRandomAccessIoController(JdbcFileSource file) throws IOException {
		this.file = file;
		factory = (JdbcFileSourceFactory) file.getFileSourceFactory();		
	}



	public JdbcFileSource getFile() {
		return file;
	}



	public long getLastWritePosition() {
		return lastWritePosition;
	}



	public long getLastReadPosition() {
		return lastReadPosition;
	}




	public boolean isDirty() {
		return currentChunk !=null && currentChunk.isDirty;
	}

	public boolean contains(long pos) {
		return currentChunk == null ? false: currentChunk.contains(pos);
	}

	
	public long length() throws IOException {
		long ret = file.length();
		if(currentChunk !=null && currentChunk.isNew) {
			if( maxWriteOffset>=0) {
				ret += maxWriteOffset+1;
			}
		}
		return ret;
	}
	/*

Dead lift


Bench press
Overhead lift
Curl

Seated row??

Squat
Lunge
Calf

	 */

	public int read(long pos) throws IOException {
		if(pos<0) {
			throw new IOException("Negative position");
		}

		int ret = -1;
		if(currentChunk == null || !currentChunk. contains(pos)) {
			// find and load data
			loadChunkFor(pos);

		}

		if( currentChunk.isNew) {
			if(currentChunk.isDirty) {

				int offset = (int)(pos-currentChunk.start);
				if( offset < (maxWriteOffset+1)) {
					ret = currentChunk.data[offset];
					lastReadPosition = pos;
				}				
			} else {
				//  will return -1
			}
		} else if(currentChunk.contains(pos)) {
			int offset = (int)(pos-currentChunk.start);
			ret = currentChunk.data[offset];
			lastReadPosition = pos;
		} else {
			throw new IOException("Logic error");
		}

		return ret;
	}

	public void write(long pos, byte value) throws IOException {
		if(pos<0) {
			throw new IOException("Negative position");
		}

		if( currentChunk == null) {
			loadChunkFor(pos);
		}

		if(currentChunk == null || 
				!contains(pos)) {			
			loadChunkFor(pos);
		}

		int offset = (int)(pos-currentChunk.start);
		if( offset < 0 ) {
			throw new IOException("Negative offset pos="+pos+" start="+currentChunk.start);
		}
		currentChunk.data[offset] = value;
		currentChunk.isDirty = true;
		maxWriteOffset = Math.max(offset, maxWriteOffset);

	}

	private void loadChunkFor(long pos) throws IOException {
		// save any new writes

		if( currentChunk!=null ) {
			if( currentChunk.isDirty)  {
				save();
			} else if(maxWriteOffset!=-1) {
				throw new IOException("Max write is not 0 but chunk is not dirty");	
			}
		}
		// find and load data
		getMaxChuckFor(pos);

	}

	public void save() throws IOException {
		if( currentChunk !=null ) {
			if( currentChunk.size != currentChunk.data.length) {
				throw new IOException("Chunk size is not valid chunk="+currentChunk.size+" data = "+currentChunk.data.length);
			}
			if(currentChunk.isDirty) {
				if(currentChunk.isNew || currentChunk.chunk_number<=0) {
					if( maxWriteOffset >= currentChunk.data.length ) {
						throw new IOException("Maxwrite invalid = "+maxWriteOffset+" data.len="+currentChunk.data.length);
					}
					//  chunk is dirty so we know something was written
					//  the offset is 0 based					
					int size = maxWriteOffset+1;
					byte [] tmp = new byte[size];
					for (int idx = 0; idx < tmp.length; idx++) {
						tmp[idx] = currentChunk.data[idx];
					}
					currentChunk.data = tmp;
					currentChunk.size = tmp.length;

					file.appendData(size, currentChunk.data);
				}else {
					try(PreparedStatement pstmt= getConnection().prepareStatement("update file_source.file_data set length=?, data = ? "
							+ " where chunk_number=? and fileid = ?")) {
						pstmt.setInt(1,currentChunk.size);
						pstmt.setBytes(2,currentChunk.data);

						pstmt.setLong(3,currentChunk.chunk_number);
						pstmt.setLong(4, file.getFileId());
						if( pstmt.executeUpdate() != 1) {
							throw new IOException("Can't update chunk="+currentChunk);
						}						
					} catch (SQLException e) {
						throw new IOException(e);
					}
				}
			}
			currentChunk.isDirty = false;
			currentChunk.isNew = false;
			maxWriteOffset = -1;
			file.fieldCache.remove(JdbcFileSource.LENGTH);
		}
	}

	public void setLength(long newLength) throws IOException {
		save();
		long len = file.length();
		if( len == newLength) {
			return;
		}

		if(  newLength > len) {
			expandTo(newLength);
		} else {
			shrinkTo(newLength);
		}
		currentChunk = null;
		file.fieldCache.remove(JdbcFileSource.LENGTH);
	}

	private void expandTo(long newLength) throws IOException {
		long len = file.length();
		long expand = newLength - len;
		if( expand<=0) {
			// positive 
			throw new IOException("Logic error epand shoudl be positive ="+expand);
		}

		// current chunk and pointer does not change
		int size = factory.getChunk_size();
		if( expand > size) {
			byte [] tmp = new byte[size];
			while( expand > size) {
				file.appendData(tmp.length, tmp);
				expand -= size;
			}					
		}
		if( expand > 0 ) {
			byte [] tmp = new byte[(int)expand];					
			file.appendData(tmp.length, tmp);
		}		
	}

	private void shrinkTo(long newLength) throws IOException {
		long len = file.length();
		if( newLength >= len) {
			// positive 
			throw new IOException("Logic error newLength should be > len"+newLength);
		}


		loadChunkFor(newLength);
		if( currentChunk == null ) {
			throw new IOException("Logic current chunk is null");
		}
		long chunkNumber = currentChunk.chunk_number;
		if( currentChunk.end > newLength) {
			chunkNumber--;
		}


		try(PreparedStatement pstmt = getConnection().prepareStatement(
				"delete from file_source.file_data where chunk_number > ? and fileid=?"
				)) {
			pstmt.setLong(1, chunkNumber);
			pstmt.setLong(2, file.getFileId());
			pstmt.executeUpdate();
		} catch (SQLException e) {
			throw new IOException(e);
		}

		len = file.length(true);
		int delta = (int)(newLength- currentChunk.start);

		if( delta > 0 ) {
			byte[] tmp = new byte[delta];
			for (int idx = 0; idx < tmp.length; idx++) {
				tmp[idx] = currentChunk.data[idx];
			}
			// this row was deleted so insert it back with a smaller size
			file.appendData(delta, tmp);
		}

	}



	private Connection getConnection() throws IOException {
		if( con == null ) {
			con = factory.getConnection();
		}
		return con;
	}

	private void getMaxChuckFor(long pos) throws IOException {
		int max = file.getChunkCount();
		
		
		// just to be safe
		maxWriteOffset = -1;
		if( pos >= file.length() || max == 0) {
			Chunk c = new Chunk();
			c.start = file.length();
			c.chunk_number=-1;
			c.size = factory.getChunk_size();

			c.data = new byte[c.size];			
			c.isNew = true;
			c.isDirty = false;
			c.end = c.start+c.data.length;
			currentChunk = c;
			if( pos >= c.end) {
				c.size = (int) (pos-c.start)+10;
				c.data = new byte[c.size];
			}

			return;
		}

		if( currentChunk !=null && currentChunk.end==pos && currentChunk.chunk_number<max) {
			//  just get the next chunk
			long chunk_number = currentChunk.chunk_number+1;
			
			byte [] data1 = file.getChunk(chunk_number);
			long start_pos = currentChunk.end;
			Chunk c = new Chunk(start_pos, chunk_number, data1);
			c.size = data1.length;
			c.end = start_pos+data1.length;
			currentChunk = c;
			return;
		}
	
/*
select max(chunk_number) as chunk_number ,max(start_pos) as start_pos
from (
	select chunk_number, sum(length)  as start_pos
		from file_source.file_data where fileid = 2
		group by chunck_number
		order  by chunk_number
	) tt
where start_pos <= 100
 */
		try(PreparedStatement pstmt1 = getConnection().prepareStatement(
				"select chunk_number, length \n"
				+ "from file_source.file_data \n"
				+ "where fileid = ?\n"
				+ "order by chunk_number"
				)){
			
			long id = file.getFileId();
			pstmt1.setLong(1, id);
			

			long max_chunk = 0;
			long start_pos = 0;
			long end_pos = -1;
			long size=0;
			
			try(ResultSet rs1 = pstmt1.executeQuery()) {
				while(end_pos < pos && rs1.next()) {					
					max_chunk = rs1.getLong("chunk_number");
					size      = rs1.getLong("length");
					start_pos = end_pos < 0 ?0:end_pos;
					end_pos   = start_pos+size;
				}
				if( end_pos == pos) {
					max_chunk++;
					start_pos = end_pos;
				}
				long chunk_number = max_chunk;
				byte [] data1 = file.getChunk(chunk_number);
				if( data1 == null ) {
					data1 = file.getChunk(--chunk_number);
					if(data1 == null ) {
						System.out.println("Can't do it");
						}
				}

				Chunk c = new Chunk(start_pos, chunk_number, data1);
				c.size = data1.length;
				c.end = start_pos+data1.length;
				currentChunk = c;
				 
			}

		} catch (SQLException e) {
			throw new IOException(e);
		}

	}


	@Override
	public void close() throws Exception {
		save();

	}

}
