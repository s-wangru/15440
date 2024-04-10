import java.io.Serializable;

public class Commit_message implements Serializable {
    public String type;
    public String fileName;
    public byte[] content;
    public String[] sources;

    public Commit_message(String type, String fileName, byte[] content, String[] sources) {
        this.type = type;
        this.fileName = fileName;
        this.content = content;
        this.sources = sources;
    }

}