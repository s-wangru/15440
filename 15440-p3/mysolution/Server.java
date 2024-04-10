/* Sample code for basic Server */

import java.rmi.Naming;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

public class Server extends UnicastRemoteObject implements remoteServer{

	public static final ConcurrentHashMap<Integer, Integer> roles = new ConcurrentHashMap<>();
	public static final ConcurrentLinkedQueue<Cloud.FrontEndOps.Request> requests = new ConcurrentLinkedQueue<>();
	private static int midN;
	private static int frontN;
	private static ServerLib SL;
	private static long cooldown;

	private Server() throws RemoteException {
        super(0);
    }



	public static void main ( String args[] ) throws Exception {
		// Cloud class will start one instance of this Server intially [runs as separate process]
		// It starts another for every startVM call [each a seperate process]
		// Server will be provided 3 command line arguments
		if (args.length != 3) throw new Exception("Need 3 args: <cloud_ip> <cloud_port> <VM id>");
		
		// Initialize ServerLib.  Almost all server and cloud operations are done 
		// through the methods of this class.  Please refer to the html documentation in ../doc
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

		frontN = 0;
		midN = 35;
		if (myVMid == 1){
			long start;

			SL.register_frontend();
			long arrival = 0;
			long midInter = 0;
			roles.put(SL.startVM(), 2);
			long s = System.currentTimeMillis();

			Cloud.FrontEndOps.Request r = SL.getNextRequest();
			long first = System.currentTimeMillis() - s;
			if (first > 500){
				SL.processRequest(r);
				arrival += first;
			}
			start = System.currentTimeMillis();
			Cloud.FrontEndOps.Request c = SL.getNextRequest();
			arrival += (System.currentTimeMillis() - start);
			if (first <= 500){
				SL.processRequest(r);
			}
			SL.processRequest(c);


			System.out.println(arrival);
			System.out.println(midInter);

			if (arrival > 1500){
				frontN = 0;
				midN = 1;
			}else{
				int diff = (int) (1500-arrival);
				midN = (int) Math.round(119.4*Math.exp(-0.01391*arrival)+2.314);
				if (arrival == 60){
					midN += 2;
				}
				System.out.println(midN);
			}
			midN = Math.min(midN, 6);
			if (arrival >= 200){
				frontN = 0;
			}else if (arrival > 60){
				frontN = 1;
			}else{
				frontN = 2;
			}
			

			for(int i = 1; i < midN; i++){
				roles.put(SL.startVM(), 2);
			}
			for (int i = 0; i < frontN; i++){
				roles.put(SL.startVM(), 1);
			}

			while (System.currentTimeMillis()-s < 500){}
			while ((System.currentTimeMillis()-s) < 4700){
				SL.dropHead();
			}
			while ((System.currentTimeMillis()-s) < 5000-arrival){
				if (requests.size() > 3){
					SL.dropHead();
				}else{
					r = SL.getNextRequest();
					requests.add(r);
				}
			}

			long lastScaleMid = System.currentTimeMillis();

			while (true){
				ServerLib.Handle h = SL.acceptConnection(); 
				// read and parse request from client connection at the given handle
				r = SL.parseRequest( h ); //60ms
				requests.add(r);

				if (requests.size() > 1.5*midN && System.currentTimeMillis()-lastScaleMid >= 5200 && midN < 20){
					System.out.println("hi");
					int out = Math.min((int)Math.ceil(requests.size()/1.5-midN),5);
					scaleOut(SL,2,1);             
					midN+=1;
					scaleOut(SL, 1, midN/5-frontN);
					frontN += Math.max(midN/5-frontN, 0);
					lastScaleMid = System.currentTimeMillis();
				}


			}
		}else{
			int role = master.getRole(myVMid);
			if (role == 1){
				SL.register_frontend();
				frontEnd(SL, myVMid, port, master);
			}else{
				midTier(SL, myVMid, master);
			}
		}

	}

	private static void scaleOut(ServerLib SL, int role, int num){
		System.out.println(role + " scaling out" + num);
		for (int i = 0; i < num; i++){
			roles.put(SL.startVM(), role);
		}
	}


	private static void frontEnd(ServerLib SL, int vmid, int port, remoteServer master){
		try{
			while (true){
				long s = System.currentTimeMillis();
				Cloud.FrontEndOps.Request r = SL.getNextRequest();
				master.enqTask(r);
				if (System.currentTimeMillis() - s > 500){
					SL.unregisterFrontend();
					while (SL.getQueueLength() > 0){
						r = SL.getNextRequest();
						master.enqTask(r);
					}
					SL.endVM(vmid);
					master.scaleIn(vmid, 1);
					System.out.println("bye front");
					// remoteServer rs = (remoteServer) Naming.lookup("//localhost:" + port + "/Server_" + vmid);
					// UnicastRemoteObject.unexportObject(rs,true);
					return;
				}
			}
		} catch (Exception e){
			System.err.println(e.toString());
		}
	}
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
					if (System.currentTimeMillis()-period > 500){
						count++;
						period = System.currentTimeMillis();
					}
				}
				if (count > 5){
					if (master.scaleIn(vmid, 2)){
						System.out.println("bye");
						SL.endVM(vmid);
						count = 0;
					}
					period = System.currentTimeMillis();
				}
			}
		} catch (RemoteException e){
			System.err.println(e.toString());
		}
	}

	public boolean scaleIn(int vmid, int role){
		if (role == 2 && vmid == 2){
			return false;
		}
		roles.remove(vmid);
		if (role == 2){
			midN--;
		}else{
			frontN--;
		}
		return true;
	}

	public Cloud.FrontEndOps.Request middleTask() throws RemoteException{
		if  (!requests.isEmpty()){
			while (requests.size() > midN*1.5){
				SL.dropRequest(requests.poll());
			}
			return requests.poll();
		}else{
			return null;
		}
	}

	public void enqTask(Cloud.FrontEndOps.Request r) throws RemoteException{
		requests.add(r);
	}

	public void insertRole(int vmid, int role) throws RemoteException{
		roles.put(vmid, role);
	}

	public int getRole (int vmid) throws RemoteException{
		while(roles.get(vmid) == null){}
		return roles.get(vmid);
	}
}





