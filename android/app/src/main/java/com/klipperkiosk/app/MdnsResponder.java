package com.klipperkiosk.app;

import android.content.Context;
import android.net.wifi.WifiManager;

import java.io.ByteArrayOutputStream;
import java.net.DatagramPacket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.util.Collections;

/**
 * Lightweight RFC 6762 mDNS responder that advertises a static `.local` hostname
 * (e.g. klipper-tablet.local) pointing to the tablet's current IPv4 address.
 */
public class MdnsResponder {
    private static final String MDNS_GROUP = "224.0.0.251";
    private static final int MDNS_PORT = 5353;

    private final Context appContext;
    private final String hostname;
    private volatile boolean running = false;
    private MulticastSocket socket;
    private WifiManager.MulticastLock multicastLock;
    private Thread listenerThread;

    public MdnsResponder(Context context, String hostname) {
        this.appContext = context.getApplicationContext();
        String clean = hostname.trim().toLowerCase();
        if (clean.endsWith(".")) {
            clean = clean.substring(0, clean.length() - 1);
        }
        this.hostname = clean;
    }

    public String getHostname() {
        return hostname;
    }

    public void start() {
        if (running) return;
        running = true;

        try {
            WifiManager wm = (WifiManager) appContext.getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                multicastLock = wm.createMulticastLock("klipperkiosk_mdns");
                multicastLock.setReferenceCounted(false);
                multicastLock.acquire();
            }
        } catch (Exception ignored) {
        }

        listenerThread = new Thread(this::runLoop, "KioskMdnsResponder");
        listenerThread.setDaemon(true);
        listenerThread.start();
    }

    public void announceAsync() {
        Thread t = new Thread(this::sendAnnouncement, "KioskMdnsAnnounce");
        t.setDaemon(true);
        t.start();
    }

    public void stop() {
        running = false;
        try {
            if (socket != null) {
                socket.close();
            }
        } catch (Exception ignored) {
        }
        try {
            if (multicastLock != null && multicastLock.isHeld()) {
                multicastLock.release();
            }
        } catch (Exception ignored) {
        }
    }

    private void runLoop() {
        try {
            InetAddress group = InetAddress.getByName(MDNS_GROUP);
            socket = new MulticastSocket(null);
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(MDNS_PORT));
            socket.setTimeToLive(255);

            NetworkInterface nif = findActiveIpv4Interface();
            if (nif != null) {
                try {
                    socket.joinGroup(new InetSocketAddress(group, MDNS_PORT), nif);
                } catch (Exception e) {
                    socket.joinGroup(group);
                }
            } else {
                socket.joinGroup(group);
            }

            // Send initial announcement so LAN hosts cache the address right away
            sendAnnouncement();

            byte[] buf = new byte[1500];
            while (running) {
                DatagramPacket packet = new DatagramPacket(buf, buf.length);
                socket.receive(packet);
                handlePacket(packet, group);
            }
        } catch (Exception ignored) {
        }
    }

    private void sendAnnouncement() {
        try {
            byte[] ipBytes = getLocalIpv4Bytes();
            if (ipBytes == null || socket == null || socket.isClosed()) return;
            byte[] resp = buildARecordResponse(hostname, ipBytes);
            InetAddress group = InetAddress.getByName(MDNS_GROUP);
            socket.send(new DatagramPacket(resp, resp.length, group, MDNS_PORT));
        } catch (Exception ignored) {
        }
    }

    private void handlePacket(DatagramPacket packet, InetAddress group) {
        byte[] data = packet.getData();
        int len = packet.getLength();
        if (len < 12) return;

        int flags = ((data[2] & 0xFF) << 8) | (data[3] & 0xFF);
        // Only respond to queries (QR bit == 0)
        if ((flags & 0x8000) != 0) return;

        int qdcount = ((data[4] & 0xFF) << 8) | (data[5] & 0xFF);
        if (qdcount <= 0) return;

        int pos = 12;
        boolean shouldRespond = false;
        boolean unicastRequested = packet.getPort() != MDNS_PORT;

        for (int i = 0; i < qdcount; i++) {
            StringBuilder qname = new StringBuilder();
            int nextPos = readName(data, pos, len, qname);
            if (nextPos < 0 || nextPos + 4 > len) return;
            int qtype = ((data[nextPos] & 0xFF) << 8) | (data[nextPos + 1] & 0xFF);
            int qclass = ((data[nextPos + 2] & 0xFF) << 8) | (data[nextPos + 3] & 0xFF);
            pos = nextPos + 4;

            if (qname.toString().equalsIgnoreCase(hostname) && (qtype == 1 || qtype == 255)) {
                shouldRespond = true;
                if ((qclass & 0x8000) != 0) {
                    unicastRequested = true;
                }
            }
        }

        if (!shouldRespond) return;

        byte[] ipBytes = getLocalIpv4Bytes();
        if (ipBytes == null) return;

        try {
            byte[] resp = buildARecordResponse(hostname, ipBytes);
            socket.send(new DatagramPacket(resp, resp.length, group, MDNS_PORT));
            if (unicastRequested && packet.getAddress() != null) {
                socket.send(new DatagramPacket(resp, resp.length, packet.getAddress(), packet.getPort()));
            }
        } catch (Exception ignored) {
        }
    }

    private static byte[] buildARecordResponse(String host, byte[] ipBytes) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        // Transaction ID = 0
        out.write(new byte[]{0x00, 0x00});
        // Flags = 0x8400 (Standard query response, Authoritative Answer)
        out.write(new byte[]{(byte) 0x84, 0x00});
        // QDCOUNT = 0, ANCOUNT = 1, NSCOUNT = 0, ARCOUNT = 0
        out.write(new byte[]{0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00});

        // Answer NAME
        for (String label : host.split("\\.")) {
            byte[] b = label.getBytes(StandardCharsets.UTF_8);
            out.write(b.length);
            out.write(b);
        }
        out.write(0x00);

        // TYPE = A (1)
        out.write(new byte[]{0x00, 0x01});
        // CLASS = IN (1) with cache-flush bit (0x8001)
        out.write(new byte[]{(byte) 0x80, 0x01});
        // TTL = 120 seconds
        out.write(new byte[]{0x00, 0x00, 0x00, 0x78});
        // RDLENGTH = 4
        out.write(new byte[]{0x00, 0x04});
        // RDATA = IPv4 address
        out.write(ipBytes);

        return out.toByteArray();
    }

    private static int readName(byte[] data, int pos, int len, StringBuilder out) {
        int i = pos;
        int endPos = -1;
        int hops = 0;
        while (i < len && hops < 32) {
            int l = data[i] & 0xFF;
            if (l == 0) {
                return endPos >= 0 ? endPos : i + 1;
            }
            if ((l & 0xC0) == 0xC0) {
                if (i + 1 >= len) return -1;
                if (endPos < 0) endPos = i + 2;
                i = ((l & 0x3F) << 8) | (data[i + 1] & 0xFF);
                hops++;
                continue;
            }
            i++;
            if (i + l > len) return -1;
            if (out.length() > 0) out.append('.');
            out.append(new String(data, i, l, StandardCharsets.UTF_8));
            i += l;
        }
        return -1;
    }

    private static NetworkInterface findActiveIpv4Interface() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback() || !ni.supportsMulticast()) continue;
                for (InetAddress addr : Collections.list(ni.getInetAddresses())) {
                    if (!addr.isLoopbackAddress() && addr instanceof Inet4Address) {
                        return ni;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static byte[] getLocalIpv4Bytes() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress addr : Collections.list(ni.getInetAddresses())) {
                    if (!addr.isLoopbackAddress() && addr instanceof Inet4Address) {
                        return addr.getAddress();
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }
}

