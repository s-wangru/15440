
/**
 * 
 * This interface defines the remote methods that the server implements to facilitate file operations
 * for the file-caching proxy system. 
 */

 import java.rmi.*;

 
 public interface remoteServer extends Remote {

    Cloud.FrontEndOps.Request middleTask() throws RemoteException;
    void enqTask(Cloud.FrontEndOps.Request r) throws RemoteException;
    void insertRole(int vmid, int role) throws RemoteException;
    int getRole(int vmid) throws RemoteException;
    boolean scaleIn(int vmid, int role) throws RemoteException;
 
 }