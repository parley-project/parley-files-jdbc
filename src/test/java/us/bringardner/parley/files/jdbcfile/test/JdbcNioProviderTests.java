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
package us.bringardner.parley.files.jdbcfile.test;

import org.junit.jupiter.api.BeforeAll;

import us.bringardner.parley.files.test.AbstractNioProviderTests;

/** The shared java.nio.file provider tests over JDBC (HSQLDB). */
public class JdbcNioProviderTests extends AbstractNioProviderTests {

	@BeforeAll
	public static void setUp() throws Exception {
		localTestFileDirPath = "TestFiles";
		localCacheDirPath = "target/NioTestFiles";
		remoteTestFileDirPath = "/NioProviderTests";
		JdbcTestServer.setUp(9007);
	}
}
