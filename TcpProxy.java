import java.io.*;
import java.net.*;
public class TcpProxy {
    public static void main(String[] args) throws Exception {
        int listenPort = Integer.parseInt(args[0]);
        String targetHost = args[1];
        int targetPort = Integer.parseInt(args[2]);
        ServerSocket server = new ServerSocket(listenPort);
        System.out.println("TcpProxy " + listenPort + " -> " + targetHost + ":" + targetPort);
        while (true) {
            Socket client = server.accept();
            Socket remote = new Socket(targetHost, targetPort);
            new Thread(() -> transfer(client, remote)).start();
            new Thread(() -> transfer(remote, client)).start();
        }
    }
    static void transfer(Socket from, Socket to) {
        try {
            byte[] buf = new byte[8192];
            InputStream in = from.getInputStream();
            OutputStream out = to.getOutputStream();
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } catch (IOException e) {}
        finally { try { from.close(); } catch (IOException e) {} }
    }
}
