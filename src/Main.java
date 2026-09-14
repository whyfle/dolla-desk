import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * DollaDesk v2 - freelance invoice tracker.
 * Full web app: static frontend (public/) + JSON API, zero dependencies.
 *
 * Local run from dolla-desk/ folder:
 *   javac -d out src/Main.java
 *   java -cp out Main        (uses PORT env var if set, else 8080)
 */
public class Main {

    static final String VERSION = "2.0.0";
    static final List<Invoice> DB = new ArrayList<>();
    static Path dataFile;
    static Path publicDir;

    record Invoice(String id, String client, double amount, String due, boolean paid, String notes, String createdAt) {}

    public static void main(String[] args) throws Exception {
        int port = 8080;
        String envPort = System.getenv("PORT");
        if (envPort != null && envPort.matches("\\d{2,5}")) {
            try { port = Integer.parseInt(envPort); } catch (NumberFormatException ignored) {}
        }

        dataFile = resolveDataFile();
        publicDir = resolvePublicDir();
        load();

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/api/health", Main::handleHealth);
        server.createContext("/api/stats", Main::handleStats);
        server.createContext("/api/export.csv", Main::handleExportCsv);
        server.createContext("/api/invoices/toggle", Main::handleToggle);
        server.createContext("/api/invoices", Main::handleInvoices);
        server.createContext("/api", Main::handleApiDocs);
        server.createContext("/", Main::handleStatic);
        server.setExecutor(Executors.newFixedThreadPool(8));
        server.start();
        System.out.println("DollaDesk v" + VERSION + " at http://localhost:" + port);
        System.out.println("Public dir: " + publicDir.toAbsolutePath());
        System.out.println("Data file: " + dataFile.toAbsolutePath());
    }

    // ---------- API ----------

    static void handleHealth(HttpExchange ex) throws IOException {
        if (options(ex)) return;
        sendJson(ex, 200, "{\"ok\":true,\"app\":\"dolla-desk\",\"version\":\"" + VERSION + "\"}");
    }

    static void handleApiDocs(HttpExchange ex) throws IOException {
        if (options(ex)) return;
        if (!ex.getRequestMethod().equalsIgnoreCase("GET")) {
            sendJson(ex, 405, "{\"error\":\"use GET\"}");
            return;
        }
        String docs = "{\"app\":\"dolla-desk\",\"version\":\"" + VERSION + "\",\"endpoints\":["
                + "{\"method\":\"GET\",\"path\":\"/api/health\"},"
                + "{\"method\":\"GET\",\"path\":\"/api/stats\"},"
                + "{\"method\":\"GET\",\"path\":\"/api/invoices?q=acme&paid=true|false&sort=due|amount|client\"},"
                + "{\"method\":\"POST\",\"path\":\"/api/invoices\",\"body\":{\"client\":\"Acme\",\"amount\":500,\"due\":\"2026-10-01\",\"notes\":\"optional\"}},"
                + "{\"method\":\"POST\",\"path\":\"/api/invoices/toggle?id=ID\"},"
                + "{\"method\":\"DELETE\",\"path\":\"/api/invoices?id=ID\"},"
                + "{\"method\":\"GET\",\"path\":\"/api/export.csv\"}"
                + "]}";
        sendJson(ex, 200, docs);
    }

    static void handleStats(HttpExchange ex) throws IOException {
        if (options(ex)) return;
        List<Invoice> copy;
        synchronized (DB) { copy = new ArrayList<>(DB); }
        double billed = 0, paid = 0;
        int overdue = 0;
        String today = LocalDate.now().toString();
        for (Invoice i : copy) {
            billed += i.amount();
            if (i.paid()) paid += i.amount();
            else if (i.due().compareTo(today) < 0) overdue++;
        }
        double pending = Math.round((billed - paid) * 100.0) / 100.0;
        billed = Math.round(billed * 100.0) / 100.0;
        paid = Math.round(paid * 100.0) / 100.0;
        sendJson(ex, 200, "{\"count\":" + copy.size()
                + ",\"billed\":" + billed + ",\"paid\":" + paid
                + ",\"pending\":" + pending + ",\"overdue\":" + overdue + "}");
    }

    static void handleInvoices(HttpExchange ex) throws IOException {
        if (options(ex)) return;
        String method = ex.getRequestMethod().toUpperCase();
        switch (method) {
            case "GET" -> {
                Map<String, String> q = queryMap(ex.getRequestURI().getRawQuery());
                String term = q.getOrDefault("q", "").toLowerCase().trim();
                String paidF = q.getOrDefault("paid", "all");
                String sort = q.getOrDefault("sort", "");
                List<Invoice> copy;
                synchronized (DB) { copy = new ArrayList<>(DB); }
                List<Invoice> out = new ArrayList<>();
                for (Invoice inv : copy) {
                    if (paidF.equals("true") && !inv.paid()) continue;
                    if (paidF.equals("false") && inv.paid()) continue;
                    if (!term.isEmpty()
                            && !inv.client().toLowerCase().contains(term)
                            && !(inv.notes() != null && inv.notes().toLowerCase().contains(term))) continue;
                    out.add(inv);
                }
                if (sort.equals("amount")) out.sort(Comparator.comparingDouble(Invoice::amount).reversed());
                else if (sort.equals("client")) out.sort(Comparator.comparing(Invoice::client, String.CASE_INSENSITIVE_ORDER));
                else if (sort.equals("due")) out.sort(Comparator.comparing(Invoice::due));
                StringBuilder sb = new StringBuilder("[");
                for (int i = 0; i < out.size(); i++) {
                    if (i > 0) sb.append(",");
                    sb.append(toJson(out.get(i)));
                }
                sb.append("]");
                sendJson(ex, 200, sb.toString());
            }
            case "POST" -> {
                String body = readBody(ex);
                String client = extractString(body, "client");
                Double amount = extractNumber(body, "amount");
                String due = extractString(body, "due");
                String notes = extractString(body, "notes");
                if (notes == null) notes = "";
                notes = notes.trim();
                if (notes.length() > 200) {
                    sendJson(ex, 400, "{\"error\":\"notes max 200 chars\"}");
                    return;
                }
                if (client == null || client.trim().isEmpty() || client.trim().length() > 80) {
                    sendJson(ex, 400, "{\"error\":\"client is required (max 80 chars)\"}");
                    return;
                }
                if (amount == null || !(amount > 0) || amount > 1_000_000) {
                    sendJson(ex, 400, "{\"error\":\"amount must be > 0 and <= 1000000\"}");
                    return;
                }
                amount = Math.round(amount * 100.0) / 100.0;
                if (due == null || !due.matches("\\d{4}-\\d{2}-\\d{2}")) {
                    sendJson(ex, 400, "{\"error\":\"due must be YYYY-MM-DD\"}");
                    return;
                }
                try { LocalDate.parse(due); } catch (Exception e) {
                    sendJson(ex, 400, "{\"error\":\"due is not a real date\"}");
                    return;
                }
                Invoice inv = new Invoice(
                        UUID.randomUUID().toString().substring(0, 8),
                        client.trim(), amount, due, false, notes, LocalDate.now().toString());
                synchronized (DB) {
                    DB.add(0, inv);
                    saveLocked();
                }
                sendJson(ex, 201, toJson(inv));
            }
            case "DELETE" -> {
                Map<String, String> q = queryMap(ex.getRequestURI().getRawQuery());
                String id = q.get("id");
                if (id == null || id.isEmpty()) {
                    sendJson(ex, 400, "{\"error\":\"missing ?id=\"}");
                    return;
                }
                boolean removed;
                synchronized (DB) {
                    removed = DB.removeIf(i -> i.id().equals(id));
                    if (removed) saveLocked();
                }
                if (removed) sendJson(ex, 200, "{\"ok\":true}");
                else sendJson(ex, 404, "{\"error\":\"not found\"}");
            }
            default -> sendJson(ex, 405, "{\"error\":\"method not allowed\"}");
        }
    }

    static void handleToggle(HttpExchange ex) throws IOException {
        if (options(ex)) return;
        if (!ex.getRequestMethod().equalsIgnoreCase("POST")) {
            sendJson(ex, 405, "{\"error\":\"use POST\"}");
            return;
        }
        Map<String, String> q = queryMap(ex.getRequestURI().getRawQuery());
        String id = q.get("id");
        if (id == null || id.isEmpty()) id = extractString(readBody(ex), "id");
        if (id == null || id.isEmpty()) {
            sendJson(ex, 400, "{\"error\":\"missing ?id=\"}");
            return;
        }
        Invoice updated = null;
        synchronized (DB) {
            for (int i = 0; i < DB.size(); i++) {
                Invoice inv = DB.get(i);
                if (inv.id().equals(id)) {
                    updated = new Invoice(inv.id(), inv.client(), inv.amount(), inv.due(), !inv.paid(), inv.notes(), inv.createdAt());
                    DB.set(i, updated);
                    saveLocked();
                    break;
                }
            }
        }
        if (updated == null) sendJson(ex, 404, "{\"error\":\"not found\"}");
        else sendJson(ex, 200, toJson(updated));
    }

    static void handleExportCsv(HttpExchange ex) throws IOException {
        if (options(ex)) return;
        Map<String, String> q = queryMap(ex.getRequestURI().getRawQuery());
        String paidF = q.getOrDefault("paid", "all");
        List<Invoice> copy;
        synchronized (DB) { copy = new ArrayList<>(DB); }
        StringBuilder sb = new StringBuilder("id,client,amount,due,paid,notes,createdAt\n");
        for (Invoice inv : copy) {
            if (paidF.equals("true") && !inv.paid()) continue;
            if (paidF.equals("false") && inv.paid()) continue;
            sb.append(csv(inv.id())).append(",").append(csv(inv.client())).append(",")
              .append(inv.amount()).append(",").append(csv(inv.due())).append(",")
              .append(inv.paid()).append(",").append(csv(inv.notes() == null ? "" : inv.notes()))
              .append(",").append(csv(inv.createdAt())).append("\n");
        }
        byte[] b = sb.toString().getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/csv; charset=utf-8");
        ex.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"invoices.csv\"");
        ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        ex.sendResponseHeaders(200, b.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(b); }
    }

    static void handleStatic(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        if (path.equals("/")) path = "/index.html";
        if (path.contains("..") || path.contains("\\")) {
            sendText(ex, 400, "bad request", "text/plain");
            return;
        }
        Path file = publicDir.resolve(path.substring(1)).normalize();
        if (!file.startsWith(publicDir) || !Files.isRegularFile(file)) {
            if (!path.startsWith("/api/")) {
                file = publicDir.resolve("index.html");
            } else {
                sendJson(ex, 404, "{\"error\":\"not found\"}");
                return;
            }
        }
        byte[] bytes = Files.readAllBytes(file);
        ex.getResponseHeaders().set("Content-Type", contentType(file.getFileName().toString()));
        ex.getResponseHeaders().set("Cache-Control", "no-cache");
        ex.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
    }

    // ---------- helpers ----------

    static boolean options(HttpExchange ex) throws IOException {
        ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        ex.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, DELETE, OPTIONS");
        ex.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
        if (ex.getRequestMethod().equalsIgnoreCase("OPTIONS")) {
            ex.sendResponseHeaders(204, -1);
            return true;
        }
        return false;
    }

    static void sendJson(HttpExchange ex, int code, String json) throws IOException {
        byte[] b = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        ex.sendResponseHeaders(code, b.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(b); }
    }

    static void sendText(HttpExchange ex, int code, String text, String ct) throws IOException {
        byte[] b = text.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", ct + "; charset=utf-8");
        ex.sendResponseHeaders(code, b.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(b); }
    }

    static String readBody(HttpExchange ex) throws IOException {
        return new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    static String toJson(Invoice i) {
        String notes = i.notes() == null ? "" : i.notes();
        return "{\"id\":\"" + esc(i.id()) + "\",\"client\":\"" + esc(i.client()) + "\",\"amount\":"
                + i.amount() + ",\"due\":\"" + esc(i.due()) + "\",\"paid\":" + i.paid()
                + ",\"notes\":\"" + esc(notes) + "\",\"createdAt\":\"" + esc(i.createdAt()) + "\"}";
    }

    static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ").replace("\r", " ");
    }

    static String csv(String s) {
        if (s == null) return "\"\"";
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }

    static String extractString(String json, String key) {
        Pattern p = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"([^\"]*)\"");
        Matcher m = p.matcher(json == null ? "" : json);
        return m.find() ? m.group(1) : null;
    }

    static Double extractNumber(String json, String key) {
        Pattern p = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*(-?\\d+(?:\\.\\d+)?)");
        Matcher m = p.matcher(json == null ? "" : json);
        if (m.find()) {
            try { return Double.parseDouble(m.group(1)); } catch (NumberFormatException e) { return null; }
        }
        return null;
    }

    static Map<String, String> queryMap(String query) {
        Map<String, String> m = new HashMap<>();
        if (query == null || query.isEmpty()) return m;
        for (String part : query.split("&")) {
            String[] kv = part.split("=", 2);
            if (kv.length == 2) {
                try {
                    m.put(URLDecoder.decode(kv[0], StandardCharsets.UTF_8),
                          URLDecoder.decode(kv[1], StandardCharsets.UTF_8));
                } catch (Exception e) { m.put(kv[0], kv[1]); }
            }
        }
        return m;
    }

    static String contentType(String name) {
        if (name.endsWith(".html")) return "text/html";
        if (name.endsWith(".css")) return "text/css";
        if (name.endsWith(".js")) return "text/javascript";
        if (name.endsWith(".json")) return "application/json";
        if (name.endsWith(".csv")) return "text/csv";
        return "text/plain";
    }

    static Path resolvePublicDir() {
        Path[] candidates = {
                Paths.get("public"),
                Paths.get("dolla-desk/public"),
                Paths.get(System.getProperty("user.dir"), "public"),
                Paths.get(System.getProperty("user.dir"), "dolla-desk/public"),
                Paths.get("/app/public")
        };
        for (Path p : candidates) {
            if (Files.isRegularFile(p.resolve("index.html"))) return p.toAbsolutePath();
        }
        return Paths.get("public").toAbsolutePath();
    }

    static Path resolveDataFile() {
        // Docker / free hosts have ephemeral disk: allow DATA_FILE env override
        String env = System.getenv("DATA_FILE");
        if (env != null && !env.isBlank()) return Paths.get(env).toAbsolutePath();
        Path[] candidates = { Paths.get("data.json"), Paths.get("dolla-desk/data.json") };
        for (Path p : candidates) {
            if (Files.exists(p)) return p.toAbsolutePath();
        }
        if (Files.isDirectory(Paths.get("dolla-desk"))) return Paths.get("dolla-desk/data.json").toAbsolutePath();
        if (Files.isDirectory(Paths.get("/app"))) return Paths.get("/app/data.json").toAbsolutePath();
        return Paths.get("data.json").toAbsolutePath();
    }

    static void load() {
        try {
            if (!Files.exists(dataFile)) return;
            String json = Files.readString(dataFile, StandardCharsets.UTF_8).trim();
            if (json.isEmpty() || json.equals("[]")) return;
            Matcher m = Pattern.compile("\\{[^{}]*\\}").matcher(json);
            synchronized (DB) {
                DB.clear();
                while (m.find()) {
                    String o = m.group();
                    String id = extractString(o, "id");
                    String client = extractString(o, "client");
                    Double amount = extractNumber(o, "amount");
                    String due = extractString(o, "due");
                    String createdAt = extractString(o, "createdAt");
                    String notes = extractString(o, "notes");
                    boolean paid = o.contains("\"paid\":true");
                    if (id != null && client != null && amount != null && due != null) {
                        DB.add(new Invoice(id, client, amount, due, paid,
                                notes != null ? notes : "",
                                createdAt != null ? createdAt : LocalDate.now().toString()));
                    }
                }
            }
            System.out.println("Loaded " + DB.size() + " invoices.");
        } catch (Exception e) {
            System.out.println("Could not load data.json, starting empty: " + e.getMessage());
        }
    }

    static void saveLocked() {
        try {
            StringBuilder sb = new StringBuilder("[\n");
            for (int i = 0; i < DB.size(); i++) {
                sb.append("  ").append(toJson(DB.get(i)));
                if (i < DB.size() - 1) sb.append(",");
                sb.append("\n");
            }
            sb.append("]");
            if (dataFile.getParent() != null) Files.createDirectories(dataFile.getParent());
            Files.writeString(dataFile, sb.toString(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.out.println("Save failed: " + e.getMessage());
        }
    }
}
