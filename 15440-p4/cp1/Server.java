/* Skeleton code for Server */

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.LinkedBlockingQueue;

public class Server implements ProjectLib.CommitServing {

    private static final ConcurrentHashMap<String, Commit_Thread> commitThreads = new ConcurrentHashMap<>();

    public static ProjectLib PL;

    private class Commit_Thread implements Runnable {

        private String[] sources;
        private byte[] img;
        private String filename;
        private ConcurrentHashMap<String, ArrayList<String>> sourceFile = new ConcurrentHashMap<>();

        private BlockingQueue<ProjectLib.Message> msgQueue = new LinkedBlockingQueue<>();

        private Commit_Thread(String fileName, byte[] img, String[] sources) {
            this.filename = fileName;
            this.img = img;
            this.sources = sources;
        }

        @Override
        public void run() {
            try {
                commitThreads.put(filename, this);
                for (String s : sources) {
                    String[] splitted = s.split(":");
                    String srcadr = splitted[0];
                    ArrayList<String> files = sourceFile.get(srcadr);
                    if (files == null) {
                        files = new ArrayList<>();
                        files.add(splitted[1]);
                        sourceFile.put(srcadr, files);
                    } else {
                        files.add(splitted[1]);
                    }
                }

                // prepare stage: asking every involved user node
                for (String key : sourceFile.keySet()) {
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    ObjectOutputStream oos = new ObjectOutputStream(bos);
                    ArrayList<String> localSrc = sourceFile.get(key);
                    String[] srcArr = new String[localSrc.size()];
                    Commit_message m = new Commit_message("PREPARE", this.filename, img, localSrc.toArray(srcArr));
                    oos.writeObject(m);
                    ProjectLib.Message newM = new ProjectLib.Message(key, bos.toByteArray());
                    PL.sendMessage(newM);
                }
                System.out.println("prepared");

                int numUser = sourceFile.size();
                System.out.println(numUser);
                long start = System.currentTimeMillis();
                boolean agreed = true;

                while (numUser > 0 && agreed) {
                    while (msgQueue.size() > 0) {
                        System.out.println("got 1 response");
                        ProjectLib.Message msg = msgQueue.poll();
                        if (sourceFile.keySet().contains(msg.addr)) {
                            Commit_message reply = deserializeByteArrayToObject(msg.body);
                            if (reply.type.equals("VOTECOMMIT")) {
                                numUser--;
                            } else {
                                System.out.println(reply.type);
                                agreed = false;
                                break;
                            }
                        }
                    }
                }
                if (numUser == 0 && agreed) {
                    System.out.println("everyone said yes");
                    FileOutputStream out = new FileOutputStream(filename);
                    out.write(img);
                    out.close();
                    for (String key : sourceFile.keySet()) {
                        ByteArrayOutputStream bos = new ByteArrayOutputStream();
                        ObjectOutputStream oos = new ObjectOutputStream(bos);
                        ArrayList<String> localSrc = sourceFile.get(key);
                        String[] srcArr = new String[localSrc.size()];
                        Commit_message m = new Commit_message("COMMIT_SUC", filename, img,
                                localSrc.toArray(srcArr));
                        oos.writeObject(m);
                        ProjectLib.Message newM = new ProjectLib.Message(key, bos.toByteArray());
                        PL.sendMessage(newM);
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        public void insertMessage(ProjectLib.Message msg) {
            msgQueue.offer(msg);
        }

    }

    public void startCommit(String filename, byte[] img, String[] sources) {
        try {
            System.out.println("Server: Got request to commit " + filename);
            Commit_Thread m = new Commit_Thread(filename, img, sources);
            Thread t = new Thread(m);
            t.run();
        } catch (Exception e) {
            e.printStackTrace();
        }

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

    public static void main(String args[]) throws Exception {
        if (args.length != 1)
            throw new Exception("Need 1 arg: <port>");
        Server srv = new Server();
        PL = new ProjectLib(Integer.parseInt(args[0]), srv);

        // main loop
        while (true) {
            ProjectLib.Message msg = PL.getMessage();
            Commit_message m = deserializeByteArrayToObject(msg.body);
            System.out.println("received " + m.fileName);
            commitThreads.get(m.fileName).msgQueue.put(msg);
        }
    }
}
