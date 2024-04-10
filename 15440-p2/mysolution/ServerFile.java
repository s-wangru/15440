
/**
 * 
 * This interface defines the remote methods that the server implements to facilitate file operations
 * for the file-caching proxy system. 
 */

import java.rmi.*;

/**
 * ServerFile
 */

public interface ServerFile extends Remote {
    fileData openFile(String path, int mode) throws RemoteException;

    int closeFile(fileData fd) throws RemoteException;

    int unlink(String path) throws RemoteException;

    fileData checkVersion(String path, int currVersion) throws RemoteException;

    int writeChunk(String tmpPath, byte[] data, int len, int offset) throws RemoteException;

    int closeChunk(String tmpPath, String finalPath) throws RemoteException;

    fileData read(String path, int offset) throws RemoteException;

}