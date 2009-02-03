/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node;

import com.db4o.ObjectContainer;

import freenet.client.async.ChosenBlock;
import freenet.client.async.ClientContext;
import freenet.keys.Key;
import freenet.support.Logger;
import freenet.support.OOMHandler;
import freenet.support.RandomGrabArrayItem;
import freenet.support.RandomGrabArrayItemExclusionList;
import freenet.support.TokenBucket;
import freenet.support.math.RunningAverage;

/**
 * Starts requests.
 * Nobody starts a request directly, you have to go through RequestStarter.
 * And you have to provide a RequestStarterClient. We do round robin between 
 * clients on the same priority level.
 */
public class RequestStarter implements Runnable, RandomGrabArrayItemExclusionList {

	/*
	 * Priority classes
	 */
	/** Anything more important than FProxy */
	public static final short MAXIMUM_PRIORITY_CLASS = 0;
	/** FProxy etc */
	public static final short INTERACTIVE_PRIORITY_CLASS = 1;
	/** FProxy splitfile fetches */
	public static final short IMMEDIATE_SPLITFILE_PRIORITY_CLASS = 2;
	/** USK updates etc */
	public static final short UPDATE_PRIORITY_CLASS = 3;
	/** Bulk splitfile fetches */
	public static final short BULK_SPLITFILE_PRIORITY_CLASS = 4;
	/** Prefetch */
	public static final short PREFETCH_PRIORITY_CLASS = 5;
	/** Anything less important than prefetch (redundant??) */
	public static final short MINIMUM_PRIORITY_CLASS = 6;
	
	public static final short NUMBER_OF_PRIORITY_CLASSES = MINIMUM_PRIORITY_CLASS - MAXIMUM_PRIORITY_CLASS + 1; // include 0 and max !!
	
	/** If true, local requests are subject to shouldRejectRequest(). If false, they are only subject to the token
	 * buckets and the thread limit. FIXME make configurable. */
	private static final boolean LOCAL_REQUESTS_COMPETE_FAIRLY = true;
	
	public static boolean isValidPriorityClass(int prio) {
		return !((prio < MAXIMUM_PRIORITY_CLASS) || (prio > MINIMUM_PRIORITY_CLASS));
	}
	
	final BaseRequestThrottle throttle;
	final TokenBucket inputBucket;
	final TokenBucket outputBucket;
	final RunningAverage averageInputBytesPerRequest;
	final RunningAverage averageOutputBytesPerRequest;
	RequestScheduler sched;
	final NodeClientCore core;
	final NodeStats stats;
	private long sentRequestTime;
	private final boolean isInsert;
	private final boolean isSSK;
	
	public RequestStarter(NodeClientCore node, BaseRequestThrottle throttle, String name, TokenBucket outputBucket, TokenBucket inputBucket,
			RunningAverage averageOutputBytesPerRequest, RunningAverage averageInputBytesPerRequest, boolean isInsert, boolean isSSK) {
		this.core = node;
		this.stats = core.nodeStats;
		this.throttle = throttle;
		this.name = name;
		this.outputBucket = outputBucket;
		this.inputBucket = inputBucket;
		this.averageOutputBytesPerRequest = averageOutputBytesPerRequest;
		this.averageInputBytesPerRequest = averageInputBytesPerRequest;
		this.isInsert = isInsert;
		this.isSSK = isSSK;
	}

	void setScheduler(RequestScheduler sched) {
		this.sched = sched;
	}
	
	void start() {
		sched.start(core);
		core.getExecutor().execute(this, name);
		sched.queueFillRequestStarterQueue();
	}
	
	final String name;
	
	@Override
	public String toString() {
		return name;
	}
	
	void realRun() {
		ChosenBlock req = null;
		sentRequestTime = System.currentTimeMillis();
		// The last time at which we sent a request or decided not to
		long cycleTime = sentRequestTime;
		while(true) {
			// Allow 5 minutes before we start killing requests due to not connecting.
			OpennetManager om;
			if(core.node.peers.countConnectedPeers() < 3 && (om = core.node.getOpennet()) != null &&
					System.currentTimeMillis() - om.getCreationTime() < 5*60*1000) {
				try {
					synchronized(this) {
						wait(1000);
					}
				} catch (InterruptedException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
				continue;
			}
			sched.moveKeysFromCooldownQueue();
			boolean logMINOR = Logger.shouldLog(Logger.MINOR, this);
			if(req == null) {
				req = sched.grabRequest();
			}
			if(req != null) {
				if(logMINOR) Logger.minor(this, "Running "+req+" priority "+req.getPriority());
				// Wait
				long delay = throttle.getDelay();
				if(logMINOR) Logger.minor(this, "Delay="+delay+" from "+throttle);
				long sleepUntil = cycleTime + delay;
				if(!LOCAL_REQUESTS_COMPETE_FAIRLY) {
					inputBucket.blockingGrab((int)(Math.max(0, averageInputBytesPerRequest.currentValue())));
					outputBucket.blockingGrab((int)(Math.max(0, averageOutputBytesPerRequest.currentValue())));
				}
				long now;
				do {
					now = System.currentTimeMillis();
					if(now < sleepUntil)
						try {
							Thread.sleep(sleepUntil - now);
							if(logMINOR) Logger.minor(this, "Slept: "+(sleepUntil-now)+"ms");
						} catch (InterruptedException e) {
							// Ignore
						}
				} while(now < sleepUntil);
				String reason;
				if(LOCAL_REQUESTS_COMPETE_FAIRLY) {
					if((reason = stats.shouldRejectRequest(true, isInsert, isSSK, true, false, null)) != null) {
						if(logMINOR)
							Logger.minor(this, "Not sending local request: "+reason);
						// Wait one throttle-delay before trying again
						cycleTime = System.currentTimeMillis();
						continue; // Let local requests compete with all the others
					}
				} else {
					stats.waitUntilNotOverloaded(isInsert);
				}
			} else {
				if(logMINOR) Logger.minor(this, "Waiting...");				
				// Always take the lock on RequestStarter first. AFAICS we don't synchronize on RequestStarter anywhere else.
				// Nested locks here prevent extra latency when there is a race, and therefore allow us to sleep indefinitely
				synchronized(this) {
					req = sched.grabRequest();
					if(req == null) {
						try {
							wait(100*1000); // as close to indefinite as I'm comfortable with! Toad
						} catch (InterruptedException e) {
							// Ignore
						}
					}
				}
			}
			if(req == null) continue;
			if(!startRequest(req, logMINOR)) {
				// Don't log if it's a cancelled transient request.
				if(!((!req.isPersistent()) && req.isCancelled()))
					Logger.normal(this, "No requests to start on "+req);
			}
			req = null;
			cycleTime = sentRequestTime = System.currentTimeMillis();
		}
	}

	private boolean startRequest(ChosenBlock req, boolean logMINOR) {
		if((!req.isPersistent()) && req.isCancelled()) {
			return false;
		}
		if(req.key != null) {
			if(!sched.addToFetching(req.key))
				return false;
		}
		if(logMINOR) Logger.minor(this, "Running request "+req+" priority "+req.getPriority());
		core.getExecutor().execute(new SenderThread(req, req.key), "RequestStarter$SenderThread for "+req);
		return true;
	}

	public void run() {
	    freenet.support.Logger.OSThread.logPID(this);
		while(true) {
			try {
				realRun();
            } catch (OutOfMemoryError e) {
				OOMHandler.handleOOM(e);
			} catch (Throwable t) {
				Logger.error(this, "Caught "+t, t);
			}
		}
	}
	
	private class SenderThread implements Runnable {

		private final ChosenBlock req;
		private final Key key;
		
		public SenderThread(ChosenBlock req, Key key) {
			this.req = req;
			this.key = key;
		}

		public void run() {
			try {
		    freenet.support.Logger.OSThread.logPID(this);
		    // FIXME ? key is not known for inserts here
		    if (key != null)
		    	stats.reportOutgoingLocalRequestLocation(key.toNormalizedDouble());
		    if(!req.send(core, sched)) {
				if(!((!req.isPersistent()) && req.isCancelled()))
					Logger.error(this, "run() not able to send a request on "+req);
				else
					Logger.normal(this, "run() not able to send a request on "+req+" - request was cancelled");
			}
			if(Logger.shouldLog(Logger.MINOR, this)) 
				Logger.minor(this, "Finished "+req);
			} finally {
				if(key != null) sched.removeFetchingKey(key);
			}
		}
		
	}

	public void wakeUp() {
		synchronized(this) {
			notifyAll();
		}
	}

	public boolean exclude(RandomGrabArrayItem item, ObjectContainer container, ClientContext context) {
		if(sched.isRunningOrQueuedPersistentRequest((SendableRequest)item)) {
			Logger.normal(this, "Excluding already-running request: "+item, new Exception("debug"));
			return true;
		}
		if(isInsert) return false;
		if(!(item instanceof BaseSendableGet)) {
			Logger.error(this, "On a request scheduler, exclude() called with "+item, new Exception("error"));
			return false;
		}
		BaseSendableGet get = (BaseSendableGet) item;
		if(get.hasValidKeys(sched.fetchingKeys(), container, context))
			return false;
		Logger.normal(this, "Excluding (no valid keys): "+get);
		return true;
	}

}
