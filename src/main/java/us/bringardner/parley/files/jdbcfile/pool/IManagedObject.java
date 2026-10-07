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

/**
 * 
 * Creation date: (7/2/02 4:27:41 PM)
 * @author: Tony Bringardner
 */
public interface IManagedObject 
{
	public final static int NOT_USED = 0;  // a new object
	public final static int FREE = 1;		// inited but not in use
	public final static int IN_USE=2;		// currently in use
	public final static int DESTROYED=3;	// I've been destroyed
	
/**
 * Return the timestamp captured when this object was created
 * Creation date: (7/3/02 3:26:36 PM)
 * @return long
 */
public long getCreateTime();
/**
 * Return the last acess time (this should be set any thime the object is accessed)
 * Creation date: (7/3/02 3:26:36 PM)
 * @return long
 */
public long getLastAccess() ;
/**
 * Return the Object managed here
 * Creation date: (7/3/02 3:26:36 PM)
 * @return java.lang.Object
 */
public java.lang.Object getObject() ;
/**
 * Return the startTime (This should be set as of the last status change)
 * Creation date: (7/3/02 3:26:36 PM)
 * @return long
 */
public long getStartTime() ;
/**
 * GEt the current status
 * Creation date: (7/3/02 3:26:36 PM)
 * @return int
 */
public int getStatus();
/**
 * returns true if the now > (startTime+inactive)
 * Creation date: (7/3/02 3:26:36 PM)
 * @return boolean
 */
public boolean hasExpired(long inactive, long now);
/**
 * true is the current status == DESTROYED
 * Creation date: (7/3/02 3:26:36 PM)
 * @return boolean
 */
public boolean isDestroyed();
public boolean isFree();
public boolean isInUse();
public boolean isNotUsed();
/**
 * true is idleTime > 0 and now > (lastAccess+idleTime)
 * Creation date: (7/3/02 3:26:36 PM)
 * @return long
 */
public boolean reachedInUseIdleTime(long idleTime, long now);
/**
 * true if maxTTL > 0 and now > (createTime+maxTTL)
 * Creation date: (7/3/02 3:26:36 PM)
 * @return long
 */
public boolean reachedTTL(long maxTTL, long now);
/**
 * Release this object and return it to the pool;
 * Creation date: (7/3/02 3:26:36 PM)
 * @return java.lang.Object
 */
public void release();
public void setDestroyed();
public void setFree() ;
public void setInUse() ;
public void setPool(ObjectPool newPool);
public void setCaptureSource(boolean trueOrFalse);
public StackTraceElement [] getSource();
public boolean isCaptureSource() ;

}
