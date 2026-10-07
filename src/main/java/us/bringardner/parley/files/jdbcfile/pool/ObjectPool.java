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
 * ~version~V001.01.47-V000.01.29-V000.01.26-V000.01.25-V000.01.02-V000.00.01-V000.00.00-
 */
package us.bringardner.parley.files.jdbcfile.pool;

import java.util.ArrayList;
import java.util.Iterator;

/**
 * A pool of reusable Objects.
 * 
 * Creation date: (7/3/02 3:09:53 PM)
 * @author: Tony Bringardner
 */
abstract public class ObjectPool implements Runnable {

	//  Class variable are used to initialize new objects
	private static int defaultMin = 1;
	private static int defaultMax = 40;
	private static int defaultAutoDebug = 35;
	private static boolean defaultDebug = false;


	private static int defaultInterval = 60*1000; // wake up every 10 seconds and do maint
	private static long defaultExpire=5*60*1000; //  Expire object after 5 min of inactivity

	//  This is the longest any object can live
	//  -1 is forever
	private static long defaultMaxTTL = 60*60*1000;  // one hour

	//  How long can this object be in use and idle without being destroyed.
	//  This is to prevent memory leaks.
	private static long defaultInUseIdleTime = 10*60*1000;

	//  Should the thread be a deamon?
	//  If not then the JVM will not die when the main method completes
	private static boolean defaultRunAsDeamon = true;

	//  How long should we wait for an object to free up
	private static long defaultTimeToWait= 5000; //  Five seconds
	private static long defaultTimeToSleep = 500;   //  .5 seconds


	
	//  -----------   Instance variables  ------------	

	private int min=getDefaultMin();
	private int max=getDefaultMax();
	private long interval = getDefaultInterval();
	private long expire	= getDefaultExpire();

	//  This is the longest any object can live
	//  -1 is forever
	private long maxTTL = getDefaultMaxTTL();
	private long inUseIdleTime = getDefaultInUseIdleTime();
	private boolean runAsDeamon = isDefRunAsDeamon();
	private long timeToWait = getDefaultTimeToWait();
	private long timeToSleep = getDefaultTimeToSleep();	
	private int autoDebug = getDefaultAutoDebug();
	private boolean debug = isDefDebug();

	private ArrayList<IManagedObject> pool= new ArrayList<IManagedObject>(getDefaultMax());
	private boolean running = false;
	private boolean started = false;
	private Thread thread;



	abstract public IManagedObject createObject() throws Exception;

	public void destroyAll(){

		IManagedObject obj = null;

		synchronized (pool) {

			for(int i=0, sz=pool.size(); i<sz; i++ ) {
				obj = (IManagedObject)pool.get(i);
				if( !obj.isDestroyed()) {
					try {
						destroyObject(obj.getObject());
						obj.setDestroyed();
					} catch(Throwable ex) {}
				}
			}
			pool.clear();
		}

	}

	abstract public void destroyObject(Object obj) throws Exception;
	
	/**
	 * 
	 * Creation date: (10/4/2002 6:51:55 AM)
	 * @return long
	 */
	public static long getDefaultExpire() {
		return defaultExpire;
	}
	/**
	 * 
	 * Creation date: (10/4/2002 6:51:55 AM)
	 * @return int
	 */
	public static int getDefaultInterval() {
		return defaultInterval;
	}
	/**
	 * 
	 * Creation date: (10/4/2002 6:51:55 AM)
	 * @return long
	 */
	public static long getDefaultInUseIdleTime() {
		return defaultInUseIdleTime;
	}
	/**
	 * 
	 * Creation date: (10/4/2002 6:51:55 AM)
	 * @return int
	 */
	public static int getDefaultMax() {
		return defaultMax;
	}
	/**
	 * 
	 * Creation date: (10/4/2002 6:51:55 AM)
	 * @return long
	 */
	public static long getDefaultMaxTTL() {
		return defaultMaxTTL;
	}
	/**
	 * 
	 * Creation date: (10/4/2002 6:51:55 AM)
	 * @return int
	 */
	public static int getDefaultMin() {
		return defaultMin;
	}
	/**
	 * 
	 * Creation date: (10/4/2002 4:37:23 PM)
	 * @return long
	 */
	public static long getDefaultTimeToSleep() {
		return defaultTimeToSleep;
	}
	/**
	 * 
	 * Creation date: (10/4/2002 4:28:57 PM)
	 * @return long
	 */
	public static long getDefaultTimeToWait() {
		return defaultTimeToWait;
	}
	/**
	 * 
	 * Creation date: (7/3/02 3:29:22 PM)
	 * @return long
	 */
	final public long getExpire() {
		return expire;
	}
	/**
	 * 
	 * Creation date: (7/4/2002 2:36:52 PM)
	 * @return long
	 */
	public long getInUseIdleTime() {
		return inUseIdleTime;
	}
	/**
	 * 
	 * Creation date: (7/3/02 3:29:22 PM)
	 * @return int
	 */
	final public int getMax() {
		return max;
	}
	/**
	 * 
	 * Creation date: (7/3/02 3:29:22 PM)
	 * @return int
	 */
	final public int getMin() {
		return min;
	}
	abstract public String getName();
	/**
	 *  GEt an object from the pool.  If no object is available then
	 * 	create one unless we've reached max in size.
	 **/

	public IManagedObject getObject()
			throws Exception
	{
		IManagedObject ret = null;
		IManagedObject mo = null;
		String message = "Nothing attempted";


		synchronized (pool) {			
			long maxTime = System.currentTimeMillis()+timeToWait;

			while( ret == null && System.currentTimeMillis()< maxTime ) {
				for(int i=0, sz=pool.size(); i< sz; i++ ) {
					mo = (IManagedObject)pool.get(i);
					if(!mo.isDestroyed() && mo.getStatus() == IManagedObject.FREE ) {
						ret = mo;
						break;
					}
				}

				if( ret == null ){
					if( pool.size() < max) {
						if( (ret = createObject()) != null ) {
							ret.setPool(this);
							pool.add(ret);
							if(debug|| pool.size()>=autoDebug){
								ret.setCaptureSource(true);
							}
						} else {
							message = "Attempt to create object failed";
						}
					} else {
						message = "Pool size("+pool.size()+" is > max("+max+")";
					}
				}

				if( ret != null ) {
					ret.setInUse();
				} else {
					//  Could wait the full timeToWait.  But under some conditions
					//  and object may be availible without notifying the pool.
					//  So, we'll wake up every so often and check it.
					pool.wait(timeToSleep);
				}
			}
		}

		if( ret == null ) {
			if(debug|| pool.size()>=autoDebug){
				System.out.println(getAllSource());
			}
			throw new ObjectCreateException(message);
		}

		return ret;
	}
	/**
	 * 
	 * Creation date: (10/4/2002 4:35:32 PM)
	 * @return long
	 */
	public long getTimeToWait() {
		return timeToWait;
	}
	/**
	 * 
	 * Creation date: (10/4/2002 6:51:55 AM)
	 * @return boolean
	 */
	public static boolean isDefRunAsDeamon() {
		return defaultRunAsDeamon;
	}
	/**
	 * 
	 * Creation date: (7/4/2002 3:01:12 PM)
	 * @return boolean
	 */
	public boolean isRunAsDeamon() {
		return runAsDeamon;
	}
	private void remove(Iterator<IManagedObject> it, IManagedObject obj)
	{

		it.remove();
		obj.setDestroyed();
		try {
			destroyObject(obj.getObject());
		} catch(Exception ex) {}

	}

	public void run()
	{

		started = running = true;
		
		while( running ) {


			//  do work here
			IManagedObject obj = null;
			long now = System.currentTimeMillis();

			synchronized (pool) {
				final Iterator<IManagedObject> it = pool.iterator(); 
				while( running && it.hasNext() ) {
					obj = (IManagedObject)it.next();
					try {
						if( obj.isDestroyed() ) {
							//  This object has been destroyed?  Get rid of it
							remove(it,obj);
							//System.out.println("removed destroyed object from pool");
						} else 	if( obj.isFree() ) {
							/*
							The Object is free.
							If it has lived longer than the MaxTTL
							or if the pool.size is larger than the min and this object has been
							idle for more than the expiration time
							then get rid of it.
							 */
							if( obj.reachedTTL(maxTTL,now) ||
									(pool.size() > min && obj.hasExpired(expire,now))) {
								System.out.println("removed maxTTL object from pool min="+min+" size="+pool.size());
								remove(it,obj);
							}
						} else if(obj.reachedInUseIdleTime(inUseIdleTime,now) ) {
							//  If it's in use but has not been accessed 
							//	for more than inUseIdleTime then get rid of it
							//  
							remove(it,obj);
							//System.out.println("removed InUseIdle object from pool");							
						}
					} catch(Exception ex) {
						System.err.println("Un expected error in "+getName()+" run");
						ex.printStackTrace(System.err);
					}
				}
			}

			if( running ) {
				try {
					Thread.sleep(interval);
				} catch(Exception ex) {}
			}


		}

		try {
			destroyAll();	
		} catch (Throwable e) {

		}


	}

	public void setDeamon(boolean b)
	{
		if( thread != null ) {
			thread.setDaemon(b);
		}
	}
	/**
	 * 
	 * Creation date: (10/4/2002 6:51:55 AM)
	 * @param newDefExpire long
	 */
	public static void setDefaultExpire(long newDefExpire) {
		defaultExpire = newDefExpire;
	}
	/**
	 * 
	 * Creation date: (10/4/2002 6:51:55 AM)
	 * @param newDefInterval int
	 */
	public static void setDefaultInterval(int newDefInterval) {
		defaultInterval = newDefInterval;
	}
	/**
	 * 
	 * Creation date: (10/4/2002 6:51:55 AM)
	 * @param newDefInUseIdleTime long
	 */
	public static void setDefaultInUseIdleTime(long newDefInUseIdleTime) {
		defaultInUseIdleTime = newDefInUseIdleTime;
	}
	/**
	 * 
	 * Creation date: (10/4/2002 6:51:55 AM)
	 * @param newDefMax int
	 */
	public static void setDefaultMax(int newDefMax) {
		defaultMax = newDefMax;
	}
	/**
	 * 
	 * Creation date: (10/4/2002 6:51:55 AM)
	 * @param newDefMaxTTL long
	 */
	public static void setDefaultMaxTTL(long newDefMaxTTL) {
		defaultMaxTTL = newDefMaxTTL;
	}
	/**
	 * 
	 * Creation date: (10/4/2002 6:51:55 AM)
	 * @param newDefMin int
	 */
	public static void setDefaultMin(int newDefMin) {
		defaultMin = newDefMin;
	}
	/**
	 * 
	 * Creation date: (10/4/2002 6:51:55 AM)
	 * @param newDefRunAsDeamon boolean
	 */
	public static void setDefaultRunAsDeamon(boolean newDefRunAsDeamon) {
		defaultRunAsDeamon = newDefRunAsDeamon;
	}
	/**
	 * 
	 * Creation date: (10/4/2002 4:37:23 PM)
	 * @param newDefTimeToSleep long
	 */
	public static void setDefaultTimeToSleep(long newDefTimeToSleep) {
		defaultTimeToSleep = newDefTimeToSleep;
	}
	/**
	 * 
	 * Creation date: (10/4/2002 4:28:57 PM)
	 * @param newDefTimeToWait long
	 */
	public static void setDefaultTimeToWait(long newDefTimeToWait) {
		defaultTimeToWait = newDefTimeToWait;
	}
	/**
	 * 
	 * Creation date: (7/3/02 3:29:22 PM)
	 * @param newExpire long
	 */
	final public void setExpire(long newExpire) {
		expire = newExpire;
	}
	/**
	 * 
	 * Creation date: (7/3/02 3:29:22 PM)
	 * @param newMax int
	 */
	final public void setInterval(long newInterval) 
	{
		interval = newInterval;
	}
	/**
	 * 
	 * Creation date: (7/4/2002 2:36:52 PM)
	 * @param newInUseIdleTime long
	 */
	public void setInUseIdleTime(long newInUseIdleTime) {
		inUseIdleTime = newInUseIdleTime;
	}
	/**
	 * 
	 * Creation date: (7/3/02 3:29:22 PM)
	 * @param newMax int
	 */
	final public void setMax(int newMax) {
		max = newMax;
	}
	/**
	 * 
	 * Creation date: (7/3/02 3:29:22 PM)
	 * @param newMax int
	 */
	final public void setMaxTTL(long newTTL) 
	{
		maxTTL = newTTL;
	}
	/**
	 * 
	 * Creation date: (7/3/02 3:29:22 PM)
	 * @param newMin int
	 */
	final public void setMin(int newMin) {
		min = newMin;
	}
	/**
	 * 
	 * Creation date: (7/4/2002 3:01:12 PM)
	 * @param newRunAsDeamon boolean
	 */
	public void setRunAsDeamon(boolean newRunAsDeamon) {
		runAsDeamon = newRunAsDeamon;
	}
	/**
	 * 
	 * Creation date: (10/4/2002 4:35:32 PM)
	 * @param newTimeToWait long
	 */
	public void setTimeToWait(long newTimeToWait) {
		timeToWait = newTimeToWait;
	}
	final public int  size()
	{
		return pool.size();
	}
	public void start()
	{
		if( !running ) {
			thread = new Thread(this);
			thread.setName(getName());
			thread.setDaemon(runAsDeamon);
			thread.start();

		}
	}
	public void stop()
	{
		running = false;
		thread.interrupt();

	}
	public static int getDefaultAutoDebug() {
		return defaultAutoDebug;
	}
	public static void setDefaultAutoDebug(int defaultAutoDebug) {
		ObjectPool.defaultAutoDebug = defaultAutoDebug;
	}
	public static boolean isDefDebug() {
		return defaultDebug;
	}
	public static void setDefaultDebug(boolean defaultDebug) {
		ObjectPool.defaultDebug = defaultDebug;
	}
	public int getAutoDebug() {
		return autoDebug;
	}
	public void setAutoDebug(int autoDebug) {
		this.autoDebug = autoDebug;
	}
	public boolean isDebug() {
		return debug;
	}
	public void setDebug(boolean debug) {
		this.debug = debug;
	}
	public long getTimeToSleep() {
		return timeToSleep;
	}
	public void setTimeToSleep(long timeToSleep) {
		this.timeToSleep = timeToSleep;
	}
	public long getInterval() {
		return interval;
	}
	public long getMaxTTL() {
		return maxTTL;
	}
	public boolean isRunning() {
		return running;
	}
	
	public boolean hasStarted () {
		return started;
	}
	
	public Thread getThread() {
		return thread;
	}

	public String getAllSource() {
		StringBuffer ret = new StringBuffer();

		for(int i=0, sz=pool.size(); i< sz; i++ ) {
			IManagedObject mo = (IManagedObject)pool.get(i);
			ret.append("item "+i+" is ");
			switch(mo.getStatus()) {
			case IManagedObject.FREE: ret.append("Free"); break; 
			case IManagedObject.DESTROYED: ret.append("DESTROYED"); break;
			case IManagedObject.IN_USE: ret.append("IN_USE"); break;
			case IManagedObject.NOT_USED: ret.append("NOT_USED"); break;
			default: ret.append("Unknown status = "+mo.getStatus());
			}

			ret.append(" isCaptureSource = "+mo.isCaptureSource()+"\n");
			if( mo.isCaptureSource() ){
				StackTraceElement [] source = mo.getSource();
				if( source == null ) {
					ret.append("\t No Source.");
				} else {
					for (int idx = 0; idx < source.length; idx++) {
						ret.append('\t');
						ret.append(source[idx].getClassName());
						ret.append('.');
						ret.append(source[idx].getMethodName());
						ret.append(" line ");
						ret.append(source[idx].getLineNumber());
						ret.append("\n");
					}
				}
			} 

		}	

		return ret.toString();
	}
}
