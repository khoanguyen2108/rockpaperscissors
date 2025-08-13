import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * RpsServer
 * - Accepts many clients.
 * - Pairs clients into GameRooms (2 players per room).
 * - Plays best-of-3; after a match, players can rematch or return to lobby.
 */
public class RpsServer {
    private final int port;
    private volatile boolean running = true;

    private final ExecutorService clientPool = Executors.newCachedThreadPool();
    private final ExecutorService roomPool = Executors.newCachedThreadPool();

    // Players waiting to be matched
    private final BlockingQueue<ClientHandler> lobby = new LinkedBlockingQueue<>();

    public RpsServer(int port) {
        this.port = port;
    }

    public void start() throws IOException {
        try (ServerSocket serverSocket = new ServerSocket(port)) {
            log("Server listening on port " + port);

            // Thread to constantly match players
            Thread matcher = new Thread(this::matchLoop, "Matcher");
            matcher.setDaemon(true);
            matcher.start();

            while (running) {
                Socket socket = serverSocket.accept();
                socket.setTcpNoDelay(true);
                ClientHandler handler = new ClientHandler(socket, this);
                clientPool.submit(handler);
            }
        }
    }

    private void matchLoop() {
        while (running) {
            try {
                ClientHandler p1 = lobby.take(); // waits for a player
                ClientHandler p2 = lobby.take(); // waits for the second player

                if (!p1.isAlive()) continue;
                if (!p2.isAlive()) { // if p2 died, put p1 back to lobby
                    queuePlayer(p1);
                    continue;
                }

                GameRoom room = new GameRoom(p1, p2, this);
                roomPool.submit(room);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    void queuePlayer(ClientHandler handler) {
        if (handler == null) return;
        if (!handler.isAlive()) return;
        lobby.offer(handler);
        handler.send("\n=== Bạn đã vào sảnh. Đang đợi người chơi khác... ===\n");
    }

    static void log(String msg) {
        System.out.println("[SERVER] " + msg);
    }

    public static void main(String[] args) throws Exception {
        int port = 5000;
        if (args.length > 0) {
            port = Integer.parseInt(args[0]);
        }
        new RpsServer(port).start();
    }
}

class ClientHandler implements Runnable {
    private final Socket socket;
    private final RpsServer server;
    private BufferedReader in;
    private PrintWriter out;
    private volatile boolean alive = true;
    private String name = "?";

    public ClientHandler(Socket socket, RpsServer server) {
        this.socket = socket;
        this.server = server;
    }

    @Override
    public void run() {
        try {
            in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream()), true);

            send("Chào mừng đến với RPS Server!\nNhập tên của bạn: ");
            String n = in.readLine();
            if (n == null || n.trim().isEmpty()) n = "Player" + new Random().nextInt(1000);
            this.name = n.trim();
            send("Xin chào, " + name + "!\n");

            server.queuePlayer(this);

            // Keep thread alive to detect disconnect
            while (alive) {
                String line = in.readLine();
                if (line == null) break; // client closed; ignore random input when in lobby
            }
        } catch (IOException ignored) {
        } finally {
            close();
        }
    }

    public void send(String msg) {
        if (out != null) out.print(msg);
        if (out != null) out.flush();
    }

    public String request(String prompt) throws IOException {
        send(prompt);
        String line = in.readLine();
        if (line == null) throw new IOException("Client disconnected");
        return line;
    }

    public boolean isAlive() { return alive && !socket.isClosed(); }

    public String getName() { return name; }

    public void close() {
        alive = false;
        try { socket.close(); } catch (IOException ignored) {}
        RpsServer.log("Client disconnected: " + name);
    }
}

class GameRoom implements Runnable {
    enum Move { ROCK, PAPER, SCISSORS; }

    private final ClientHandler p1;
    private final ClientHandler p2;
    private final RpsServer server;

    public GameRoom(ClientHandler p1, ClientHandler p2, RpsServer server) {
        this.p1 = p1; this.p2 = p2; this.server = server;
    }

    @Override
    public void run() {
        RpsServer.log("New room: " + p1.getName() + " vs " + p2.getName());
        try {
            broadcast("\n=== Trận đấu bắt đầu: " + p1.getName() + " vs " + p2.getName() + " ===\n");
            boolean bothWantPlay = true;
            while (bothWantPlay && p1.isAlive() && p2.isAlive()) {
                playBestOf3();
                bothWantPlay = askRematch();
            }
        } catch (IOException e) {
            // someone disconnected mid-game
        } finally {
            // Return remaining alive players to lobby
            if (p1.isAlive()) server.queuePlayer(p1); else p1.close();
            if (p2.isAlive()) server.queuePlayer(p2); else p2.close();
        }
    }

    private void playBestOf3() throws IOException {
        int s1 = 0, s2 = 0;
        int round = 1;
        while (s1 < 2 && s2 < 2) {
            sendBoth("\n-- ROUND " + round + " --\n");
            Move m1 = askMove(p1);
            Move m2 = askMove(p2);

            int res = compare(m1, m2);
            if (res == 0) {
                sendBoth("Hòa! (" + m1 + " vs " + m2 + ")\n");
            } else if (res > 0) {
                s1++; sendBoth(p1.getName() + " thắng round này! (" + m1 + " beats " + m2 + ")\n");
            } else {
                s2++; sendBoth(p2.getName() + " thắng round này! (" + m2 + " beats " + m1 + ")\n");
            }
            sendBoth("Tỷ số: " + p1.getName() + " " + s1 + " - " + s2 + " " + p2.getName() + "\n");
            round++;
        }

        if (s1 > s2) sendBoth("\n>>> " + p1.getName() + " THẮNG TRẬN! <<<\n");
        else sendBoth("\n>>> " + p2.getName() + " THẮNG TRẬN! <<<\n");
    }

    private boolean askRematch() throws IOException {
        String a1 = p1.request("Chơi lại? (y/n): ");
        String a2 = p2.request("Chơi lại? (y/n): ");
        boolean p1y = a1 != null && a1.trim().toLowerCase().startsWith("y");
        boolean p2y = a2 != null && a2.trim().toLowerCase().startsWith("y");

        if (p1y && p2y) {
            sendBoth("\nBắt đầu trận mới!\n");
            return true;
        }

        if (p1y && !p2y) {
            p2.send("Tạm biệt! Quay lại sảnh.\n");
            server.queuePlayer(p1);
            return false; // room ends
        }
        if (!p1y && p2y) {
            p1.send("Tạm biệt! Quay lại sảnh.\n");
            server.queuePlayer(p2);
            return false;
        }

        sendBoth("Kết thúc. Cả hai trở về sảnh.\n");
        return false;
    }

    private void broadcast(String msg) {
        p1.send(msg);
        p2.send(msg);
    }

    private void sendBoth(String msg) {
        broadcast(msg);
    }

    private Move askMove(ClientHandler p) throws IOException {
        while (true) {
            String ans = p.request(p.getName() + ", nhập nước đi [rock/paper/scissors]: ");
            if (ans == null) throw new IOException("disconnect");
            Move m = parseMove(ans.trim());
            if (m != null) return m;
            p.send("Không hợp lệ. Vui lòng nhập rock/paper/scissors.\n");
        }
    }

    private Move parseMove(String s) {
        s = s.toLowerCase(Locale.ROOT);
        switch (s) {
            case "r": case "rock": return Move.ROCK;
            case "p": case "paper": return Move.PAPER;
            case "s": case "scissors": return Move.SCISSORS;
            default: return null;
        }
    }

    // return 0 tie, >0 p1 wins, <0 p2 wins
    private int compare(Move a, Move b) {
        if (a == b) return 0;
        if ((a == Move.ROCK && b == Move.SCISSORS) ||
                (a == Move.PAPER && b == Move.ROCK) ||
                (a == Move.SCISSORS && b == Move.PAPER)) return 1;
        return -1;
    }
}