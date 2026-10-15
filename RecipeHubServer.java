import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

/**
 * RecipeHub server (plain Java 11+, no dependencies).
 *
 *   javac RecipeHubServer.java
 *   java RecipeHubServer            (then open http://localhost:8080)
 *
 * GET  /            serves index.html from the same folder
 * GET  /api/state   returns the saved platform state (users, recipes, settings, activity) as JSON
 * PUT  /api/state   saves the platform state to data.json
 *
 * The PORT environment variable is used when set (most hosts, such as Render or Railway, provide it).
 */
public class RecipeHubServer {

    private static final Path PAGE = Paths.get("index.html");
    private static final Path DATA = Paths.get("data.json");
    private static final int MAX_BODY = 12 * 1024 * 1024; // photos are stored inside the state
    private static final Object LOCK = new Object();

    public static void main(String[] args) throws IOException {
        int port = 8080;
        String env = System.getenv("PORT");
        if (env != null && !env.isEmpty()) {
            port = Integer.parseInt(env.trim());
        }

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/api/state", RecipeHubServer::handleState);
        server.createContext("/", RecipeHubServer::handlePage);
        server.start();
        System.out.println("RecipeHub running at http://localhost:" + port);
    }

    private static void handlePage(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        if (!"GET".equals(ex.getRequestMethod()) || !(path.equals("/") || path.equals("/index.html"))) {
            send(ex, 404, "text/plain; charset=utf-8", "Not found".getBytes(StandardCharsets.UTF_8));
            return;
        }
        if (!Files.exists(PAGE)) {
            send(ex, 500, "text/plain; charset=utf-8",
                    "index.html must be in the same folder as the server.".getBytes(StandardCharsets.UTF_8));
            return;
        }
        send(ex, 200, "text/html; charset=utf-8", Files.readAllBytes(PAGE));
    }

    private static void handleState(HttpExchange ex) throws IOException {
        String method = ex.getRequestMethod();
        if ("GET".equals(method)) {
            byte[] body;
            synchronized (LOCK) {
                body = Files.exists(DATA) ? Files.readAllBytes(DATA) : "{}".getBytes(StandardCharsets.UTF_8);
            }
            send(ex, 200, "application/json; charset=utf-8", body);
        } else if ("PUT".equals(method)) {
            byte[] body = ex.getRequestBody().readNBytes(MAX_BODY + 1);
            if (body.length > MAX_BODY) {
                send(ex, 413, "text/plain; charset=utf-8", "State too large".getBytes(StandardCharsets.UTF_8));
                return;
            }
            String text = new String(body, StandardCharsets.UTF_8).trim();
            if (!text.startsWith("{") || !text.endsWith("}")) {
                send(ex, 400, "text/plain; charset=utf-8", "Expected a JSON object".getBytes(StandardCharsets.UTF_8));
                return;
            }
            synchronized (LOCK) {
                Path tmp = Paths.get("data.json.tmp");
                Files.write(tmp, body);
                Files.move(tmp, DATA, StandardCopyOption.REPLACE_EXISTING);
            }
            send(ex, 204, "text/plain", new byte[0]);
        } else {
            send(ex, 405, "text/plain; charset=utf-8", "Method not allowed".getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void send(HttpExchange ex, int status, String type, byte[] body) throws IOException {
        ex.getResponseHeaders().set("Content-Type", type);
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        if (status == 204) {
            ex.sendResponseHeaders(204, -1);
        } else {
            ex.sendResponseHeaders(status, body.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(body);
            }
        }
        ex.close();
    }
}
