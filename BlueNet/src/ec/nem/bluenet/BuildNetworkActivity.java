package ec.nem.bluenet;

import java.util.Set;

import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.util.Log;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ExpandableListView;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;
import ec.nem.bluenet.BluetoothNodeService.LocalBinder;
import ec.nem.bluenet.utils.BluetoothExpandableListAdapter;
import ec.nem.bluenet.utils.UniqueArrayAdapter;

public class BuildNetworkActivity extends Activity implements NodeListener {

	private static final String TAG = "BuildNetworkActivity";
	/** The smallest number of nodes that need to be in the network for the app to function (this is not the same as number of players using your app just the number using BlueNet) */
	public static final String EXTRA_MINIMUM_NETWORK_SIZE = "network_size";
	/** The name for the key in getIntExtra for the username*/
	public static final String EXTRA_USERNAME = BluetoothNodeService.EXTRA_USERNAME;
	/** The name for the key in getIntExtra for the port*/
	public static final String EXTRA_PORT = BluetoothNodeService.EXTRA_PORT;
	/** The name for the key in getIntExtra for the timeout*/
	public static final String EXTRA_TIMEOUT = BluetoothNodeService.EXTRA_TIMEOUT;
	
	private static final int REQUEST_ENABLE_BT = 2039234;
	
	private BluetoothNodeService connectionService;
	private boolean boundToService = false;
	private BluetoothAdapter btAdapter;
    
	private ArrayAdapter<String> pairedDevicesArrayAdapter;
    private ArrayAdapter<String> newDevicesArrayAdapter;
    private BluetoothExpandableListAdapter currentNetworkListAdapter;
    
    private Handler uiHandler;
    
    private int minimumNetworkSize;
    
    // Holds the service's extras
    private String blueUsername;
    private int bluePort;
    private int blueTimeout;
    
    /*
	* BuildNetworkActivity
	* - takes minimum network size, name, uuid, next activity?
	* - starts bluetooth, displays paired devices,
	*   gives access to device discovery
	*/
	
	@Override
	public void onCreate(Bundle savedInstance){
		super.onCreate(savedInstance);
		minimumNetworkSize = getIntent().getIntExtra(EXTRA_MINIMUM_NETWORK_SIZE, 2);
		blueUsername = getIntent().getStringExtra(BluetoothNodeService.EXTRA_USERNAME);
		bluePort = getIntent().getIntExtra(BluetoothNodeService.EXTRA_PORT, -1);
		blueTimeout = getIntent().getIntExtra(BluetoothNodeService.EXTRA_TIMEOUT, -1);
		setContentView(R.layout.buildnetwork);
		
		btAdapter = BluetoothAdapter.getDefaultAdapter();
		currentNetworkListAdapter = new BluetoothExpandableListAdapter(this);
		
		uiHandler = new Handler();
		
		// Set result CANCELED in case the user backs out
		setResult(Activity.RESULT_CANCELED);
	}
	
	@Override
    protected void onDestroy() {
        super.onDestroy();

        if(boundToService){
        	unbindService(connection);
        	boundToService = false;
        }
        // Make sure we're not doing discovery anymore
        if (btAdapter != null) {
            btAdapter.cancelDiscovery();
        }
        
        // Unregister broadcast listeners
        if(mReceiver != null){
        	try{
        		this.unregisterReceiver(mReceiver);
        	} catch(IllegalArgumentException e){}
        }
    }
	
	@Override
	protected void onStart(){
		super.onStart();
		if (btAdapter != null){
			if(!btAdapter.isEnabled()) {
			    Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
			    startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT);
			}
			else{
				startNetwork(null);
			}
		}
		else{
			// Display an error and quit because we don't have a Bluetooth Adapter
			AlertDialog.Builder builder = new AlertDialog.Builder(this);
			builder.setMessage("Error: No Bluetooth Adapter available on this device.")
			       .setCancelable(false)
			       .setNeutralButton("OK", new DialogInterface.OnClickListener() {
			           public void onClick(DialogInterface dialog, int id) {
			                BuildNetworkActivity.this.finish();
			           }
			       });
			AlertDialog alert = builder.create();
			alert.show();
		}
	}
	
	@Override
	protected void onStop(){
		super.onStop();
    	if(boundToService){
    		unbindService(connection);
        	boundToService = false;
    	}
	}
	
	public void startNetwork(Intent data){
		Intent serviceIntent = new Intent(this, BluetoothNodeService.class);
		if(blueUsername != null){
			serviceIntent.putExtra(BluetoothNodeService.EXTRA_USERNAME, blueUsername);
		}
		if(bluePort >= 0){
			serviceIntent.putExtra(BluetoothNodeService.EXTRA_PORT, bluePort);
		}
		//timeout min is 1 Min
		if(blueTimeout >= 1000 * 60){
			serviceIntent.putExtra(BluetoothNodeService.EXTRA_TIMEOUT, blueTimeout);
		}
		startService(serviceIntent);
    	bindService(serviceIntent, connection, Context.BIND_AUTO_CREATE);
		
		Button scanButton = (Button) findViewById(R.id.discover_users_button);
        scanButton.setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
                doDiscovery();
                Button discoverUsers = (Button)v;
                discoverUsers.setEnabled(false);
            }
        });

        ExpandableListView currentNetworkView = (ExpandableListView)findViewById(R.id.current_network);
        currentNetworkView.setAdapter(currentNetworkListAdapter);
        
        pairedDevicesArrayAdapter = new UniqueArrayAdapter<String>(this, android.R.layout.simple_list_item_1);
        newDevicesArrayAdapter = new UniqueArrayAdapter<String>(this, android.R.layout.simple_list_item_1);
        
        ListView pairedListView = (ListView) findViewById(R.id.paired_devices);
        pairedListView.setAdapter(pairedDevicesArrayAdapter);
        pairedListView.setOnItemClickListener(mDeviceClickListener);
        
        ListView newDevicesListView = (ListView) findViewById(R.id.new_devices);
        newDevicesListView.setAdapter(newDevicesArrayAdapter);
        newDevicesListView.setOnItemClickListener(mDeviceClickListener);
        
        // Register for broadcasts when a device is discovered
        IntentFilter filter = new IntentFilter(BluetoothDevice.ACTION_FOUND);
        this.registerReceiver(mReceiver, filter);
        
        // Register for broadcasts when discovery has finished
        filter = new IntentFilter(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
        this.registerReceiver(mReceiver, filter);
        
        Set<BluetoothDevice> pairedDevices = btAdapter.getBondedDevices();

        // If there are paired devices, add each one to the ArrayAdapter
        if (pairedDevices.size() > 0) {
            findViewById(R.id.title_paired_devices).setVisibility(View.VISIBLE);
            for (BluetoothDevice device : pairedDevices) {
                pairedDevicesArrayAdapter.add(device.getName() + "\n" + device.getAddress());
            }
        } else {
            String noDevices = getResources().getText(R.string.none_paired).toString();
            pairedDevicesArrayAdapter.add(noDevices);
        }
	}
	
	private void doDiscovery() {
        Log.d(TAG, "doDiscovery()");

        // Turn on sub-title for new devices
        findViewById(R.id.title_new_devices).setVisibility(View.VISIBLE);

        // If we're already discovering, stop it
        if (btAdapter.isDiscovering()) {
            btAdapter.cancelDiscovery();
        }

        // Request discover from BluetoothAdapter
        btAdapter.startDiscovery();
    }
	
	// The on-click listener for all devices in the ListViews
    private OnItemClickListener mDeviceClickListener = new OnItemClickListener() {
        public void onItemClick(AdapterView<?> av, View v, int arg2, long arg3) {
            // Cancel discovery because it's costly and we're about to connect
            btAdapter.cancelDiscovery();

            // Get the device MAC address, which is the last 17 chars in the View
            String info = ((TextView) v).getText().toString();
            String address = info.substring(info.length() - 17);

            if(!connectionService.connectTo(address)){
            	Toast.makeText(BuildNetworkActivity.this, 
            			"Could not connect to " + info, 
            			Toast.LENGTH_SHORT).show();
            }
            else{
            	Toast.makeText(BuildNetworkActivity.this, 
            			"Connecting to " + info, Toast.LENGTH_SHORT).show();
            }
        }
    };
    
    // The BroadcastReceiver that listens for discovered devices and
    // changes the title when discovery is finished
    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();

            // When discovery finds a device
            if (BluetoothDevice.ACTION_FOUND.equals(action)) {
                // Get the BluetoothDevice object from the Intent
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                // If it's already paired, skip it, because it's been listed already
                if (device.getBondState() != BluetoothDevice.BOND_BONDED) {
                    newDevicesArrayAdapter.add(device.getName() + "\n" + device.getAddress());
                }
            // When discovery is finished
            } else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(action)) {
                if (newDevicesArrayAdapter.getCount() == 0) {
                    String noDevices = getResources().getText(R.string.none_found).toString();
                    newDevicesArrayAdapter.add(noDevices);
                }
                Button discoverUsers = (Button)findViewById(R.id.discover_users_button);
                discoverUsers.setEnabled(true);
            }
        }
    };
    
    private ServiceConnection connection = new ServiceConnection() {

        @Override
        public void onServiceConnected(ComponentName className,
                IBinder service) {
            // We've bound to LocalService, cast the IBinder and get LocalService instance
            LocalBinder binder = (LocalBinder) service;
            connectionService = binder.getService();
            connectionService.addNodeListener(BuildNetworkActivity.this);
            boundToService = true;
            Toast.makeText(connectionService, "Service Connected...", Toast.LENGTH_LONG).show();
            Log.d(TAG, "Service Connected...");
        }

        @Override
        public void onServiceDisconnected(ComponentName arg0) {
        	Toast.makeText(connectionService, "Service disconnected...", Toast.LENGTH_LONG).show();
        	Log.d(TAG, "Service disconnected...");
            boundToService = false;
            connectionService.removeNodeListener(BuildNetworkActivity.this);
            connectionService = null;
        }
    };
	
	@Override
	protected void onActivityResult(int requestCode, int resultCode, Intent data){
		super.onActivityResult(requestCode, resultCode, data);
		if(requestCode == REQUEST_ENABLE_BT){
			if(resultCode == RESULT_OK){
				startNetwork(data);
			}
			else{ 
				Log.d(TAG, "Bluetooth was not enabled. Exiting.");
				finish();
			}
		}
	}

	@Override
	public void onNodeEnter(final String node) {
		Log.d(TAG, node + " has joined the network.");
		
		uiHandler.post(new Runnable() {
			@Override
			public void run() {
				currentNetworkListAdapter.addChild(node);
			}
		});
		
		if(connectionService != null &&
				connectionService.getNetworkSize() >= minimumNetworkSize){
			uiHandler.post(new Runnable() {
				@Override
				public void run() {
					Button b = (Button)findViewById(R.id.begin_game_button);
					b.setEnabled(true);	
				}
			});
		}
	}

	@Override
	public void onNodeExit(final String node) {
		Log.d(TAG, node + " has left the network.");
		/*if(connectionService != null &&
				connectionService.getNetworkSize() < minimumNetworkSize){
			uiHandler.post(new Runnable() {
				@Override
				public void run() {
					Button b = (Button)findViewById(R.id.begin_game_button);
					b.setEnabled(false);	
				}
			});
		}*/
	}
	
	public void closeNetworkBuilder(View v){
		setResult(Activity.RESULT_OK);
		finish();
	}
}
