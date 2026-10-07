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
 * ~version~V001.01.47-V000.01.25-V000.00.01-V000.00.00-
 */
package us.bringardner.parley.files.jdbcfile.pool;

import java.io.*;
import java.sql.*;
/**
 * Test the concurrency management of the JDBCPool
 * Creation date: (10/4/2002 8:34:06 PM)
 * @author: Tony Bringardner
 */
public class TestPool extends Thread 
{
	private static int seq=0;
	private String userid;
	private String password;
	private String url;
	private int id;
	/**
	 * TestPool constructor comment.
	 */
	public TestPool(String myUrl, String myPassword, String myUserid) 
	{
		super();
		userid = myUserid;
		password = myPassword;
		url = myUrl;
		id = getSeq();
	}
	public Connection getConnection(JdbcConnectionPool pool)
	throws SQLException
	{

		long initTime = System.currentTimeMillis();
		Connection ret = null;

		for(int i=0; i< 4 && ret==null; i++) {
			long startTime = System.currentTimeMillis();
			print(" startGetConnection");
			ret = pool.getConnection();
			print(" retFromGetConnection time="+(System.currentTimeMillis()-startTime)+" ret="+ret);
		}

		print(" complete totalTime="+(System.currentTimeMillis()-initTime)+" ret="+ret);
		return ret;

	}

	public String getIdx()
	{
		return "TestPool("+id+")";
	}
	private synchronized int getSeq()
	{
		return seq++;
	}
	private void print(String msg)
	{
		System.out.println(getIdx()+msg);
	}
	public void run()
	{
		try {


			JdbcConnectionPool pool = JdbcConnectionPool.getConnectionPool(url,userid,password);
			Connection [] con = new Connection[pool.getMax()];

			//  First get all of the connections
			for( int i= 0 ; i<con.length; i++ ) {
				con[i] = getConnection(pool);
				//  Make sure it's good
				if( con[i] != null ) {

					DatabaseMetaData md = con[i].getMetaData();
					ResultSet rs = md.getTables(null,null,null,new String[] {"TABLE"});

					while(rs.next()) {
						print("Test connection"+i+" "+ rs.getString("TABLE_NAME"));
					}
					rs.close();

				}
			}

			//  Now give them up one at a time after a key press

			BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
			String tmp = null;
			for( int i= 0 ; i<con.length; i++ ) {
				if( con[i] == null ) {
					print("connection "+i+" was not allocated");
				} else {
					print("Press enter to release the "+i+" conection");
					tmp = in.readLine();
					con[i].close();
					if( tmp == null || tmp.equals("quit")) {
						break;
					}
				}
			}	


		} catch(Exception ex) {
			print("Unexpected error "+ex.toString());
			ex.printStackTrace();
		}
	}
}
