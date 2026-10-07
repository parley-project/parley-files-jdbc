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
 * ~version~V001.01.47-V000.01.26-V000.01.25-V000.01.02-V000.00.01-V000.00.00-
 */
package us.bringardner.parley.files.jdbcfile.pool;

/**
 * 
 * Creation date: (7/2/02 4:27:41 PM)
 * @author: Tony Bringardner
 */
public class ManagedObjectImp implements IManagedObject
{

	private Object object;
	private long startTime;
	private int status;
	private ObjectPool pool;
	private long createTime;
	private long lastAccess;
	private StackTraceElement [] source;
	private boolean captureSource = false;

	public ManagedObjectImp (Object obj)
	{
		object = obj;
		status = FREE;
		startTime = System.currentTimeMillis();
		createTime = startTime;
	}
	
	
	public long getCreateTime()
	{
		return createTime;
	}
	/**
	 * 
	 * Creation date: (7/3/02 3:26:36 PM)
	 * @return long
	 */
	public long getLastAccess() {
		return lastAccess;
	}
	/**
	 * 
	 * Creation date: (7/3/02 3:26:36 PM)
	 * @return java.lang.Object
	 */
	public java.lang.Object getObject() {
		return object;
	}
	/**
	 * 
	 * Creation date: (7/3/02 3:26:36 PM)
	 * @return long
	 */
	public long getStartTime() {
		return startTime;
	}
	public int getStatus()
	{
		return status;
	}
	public boolean hasExpired(long inactive, long now)
	{
		boolean ret = (getStartTime()+inactive)<now;

		return ret;
	}
	public boolean isDestroyed()
	{
		return status == IManagedObject.DESTROYED;
	}
	public boolean isFree()
	{
		return status == FREE;
	}
	public boolean isInUse()
	{
		return status == IN_USE;
	}
	public boolean isNotUsed()
	{
		return status == NOT_USED;
	}
	public boolean reachedInUseIdleTime(long idleTime, long now)
	{
		boolean ret = false;

		if( idleTime > 0 ) {
			long tmp =(getLastAccess()+idleTime);
			ret = tmp<now;
			if( ret ){
				//System.out.println("HEre");
			}
		}

		return ret;
	}
	public boolean reachedTTL(long ttl, long now)
	{
		boolean ret = false;
		if( ttl > 0 ) {
			ret = (getCreateTime()+ttl)<now;
		}

		return ret;
	}
	/**
	 * Release this object and return it to the pool;
	 * Creation date: (7/3/02 3:26:36 PM)
	 * @return java.lang.Object
	 */
	public void release()
	{

		setStatus( FREE);
		source = null;
	}

	public void setDestroyed() 
	{ 
		setStatus(DESTROYED);
	}
	public void setFree() 
	{ 
		release();
	}
	public void setInUse() 
	{ 
		setStatus(IN_USE);
	}
	public void setPool(ObjectPool newPool)
	{
		pool = newPool;
	}
	private void setStatus(int stat)
	{
		status = stat;
		startTime = System.currentTimeMillis();
		if( status == IN_USE){
			if( isCaptureSource()){
				RuntimeException ex = new RuntimeException();
				source = ex.getStackTrace();
			}
		}
		if( pool != null ) {
			synchronized (pool) {
				// there should always be a pool
				//  send a signal that somthing has changed.
				pool.notify();	
			}	
		}
	}

	protected void touch()
	{
		lastAccess = System.currentTimeMillis();
	}
	/* (non-Javadoc)
	 * @see us.bringardner.parley.files.jdbcfile.pool.ManagedObject#setCaptureSource(boolean)
	 */
	public void setCaptureSource(boolean trueOrFalse) {
		captureSource = trueOrFalse;
	}

	/* (non-Javadoc)
	 * @see us.bringardner.parley.files.jdbcfile.pool.ManagedObject#getSource()
	 */
	public StackTraceElement[] getSource() {

		return source;
	}

	public boolean isCaptureSource() {
		return captureSource;
	}
}
