/* Skeleton code for UserNode */

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;

public class UserNode implements ProjectLib.MessageHandling {
    public final String myId;
    public static ProjectLib PL;

    private static ArrayList<String> locked_list = new ArrayList<>();

    public UserNode(String id) {
        myId = id;
    }

    public static Commit_message deserializeByteArrayToObject(byte[] bytes) {
        ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(bytes);
        try (ObjectInputStream objectInputStream = new ObjectInputStream(byteArrayInputStream)) {
            return (Commit_message) objectInputStream.readObject();
        } catch (IOException | ClassNotFoundException e) {
            e.printStackTrace();
            return null;
        }
    }

    public boolean deliverMessage(ProjectLib.Message msg) {
        try {
            String srcAdr = msg.addr;
            Commit_message request = deserializeByteArrayToObject(msg.body);
            if (request.type.equals("PREPARE")) {
                for (String file : request.sources) {
                    if (!Files.exists(Paths.get(file)) || locked_list.contains(file)) {
                        ByteArrayOutputStream bos = new ByteArrayOutputStream();
                        ObjectOutputStream oos = new ObjectOutputStream(bos);
                        Commit_message m = new Commit_message("VOTEABORT", request.fileName, request.content,
                                request.sources);
                        oos.writeObject(m);
                        ProjectLib.Message newM = new ProjectLib.Message(srcAdr, bos.toByteArray());
                        PL.sendMessage(newM);
                        return true;
                    }
                }
                if (PL.askUser(request.content, request.sources)) {
                    String s = "VOTECOMMIT";
                    for (String file : request.sources) {
                        locked_list.add(file);
                    }
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    ObjectOutputStream oos = new ObjectOutputStream(bos);
                    Commit_message m = new Commit_message("VOTECOMMIT", request.fileName, request.content,
                            request.sources);
                    oos.writeObject(m);
                    ProjectLib.Message newM = new ProjectLib.Message(srcAdr, bos.toByteArray());
                    PL.sendMessage(newM);
                    System.out.println("agree to commit " + request.fileName);
                    return true;
                } else {
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    ObjectOutputStream oos = new ObjectOutputStream(bos);
                    Commit_message m = new Commit_message("VOTEABORT", request.fileName, request.content,
                            request.sources);
                    oos.writeObject(m);
                    ProjectLib.Message newM = new ProjectLib.Message(srcAdr, bos.toByteArray());
                    PL.sendMessage(newM);
                    return true;
                }
            } else if (request.type.equals("COMMIT_SUC")) {
                for (String file : request.sources) {
                    File f = new File(file);
                    f.delete();
                    locked_list.remove(file);
                }
                return true;
            } else if (request.type.equals("COMMIT_FAIL")) {
                for (String file : request.sources) {
                    locked_list.remove(file);
                }
                return true;
            }
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    public static void main(String args[]) throws Exception {
        if (args.length != 2)
            throw new Exception("Need 2 args: <port> <id>");
        UserNode UN = new UserNode(args[1]);
        PL = new ProjectLib(Integer.parseInt(args[0]), args[1], UN);

        ProjectLib.Message msg = new ProjectLib.Message("Server", "hello".getBytes());
        System.out.println(args[1] + ": Sending message to " + msg.addr);
    }
}
