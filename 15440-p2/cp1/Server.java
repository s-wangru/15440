
/**
 * 
 * This file implements the server-side logic for a distributed file-caching system, providing 
 * file services to a proxy over RMI. This class is responsible for handling file operations 
 * requested by the proxy, including opening, reading, writing, and deleting files.
 * It manages file versions to ensure consistency across operations and provide file data to the proxy
 * (with chunking when necessary).
 *
 * 
 */

import java.io.*;
import java.net.MalformedURLException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.rmi.*;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;

public class Server extends UnicastRemoteObject implements ServerFile {

    private String rootdir; // path to the root directory
    private String canonicalRoot; // the canonical path to the root directory
    private static final Integer MAXSIZE = 300000;

    private static final ConcurrentHashMap<String, Integer> versions = new ConcurrentHashMap<>();

    public Server() throws RemoteException {
        super(0);
    }

    // validates and creates parent directories as needed, ensuring
    // file operations occur within the server's root directory.
    public int checkParent(String path) {
        if (path == null) {
            return -1;
        }
        if (path.equals(canonicalRoot)) {
            return 0;
        }
        File f = new File(path);
        if (checkParent(f.getParent()) == -1) {
            return -1;
        }
        if (!f.isFile()) {
            f.mkdir();
        }
        return 0;
    }

    // checks if the path tries to access file or directories outside of the rootdir
    public int simplify(String path) {
        if (path == null) {
            return -1;
        }
        if (path.equals(canonicalRoot)) {
            return 0;
        }
        File f = new File(path);
        System.err.println(path);
        return simplify(f.getParent());
    }

    // Opens or creates a file at the specified path, handling it based on the
    // provided error code.
    public fileData openCreate(String path, int error) throws IOException {
        File f = new File(path);
        if (checkParent(new File(path).getCanonicalFile().getParent()) == -1) {
            System.err.println("outside of root dir");
            return new fileData(path, null, -1);
        }
        boolean create = f.createNewFile();
        if (!create) {
            if (!f.canRead() || !f.canWrite()) {
                return new fileData(path, null, -13);
            }
            if (f.isDirectory()) {
                return new fileData(path, null, -21);
            }
        }
        RandomAccessFile file = new RandomAccessFile(f, "rw");
        int v = 0;
        if (versions.get(path) != null) {
            v = versions.get(path);
        } else {
            versions.put(path, v);
        }
        if (file.length() > MAXSIZE) {
            byte[] firstChunk = new byte[MAXSIZE];
            file.read(firstChunk);
            int offset = (int) file.getFilePointer();
            int size = (int) file.length();
            file.close();
            return new fileData(path, firstChunk, size, offset, error);
        }
        return new fileData(file, path, error, v);
    }

    // Specifically handles the creation of a new file at the given path, ensuring
    // it does not already exist.
    public fileData openCreateNew(String path, int error) throws IOException {
        File f = new File(path);
        if (checkParent(new File(path).getCanonicalFile().getParent()) == -1) {
            return new fileData(path, null, -1);
        }
        if (f.exists()) {
            return new fileData(path, null, -17);
        }
        boolean create = f.createNewFile();
        if (!create) {
            return new fileData(path, null, -17);
        }
        RandomAccessFile file = new RandomAccessFile(path, "rw");
        int v = 0;
        if (versions.get(path) != null) {
            v = versions.get(path);
        } else {
            versions.put(path, v);
        }
        return new fileData(file, path, error, v);
    }

    // Opens a file for writing, verifying its existence and accessibility before
    // proceeding.
    public fileData openWrite(String path, int error) throws IOException {
        File f = new File(path);
        if (!f.isFile()) {
            return new fileData(null, path, -2, -1);
        }
        if (f.isDirectory()) {
            return new fileData(null, path, -21, -1);
        }
        if (!f.canRead() || !f.canWrite()) {
            return new fileData(null, path, -13, -1);
        }
        RandomAccessFile file = new RandomAccessFile(path, "rw");
        int v = 0;
        if (versions.get(path) != null) {
            v = versions.get(path);
        } else {
            versions.put(path, v);
        }
        if (file.length() > MAXSIZE) {
            byte[] firstChunk = new byte[MAXSIZE];
            file.read(firstChunk);
            int offset = (int) file.getFilePointer();
            int size = (int) file.length();
            file.close();
            return new fileData(path, firstChunk, size, offset, error);
        }
        return new fileData(file, path, error, v);
    }

    // Opens a file for reading, ensuring the file exists and is readable.
    public fileData openRead(String path, int error) throws FileNotFoundException, IOException {
        File f = new File(path);
        if (!f.exists()) {
            return new fileData(null, path, -2, -1);
        }
        if (!f.canRead()) {
            return new fileData(null, path, -13, -1);
        }
        if (f.isDirectory()) {
            return new fileData(path, null, 0, true);
        }
        RandomAccessFile file = new RandomAccessFile(path, "r");
        int v = 0;
        if (versions.get(path) != null) {
            v = versions.get(path);
        } else {
            versions.put(path, v);
        }
        if (file.length() > MAXSIZE) {
            byte[] firstChunk = new byte[MAXSIZE];
            file.read(firstChunk);
            int offset = (int) file.getFilePointer();
            int size = (int) file.length();
            file.close();
            return new fileData(path, firstChunk, size, offset, error);
        }
        return new fileData(file, path, error, v);
    }

    // handles the proxy's request to open a file and send the metadata of the file
    // back if operation can be done
    public fileData openFile(String path, int mode) {
        try {
            path = new File(rootdir + path).getCanonicalPath();
            int res = simplify(path);
            if (res == 0) {
                path = rootdir + path.substring(canonicalRoot.length() + 1);
            } else {
                return new fileData(path, null, -1);
            }
        } catch (IOException e) {
            e.printStackTrace();
            return new fileData(path, null, -1);
        }
        int error = 0;
        try {
            if (mode == 1) {
                return openCreate(path, error);
            } else if (mode == 2) {
                return openCreateNew(path, error);
            } else if (mode == 3) {
                return openWrite(path, error);
            } else if (mode == 4) {
                return openRead(path, error);
            }
        } catch (NoSuchFileException e) {
            e.printStackTrace();
            return new fileData(path, null, -2);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
        return null;
    }

    // Closes a file, applying any changes made during the session and return the
    // updated file version to the proxy.
    public int closeFile(fileData fd) {
        String path = rootdir + fd.getFileName();
        try {
            RandomAccessFile f = new RandomAccessFile(path, "rw");
            System.err.println("closing " + path);
            new FileOutputStream(path).close();
            f.write(fd.getData());
            f.close();
            versions.put(path, versions.get(path) + 1);
            System.err.println(path + " current version:" + versions.get(path));
            return versions.get(path);
        } catch (IOException e) {
            e.printStackTrace();
            return -1;
        }
    }

    // Reads a chunk of data from a file starting at a specific offset and send the
    // data back to the proxy
    public fileData read(String path, int offset) {
        path = rootdir + path;
        byte[] buf = new byte[MAXSIZE];
        try {
            RandomAccessFile f = new RandomAccessFile(path, "rw");
            f.seek(offset);
            f.read(buf);
            int newOffset = (int) f.getFilePointer();
            int size = (int) f.length();
            f.close();
            return new fileData(path, buf, size, newOffset, 0);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }

    }

    // emulates the link operation semantics in C that deletes a name from the
    // filesystem
    public int unlink(String path) {
        path = rootdir + path;
        File file = new File(path);
        if (!file.exists()) {
            return -2;
        }
        if (file.isDirectory()) {
            return -21;
        }
        try {
            if (file.delete()) {
                if (versions.get(path) != null) {
                    versions.put(path, versions.get(path) + 1);
                }
                return 0;
            } else {
                return -2;
            }
        } catch (SecurityException e) {
            return -1;
        }
    }

    // Checks the version of a file against the current version stored on the
    // server.
    // send back the metadata of the file if a newer version is available on the
    // server
    // otherwise send back null
    public fileData checkVersion(String path, int currVersion) {
        path = rootdir + path;
        int v = versions.get(path);
        if (!new File(path).exists()) {
            return new fileData(path, null, -2, false);
        }
        if (v == currVersion) {
            return null;
        } else {
            try {
                RandomAccessFile f = new RandomAccessFile(path, "r");
                if (f.length() > MAXSIZE) {
                    byte[] firstChunk = new byte[MAXSIZE];
                    f.read(firstChunk);
                    int offset = (int) f.getFilePointer();
                    int len = (int) f.length();
                    f.close();
                    return new fileData(path, firstChunk, len, offset, 0);
                }
                return new fileData(f, path, 0, v);
            } catch (NoSuchFileException e) {
                return new fileData(path, null, -2, false);
            } catch (IOException e) {
                e.printStackTrace();
                return null;
            }
        }
    }

    // Writes a chunk of data to a temporary file at a specific offset
    public int writeChunk(String tmpPath, byte[] data, int len, int offset) {
        System.err.println("writing chunks of size: " + len);
        tmpPath = rootdir + tmpPath;
        File f = new File(tmpPath);
        try {
            if (offset == 0) {
                f.createNewFile();
            }
            RandomAccessFile raf = new RandomAccessFile(tmpPath, "rw");
            raf.seek(offset);
            raf.write(data, 0, len);
            raf.close();
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return 0;
    }

    // Finalizes the chunking write process by moving data from a temporary file to
    // its final destination.
    public int closeChunk(String tmpPath, String finalPath) {
        tmpPath = rootdir + tmpPath;
        finalPath = rootdir + finalPath;
        try {
            copyContent(new File(tmpPath), new File(finalPath));
            versions.put(finalPath, versions.get(finalPath) + 1);
            new File(tmpPath).delete();
            return versions.get(finalPath);
        } catch (Exception e) {
            e.printStackTrace();
            return -1;
        }
    }

    // copy the content of the file orig to file copy
    public static void copyContent(File orig, File copy)
            throws Exception {

        new FileOutputStream(copy).close();
        FileInputStream in = new FileInputStream(orig);
        FileOutputStream out = new FileOutputStream(copy);

        try {
            byte[] buf = new byte[4000];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        } finally {
            if (in != null) {
                in.close();
            }
            if (out != null) {
                out.close();
            }
        }
    }

    public static void main(String[] args) throws RemoteException, MalformedURLException, IOException {
        int port = Integer.parseInt(args[0]);
        Server rmiServer = new Server();

        rmiServer.rootdir = args[1] + "/";
        rmiServer.canonicalRoot = new File(args[1]).getCanonicalPath();

        Registry registry = LocateRegistry.createRegistry(port);

        Naming.rebind("//localhost:" + port + "/Server", rmiServer);
    }
}