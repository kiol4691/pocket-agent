package com.dsh.android.runtime;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;

/** Minimal in-process HTTP proxy (CONNECT tunnels + absolute-form requests) listening on the
 * app's loopback. proroot shares the kernel network namespace, so the guest reaches it directly
 * at 127.0.0.1:<port>. Name resolution happens here, on the Android side, which sidesteps any
 * DNS trouble inside the rootfs. */
public final class LocalProxy {

    private static final int BASE_PORT = 7899;
    private static final int MAX_PORT_TRIES = 20;
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int IDLE_TIMEOUT_MS = 60_000;
    private static final int BUF = 16 * 1024;

    private static volatile boolean started;
    private static volatile int port = -1;

    public static synchronized void ensure() {
        if (started) return;
        for (int i = 0; i < MAX_PORT_TRIES; i++) {
            int p = BASE_PORT + i;
            try {
                ServerSocket ss = new ServerSocket();
                ss.setReuseAddress(true);
                ss.bind(new InetSocketAddress("127.0.0.1", p));
                port = p;
                started = true;
                Thread t = new Thread(() -> acceptLoop(ss), "dsh-proxy-accept");
                t.setDaemon(true);
                t.start();
                return;
            } catch (IOException ignored) {
            }
        }
    }

    public static int port() {
        return port;
    }

    public static boolean running() {
        return started;
    }

    private static void acceptLoop(ServerSocket ss) {
        while (started) {
            try {
                Socket client = ss.accept();
                Thread t = new Thread(() -> handle(client), "dsh-proxy-conn");
                t.setDaemon(true);
                t.start();
            } catch (IOException e) {
                if (started) {
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException ie) {
                        return;
                    }
                }
            }
        }
    }

    private static void handle(Socket client) {
        try {
            client.setSoTimeout(IDLE_TIMEOUT_MS);
            client.setTcpNoDelay(true);
            InputStream in = client.getInputStream();
            String reqLine = readLine(in);
            if (reqLine == null || reqLine.isEmpty()) {
                close(client);
                return;
            }
            String[] parts = reqLine.split(" ");
            if (parts.length < 3) {
                writeSimple(client, "400 Bad Request");
                close(client);
                return;
            }
            String method = parts[0].toUpperCase(java.util.Locale.US);
            String target = parts[1];
            if ("CONNECT".equals(method)) {
                doConnect(client, in, target);
            } else {
                doPlain(client, in, method, target);
            }
        } catch (Exception e) {
            close(client);
        }
    }

    private static void doConnect(Socket client, InputStream in, String authority) {
        OutputStream cout;
        try {
            cout = client.getOutputStream();
        } catch (IOException e) {
            close(client);
            return;
        }
        drainHeaders(in);
        int c = authority.lastIndexOf(':');
        String host = c > 0 ? authority.substring(0, c) : authority;
        int portNo = c > 0 ? Integer.parseInt(authority.substring(c + 1)) : 443;
        Socket up;
        try {
            up = new Socket();
            up.connect(new InetSocketAddress(host, portNo), CONNECT_TIMEOUT_MS);
            up.setTcpNoDelay(true);
        } catch (Exception e) {
            try {
                cout.write(("HTTP/1.1 502 Bad Gateway\r\n\r\n").getBytes("ISO-8859-1"));
                cout.flush();
            } catch (IOException ignored) {
            }
            close(client);
            return;
        }
        try {
            cout.write(("HTTP/1.1 200 Connection established\r\n\r\n").getBytes("ISO-8859-1"));
            cout.flush();
            pipeBidir(client, up);
        } catch (Exception e) {
            close(client);
            close(up);
        }
    }

    private static void doPlain(Socket client, InputStream in, String method, String target) throws IOException {
        String line;
        StringBuilder head = new StringBuilder();
        int contentLen = 0;
        while ((line = readLine(in)) != null && !line.isEmpty()) {
            String l = line.toLowerCase(java.util.Locale.US);
            if (l.startsWith("content-length:")) {
                try {
                    contentLen = Integer.parseInt(l.substring(15).trim());
                } catch (NumberFormatException ignored) {
                }
            }
            if (l.startsWith("proxy-connection:") || l.startsWith("proxy-authorization:")) continue;
            head.append(line).append("\r\n");
        }
        URI uri;
        try {
            uri = new URI(target);
        } catch (Exception e) {
            writeSimple(client, "400 Bad Request");
            close(client);
            return;
        }
        String scheme = uri.getScheme() == null ? "http" : uri.getScheme();
        if (!"http".equals(scheme)) {
            writeSimple(client, "502 Only http supported on plain proxy");
            close(client);
            return;
        }
        int portNo = uri.getPort() == -1 ? 80 : uri.getPort();
        Socket up;
        try {
            up = new Socket();
            up.connect(new InetSocketAddress(uri.getHost(), portNo), CONNECT_TIMEOUT_MS);
            up.setTcpNoDelay(true);
        } catch (Exception e) {
            writeSimple(client, "502 Bad Gateway");
            close(client);
            return;
        }
        OutputStream uout = up.getOutputStream();
        String pathQ = uri.getRawPath() == null ? "/" : uri.getRawPath()
                + (uri.getRawQuery() != null ? "?" + uri.getRawQuery() : "");
        StringBuilder fwd = new StringBuilder();
        fwd.append(method).append(' ').append(pathQ).append(" HTTP/1.1\r\n");
        fwd.append(head);
        if (!head.toString().toLowerCase().contains("host:")) {
            fwd.append("Host: ").append(uri.getRawAuthority()).append("\r\n");
        }
        fwd.append("Connection: close\r\n\r\n");
        uout.write(fwd.toString().getBytes("ISO-8859-1"));
        uout.flush();
        if (contentLen > 0) {
            copyN(in, uout, contentLen);
        }
        InputStream uin = up.getInputStream();
        OutputStream cout = client.getOutputStream();
        try {
            copy(uin, cout);
        } catch (IOException ignored) {
        }
        close(up);
        close(client);
    }

    private static void pipeBidir(Socket a, Socket b) {
        Thread t = new Thread(() -> {
            try {
                copy(a.getInputStream(), b.getOutputStream());
            } catch (IOException ignored) {
            } finally {
                close(b);
                shutdown(a);
            }
        }, "dsh-proxy-pipe");
        t.setDaemon(true);
        t.start();
        try {
            copy(b.getInputStream(), a.getOutputStream());
        } catch (IOException ignored) {
        } finally {
            close(a);
            shutdown(b);
            t.interrupt();
        }
    }

    private static void drainHeaders(InputStream in) {
        try {
            String l;
            while ((l = readLine(in)) != null && !l.isEmpty()) {
            }
        } catch (Exception ignored) {
        }
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder(64);
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') sb.append((char) c);
            if (sb.length() > 16384) throw new IOException("header too long");
        }
        return c == -1 && sb.length() == 0 ? null : sb.toString();
    }

    private static void copyN(InputStream src, OutputStream dst, long n) throws IOException {
        byte[] buf = new byte[BUF];
        long left = n;
        while (left > 0) {
            int r = src.read(buf, 0, (int) Math.min(buf.length, left));
            if (r == -1) break;
            dst.write(buf, 0, r);
            left -= r;
        }
        dst.flush();
    }

    private static void copy(InputStream src, OutputStream dst) throws IOException {
        byte[] buf = new byte[BUF];
        int r;
        while ((r = src.read(buf)) != -1) {
            dst.write(buf, 0, r);
        }
        dst.flush();
    }

    private static void shutdown(Socket s) {
        try {
            s.shutdownOutput();
        } catch (Exception ignored) {
        }
    }

    private static void close(Socket s) {
        try {
            s.close();
        } catch (Exception ignored) {
        }
    }

    private static void writeSimple(Socket s, String status) {
        try {
            OutputStream o = s.getOutputStream();
            o.write(("HTTP/1.1 " + status + "\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                    .getBytes("ISO-8859-1"));
            o.flush();
        } catch (Exception ignored) {
        }
    }
}
