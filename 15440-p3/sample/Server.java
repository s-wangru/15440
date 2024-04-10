/**
 * Server.java
 * 
 * This file implements the server-side logic for a scalable cloud-based service using Java RMI (Remote Method Invocation).
 * It supports dynamic scaling of resources to meet demand, including the ability to start new server instances as virtual machines (VMs)
 * with designated roles (either front-end or mid-tier) based on workload. The server architecture is designed to handle requests efficiently
 * by distributing tasks among front-end and mid-tier servers, managing VM scaling, and optimizing resource utilization.
 * 
 * Features:
 * - Initial setup of RMI registry and binding of server instances for remote method calls.
 * - Dynamic scaling of server resources by starting and stopping VMs based on real-time demand.
 * - Distribution of requests to appropriate server instances based on their roles.
 * - Implementation of front-end server logic to forward requests to the mid-tier.
 * - Implementation of mid-tier server logic to process requests.
 * - Efficient management of a concurrent request queue and a roles map to keep track of server roles.
 * 
 * Usage:
 * The server is initiated with three command-line arguments specifying the cloud IP, cloud port, and the VM id. The first VM instance
 * acts as the master server, handling initial scaling decisions and forwarding requests. Additional VMs can be started as either
 * front-end or mid-tier servers based on the current workload, as measured by request arrival intervals.
 */

import java.rmi.Naming;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

public class Server extends UnicastRemoteObject implements remoteServer{

	//maps each VM id to its role
	public static final ConcurrentHashMap<Integer, Integer> roles = new ConcurrentHashMap<>();

	//stores all parsed requests from front end servers
	public static final ConcurrentLinkedQueue<Cloud.FrontEndOps.Request> requests = new ConcurrentLinkedQueue<>();

	//number of midTier servers
	private static int midN = 0;

	//number of front end servers
	private static int frontN = 0;

	//ServerLib created using cloud_ip and cloud_port arguments
	private static ServerLib SL;



	//the cooldown period to avoid consecutive scaling out
	private final static long COOLDOWN = 5200;

	//when the queue size exceeds scale_out_factor*number of middle server
	//it indicates a scale out is needed
	private final static double SCALE_OUT_FACTOR = 1.5;

	//the maximum number of middle server that can be opened
	private final static int MID_LIMIT = 20;

	//the approximate boot time of a VM
	private final static int BOOT_TIME = 5000;

	//role representation of a front end server
	private final static int FRONT = 1;

	//role representation of a middle server
	private final static int MIDDLE = 2;

	//VM id of master VM
	private final static int MASTERVM = 1;

	// if a front server has been idle for 500ms, this indicates the server 
	// needs to be scaled in
	private final static long SCALE_IN_LIMIT = 500;

	// if a middle server has been idle for 500ms 5 times consecutively,
	// this indicates the server needs to be scaled in
	private final static int SCALE_IN_COUNT = 5;

	// the initial maximum number of middle server that can be booted
	private final static int MID_CAP = 6;

	// the initial maximum number of requests that can be placed in the
	// centralized queue
	private final static int DROP_THRESHOLD = 3;

	//during the initial booting stage, all requests landed in this interval
	//should be dropped as it can't be fulfilled in time
	private final static int INIT_DROP_INTERVAL = 4700;

	// the ideal ratio for number of middle servers to number of front ends
	private final static int MID_FRONT_RATIO = 5;

	// the threshold for a arrival rate to be indicated as slow 
	// (only 1 mid needed)
	private final static int SLOW_THRESHOLD = 1500;

	// the threshold of the arrival rate to indicate no extra front
	// end servers are needed
	private final static int FRONT_INIT_THRESHOLD = 200;

	// the threshold of the arrival rate to indicate additional front
	// end servers are needed
	private final static int FRONT_INIT_TOP = 60;

	// the threshold to indicate whether the first request can be completed
	// on time
	private final static int FIRST_ARRIVAL_LIMIT = 500;



	/**
     * Constructor for Server class.
     * Initializes a new instance of the Server.
     * @throws RemoteException if the object cannot be exported or is already exported.
     */
	private Server() throws RemoteException {
        super(0);
    }



	/**
     * The main method that initializes the Server.
     * It sets up the RMI registry, starts server instances, and handles roles.
     * @param args Command line arguments [cloud IP, cloud port, VM id].
     * @throws Exception for general exceptions, particularly for invalid argument count.
     */
	public static void main ( String args[] ) throws Exception {
		// Cloud class will start one instance of this Server intially [runs as separate process]
		// It starts another for every startVM call [each a seperate process]
		// Server will be provided 3 command line arguments
		if (args.length != 3) throw new Exception("Need 3 args: <cloud_ip> <cloud_port> <VM id>");
		
		// Initialize ServerLib
		int port = Integer.parseInt(args[1]);
		SL = new ServerLib( args[0], port);

		Registry registry = LocateRegistry.getRegistry(Integer.parseInt(args[1]));

		// get the VM id for this instance of Server in case we need it
		int myVMid = Integer.parseInt(args[2]);
		remoteServer master;

		if (myVMid == 1){
			master = new Server();
			Naming.rebind("//localhost:" + port + "/Server", master);
		}else{
			master = (remoteServer) Naming.lookup("//localhost:" + port + "/Server");
			Server child = new Server();
			Naming.rebind("//localhost:" + port + "/Server_" + myVMid, child);
		}

		// on master VM
		if (myVMid == MASTERVM){
			executeMaster();
		}else{
			int role = master.getRole(myVMid);
			if (role == FRONT){
				SL.register_frontend();
				frontEnd(SL, myVMid, port, master);
			}else{
				midTier(SL, myVMid, master);
			}
		}

	}

	/**
     * Executes the master server logic.
     * Handles the initial boot, scaling, and request forwarding.
     */
	private static void executeMaster(){

		//master will server as a front end after initial boots
		SL.register_frontend();
		long midInter = 0;

		//start an initial midtier VM
		long init_boot = System.currentTimeMillis();
		roles.put(SL.startVM(), MIDDLE);
		
		long arrival = measureInterval();
		initialVM_boot(arrival);

		//the requests arrive during beginning of the booting won't be able to 
		//get processed on time so drop all requests that arrive prior to when
		//the first middle server starts running
		while ((System.currentTimeMillis()-init_boot) < INIT_DROP_INTERVAL){
			SL.dropHead();
		}
		while ((System.currentTimeMillis()-init_boot) < BOOT_TIME-arrival){
			if (requests.size() > DROP_THRESHOLD){
				SL.dropHead();
			}else{
				Cloud.FrontEndOps.Request r = SL.getNextRequest();
				requests.add(r);
			}
		}

		long lastScaleMid = System.currentTimeMillis();
		while (true){
			ServerLib.Handle h = SL.acceptConnection(); 
			// read and parse request from client connection at the given handle
			Cloud.FrontEndOps.Request r = SL.parseRequest( h );
			requests.add(r);

			//check if scale out is needed and not currently in cooldown period 
			//and not exceeding max server limit
			if (requests.size() > SCALE_OUT_FACTOR*midN 
				&& System.currentTimeMillis()-lastScaleMid >= COOLDOWN 
				&& midN < MID_LIMIT){
				scaleOut(SL, MIDDLE,1);
				midN+=1;
				scaleOut(SL, FRONT, midN/MID_FRONT_RATIO-frontN);
				frontN += Math.max(midN/MID_FRONT_RATIO-frontN, 0);
				lastScaleMid = System.currentTimeMillis();
			}
		}

	}

	/**
     * Initializes the number of mid-tier and front-end VMs based on the arrival rate.
     * @param arrival The arrival time of the initial requests.
     */
	private static void initialVM_boot(long arrival){
		if (arrival > SLOW_THRESHOLD){
			frontN = 0;
			midN = 1;
		}else{
			midN = (int) Math.round(119.4*Math.exp(-0.01391*arrival)+2.314);
		}
		midN = Math.min(midN, MID_CAP);
		if (arrival >= FRONT_INIT_THRESHOLD){
			frontN = 0;
		}else if (arrival > FRONT_INIT_TOP){
			frontN = 1;
		}else{
			frontN = 2;
		}

		for(int i = 1; i < midN; i++){
			roles.put(SL.startVM(), MIDDLE);
		}
		for (int i = 0; i < frontN; i++){
			roles.put(SL.startVM(), FRONT);
		}

	}

	/**
     * Measures the interval between request arrivals to estimate the workload.
     * @return The arrival time for the second request
     */
	private static long measureInterval(){
		long s = System.currentTimeMillis();
		long arrival = 0;

		Cloud.FrontEndOps.Request r = SL.getNextRequest();
		long first = System.currentTimeMillis() - s;
		if (first > FIRST_ARRIVAL_LIMIT){
			SL.processRequest(r);
			arrival += first;
		}
		long start = System.currentTimeMillis();
		Cloud.FrontEndOps.Request c = SL.getNextRequest();
		arrival += (System.currentTimeMillis() - start);
		if (first <= FIRST_ARRIVAL_LIMIT){
			SL.processRequest(r);
		}
		SL.processRequest(c);
		return arrival;
	}

	/**
     * Scales out the server by starting additional VMs based on the role.
     * @param SL ServerLib instance for server operations.
     * @param role The role of the new VM (front-end or mid-tier).
     * @param num The number of new VMs to start.
     */
	private static void scaleOut(ServerLib SL, int role, int num){
		for (int i = 0; i < num; i++){
			roles.put(SL.startVM(), role);
		}
	}


	/**
     * Front-end server logic handling request forwarding to the master server.
     * @param SL ServerLib instance for server operations.
     * @param vmid The VM id of this server.
     * @param port The port number for RMI communication.
     * @param master The master server instance.
     */
	private static void frontEnd(ServerLib SL, int vmid, int port, 
								remoteServer master){
		try{
			while (true){
				long s = System.currentTimeMillis();
				Cloud.FrontEndOps.Request r = SL.getNextRequest();
				master.enqTask(r);
				if (System.currentTimeMillis() - s > SCALE_IN_LIMIT){
					SL.unregisterFrontend();
					while (SL.getQueueLength() > 0){
						r = SL.getNextRequest();
						master.enqTask(r);
					}
					SL.endVM(vmid);
					master.scaleIn(vmid, FRONT);
					return;
				}
			}
		} catch (Exception e){
			e.printStackTrace();
		}
	}

	/**
     * Mid-tier server logic handling request processing from the queue.
     * @param SL ServerLib instance for server operations.
     * @param vmid The VM id of this server.
     * @param master The master server instance.
     */
	private static void midTier(ServerLib SL, int vmid, remoteServer master){
		Cloud.FrontEndOps.Request r;
		try{
			int count = 0;
			long period = System.currentTimeMillis();
			while (true){
				if((r=master.middleTask()) != null){
					long start = System.currentTimeMillis();
					SL.processRequest( r );
					long mid = System.currentTimeMillis() - start;
					count = 0;
					period = System.currentTimeMillis();
				}else{
					if (System.currentTimeMillis()-period > SCALE_IN_LIMIT){
						count++;
						period = System.currentTimeMillis();
					}
				}
				if (count > SCALE_IN_COUNT){
					if (master.scaleIn(vmid, MIDDLE)){
						SL.endVM(vmid);
						count = 0;
					}
					period = System.currentTimeMillis();
				}
			}
		} catch (RemoteException e){
			e.printStackTrace();
		}
	}

	/**
     * Handles the scaling in of VMs by removing them based on the role.
     * @param vmid The VM id to be removed.
     * @param role The role of the VM (front-end or mid-tier).
     * @return true if scaling in was successful, false otherwise.
     */
	public boolean scaleIn(int vmid, int role){
		if (role == MIDDLE && vmid == 2){
			return false;
		}
		roles.remove(vmid);
		if (role == MIDDLE){
			midN--;
		}else{
			frontN--;
		}
		return true;
	}

	 /**
     * Retrieves a request from the queue for mid-tier processing.
     * @return The next request to be processed, or null if the queue is empty.
     * @throws RemoteException if there is a communication-related exception.
     */
	public Cloud.FrontEndOps.Request middleTask() throws RemoteException{
		if  (!requests.isEmpty()){
			while (requests.size() > midN*SCALE_OUT_FACTOR){
				SL.dropRequest(requests.poll());
			}
			return requests.poll();
		}else{
			return null;
		}
	}

	/**
     * Enqueues a task into the request queue.
     * @param r The request to be enqueued.
     * @throws RemoteException if there is a communication-related exception.
     */
	public void enqTask(Cloud.FrontEndOps.Request r) throws RemoteException{
		requests.add(r);
	}

	/**
     * Retrieves the role of a VM given its id.
     * @param vmid The VM id whose role is to be determined.
     * @return The role of the specified VM.
     * @throws RemoteException if there is a communication-related exception.
     */
	public int getRole (int vmid) throws RemoteException{
		while(roles.get(vmid) == null){}
		return roles.get(vmid);
	}
}

