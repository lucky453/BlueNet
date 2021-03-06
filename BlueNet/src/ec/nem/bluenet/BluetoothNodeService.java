package ec.nem.bluenet;

import java.text.ParseException;
import java.util.List;

import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.util.Log;
import android.widget.Toast;
import ec.nem.bluenet.net.Segment;
import ec.nem.bluenet.net.Socket;
import ec.nem.bluenet.net.SocketManager;

public class BluetoothNodeService extends Service {
	private static final String TAG = "BluetoothNodeService";

	/** The name for the key in getIntExtra for the username */
	public static final String EXTRA_USERNAME = "username";

	/** The name for the key in getIntExtra for the port <br> Note, value must be greater than 0 */
	public static final String EXTRA_PORT = "port";
	
	/** The name for the key in getIntExtra for the port <br> Note, value is minimum of 1 Minute in order to ensure connection */
	public static final String EXTRA_TIMEOUT = "timeout";

	/** Username that will show up on messages sent on this service */
	private String username = "No one.";
	
	/** The default port which this game will use*/
	private static final int DEFAULT_BLUENET_PORT = 50000;
	
	/** The port which this game will use*/
	private int port = DEFAULT_BLUENET_PORT;
	
	/** Default timeout for the service: 10 minutes*/
	public static final int DEFAULT_TIMEOUT = 1000 * 60 * 10;
	
	/** Thread that owns the networking stack */
	private CommunicationThread mCommThread;
	
	/** Timeout to determine how many seconds to wait before the service crashes. Set to 0 for no timeout*/
	private int commThreadTimeout = DEFAULT_TIMEOUT;

	/** Exposes the service to clients. */
	private final IBinder binder = new LocalBinder();
	
	/** The socket representing our Bluetooth socket. */
	private Socket socket;

	/** Provides access to the local bluetooth adapter*/
	BluetoothAdapter adapter;
	
	@Override
	public void onCreate() {
		super.onCreate();
		if(mCommThread==null){
			mCommThread = new CommunicationThread(this.getApplicationContext(),	commThreadTimeout);
			Toast.makeText(this, "Service started...", Toast.LENGTH_LONG).show();
		}
		else{
			Log.d(TAG, "Tried to start comm thread again oops");
		}
	}

	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		Log.d(TAG, "Received start id " + startId + ": " + intent);
		
		// Set the username and port if passed in.
		if(intent != null){
			String username = intent.getStringExtra(EXTRA_USERNAME);
			if(username != null){
				this.username = username;
			}
			port = intent.getIntExtra(EXTRA_PORT, DEFAULT_BLUENET_PORT);
			commThreadTimeout = intent.getIntExtra(EXTRA_TIMEOUT, DEFAULT_TIMEOUT);
		}
		else{
			Log.d(TAG, "Service Intent is null.");
		}
		
		if(socket==null){
			SocketManager sm = SocketManager.getInstance();
			socket = sm.requestSocket(Segment.TYPE_UDP);
			socket.bind(port);
			Log.d(TAG, "Bound on port " + port);
		}

		Log.d(TAG, "Thread state when calling startService: " + mCommThread.getState().name());
		if(mCommThread.getState() == Thread.State.NEW) {
			mCommThread.setDaemon(true);
			mCommThread.start();
		}
		else if(mCommThread.getState() == Thread.State.TERMINATED) {
			mCommThread = new CommunicationThread(this.getApplicationContext(), commThreadTimeout);
			mCommThread.setDaemon(true);
			mCommThread.start();
		}
		return START_STICKY;
	}

	@Override
	public void onDestroy() {
		Log.d(TAG, "onDestroy()");
		stop();
		// Tell the user we stopped.
		Toast.makeText(this, R.string.comm_service_stopped, Toast.LENGTH_SHORT).show();
	}

	/*
	 * Kills the Communication thread for routing
	 */
    protected void stop() {
    	Log.d(TAG, "Communication thread is stopping...");
    	if(mCommThread.isRunning()) {
    		mCommThread.stopThread();
    		try {
    			mCommThread.join();
			}
    		catch(InterruptedException e) {
    			Log.e(TAG, e.getMessage());
			}
    	}
    }
    
	public String getUsername() {
		return username;
	}

    /*
     * Returns the Node for the current device
     */
    public Node getLocalNode() {
    	return mCommThread.getLocalNode();
    }
    
    /*
     * Returns a list of all devices that are on the network.
     */
    public List<Node> getAvailableNodes() {
    	return mCommThread.getAvailableNodes();
    }
	
	public int getNetworkSize(){
		return mCommThread.getAvailableNodes().size();
	}
	
	public boolean connectTo(String address){
		resetTimeout();
		try {
			Node n = new Node(address);
			mCommThread.connectTo(n);
		} catch (ParseException e) {
			e.printStackTrace();
			return false;
		}
		return true;
	}
	
	public void broadcastMessage(String text){
		broadcastMessage(text, null);
	}
	
	public void broadcastMessage(Object o){
		broadcastMessage(null, o);
	}
	
	public void broadcastMessage(String text, Object o){
		List<Node> nodes = mCommThread.getAvailableNodes();
		/*if(nodes.size()<=0){
			Toast.makeText(getApplicationContext(), "No nodes Connected", Toast.LENGTH_LONG);
		}*/
			
		for (Node n :nodes) {
			sendMessage(n, text, o);
		}
	}
	
	public void sendMessage(Node destinationNode, String text) {
		sendMessage(destinationNode, text, null);
	}

	public void sendMessage(Node destinationNode, Object o) {
		sendMessage(destinationNode, null, o);
	}
	
	public void sendMessage(Node destinationNode, String text, Object o){
		resetTimeout();
		// Don't send message to self
		if (destinationNode != getLocalNode()) {
			Message m = new Message(username, getLocalNode().getAddress(),
					text, o, (System.currentTimeMillis() / 1000L));
			socket.connect(destinationNode, port);
			socket.send(Message.serialize(m));
		}
	}
	
	public void addNodeListener(NodeListener l){
		resetTimeout();
		mCommThread.addNodeListener(l);
	}

	public boolean removeNodeListener(NodeListener l){
		resetTimeout();
		return mCommThread.removeNodeListener(l);
	}

	public void addMessageListener(MessageListener l){
		resetTimeout();
		/// This would break if we don't have the SocketManager in existence
		SocketManager.getInstance().addMessageListener(l, port);
	}

	public boolean removeMessageListener(MessageListener l){
		resetTimeout();
		return mCommThread.removeMessageListener(l, port);
	}
	
	/**
	 * Reset the communication thread's timeout to keep the service alive.
	 * When a user calls any of the service's public methods, this method
	 * should be called to make sure the service stays alive.
	 */
	private void resetTimeout(){
		synchronized(mCommThread){
			mCommThread.notify();
		}
	}

	public class LocalBinder extends Binder{
		public BluetoothNodeService getService(){
			return BluetoothNodeService.this;
		}
	}

	@Override
	public IBinder onBind(Intent arg0) {
		return binder;
	}
}
