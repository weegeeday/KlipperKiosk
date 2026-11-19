package com.klipperkiosk.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import java.io.ByteArrayOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

public final class HostResolver {
    private static final String PREFS_NAME = "klipperkiosk_host_cache";
    private static final Pattern IPV4_PATTERN = Pattern.compile("^(\\d{1,3}\\.){3}\\d{1,3}$");

    private HostResolver() {}

    public static final class Result {
        public final String url;
        public final String ip;
        public final String source;
        public final String host;

        public Result(String url, String ip, String source, String host) {
            this.url = url;
            this.ip = ip;
            this.source = source;
            this.host = host;
        }
    }

    public static String getCachedUrl(Context context, String rawUrl) {
        try {
            Uri uri = Uri.parse(rawUrl);
            String host = uri.getHost();
            if (host == null || host.isEmpty() || IPV4_PATTERN.matcher(host).matches()
                || "localhost".equalsIgnoreCase(host)) {
                return rawUrl;
            }
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            String cachedIp = prefs.getString(host, null);
            if (cachedIp == null || cachedIp.isEmpty()) {
                return null;
            }
            return buildUrlWithIp(uri, cachedIp);
        } catch (Exception ignored) {
            return rawUrl;
        }
    }

    public static String resolveUrlSync(Context context, String rawUrl) {
        try {
            Uri uri = Uri.parse(rawUrl);
            String host = uri.getHost();
            if (host == null || host.isEmpty() || IPV4_PATTERN.matcher(host).matches()
                || "localhost".equalsIgnoreCase(host)) {
                return rawUrl;
            }
            String scheme = uri.getScheme() != null ? uri.getScheme() : "http";
            int port = uri.getPort();
            if (port == -1) {
                port = "https".equalsIgnoreCase(scheme) ? 443 : 80;
            }
            String path = uri.getEncodedPath() != null ? uri.getEncodedPath() : "";
            if (uri.getEncodedQuery() != null) {
                path += "?" + uri.getEncodedQuery();
            }
            if (uri.getEncodedFragment() != null) {
                path += "#" + uri.getEncodedFragment();
            }

            Result res = resolveHost(context, host, scheme, port, path);
            return res != null && res.url != null ? res.url : rawUrl;
        } catch (Exception ignored) {
            String cached = getCachedUrl(context, rawUrl);
            return cached != null ? cached : rawUrl;
        }
    }

    public static Result resolveHost(Context context, String host, String scheme, int port, String path)
        throws Exception {
        String finalHost = host.trim();
        String normalizedPath = path == null ? "" : path.trim();
        if (!normalizedPath.isEmpty() && !normalizedPath.startsWith("/")) {
            normalizedPath = "/" + normalizedPath;
        }

        String resolvedIp = resolveWithMdns(finalHost);
        String source = "mdns";

        if (resolvedIp == null || resolvedIp.isEmpty()) {
            InetAddress fallbackAddress = InetAddress.getByName(finalHost);
            resolvedIp = fallbackAddress.getHostAddress();
            source = "dns";
        }

        if (resolvedIp == null || resolvedIp.isEmpty()) {
            throw new IllegalStateException("Unable to resolve host: " + finalHost);
        }

        if (context != null) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(finalHost, resolvedIp)
                .apply();
        }

        boolean isDefaultPort =
            ("http".equalsIgnoreCase(scheme) && port == 80) ||
            ("https".equalsIgnoreCase(scheme) && port == 443);
        String portPart = isDefaultPort ? "" : ":" + port;
        String resolvedUrl = scheme + "://" + resolvedIp + portPart + normalizedPath;

        return new Result(resolvedUrl, resolvedIp, source, finalHost);
    }

    private static String buildUrlWithIp(Uri uri, String ip) {
        String scheme = uri.getScheme() != null ? uri.getScheme() : "http";
        int port = uri.getPort();
        boolean isDefaultPort = port == -1
            || ("http".equalsIgnoreCase(scheme) && port == 80)
            || ("https".equalsIgnoreCase(scheme) && port == 443);
        String portPart = isDefaultPort ? "" : ":" + port;
        StringBuilder sb = new StringBuilder();
        sb.append(scheme).append("://").append(ip).append(portPart);
        if (uri.getEncodedPath() != null) sb.append(uri.getEncodedPath());
        if (uri.getEncodedQuery() != null) sb.append('?').append(uri.getEncodedQuery());
        if (uri.getEncodedFragment() != null) sb.append('#').append(uri.getEncodedFragment());
        return sb.toString();
    }

    private static String resolveWithMdns(String host) {
        try {
            InetAddress[] addresses = InetAddress.getAllByName(host);
            if (addresses != null) {
                for (InetAddress address : addresses) {
                    if (address instanceof Inet4Address) {
                        return address.getHostAddress();
                    }
                }
                if (addresses.length > 0) {
                    return addresses[0].getHostAddress();
                }
            }
        } catch (Exception ignored) {
        }

        try (DatagramSocket socket = new DatagramSocket()) {
            byte[] query = buildMdnsQuery(host);
            InetAddress mdnsAddress = InetAddress.getByName("224.0.0.251");
            socket.setSoTimeout(1200);
            DatagramPacket packet = new DatagramPacket(query, query.length, mdnsAddress, 5353);
            socket.send(packet);

            long end = System.currentTimeMillis() + 1200;
            byte[] buf = new byte[1500];
            while (System.currentTimeMillis() < end) {
                try {
                    DatagramPacket resp = new DatagramPacket(buf, buf.length);
                    socket.receive(resp);
                    String ip = parseMdnsResponseForA(resp.getData(), resp.getLength(), host);
                    if (ip != null) {
                        return ip;
                    }
                } catch (SocketTimeoutException ste) {
                    break;
                } catch (Exception ignored) {
                }
            }
        } catch (Exception ignored) {
        }

        return null;
    }

    private static byte[] buildMdnsQuery(String host) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(new byte[]{0x00, 0x00, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00});
        for (String p : host.split("\\.")) {
            byte[] b = p.getBytes(StandardCharsets.UTF_8);
            out.write(b.length);
            out.write(b);
        }
        out.write(0x00);
        out.write(new byte[]{0x00, 0x01, 0x00, 0x01});
        return out.toByteArray();
    }

    private static String parseMdnsResponseForA(byte[] data, int len, String host) {
        try {
            if (len < 12) return null;
            int qdcount = ((data[4] & 0xFF) << 8) | (data[5] & 0xFF);
            int ancount = ((data[6] & 0xFF) << 8) | (data[7] & 0xFF);
            int pos = 12;

            for (int i = 0; i < qdcount; i++) {
                pos = skipName(data, pos, len);
                pos += 4;
                if (pos >= len) return null;
            }

            String fallbackIp = null;
            for (int i = 0; i < ancount; i++) {
                int namePos = pos;
                pos = skipName(data, pos, len);
                if (pos + 10 > len) return null;
                int type = ((data[pos] & 0xFF) << 8) | (data[pos + 1] & 0xFF);
                int rdlen = ((data[pos + 8] & 0xFF) << 8) | (data[pos + 9] & 0xFF);
                pos += 10;
                if (pos + rdlen > len) return null;

                if (type == 1 && rdlen == 4) {
                    String ip = (data[pos] & 0xFF) + "." + (data[pos + 1] & 0xFF) + "."
                        + (data[pos + 2] & 0xFF) + "." + (data[pos + 3] & 0xFF);
                    String answeredName = readNameAt(data, namePos, len);
                    if (answeredName != null && answeredName.equalsIgnoreCase(host)) {
                        return ip;
                    }
                    if (fallbackIp == null) {
                        fallbackIp = ip;
                    }
                }
                pos += rdlen;
            }
            return fallbackIp;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static int skipName(byte[] data, int pos, int len) {
        int original = pos;
        while (pos < len) {
            int l = data[pos] & 0xFF;
            if (l == 0) return pos + 1;
            if ((l & 0xC0) == 0xC0) return pos + 2;
            pos += 1 + l;
        }
        return original;
    }

    private static String readNameAt(byte[] data, int pos, int len) {
        try {
            StringBuilder sb = new StringBuilder();
            int i = pos;
            while (i < len) {
                int l = data[i] & 0xFF;
                if (l == 0) break;
                if ((l & 0xC0) == 0xC0) {
                    int b2 = data[i + 1] & 0xFF;
                    i = ((l & 0x3F) << 8) | b2;
                    continue;
                }
                i++;
                if (i + l > len) return null;
                if (sb.length() > 0) sb.append('.');
                sb.append(new String(data, i, l, StandardCharsets.UTF_8));
                i += l;
            }
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }
}

