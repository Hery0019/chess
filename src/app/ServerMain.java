package app;

import net.ChessServer;
import net.Protocol;

import java.io.IOException;

/**
 * Standalone relay server: {@code java -cp chess.jar app.ServerMain [port]}.
 * Players on other machines pick "Online 1 v 1" and join {@code host:port}.
 * Runs until killed (Ctrl+C).
 */
public final class ServerMain {
    public static void main(String[] args) throws IOException, InterruptedException {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : Protocol.DEFAULT_PORT;
        ChessServer server = new ChessServer(port, false);
        server.start();
        System.out.println("Chess server listening on port " + server.port() + " — Ctrl+C to stop.");
        Runtime.getRuntime().addShutdownHook(new Thread(server::close));
        Thread.currentThread().join();
    }
}
