
/**
 *
 * This class implements the caching logic for the file-caching proxy system. It manages a local cache of files
 * fetched from the server, optimizing access to frequently used files by reducing network overhead. The Cache
 * class uses a combination of a ConcurrentHashMap for fast concurrent access to cache entries and a LinkedList
 * to maintain an order for cache eviction based on the Least Recently Used (LRU) policy. It dynamically manages 
 * the cache size within a specified limit, evicting the least recently used files when necessary.
 *
 * The class provides methods for cache operations including obtaining, inserting, and removing cache entries, 
 * as well as managing cache size and evictions.
 *
 * 
 */

import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.concurrent.ConcurrentHashMap;
import java.io.File;
import java.io.IOException;
import java.lang.*;

public class Cache {

    private int size; // current size of the cache directory
    private int limit; // capacity of the cache directory
    private String cacheDir; // path to the cache directory

    private ConcurrentHashMap<String, cacheEntry> cacheCopies;
    private LinkedList<String> orders;

    public Cache(int limit, String cachedir) {
        this.limit = limit;
        this.cacheCopies = new ConcurrentHashMap<>();
        this.orders = new LinkedList<>();
        this.size = 0;
        this.cacheDir = cachedir;
    }

    // Obtains a cache entry for the given path. If the entry exists,
    // it updates the order of access to reflect its recent use.
    public cacheEntry obtainEntry(String path) {
        cacheEntry c = this.cacheCopies.get(path);
        if (c != null) {
            orders.remove(path);
            orders.addFirst(path);
        }
        return c;
    }

    // Performs eviction of the least recently used (LRU) entries until the cache
    // size is within the limit.
    // This method is synchronized to handle concurrent modifications.
    public synchronized int eviction() {
        if (this.size <= this.limit) {
            return 0;
        }
        if (orders.size() == 0) {
            return -1;
        }
        String s = orders.removeLast();
        cacheEntry removed = cacheCopies.get(s);
        if (!(removed.isDirectory) && removed.reference != 0) {
            int res = eviction();
            orders.addLast(s);
            return res;
        }
        File f = new File(cacheDir + "/" + s + "_" + removed.version);
        cacheCopies.remove(s);
        f.delete();
        this.size -= removed.size;
        return eviction();
    }

    // Inserts a new cache entry for the given path and file attributes.
    // If necessary, eviction is performed to ensure the cache size does not exceed
    // its limit.
    public synchronized int insertEntry(String path, boolean isdir, int v, int size) {
        if (cacheCopies.get(path) != null) {
            return 0;
        }
        this.size += size;
        cacheCopies.put(path, new cacheEntry(path, isdir, v, size));
        int res = eviction();
        if (res < 0) {
            return res;
        }
        orders.addFirst(path);
        return 0;
    }

    // Decrements the reference count for a cache entry.
    // This is used to manage shared access to cached files.
    public void decReference(String path) {
        cacheEntry c = this.cacheCopies.get(path);
        if (c != null) {
            c.reference--;
        }
    }

    // Increments the reference count for a cache entry.
    // This is used when a file begins to be accessed or modified.
    public void writeRef(String path) {
        cacheEntry c = this.cacheCopies.get(path);
        if (c != null) {
            c.reference++;
        }
    }

    // Updates the size of a specific cache entry and performs eviction if
    // necessary.
    // This method is synchronized to handle concurrent updates safely.
    public synchronized int updateSize(cacheEntry c, int newSize) {
        this.size += newSize;
        c.size = newSize;
        return eviction();
    }

    // Updates the total size of the cache and performs eviction if necessary.
    public synchronized int updateCacheSize(long origS, long newS) {
        this.size += newS - origS;
        return eviction();
    }

    // a helper class that stores information about a cached file,
    // including its path, size, version, and whether it is a directory.
    public static class cacheEntry {
        int reference;
        String cpyPath;
        boolean isDirectory;
        int version;
        int size;

        public cacheEntry(String path, boolean isdir, int v, int s) {
            this.reference = 0;
            this.cpyPath = path;
            this.isDirectory = isdir;
            this.version = v;
            this.size = s;
        }
    }
}