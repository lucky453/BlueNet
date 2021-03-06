package ec.nem.bluenet.net.routing;



import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

import android.os.Environment;
import android.util.Log;
import ec.nem.bluenet.Node;
import ec.nem.bluenet.net.NetworkLayer;
import ec.nem.bluenet.net.routing.RoutingMessage.Type;

/**
 * A single run of the Routing Protocol
 * 
 * @author mmullins, and Ivan Hernandez
 */
public class RoutingProtocol {
	///Graph drawing header
	static final String PrintGraphHeader = "digraph \"Routes\" {\n\tgraph [ fontsize=12,\n\t\tlabel=\"\\n\\n\\n\\nRouting Table\\nBulueNet, 2012\" ];\n\tnode [ shape = box,\n\t\tdistortion=\"0.0\",\n\t\torientation=\"0.0\",\n\t\tskew=\"0.0\",\n\t\tcolor=black,\n\t\tfillcolor=white,\n\t\tstyle=filled ];\n\t\t";
	
	static String TAG = "RoutingProtocol";
	
	/* static public ConcurrentHashMap<String, RoutingProtocol> devices =
		new ConcurrentHashMap<String, RoutingProtocol>();
	*/
	/// The node representing us
	Node mNode;
	///Our network layer that we use to send messages
	NetworkLayer mNetworkLayer;

	///Possible states for nodes on the network to be in.
	enum LinkState {
		None,
		HelloSent,
		HandshakeCompleted,
		FullyConnected
	};
	
	///State of the nodes in the graph
	HashMap<Node, LinkState> mLinks = new HashMap<Node, LinkState>();
	///List of known link state advertisements
	HashMap<Node, LinkStateAdvertisement> mGraph = new HashMap<Node, LinkStateAdvertisement>();
	///The actual Routing Table
	Map<Node, GraphNode> mRoutingTable;
	
	/**
	 * Constructs a routing table with our local node and Network Layer Access
	 * @param node Local node
	 * @param networkLayer The network layer we're going to work with
	 */
	public RoutingProtocol(Node node, NetworkLayer networkLayer) {
		mNode = node;
		mNetworkLayer = networkLayer;
	}
	
	public void receiveMessage(RoutingMessage msg) {
		switch (msg.type) {
		case Hello: {
			Node n = (Node) msg.obj;
			Log.d(TAG, MessageFormat.format("Received Hello packet from {0}", n.getAddress()));
			
			LinkState state = mLinks.get(n);
			if (state==null || state == LinkState.None) {
				connectTo(n);
			} else if (state == LinkState.HelloSent) {
				mLinks.put(n, LinkState.FullyConnected);
					
				Log.d(TAG, MessageFormat.format("Sending HelloAck to {0}",	n.getAddress()));
				
				RoutingMessage newMsg = new RoutingMessage();
				newMsg.type = Type.HelloAck;
				newMsg.obj = mNode;
				mNetworkLayer.sendRoutingMessage(n, newMsg);

				handshakeFinished(n);
			} else {
				Log.e(TAG, MessageFormat.format("Received erroneous Hello from {0}. Current state:{1}", n.getAddress(), state));
			}
			break;
		}
			
		case HelloAck: {
			Node n = (Node) msg.obj;
			//does same in hello if we sent a hello so that we can do things more quickly
			LinkState state = mLinks.get(n);
			if (state == LinkState.HelloSent) {
				mLinks.put(n, LinkState.FullyConnected);
				handshakeFinished(n);
			} else {
				Log.e(TAG, MessageFormat.format("Received erroneous HelloAck from {0}. Current state:{1}", n.getAddress(), state));
			}
			break;
		}
		
		case LinkStateAdvertisement: {
			LinkStateAdvertisement lsa = (LinkStateAdvertisement) msg.obj;
			handleNewLsa(lsa);
			break;
		}
		
		case Quit: {
			Node n = (Node) msg.obj;
			//if we're connected we want the network to know that we're not anymore.
			LinkState state = mLinks.get(n);
			if (state == LinkState.FullyConnected) {
				removeNode(n);
			} else {
				Log.e(TAG, MessageFormat.format("Received erroneous Quit from {0}. Current state:{1}", n.getAddress(), state));
			}
			break;
		}
		
		default:
			Log.e(TAG, MessageFormat.format("Received some message I don't understand: {0}", msg));
		}
	}
	
	void handleNewLsa(LinkStateAdvertisement lsa) {
		if (!mGraph.containsKey(lsa.source) || mGraph.get(lsa.source).sequence < lsa.sequence) {
			Log.d(TAG, MessageFormat.format(
					"Got an LSA of sequence {0} from {1}",
					lsa.sequence, lsa.source.getAddress()));
			
			sendLSA(lsa);
			
			mGraph.put(lsa.source, lsa);
			
			recomputeRoutingTable();
		} else {
			Log.d(TAG, MessageFormat.format("Erroneous new LSA: sequence {0} from {1}",
					lsa.sequence, lsa.source.getAddress()));
		}
	}
	
	void handshakeFinished(Node n) {
		Log.d(TAG, MessageFormat.format("Finished handshake with {0}", n.getAddress()));
		
		LinkStateAdvertisement thisLsa;
		if (mGraph.containsKey(mNode)) {
			thisLsa = mGraph.get(mNode);
			thisLsa.sequence++;
		} else {
			thisLsa = new LinkStateAdvertisement();
			thisLsa.source = mNode;
			mGraph.put(mNode, thisLsa);
		}
		
		thisLsa.others.add(n);
		
		sendLSA(thisLsa);
		
		sendLSADb(n);
	}

	/**
	 * Send the entire link state database to the new node
	 * 
	 * @param n Node which to send the current database
	 */
	private void sendLSADb(Node n) {
		for (Node origin : mGraph.keySet()) {
			// Ignore the LSA received directly from the connecting node
			if (n == origin)
				continue;

			RoutingMessage msg = new RoutingMessage();
			msg.type = Type.LinkStateAdvertisement;
			msg.obj = mGraph.get(origin);
			mNetworkLayer.sendRoutingMessage(n, msg);
		}
	}

	/**
	 * Sends the Link State Advertisement to all connected nodes. 
	 * @param lsa The Link State Advertisement to send to 
	 */
	private void sendLSA(LinkStateAdvertisement lsa) {
		// Send the new link state announcement to all connected devices
		RoutingMessage msg = new RoutingMessage();
		msg.type = Type.LinkStateAdvertisement;
		msg.obj = lsa;
		LinkStateAdvertisement thisLsa = mGraph.get(mNode);
		for (Node n: thisLsa.others) {
			Log.d(TAG, MessageFormat.format(
					"Sending updated LSA sequence {0} from {1} to {2}",
					lsa.sequence, lsa.source.getAddress(),
					n.getAddress()));
			mNetworkLayer.sendRoutingMessage(n, msg);
		}
//		printRoutingTable(mRoutingTable);
//		printLSAs(mGraph);
	}

	/**
	 * Removes the node from the network  
	 * @param n The node to be removed
	 */
	public void removeNode(Node n) {
		Log.d(TAG, MessageFormat.format("{0} has quit.", n.getAddress()));
		
		LinkStateAdvertisement thisLsa;
		if (mGraph.containsKey(mNode)) {
			thisLsa = mGraph.get(mNode);
			thisLsa.sequence++;
		} else {
			thisLsa = new LinkStateAdvertisement();
			thisLsa.source = mNode;
			mGraph.put(mNode, thisLsa);
		}
		
		//send that node quit to the network
		RoutingMessage newMsg = new RoutingMessage();
		newMsg.type = Type.Quit;
		newMsg.obj = n;
		mNetworkLayer.sendRoutingMessage(n, newMsg);
		
		//complete removal of node
		thisLsa.others.remove(n);
		mGraph.remove(n);
		mLinks.remove(n);
		recomputeRoutingTable();
		
		sendLSA(thisLsa);
	}

	/**
	 * Connects this device to the specified node
	 * @param n Node to which to connect. 
	 */
	public void connectTo(Node n) {
		Log.d(TAG, MessageFormat.format("Sending Hello packet to {0}", n.getAddress()));
		
		RoutingMessage newMsg = new RoutingMessage();
		newMsg.type = Type.Hello;
		newMsg.obj = mNode;
		
		mLinks.put(n, LinkState.HelloSent);
		mNetworkLayer.sendRoutingMessage(n, newMsg);
	}
	
	/**
	 * Obtains all nodes that routing knows about
	 * @return list of all Routing table key nodes
	 */
	public List<Node> getAvailableNodes() {
		return new ArrayList<Node>(mGraph.keySet());
	}
	
	/**
	 * Tells this node to drop off the network.
	 * @return whether quitting was successful
	 */
	public boolean quit(){
		RoutingMessage newMsg = new RoutingMessage();
		newMsg.type = Type.Quit;
		newMsg.obj = mNode;
		LinkStateAdvertisement thisLsa;
		if (mGraph.containsKey(mNode)) {
			thisLsa = mGraph.get(mNode);
		} else {
			Log.d(TAG, "Quitting when we haven't even connected...");
			return false;
		}
		Log.d(TAG, "Quitting!");
		for(Node n: thisLsa.others)
		{
			mNetworkLayer.sendRoutingMessage(n, newMsg);
		}
		return true;
	}
	
	/**
	 * Helper class for Dijkstra's algorithm.
	 * @author mmullins, Ivan Hernandez
	 */
	public class GraphNode implements Comparable<GraphNode> {
		///Current node
		public Node node;
		///Distance Between nodes
		public int distance;
		///The node we are connected to
		public Node nextHop;
	
		///Initializes a Graph node with starting node and the node it is paired with as well as the distance between nodes.
		public GraphNode(Node n, int d, Node p) {
			node = n;
			distance = d;
			nextHop = p;
		}
		
		/**
		 * Compare based on distance, then by node MAC, then by predecessor MAC
		 * Provides a complete ordering so as not to screw with other data structures
		 */
		public int compareTo(GraphNode o) {
			if (distance == o.distance) {
				int compNode = node.getAddress().compareTo(o.node.getAddress());
				int compPred = nextHop.getAddress().compareTo(o.node.getAddress());
				
				if (compNode != 0) {
					return compNode;
				} else if (compPred != 0) {
					return compPred;
				} else {
					return 0;
				}
			} else if (distance < o.distance) {
				return -1;
			} else if (distance > o.distance) {
				return 1;
			} else {
				Log.e(TAG, "Whoa, GraphNode::compareTo failed massively!");
				return Integer.MAX_VALUE;
			}
		}
	}
	
	/**
	 * Implements Dijkstra's algorithm to calculate the routing table
	 */
	void recomputeRoutingTable() {
		//LinkStateAdvertisement thisLsa = mGraph.get(mNode);
		Map<Node, GraphNode> finalGraph = new HashMap<Node, GraphNode>();
		PriorityQueue<GraphNode> queue = new PriorityQueue<GraphNode>();
		//our node added to the queue
		queue.add(new GraphNode(mNode, 0, null));
		
		while (!queue.isEmpty()) {
			GraphNode gn = queue.remove();
			
			// Have we already found a path to this
			if (finalGraph.containsKey(gn.node)) {
				// Is that path shorter?  Then skip the rest!
				if (finalGraph.get(gn.node).distance < gn.distance) {
					continue;
				}
			}

			finalGraph.put(gn.node, gn);
			
			for (Node n: mGraph.get(gn.node).others) {
				/* Only add the node to the queue if we've received an LSA from it
				 * and is well connected. 
				 */
				if (mGraph.containsKey(n) && mGraph.get(n).others.contains(gn.node)) {
					/* Find the next hop for the routing table */
					Node nextHop;
					if (gn.node == mNode) {
						/* If the node that got us here is this node, then we
						 * want to actually set the predecessor */
						nextHop = n;
					} else {
						/* Otherwise, let's take the same predecessor from the node that got us here */
						nextHop = gn.nextHop;
					}
					/**
					 * TODO: possibly take into account current structure to
					 * 		rearrange nodes so everyone will fit but other than that
					 * 		we're good
					 */
					GraphNode ngn = new GraphNode(n, gn.distance + 1, nextHop);
					queue.add(ngn);
				} else {
					Log.d(TAG, MessageFormat.format("Skipping node {0}", n.getAddress()));
				}
			}
		}
		
		Log.d(TAG, "Routing table computation complete!");
//		printRoutingTable(finalGraph);
		mRoutingTable = finalGraph;
	}
	

	public Map<Node, GraphNode> getRoutingTable() {
		return mRoutingTable;
	}
	
	/**
	 * Saves the routing table passed in to a text file named by timestamp
	 * @param rt The Routing table to print
	 */
	@SuppressWarnings("unused")
	private void printRoutingTable(Map<Node, GraphNode> rt) {
		if(rt == null){
			return;
		}
		String logfile = "NextHop" + mNode.getAddress().replace(':', '-') + System.currentTimeMillis() + ".gv";
		String logpath = "BlueNet/logs/";
		
		if(!Environment.getExternalStorageState().equals(Environment.MEDIA_MOUNTED)) 
        {
           Log.d(TAG, "Sdcard was not mounted !!" ); 
		} else {
			File file = null;
			File root = Environment.getExternalStorageDirectory();
			FileWriter f = null;
			try {
				// open file to write in our dir and timestamp

				file = new File(root, logpath);
				if (!file.exists()) {
					file.mkdirs();
				}
				file = new File(root, logpath + logfile);

				f = new FileWriter(file);
				
				// write dotty header
				try{
					f.write(PrintGraphHeader);
				}catch (IllegalArgumentException e) {
					Log.e(TAG,"Seems we've screwed up with our argumentations");
				} 

				for (Node n : rt.keySet()) {
					// print out the graph Nodes and status
					f.write("\"" + n + "\""
							+ " [ skew=\"" + -0.126818 + "\"");
					// print states for each node.
					LinkState state = mLinks.get(n);
					if (state != null) {
						switch (state) {
						case None:
							f.write(", fillcolor=salmon2");
							break;
						case HelloSent:
							f.write(", fillcolor=yellow");
							break;
						case HandshakeCompleted:
							f.write(", fillcolor=blue");
							break;
						case FullyConnected:
							f.write(", fillcolor=green");
							break;
						default:
							f.write(", fillcolor=red");
							break;
						}
					}
					else{
						if(n!=mNode){
							f.write(", fillcolor=salmon2");
						}
					}
					f.write("];\n\t\t");
				}
				for (GraphNode n : rt.values()) {
					// Write out connections
					f.write("\"" + n.nextHop + "\" -> \""
							+ n.node + "\";\n\t\t");
				}

				// write closing braces
				f.write("\n}");

			} catch (IOException e) {
				Log.e(TAG,
						file.getAbsolutePath() + " could not be written.\n" + e.getMessage());
			} finally{
				try {
					//close the file
					if(f!= null){
						f.close();
					}
				} catch (IOException e) {
					e.printStackTrace();
				}
			}
		}
	}
	
	/**
	 * Saves the Link State Advertisement list passed in to a text file named by timestamp
	 * @param lsas The Routing table to print
	 */
	@SuppressWarnings("unused")
	private void printLSAs(HashMap<Node, LinkStateAdvertisement> lsas) {
		if(lsas == null){
			return;
		}
		String logfile = "LSA" + mNode.getAddress().replace(':', '-') + System.currentTimeMillis() + ".gv";
		String logpath = "BlueNet/logs/";
		
		if(!Environment.getExternalStorageState().equals(Environment.MEDIA_MOUNTED)) 
        {
           Log.d(TAG, "Sdcard was not mounted !!" ); 
		} else {
			File file = null;
			File root = Environment.getExternalStorageDirectory();
			FileWriter f = null;
			try {
				// open file to write in our dir and timestamp

				file = new File(root, logpath);
				if (!file.exists()) {
					file.mkdirs();
				}
				file = new File(root, logpath + logfile);

				f = new FileWriter(file);
				
				// write dotty header
				try{
				f.write(PrintGraphHeader);
				}catch (IllegalArgumentException e) {
					Log.e(TAG,"Seems we've screwed up with our argumentations");
				} 

				for (Node n : lsas.keySet()) {
					// print out the graph Nodes and status
					f.write("\"" + n + "\""
							+ " [ skew=\"" + -0.126818 + "\"");
					// print states for each node.
					LinkState state = mLinks.get(n);
					if (state != null) {
						switch (state) {
						case None:
							f.write(", fillcolor=salmon2");
							break;
						case HelloSent:
							f.write(", fillcolor=yellow");
							break;
						case HandshakeCompleted:
							f.write(", fillcolor=blue");
							break;
						case FullyConnected:
							f.write(", fillcolor=green");
							break;
						default:
							f.write(", fillcolor=red");
							break;
						}
					}
					else{
						if(n!=mNode){
							f.write(", fillcolor=salmon2");
						}
					}
					f.write("];\n\t\t");
				}
				
				for (LinkStateAdvertisement lsa : lsas.values()) {
					// Write out connections
					for (Node n : lsa.others) {
						// print out the graph Nodes and status
						f.write("\"" + n + "\"" + " [ skew=\"" + -0.126818
								+ "\"");
						// print states for each node.
						LinkState state = mLinks.get(n);
						if (state != null) {
							switch (state) {
							case None:
								f.write(", fillcolor=salmon2");
								break;
							case HelloSent:
								f.write(", fillcolor=yellow");
								break;
							case HandshakeCompleted:
								f.write(", fillcolor=blue");
								break;
							case FullyConnected:
								f.write(", fillcolor=green");
								break;
							default:
								f.write(", fillcolor=red");
								break;
							}
						} else {
							if (n != mNode) {
								f.write(", fillcolor=salmon2");
							}
						}
						f.write("];\n\t\t");
						
						f.write("\"" + lsa.source + "\" -> \""+ n + "\";\n\t\t");
					}
				}

				// write closing braces
				f.write("\n}");

				
			} catch (IOException e) {
				Log.e(TAG,
						file.getAbsolutePath() + " could not be written.\n" + e.getMessage());
			} finally {

				try {
		//close the file
					if(f != null){
						f.close();
					}
				} catch (IOException e) {
					e.printStackTrace();
				}
			}
		}
	}
}
