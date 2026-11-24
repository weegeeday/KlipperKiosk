package com.klipperkiosk.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class RemoteManagementServer {

    public interface Controller {
        String getPin();
        JSONObject getStatusJson();
        JSONObject getTabsJson();
        JSONObject addTab(String title, String url, boolean activate);
        JSONObject updateTab(String id, String title, String url);
        boolean deleteTab(String id);
        boolean activateTab(String id);
        boolean reorderTabs(List<String> ids);
        JSONObject resetTabs();
        void reloadActiveTab();
        void navigateActive(String url);
        void setKioskLocked(boolean locked);
        void exitKioskMode();
        void enterKioskMode();
        boolean updatePin(String newPin);
        JSONObject getIrInfo();
        JSONObject transmitIr(int frequencyHz, int[] patternUs, int repeat, int repeatDelayMs);
        void sleepNow();
        void wakeNow();
        boolean setSleepTimeoutMinutes(int minutes);
    }

    private final int port;
    private final Controller controller;
    private final ExecutorService workers = Executors.newFixedThreadPool(2);
    private volatile boolean running = false;
    private ServerSocket serverSocket;
    private Thread acceptThread;

    public RemoteManagementServer(int port, Controller controller) {
        this.port = port;
        this.controller = controller;
    }

    public int getPort() {
        return port;
    }

    public void start() {
        if (running) return;
        running = true;
        acceptThread = new Thread(() -> {
            try {
                serverSocket = new ServerSocket(port);
                while (running) {
                    Socket client = serverSocket.accept();
                    workers.execute(() -> handleClient(client));
                }
            } catch (IOException ignored) {
            }
        }, "KioskRemoteApi");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    public void stop() {
        running = false;
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (IOException ignored) {
        }
        workers.shutdownNow();
    }

    public static String getDeviceIpv4() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress addr : Collections.list(ni.getInetAddresses())) {
                    if (!addr.isLoopbackAddress() && addr instanceof Inet4Address) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return "0.0.0.0";
    }

    private void handleClient(Socket socket) {
        try (Socket s = socket) {
            s.setSoTimeout(5000);
            InputStream in = new BufferedInputStream(s.getInputStream());
            OutputStream out = s.getOutputStream();

            String requestLine = readLine(in);
            if (requestLine == null || requestLine.isEmpty()) return;

            String[] parts = requestLine.split(" ");
            if (parts.length < 2) {
                writeJson(out, 400, errorJson("Malformed request"));
                return;
            }
            String method = parts[0].toUpperCase(Locale.US);
            String rawTarget = parts[1];

            Map<String, String> headers = new HashMap<>();
            String headerLine;
            while ((headerLine = readLine(in)) != null && !headerLine.isEmpty()) {
                int idx = headerLine.indexOf(':');
                if (idx > 0) {
                    headers.put(
                        headerLine.substring(0, idx).trim().toLowerCase(Locale.US),
                        headerLine.substring(idx + 1).trim()
                    );
                }
            }

            if ("OPTIONS".equals(method)) {
                writeResponse(out, 204, "No Content", "");
                return;
            }

            int contentLength = 0;
            if (headers.containsKey("content-length")) {
                try {
                    contentLength = Math.min(65536, Integer.parseInt(headers.get("content-length")));
                } catch (NumberFormatException ignored) {
                }
            }
            String body = contentLength > 0 ? readExact(in, contentLength) : "";

            String path = rawTarget;
            Map<String, String> query = new HashMap<>();
            int qIdx = rawTarget.indexOf('?');
            if (qIdx >= 0) {
                path = rawTarget.substring(0, qIdx);
                parseQuery(rawTarget.substring(qIdx + 1), query);
            }

            if ("GET".equals(method) && "/api/health".equals(path)) {
                JSONObject ok = new JSONObject();
                ok.put("status", "ok");
                ok.put("app", "KlipperKiosk");
                writeJson(out, 200, ok);
                return;
            }

            if (!isAuthorized(headers, query)) {
                writeJson(out, 401, errorJson("Unauthorized: invalid or missing PIN"));
                return;
            }

            route(method, path, body, out);
        } catch (Exception ignored) {
        }
    }

    private void route(String method, String path, String body, OutputStream out) throws Exception {
        if ("GET".equals(method) && "/api/status".equals(path)) {
            writeJson(out, 200, controller.getStatusJson());
            return;
        }

        if ("GET".equals(method) && "/api/tabs".equals(path)) {
            writeJson(out, 200, controller.getTabsJson());
            return;
        }

        if ("POST".equals(method) && "/api/tabs".equals(path)) {
            JSONObject req = parseBodyJson(body);
            String url = req.optString("url", "").trim();
            if (url.isEmpty()) {
                writeJson(out, 400, errorJson("url is required"));
                return;
            }
            String title = req.optString("title", "").trim();
            boolean activate = req.optBoolean("activate", true);
            JSONObject created = controller.addTab(title, url, activate);
            writeJson(out, 201, created);
            return;
        }

        if ("POST".equals(method) && "/api/tabs/reorder".equals(path)) {
            JSONObject req = parseBodyJson(body);
            JSONArray idsArr = req.optJSONArray("ids");
            if (idsArr == null || idsArr.length() == 0) {
                writeJson(out, 400, errorJson("ids array is required"));
                return;
            }
            List<String> ids = new ArrayList<>();
            for (int i = 0; i < idsArr.length(); i++) {
                ids.add(idsArr.getString(i));
            }
            controller.reorderTabs(ids);
            writeJson(out, 200, controller.getTabsJson());
            return;
        }

        if ("POST".equals(method) && "/api/tabs/reset".equals(path)) {
            writeJson(out, 200, controller.resetTabs());
            return;
        }

        if (path.startsWith("/api/tabs/")) {
            String rest = path.substring("/api/tabs/".length());
            if ("POST".equals(method) && rest.endsWith("/activate")) {
                String id = decode(rest.substring(0, rest.length() - "/activate".length()));
                if (!controller.activateTab(id)) {
                    writeJson(out, 404, errorJson("Tab not found: " + id));
                    return;
                }
                writeJson(out, 200, okJson("Tab activated"));
                return;
            }
            String id = decode(rest);
            if ("PUT".equals(method)) {
                JSONObject req = parseBodyJson(body);
                String title = req.has("title") ? req.optString("title", "") : null;
                String url = req.has("url") ? req.optString("url", "") : null;
                JSONObject updated = controller.updateTab(id, title, url);
                if (updated == null) {
                    writeJson(out, 404, errorJson("Tab not found: " + id));
                    return;
                }
                writeJson(out, 200, updated);
                return;
            }
            if ("DELETE".equals(method)) {
                if (!controller.deleteTab(id)) {
                    writeJson(out, 404, errorJson("Tab not found: " + id));
                    return;
                }
                writeJson(out, 200, okJson("Tab deleted"));
                return;
            }
        }

        if ("POST".equals(method) && "/api/reload".equals(path)) {
            controller.reloadActiveTab();
            writeJson(out, 200, okJson("Reloading active tab"));
            return;
        }

        if ("POST".equals(method) && "/api/navigate".equals(path)) {
            JSONObject req = parseBodyJson(body);
            String url = req.optString("url", "").trim();
            if (url.isEmpty()) {
                writeJson(out, 400, errorJson("url is required"));
                return;
            }
            controller.navigateActive(url);
            writeJson(out, 200, okJson("Navigating to " + url));
            return;
        }

        if ("POST".equals(method) && "/api/kiosk/lock".equals(path)) {
            JSONObject req = parseBodyJson(body);
            if (!req.has("locked")) {
                writeJson(out, 400, errorJson("locked boolean is required"));
                return;
            }
            boolean locked = req.optBoolean("locked", false);
            controller.setKioskLocked(locked);
            JSONObject res = okJson(locked ? "Controls hidden" : "Controls visible");
            res.put("kioskLocked", locked);
            writeJson(out, 200, res);
            return;
        }

        if ("POST".equals(method) && "/api/kiosk/exit".equals(path)) {
            controller.exitKioskMode();
            writeJson(out, 200, okJson("Exited kiosk lock task mode"));
            return;
        }

        if ("POST".equals(method) && "/api/kiosk/enter".equals(path)) {
            controller.enterKioskMode();
            writeJson(out, 200, okJson("Entered kiosk lock task mode"));
            return;
        }

        if ("POST".equals(method) && "/api/pin".equals(path)) {
            JSONObject req = parseBodyJson(body);
            String newPin = req.optString("pin", "").trim();
            if (newPin.isEmpty()) {
                writeJson(out, 400, errorJson("pin is required"));
                return;
            }
            if (!controller.updatePin(newPin)) {
                writeJson(out, 400, errorJson("Invalid PIN"));
                return;
            }
            writeJson(out, 200, okJson("PIN updated"));
            return;
        }

        if ("GET".equals(method) && "/api/ir".equals(path)) {
            writeJson(out, 200, controller.getIrInfo());
            return;
        }

        if ("POST".equals(method) && ("/api/ir/transmit".equals(path) || "/api/ir".equals(path))) {
            JSONObject req = parseBodyJson(body);
            int repeat = Math.max(1, Math.min(10, req.optInt("repeat", 1)));
            int repeatDelayMs = Math.max(0, Math.min(2000, req.optInt("repeatDelayMs", 100)));

            int frequency;
            int[] pattern;

            String prontoHex = req.optString("prontoHex", "").trim();
            if (!prontoHex.isEmpty()) {
                ProntoParsed parsed = parseProntoHex(prontoHex);
                if (parsed == null) {
                    writeJson(out, 400, errorJson("Invalid prontoHex format"));
                    return;
                }
                frequency = parsed.frequencyHz;
                pattern = parsed.patternUs;
            } else {
                frequency = req.optInt("frequency", 38000);
                JSONArray patArr = req.optJSONArray("pattern");
                if (patArr == null || patArr.length() == 0) {
                    writeJson(out, 400, errorJson("pattern (array of microseconds) or prontoHex is required"));
                    return;
                }
                pattern = new int[patArr.length()];
                for (int i = 0; i < patArr.length(); i++) {
                    int val = patArr.optInt(i, -1);
                    if (val <= 0) {
                        writeJson(out, 400, errorJson("pattern values must be positive integers (microseconds)"));
                        return;
                    }
                    pattern[i] = val;
                }
            }

            if (frequency < 10000 || frequency > 100000) {
                writeJson(out, 400, errorJson("frequency must be between 10000 and 100000 Hz"));
                return;
            }

            JSONObject result = controller.transmitIr(frequency, pattern, repeat, repeatDelayMs);
            int statusCode = result.optBoolean("ok", false) ? 200 : 400;
            writeJson(out, statusCode, result);
            return;
        }

        if ("POST".equals(method) && "/api/sleep".equals(path)) {
            controller.sleepNow();
            writeJson(out, 200, okJson("Entered sleep mode"));
            return;
        }

        if ("POST".equals(method) && "/api/wake".equals(path)) {
            controller.wakeNow();
            writeJson(out, 200, okJson("Woke from sleep mode"));
            return;
        }

        if ("POST".equals(method) && "/api/sleep/config".equals(path)) {
            JSONObject req = parseBodyJson(body);
            if (!req.has("timeoutMinutes")) {
                writeJson(out, 400, errorJson("timeoutMinutes integer is required (0 to disable)"));
                return;
            }
            int minutes = req.optInt("timeoutMinutes", -1);
            if (minutes < 0 || minutes > 1440) {
                writeJson(out, 400, errorJson("timeoutMinutes must be between 0 and 1440"));
                return;
            }
            controller.setSleepTimeoutMinutes(minutes);
            JSONObject res = okJson("Sleep timeout updated");
            res.put("sleepTimeoutMinutes", minutes);
            writeJson(out, 200, res);
            return;
        }

        writeJson(out, 404, errorJson("Not found: " + method + " " + path));
    }

    private boolean isAuthorized(Map<String, String> headers, Map<String, String> query) {
        String expected = controller.getPin();
        if (expected == null || expected.isEmpty()) return false;

        String provided = headers.get("x-kiosk-pin");
        if (provided == null) {
            String auth = headers.get("authorization");
            if (auth != null && auth.regionMatches(true, 0, "Bearer ", 0, 7)) {
                provided = auth.substring(7).trim();
            }
        }
        if (provided == null) {
            provided = query.get("pin");
        }
        if (provided == null) return false;

        return MessageDigest.isEqual(
            expected.getBytes(StandardCharsets.UTF_8),
            provided.getBytes(StandardCharsets.UTF_8)
        );
    }

    private static JSONObject parseBodyJson(String body) {
        if (body == null || body.trim().isEmpty()) {
            return new JSONObject();
        }
        try {
            return new JSONObject(body);
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private static JSONObject okJson(String message) {
        JSONObject obj = new JSONObject();
        try {
            obj.put("ok", true);
            obj.put("message", message);
        } catch (Exception ignored) {
        }
        return obj;
    }

    private static JSONObject errorJson(String message) {
        JSONObject obj = new JSONObject();
        try {
            obj.put("ok", false);
            obj.put("error", message);
        } catch (Exception ignored) {
        }
        return obj;
    }

    private static void writeJson(OutputStream out, int code, JSONObject json) throws IOException {
        String statusText;
        switch (code) {
            case 200: statusText = "OK"; break;
            case 201: statusText = "Created"; break;
            case 400: statusText = "Bad Request"; break;
            case 401: statusText = "Unauthorized"; break;
            case 404: statusText = "Not Found"; break;
            default: statusText = "Error"; break;
        }
        writeResponse(out, code, statusText, json.toString());
    }

    private static void writeResponse(OutputStream out, int code, String statusText, String body)
        throws IOException {
        byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
        String headers =
            "HTTP/1.1 " + code + " " + statusText + "\r\n" +
            "Content-Type: application/json; charset=utf-8\r\n" +
            "Content-Length: " + bodyBytes.length + "\r\n" +
            "Access-Control-Allow-Origin: *\r\n" +
            "Access-Control-Allow-Methods: GET, POST, PUT, DELETE, OPTIONS\r\n" +
            "Access-Control-Allow-Headers: Content-Type, X-Kiosk-Pin, Authorization\r\n" +
            "Connection: close\r\n\r\n";
        out.write(headers.getBytes(StandardCharsets.UTF_8));
        if (bodyBytes.length > 0) {
            out.write(bodyBytes);
        }
        out.flush();
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') break;
            if (b != '\r') buf.write(b);
        }
        if (b == -1 && buf.size() == 0) return null;
        return new String(buf.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String readExact(InputStream in, int length) throws IOException {
        byte[] data = new byte[length];
        int read = 0;
        while (read < length) {
            int n = in.read(data, read, length - read);
            if (n == -1) break;
            read += n;
        }
        return new String(data, 0, read, StandardCharsets.UTF_8);
    }

    private static void parseQuery(String rawQuery, Map<String, String> out) {
        for (String pair : rawQuery.split("&")) {
            if (pair.isEmpty()) continue;
            int eq = pair.indexOf('=');
            if (eq > 0) {
                out.put(decode(pair.substring(0, eq)), decode(pair.substring(eq + 1)));
            } else {
                out.put(decode(pair), "");
            }
        }
    }

    private static String decode(String value) {
        try {
            return URLDecoder.decode(value, "UTF-8");
        } catch (Exception e) {
            return value;
        }
    }

    private static final class ProntoParsed {
        final int frequencyHz;
        final int[] patternUs;

        ProntoParsed(int frequencyHz, int[] patternUs) {
            this.frequencyHz = frequencyHz;
            this.patternUs = patternUs;
        }
    }

    private static ProntoParsed parseProntoHex(String hex) {
        try {
            String[] tokens = hex.trim().split("\\s+");
            if (tokens.length < 6) return null;
            int preamble = Integer.parseInt(tokens[0], 16);
            if (preamble != 0) return null;
            int freqCode = Integer.parseInt(tokens[1], 16);
            if (freqCode <= 0) return null;
            int frequencyHz = (int) Math.round(1_000_000.0 / (freqCode * 0.241246));
            double periodUs = 1_000_000.0 / frequencyHz;

            int count = tokens.length - 4;
            int[] pattern = new int[count];
            for (int i = 0; i < count; i++) {
                int cycles = Integer.parseInt(tokens[i + 4], 16);
                int us = (int) Math.max(1, Math.round(cycles * periodUs));
                pattern[i] = us;
            }
            return new ProntoParsed(frequencyHz, pattern);
        } catch (Exception ignored) {
            return null;
        }
    }
}

