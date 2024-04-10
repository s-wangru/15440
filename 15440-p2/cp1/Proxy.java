
/**
 *
 * This class is part of a distributed file-caching system designed to cache files from a server locally to 
 * improve read and write performance. It acts as an intermediary between clients and the server, handling file 
 * requests, caching strategies, and ensuring consistency and concurrency through Java RMI. The Proxy class manages 
 * client connections, initiates the cache system, and communicates with the server to fetch, cache, or update files 
 * based on client requests. It implements concurrency control to handle multiple client sessions efficiently.
 * This Proxy can help reduce network traffic and improve the responsiveness of the distributed system by caching 
 * frequently accessed files locally.
 * 
 */

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;
import java.rmi.*;

class Proxy {

    private static Cache cache;
    private static String cacheDir;
    private static String cacheCanonical;
    private static ServerFile server;

    private static class FileHandler implements FileHandling {
        private static final ConcurrentHashMap<Integer, fileInfo> fdToFileMap = new ConcurrentHashMap<>();
        private static final ConcurrentHashMap<Integer, String> fdToPath = new ConcurrentHashMap<>();
        private static final ConcurrentHashMap<String, Integer> readRefs = new ConcurrentHashMap<>();
        private static Integer nextFd = 3;
        private static final Integer MAXSIZE = 300000;

        private synchronized int updateFd() {
            nextFd += 1;
            return nextFd;
        }

        // create the parent directories that don't exist yet in the path
        public void checkParent(String path) {
            if (path == null || path.equals(cacheCanonical)) {
                return;
            }
            File f = new File(path);
            if (f.isFile()) {
                return;
            }
            checkParent(f.getParent());
            f.mkdir();
        }

        // checks if the path tries to access file or directories outside of the rootdir
        public int simplify(String path) {
            if (path == null) {
                return -1;
            }
            if (path.equals(cacheCanonical)) {
                return 0;
            }
            File f = new File(path);
            return simplify(f.getParent());
        }

        // normalize the path relative to the working directory
        public String simplifyPath(String path) throws IOException {
            path = new File(cacheDir + "/" + path).getCanonicalPath();
            int res = simplify(path);
            if (res == 0) {
                return path.substring(cacheCanonical.length() + 1);
            } else {
                return null;
            }
        }

        // remove the older versions of a file that are no longer opened by any clients
        public void removeObsolete(String path, int version) {
            for (int i = 0; i < version; i++) {
                String fString = path + "_" + i;
                if (readRefs.get(fString) == null || readRefs.get(fString) < 1) {
                    File f = new File(fString);
                    if (f.isFile()) {
                        cache.updateCacheSize(f.length(), 0);
                        f.delete();
                    }
                }
            }
        }

        // read the data from a big file on the server by chunking
        int chunkRead(RandomAccessFile f, fileData update, String path) throws Exception {
            int currLen = update.offset;
            f.write(update.getData());
            while (currLen < update.size) {
                fileData d = server.read(path, currLen);
                if (d == null) {
                    return -1;
                }
                f.write(d.getData(), 0, Math.min(MAXSIZE, update.size - currLen));
                currLen = d.offset;
            }
            return 0;
        }

        // when a newer version of the file exist on the server, a read copy of the new
        // version is created
        // in the cache and data is written into the file
        
        public int updateFile(fileData update, String cachePath,
                String path) throws FileNotFoundException, IOException, Exception {
            if (update.getError() != 0) {
                return update.getError();
            }
            RandomAccessFile file = new RandomAccessFile(cachePath + "_" + update.version, "rw");
            removeObsolete(cachePath, update.version);
            if (cache.updateCacheSize(0, update.size) < 0)
                return Errors.ENOMEM;
            if (update.size > MAXSIZE) {
                if (chunkRead(file, update, path) == -1)
                    return -5;
            } else {
                file.write(update.getData());
            }
            file.close();
            return 0;
        }

        // when the cache doesn't currently have the file, import data from the server
        // to create a new read copy
        // in the cache directory
        public int createNewEntry(fileData fdData, String cachePath, String path)
                throws IOException, FileNotFoundException, Exception {
            if (fdData.getError() < 0) {
                return fdData.getError();
            }
            int version = fdData.version;
            File f = new File(cachePath + "_" + version);
            checkParent(new File(cachePath + "_" + version).getCanonicalFile().getParent());
            f.createNewFile();
            removeObsolete(cachePath, version);
            cache.insertEntry(path, false, fdData.version, fdData.size);
            RandomAccessFile file = new RandomAccessFile(f, "rw");
            if (fdData.size > MAXSIZE) {
                if (chunkRead(file, fdData, path) == -1)
                    return -5;
            } else {
                file.write(fdData.getData());
            }
            file.close();
            return 0;
        }

        // make a write copy of a file by copying the content of a read copy of the same
        // version
        public void makeWriteCopy(String cachePath, int version, int fd, String path)
                throws IOException, FileNotFoundException, Exception {
            String writepath = cachePath + "_" + fd + "_" + version;
            cache.updateCacheSize(0, new File(cachePath + "_" + version).length());
            Proxy.copyContent(new File(cachePath + "_" + version), new File(writepath));
            RandomAccessFile writecopy = new RandomAccessFile(writepath, "rw");
            fdToFileMap.put(fd, new fileInfo(writecopy, true, path, version));
            fdToPath.put(fd, writepath);
        }

        // handles the case when clients open with create mode
        public int handleCreate(String path, String cachePath)
                throws FileNotFoundException, IOException, SecurityException, Exception {
            RandomAccessFile file;
            Cache.cacheEntry c = cache.obtainEntry(path);
            int version;
            if (c != null) { // file in cache
                if (c.isDirectory) {
                    return Errors.EISDIR;
                }
                fileData update = server.checkVersion(path, c.version);
                if (update != null) {// current copy stale
                    int res;
                    if ((res = updateFile(update, cachePath, path)) != 0) {
                        return res;
                    }
                    c.version = update.version;
                }
                version = c.version;
            } else { // file not in cache
                fileData fdData = server.openFile(path, 1);
                int res;
                if ((res = createNewEntry(fdData, cachePath, path)) != 0) {
                    return res;
                }
                version = fdData.version;
            }
            int fd = updateFd();
            makeWriteCopy(cachePath, version, fd, path);
            cache.writeRef(path);
            return fd;
        }

        // handles the case when clients open with create_new mode
        public int handleCreateNew(String path, String cachePath)
                throws RemoteException, Exception, IOException, FileNotFoundException {
            fileData fdData = server.openFile(path, 2);
            if (fdData.getError() < 0) {
                return fdData.getError();
            }
            File f = new File(cachePath + "_" + fdData.version);
            checkParent(new File(cachePath + "_" + fdData.version).getCanonicalFile().getParent());
            boolean create = f.createNewFile();
            if (!create) {
                return Errors.EEXIST;
            }
            RandomAccessFile file = new RandomAccessFile(cachePath + "_" + fdData.version, "rw");
            int fd = updateFd();
            String writePath = cachePath + "_" + fd + "_" + fdData.version;
            Proxy.copyContent(new File(cachePath + "_" + fdData.version), new File(writePath));
            RandomAccessFile writeCopy = new RandomAccessFile(writePath, "rw");
            cache.insertEntry(path, false, fdData.version, fdData.getData().length);
            fdToFileMap.put(fd, new fileInfo(writeCopy, true, path, fdData.version));
            cache.updateCacheSize(0, writeCopy.length());
            fdToPath.put(fd, writePath);
            cache.writeRef(path);
            removeObsolete(cachePath, fdData.version);
            return fd;
        }

        // handles the case when clients open with write mode
        public int handleWrite(String path, String cachePath)
                throws RemoteException, IOException, FileNotFoundException, Exception {
            RandomAccessFile file;
            Cache.cacheEntry c = cache.obtainEntry(path);
            int version;
            if (c != null) {// file in cache
                if (c.isDirectory) {
                    return Errors.EISDIR;
                }
                fileData update = server.checkVersion(path, c.version);
                if (update != null) {// current copy stale
                    int res;
                    if ((res = updateFile(update, cachePath, path)) != 0) {
                        return res;
                    }
                    c.version = update.version;
                }
                version = c.version;
            } else { // file not in cache
                fileData fdData = server.openFile(path, 3); // fetch from the server
                int res;
                if ((res = createNewEntry(fdData, cachePath, path)) != 0) {
                    return res;
                }
                version = fdData.version;
            }
            int fd = updateFd();
            makeWriteCopy(cachePath, version, fd, path);
            cache.writeRef(path);
            return fd;
        }

        // handles the case when clients open with read mode
        public int handleRead(String path, String cachePath)
                throws IOException, RemoteException, RemoteException, Exception {
            RandomAccessFile file;
            Cache.cacheEntry c = cache.obtainEntry(path);
            int version;
            if (c != null) { // if file is in cache
                if (c.isDirectory) {
                    int fd = updateFd();
                    fdToPath.put(fd, cachePath);
                    fdToFileMap.put(fd, new fileInfo(null, false, true, path, c.version));
                    return fd;
                }
                fileData update = server.checkVersion(path, c.version);
                if (update != null) { // if current copy is stale
                    int res;
                    if ((res = updateFile(update, cachePath, path)) != 0) {
                        return res;
                    }
                    c.version = update.version;
                }
                version = c.version;
            } else { // file not in cache
                fileData fdData = server.openFile(path, 4); // fetch the file from server
                if (fdData.getError() < 0) {
                    return fdData.getError();
                }
                if (fdData.isDir()) {
                    cache.insertEntry(path, true, fdData.version, 0);
                    int fd = updateFd();
                    fdToPath.put(fd, path);
                    fdToFileMap.put(fd, new fileInfo(null, false, true, path, fdData.version));
                    return fd;
                }
                int res;
                if ((res = createNewEntry(fdData, cachePath, path)) != 0) {
                    return res;
                }
                version = fdData.version;
            }
            int fd = updateFd();
            String readPath = cachePath + "_" + version;
            RandomAccessFile readCopy = new RandomAccessFile(readPath, "r");
            fdToFileMap.put(fd, new fileInfo(readCopy, false, path, version));
            fdToPath.put(fd, readPath);
            // update read reference count
            if (readRefs.get(readPath) == null) {
                readRefs.put(readPath, 1);
            } else {
                readRefs.put(readPath, readRefs.get(readPath) + 1);
            }
            cache.writeRef(path);
            return fd;
        }

        // emulates the open operation semantics in C in a remote file operation system
        // with caching
        public int open(String path, OpenOption o) {
            System.err.println(o);
            String cachePath;
            try {
                path = simplifyPath(path);
                if (path == null) {
                    return Errors.EPERM;
                }
                cachePath = cacheDir + "/" + path;
            } catch (IOException e) {
                e.printStackTrace();
                return Errors.EPERM;
            }
            switch (o) {
                case CREATE:
                    try {
                        return handleCreate(path, cachePath);
                    } catch (FileNotFoundException e) {
                        e.printStackTrace();
                        return Errors.ENOENT;
                    } catch (SecurityException x) {
                        x.printStackTrace();
                        return Errors.EPERM;
                    } catch (IOException e) {
                        e.printStackTrace();
                        return Errors.ENOENT;
                    } catch (Exception e) {
                        e.printStackTrace();
                        return -1;
                    }

                case CREATE_NEW:
                    try {
                        return handleCreateNew(path, cachePath);
                    } catch (FileNotFoundException e) {
                        e.printStackTrace();
                        return Errors.ENOENT;
                    } catch (SecurityException e) {
                        return Errors.EPERM;
                    } catch (IOException e) {
                        return Errors.EINVAL;
                    } catch (Exception e) {
                        e.printStackTrace();
                        return -1;
                    }
                case WRITE:
                    try {
                        return handleWrite(path, cachePath);
                    } catch (FileNotFoundException e) {
                        e.printStackTrace();
                        return Errors.ENOENT;
                    } catch (SecurityException e) {
                        return Errors.EPERM;
                    } catch (IOException e) {
                        e.printStackTrace();
                        return Errors.EINVAL;
                    } catch (Exception e) {
                        e.printStackTrace();
                        return -1;
                    }

                case READ:
                    try {
                        return handleRead(path, cachePath);
                    } catch (SecurityException e) {
                        e.printStackTrace();
                        return Errors.EPERM;
                    } catch (FileNotFoundException e) {
                        e.printStackTrace();
                        return Errors.ENOENT;
                    } catch (Exception e) {
                        e.printStackTrace();
                        return Errors.EINVAL;
                    }

                default:
                    return Errors.EINVAL;
            }
        }

        // replace the orig file with target file by deleting orig and renaming target
        // to orig
        public void replaceFile(String orig, String target) {
            File old = new File(orig);
            File newF = new File(target);
            old.delete();
            newF.renameTo(old);
        }

        // send the data of a huge file to the server using chunking
        // by sending pieces of the data each time and have the server store the data
        // into a temporary file then copy the temporary path to the final destination
        // file
        public int closeWithChunk(RandomAccessFile file, String origPath) throws IOException {
            String tmpPath = UUID.randomUUID().toString();
            int sent = 0;
            byte[] tmpStorage = new byte[MAXSIZE];
            file.seek(0);
            while (sent < file.length()) {
                long size = file.read(tmpStorage);
                server.writeChunk(tmpPath, tmpStorage, (int) size, sent);
                sent += size;
            }
            return server.closeChunk(tmpPath, origPath);
        }

        // propagate the changes to the server when the file has been modified
        public int closeModified(RandomAccessFile file, String origPath, String path, Cache.cacheEntry c)
                throws IOException, RemoteException {
            int newV;
            if (file.length() > MAXSIZE) {
                newV = closeWithChunk(file, origPath);
            } else {
                byte[] fileContent = Files.readAllBytes(Paths.get(path));
                newV = server.closeFile(new fileData(origPath, fileContent, 0));
            }
            if (newV == -1) {
                return -1;
            }
            c.version = newV;
            replaceFile(cacheDir + "/" + origPath + "_" + newV, path);
            removeObsolete(cacheDir + "/" + origPath, newV);
            if (cache.updateSize(c, (int) file.length()) < 0)
                return Errors.ENOMEM;
            if (cache.updateCacheSize(file.length(), 0) < 0)
                return Errors.ENOMEM;
            return 0;
        }

        // emulates the close operation semantics in C in a remote file operation system
        // with caching
        public int close(int fd) {
            fileInfo fi = fdToFileMap.remove(fd);
            String path = fdToPath.remove(fd);
            if (fi != null) {
                if (fi.isDir) {
                    return 0;
                }
                RandomAccessFile file = fi.raf;
                try {
                    Cache.cacheEntry c = cache.obtainEntry(fi.orig_path);
                    if (c != null) {
                        c.reference--;
                    }
                    if (fi.modified) {
                        int res;
                        if ((res = closeModified(file, fi.orig_path, path, c)) != 0) {
                            return res;
                        }
                    } else if (fi.writable) {
                        File f = new File(path);
                        cache.updateCacheSize(file.length(), 0);
                        f.delete();
                    } else {
                        int ref = readRefs.get(path);
                        if (ref <= 1 && c.version > fi.version) {
                            cache.updateCacheSize(file.length(), 0);
                            file.close();
                            new File(path).delete();
                            readRefs.put(path, 0);
                            return 0;
                        } else {
                            readRefs.put(path, ref - 1);
                        }
                    }
                    file.close();
                    return 0;
                } catch (Exception e) {
                    e.printStackTrace();
                    return Errors.EBADF;
                }
            }
            return Errors.EBADF;
        }

        // emulates the write operation semantics in C in a remote file operation system
        // with caching
        public long write(int fd, byte[] buf) {
            String path = fdToPath.get(fd);
            File f = new File(path);
            if (!f.exists()) {
                return Errors.ENOENT;
            }
            if (f.isDirectory()) {
                return Errors.EISDIR;
            }
            if (!f.canWrite()) {
                return Errors.EBADF;
            }
            fileInfo fi = fdToFileMap.get(fd);
            if (fi != null) {
                RandomAccessFile file = fi.raf;
                try {
                    file.write(buf);
                    fi.modified = true;
                    int newLen = (int) file.length();
                    int oldLen = (int) fi.length;
                    if (cache.updateCacheSize(oldLen, newLen) < 0) {
                        return Errors.ENOMEM;
                    }
                    fi.length = newLen;
                    return buf.length;
                } catch (IOException e) {
                    if (e.getMessage().contains("descriptor"))
                        return Errors.EBADF;
                    e.printStackTrace();
                    return Errors.EINVAL;
                }
            }
            return Errors.EBADF;
        }

        // emulates the read operation semantics in C in a remote file operation system
        // with caching
        public long read(int fd, byte[] buf) {
            String path = fdToPath.get(fd);
            fileInfo fi = fdToFileMap.get(fd);
            if (fi != null && fi.isDir) {
                return Errors.EISDIR;
            }
            File f = new File(path);
            if (!f.exists()) {
                return Errors.ENOENT;
            }
            if (f.isDirectory()) {
                return Errors.EISDIR;
            }
            if (!f.canRead()) {
                return Errors.EBADF;
            }
            if (fi != null) {
                RandomAccessFile file = fi.raf;
                try {
                    int res = file.read(buf);
                    if (res == -1) {
                        return 0;
                    } else {
                        return res;
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                    return Errors.EINVAL;
                }
            }
            return Errors.EBADF;
        }

        // emulates the lseek operation semantics in C in a remote file operation system
        // with caching
        public long lseek(int fd, long pos, LseekOption o) {
            String path = fdToPath.get(fd);
            File f = new File(path);
            if (!f.exists()) {
                return Errors.ENOENT;
            }
            if (f.isDirectory()) {
                return Errors.EISDIR;
            }
            fileInfo fi = fdToFileMap.get(fd);
            if (fi != null) {
                RandomAccessFile file = fi.raf;
                try {
                    switch (o) {
                        case FROM_START:
                            file.seek(pos);
                            break;
                        case FROM_CURRENT:
                            file.seek(file.getFilePointer() + pos);
                            break;
                        case FROM_END:
                            file.seek(file.length() + pos);
                            break;
                    }
                    return file.getFilePointer();
                } catch (IOException e) {
                    e.printStackTrace();
                    return Errors.EINVAL;
                }
            }
            return Errors.EBADF;
        }

        // emulates the unlink operation semantics in C in a remote file operation
        // system
        // with caching
        public int unlink(String path) {
            Cache.cacheEntry c = cache.obtainEntry(path);
            try {
                int res = server.unlink(path);
                if (res < 0) {
                    return res;
                }
            } catch (Exception e) {
                e.printStackTrace();
                return -1;
            }
            return 0;
        }

        public void clientdone() {
            return;
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

    private static class FileHandlingFactory implements FileHandlingMaking {
        public FileHandling newclient() {
            return new FileHandler();
        }
    }

    public static void main(String[] args) throws IOException {
        String url = "//" + args[0] + ":" + args[1] + "/Server";
        cacheDir = args[2];
        cacheCanonical = new File(cacheDir).getCanonicalPath();
        int limit = Integer.parseInt(args[3]);
        cache = new Cache(limit, cacheDir);
        try {
            server = (ServerFile) Naming.lookup(url);
        } catch (Exception e) {
            System.err.println(url);
            return;
        }

        (new RPCreceiver(new FileHandlingFactory())).run();
    }

    // a helper class used to store the relevant information about a specific file
    // linked by the file descriptor
    public static class fileInfo {
        RandomAccessFile raf;
        boolean writable;
        boolean modified;
        String orig_path;
        int version;
        boolean isDir;
        long length;

        public fileInfo(RandomAccessFile f, boolean writeable, boolean isDir, String path, int v) throws IOException {
            this.raf = f;
            this.writable = writeable;
            this.modified = false;
            this.orig_path = path;
            this.length = 0;
            this.version = v;
            this.isDir = isDir;
        }

        public fileInfo(RandomAccessFile f, boolean writeable, String path, int v) throws IOException {
            this.raf = f;
            this.writable = writeable;
            this.modified = false;
            this.orig_path = path;
            this.length = f.length();
            this.version = v;
            this.isDir = false;
        }
    }
}
