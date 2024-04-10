
/**
 *
 *
 * fileData is a serializable data structure for efficient data transfer between the server and 
 * proxy within the file-caching system. It includes file metadata and content, and state information, 
 * such as whether the file has been deleted or is a directory, its version for consistency, and error
 * codes for operation outcomes.
 *
 * Attributes:
 * - fileName: Name or path of the file.
 * - data: Byte array containing file data.
 * - size: Size of the data array.
 * - offset: Offset for reading chunks of data.
 * - error: Error code representing the status of file operations.
 * - isDirectory: Flag indicating if the file is a directory.
 * - deleted: Flag indicating if the file has been marked as deleted.
 * - version: Version of the file for maintaining consistency.
 * 
 * 
 */

import java.io.IOException;
import java.io.RandomAccessFile;
import java.io.Serializable;
import java.nio.file.Files;
import java.nio.file.Paths;

public class fileData implements Serializable {
    private String fileName;
    private byte[] data;
    private int error;
    private boolean isDirectory;
    public int version;
    public boolean deleted;
    public int offset;
    public int size;

    public fileData(String fileName, byte[] data, int size, int offset, int error) {
        this.fileName = fileName;
        this.data = data;
        this.error = error;
        this.isDirectory = false;
        this.deleted = false;
        this.offset = offset;
        this.size = size;
    }

    public fileData(String fileName, byte[] data, int error) {
        this.fileName = fileName;
        this.data = data;
        this.error = error;
        this.isDirectory = false;
        this.deleted = false;
        if (data != null) {
            this.size = data.length;
        } else {
            this.size = 0;
        }
    }

    public fileData(String fileName, byte[] data, int error, boolean isdir) {
        this.fileName = fileName;
        this.data = data;
        this.error = error;
        this.isDirectory = isdir;
        if (isdir) {
            this.version = 0;
        }
        this.deleted = false;
        if (data == null) {
            this.size = 0;
        } else {
            this.size = data.length;
        }
    }

    public fileData(RandomAccessFile f, String path, int error, int v) throws IOException {
        this.fileName = path;
        if (f != null) {
            this.data = new byte[(int) (f.length())];
            f.readFully(this.data);
            this.size = (int) f.length();
            f.close();
        } else {
            this.size = 0;
        }
        this.error = error;
        this.isDirectory = false;
        this.version = v;
        this.deleted = false;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public byte[] getData() {
        return data;
    }

    public void setData(byte[] data) {
        this.data = data;
    }

    public int getError() {
        return error;
    }

    public void setDir() {
        this.isDirectory = true;
    }

    public void unsetDir() {
        this.isDirectory = false;
    }

    public boolean isDir() {
        return isDirectory;
    }

}