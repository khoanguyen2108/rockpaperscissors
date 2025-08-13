import java.io.*;
import java.net.*;

/**
 * Simple console client for the RPS server.
 */
public class RpsClient {
    public static void main(String[] args) throws Exception {
        String host = (args.length > 0) ? args[0] : "127.0.0.1";
        int port = (args.length > 1) ? Integer.parseInt(args[1]) : 5000;

        try (Socket socket = new Socket(host, port)) {
            socket.setTcpNoDelay(true);
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            PrintWriter out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream()), true);
            BufferedReader console = new BufferedReader(new InputStreamReader(System.in));

            // Thread to read server messages continuously
            Thread reader = new Thread(() -> {
                try {
                    String line;
                    while ((line = in.readLine()) != null) {
                        System.out.println(line);
                    }
                } catch (IOException ignored) { }
                System.out.println("[Disconnected]");
                System.exit(0);
            }, "ServerReader");
            reader.setDaemon(true);
            reader.start();

            // Main loop: forward user input to server
            String input;
            while ((input = console.readLine()) != null) {
                out.println(input);
            }
        }
    }
}