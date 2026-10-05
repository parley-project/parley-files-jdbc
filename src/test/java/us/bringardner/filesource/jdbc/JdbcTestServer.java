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
 * ~version~V001.01.47-V000.01.28-V000.01.25-V000.01.23-V000.01.22-V000.01.18-V000.01.10-V000.01.06-V000.01.02-V000.01.01-V000.00.05-V000.00.03-V000.00.01-V000.00.00-
 */
package us.bringardner.filesource.jdbc;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Properties;

import org.hsqldb.Server;

import us.bringardner.io.filesource.FileSourceFactory;
import us.bringardner.io.filesource.jdbcfile.JdbcFileSourceFactory;
import us.bringardner.io.filesource.test.FileSourceTestSupport;
import us.bringardner.io.filesource.test.TestServerController;

/**
 * The HSQLDB server the JDBC tests use: in-memory databases (mainDb and
 * standbyDb plus the port) on the port each test class picks, so classes
 * never share data. setUp also connects a factory, creates the tables, and
 * makes them the factory and server under test for the shared FileSource
 * tests, which disconnect and stop them after the class. Other tests call
 * tearDown.
 */
final class JdbcTestServer implements TestServerController {

	static final String DRIVER = "org.hsqldb.jdbc.JDBCDriver";
	static final String USER = "SA";
	static final String PASSWORD = "";
	static final long TIMEOUT = 4000;

	private static JdbcTestServer running;
	private static JdbcFileSourceFactory factory;

	private final int port;
	private Server server;

	private JdbcTestServer(int port) {
		this.port = port;
	}

	String url() {
		return "jdbc:hsqldb:hsql://localhost:"+port+"/mainDb"+port;
	}

	/** Starts the server on port, connects a factory to it and creates the tables. */
	static JdbcFileSourceFactory setUp(int port) throws Exception {
		running = new JdbcTestServer(port);
		FileSourceTestSupport.startServer(running, TIMEOUT);
		Class.forName(DRIVER);
		DriverManager.getConnection(running.url(), USER, PASSWORD).close();

		factory = (JdbcFileSourceFactory) FileSourceFactory.getFileSourceFactory(JdbcFileSourceFactory.FACTORY_ID);
		Properties p = factory.getConnectProperties();
		p.setProperty(JdbcFileSourceFactory.JDBC_DRIVER, DRIVER);
		p.setProperty(JdbcFileSourceFactory.JDBC_URL, running.url());
		p.setProperty(JdbcFileSourceFactory.JDBC_USERID, USER);
		p.setProperty(JdbcFileSourceFactory.JDBC_PASSWORD, PASSWORD);
		assertTrue(factory.connect(p));

		try(InputStream in = JdbcTestServer.class.getResourceAsStream("/Hsqldb.ddl")) {
			String ddl = new String(in.readAllBytes());
			try(Connection con = factory.getConnection(); Statement stmt = con.createStatement()) {
				stmt.executeUpdate(ddl);
			}
		}
		FileSourceTestSupport.factory = factory;
		FileSourceTestSupport.server = running;
		return factory;
	}

	/** The factory setUp connected. */
	static JdbcFileSourceFactory factory() {
		return factory;
	}

	/** Disconnects the factory and stops the server. */
	static void tearDown() throws IOException {
		if( factory != null){
			factory.disConnect();
			if( FileSourceTestSupport.factory == factory ) {
				FileSourceTestSupport.factory = null;
			}
			factory = null;
		}
		if( running != null) {
			if( FileSourceTestSupport.server == running ) {
				FileSourceTestSupport.server = null;
			}
			FileSourceTestSupport.stopServer(running, TIMEOUT);
		}
		running = null;
	}

	@Override
	public boolean isRunning() {
		return server != null && !server.isNotRunning();
	}

	@Override
	public void start() throws IOException {
		server = new Server();
		// turn off HSQLDB logging
		server.setLogWriter(new PrintWriter(new OutputStream() {
			@Override
			public void write(int b) throws IOException {
			}
		}));
		server.setSilent(true);
		server.setDatabaseName(0, "mainDb"+port);
		server.setDatabasePath(0, "mem:mainDb"+port);
		server.setDatabaseName(1, "standbyDb"+port);
		server.setDatabasePath(1, "mem:standbyDb"+port);
		server.setPort(port);
		server.start();
	}

	@Override
	public void stop() throws IOException {
		if( server != null ) {
			server.stop();
		}
	}

	@Override
	public String getName() {
		return "HSQLDB on port "+port;
	}
}
