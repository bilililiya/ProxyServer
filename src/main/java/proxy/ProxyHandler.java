package proxy;

import cache.CacheEntry;
import cache.CacheManager;
import filter.AccessController;
import filter.HeaderModifier;
import filter.RateLimiter;
import log.ProxyLogger;
import log.StatsCollector;
import model.HttpRequest;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * 处理单个客户端请求的工作单元（实现 Runnable，交给线程池运行）
 */
public class ProxyHandler implements Runnable {

    private static final int TIMEOUT_MS = 10_000;

    private final Socket clientSocket;
    private final CacheManager cacheManager;
    private final AccessController accessController;
    private final HeaderModifier headerModifier;
    private final RateLimiter rateLimiter;   // 拓展功能：频率限制

    public ProxyHandler(Socket clientSocket,
                        CacheManager cacheManager,
                        AccessController accessController,
                        HeaderModifier headerModifier,
                        RateLimiter rateLimiter) {
        this.clientSocket     = clientSocket;
        this.cacheManager     = cacheManager;
        this.accessController = accessController;
        this.headerModifier   = headerModifier;
        this.rateLimiter      = rateLimiter;
    }

    @Override
    public void run() {
        try {
            clientSocket.setSoTimeout(TIMEOUT_MS);
            handleRequest();
        } catch (IOException e) {
            System.err.println("[Handler] 连接异常：" + e.getMessage());
        } finally {
            try { clientSocket.close(); } catch (IOException ignored) {}
        }
    }

    private void handleRequest() throws IOException {
        InputStream  clientIn  = clientSocket.getInputStream();
        OutputStream clientOut = clientSocket.getOutputStream();

        HttpRequest request = parseRequest(clientIn);
        if (request == null) return;

        System.out.println("[请求] " + request.getMethod() + " " + request.getUrl());

        // 拓展功能：频率限制检查
        String clientIp = getClientIp();
        if (!rateLimiter.allowRequest(clientIp)) {
            sendRateLimitedResponse(clientOut);
            StatsCollector.INSTANCE.record(request.getUrl(), false, true);
            return;
        }

        String host = extractHost(request);

        // 黑名单/白名单检查
        if (!accessController.isAllowed(host)) {
            sendBlockedResponse(clientOut, host);
            ProxyLogger.log(request.getMethod(), request.getUrl(), 403, false);
            StatsCollector.INSTANCE.record(request.getUrl(), false, true);
            return;
        }

        if ("CONNECT".equalsIgnoreCase(request.getMethod())) {
            handleConnect(clientSocket, request.getUrl());
        } else {
            handleHttpRequest(request, clientOut);
        }
    }

    /**
     * 获取客户端 IP 地址
     * 实际生产中可以从 Socket 获取真实 IP
     */
    private String getClientIp() {
        try {
            return clientSocket.getInetAddress().getHostAddress();
        } catch (Exception e) {
            return "unknown";
        }
    }

    /** 从原始字节流读取请求行、请求头和请求体 */
    private HttpRequest parseRequest(InputStream in) throws IOException {
        String requestLine = readLine(in);
        if (requestLine == null || requestLine.isEmpty()) return null;

        String[] parts = requestLine.split(" ", 3);
        if (parts.length < 3) return null;

        HttpRequest request = new HttpRequest();
        request.setMethod(parts[0]);
        request.setUrl(parts[1]);
        request.setVersion(parts[2]);

        StringBuilder headerBlock = new StringBuilder();
        headerBlock.append(requestLine).append("\r\n");

        String line;
        while ((line = readLine(in)) != null && !line.isEmpty()) {
            headerBlock.append(line).append("\r\n");
            request.addHeader(line);
        }
        headerBlock.append("\r\n");
        request.setRawHeaders(headerBlock.toString());

        int contentLength = request.getContentLength();
        if (contentLength > 0) {
            request.setBody(readFixedLength(in, contentLength));
        }
        return request;
    }

    private String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') {
                byte[] bytes = line.toByteArray();
                if (bytes.length > 0 && bytes[bytes.length - 1] == '\r') {
                    return new String(bytes, 0, bytes.length - 1, StandardCharsets.ISO_8859_1);
                }
                return line.toString(StandardCharsets.ISO_8859_1.name());
            }
            line.write(b);
        }
        if (line.size() == 0) return null;
        return line.toString(StandardCharsets.ISO_8859_1.name());
    }

    private byte[] readFixedLength(InputStream in, int length) throws IOException {
        byte[] body  = new byte[length];
        int    total = 0;
        while (total < length) {
            int read = in.read(body, total, length - total);
            if (read == -1) break;
            total += read;
        }
        if (total < length) {
            byte[] partial = new byte[total];
            System.arraycopy(body, 0, partial, 0, total);
            return partial;
        }
        return body;
    }

    private void handleHttpRequest(HttpRequest request, OutputStream clientOut) throws IOException {
        String method = request.getMethod();
        String url    = request.getUrl();

        // 缓存命中（仅 GET）
        if ("GET".equalsIgnoreCase(method)) {
            CacheEntry cached = cacheManager.get(url);
            if (cached != null) {
                System.out.println("[缓存] 命中：" + url);
                clientOut.write(cached.getResponseBytes());
                clientOut.flush();
                ProxyLogger.log(method, url, 200, true);
                StatsCollector.INSTANCE.record(url, true, false);
                return;
            }
        }

        // 拓展功能 E：在转发前修改请求头
        headerModifier.apply(request);

        URL    parsedUrl  = new URL(url);
        String targetHost = parsedUrl.getHost();
        int    targetPort = parsedUrl.getPort() == -1 ? 80 : parsedUrl.getPort();

        try (Socket serverSocket = new Socket(targetHost, targetPort)) {
            serverSocket.setSoTimeout(TIMEOUT_MS);

            OutputStream serverOut = serverSocket.getOutputStream();
            InputStream  serverIn  = serverSocket.getInputStream();

            serverOut.write(request.toForwardBytes());
            serverOut.flush();

            ByteArrayOutputStream responseBuffer = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int bytesRead;
            while ((bytesRead = serverIn.read(buf)) != -1) {
                clientOut.write(buf, 0, bytesRead);
                if ("GET".equalsIgnoreCase(method)) {
                    responseBuffer.write(buf, 0, bytesRead);
                }
            }
            clientOut.flush();

            if ("GET".equalsIgnoreCase(method) && responseBuffer.size() > 0
                    && responseBuffer.size() < 2 * 1024 * 1024) {
                cacheManager.put(url, new CacheEntry(responseBuffer.toByteArray()));
                System.out.println("[缓存] 已存储：" + url);
            }

            ProxyLogger.log(method, url, 200, false);
            StatsCollector.INSTANCE.record(url, false, false);

        } catch (IOException e) {
            sendErrorResponse(clientOut, 502, "Bad Gateway - 无法连接到目标服务器");
            ProxyLogger.log(method, url, 502, false);
            StatsCollector.INSTANCE.record(url, false, false);
        }
    }

    /** HTTPS CONNECT 隧道：回复 200 后双向转发加密数据流 */
    private void handleConnect(Socket clientSocket, String hostPort) throws IOException {
        String host;
        int port;
        int colonIdx = hostPort.lastIndexOf(':');
        if (colonIdx > 0) {
            host = hostPort.substring(0, colonIdx);
            port = Integer.parseInt(hostPort.substring(colonIdx + 1));
        } else {
            host = hostPort;
            port = 443;
        }

        System.out.println("[HTTPS] CONNECT 隧道：" + host + ":" + port);

        Socket serverSocket = null;
        try {
            serverSocket = new Socket(host, port);
            final Socket upstream = serverSocket;

            OutputStream clientOut = clientSocket.getOutputStream();
            clientOut.write("HTTP/1.1 200 Connection Established\r\n\r\n"
                    .getBytes(StandardCharsets.ISO_8859_1));
            clientOut.flush();

            clientSocket.setSoTimeout(0);
            upstream.setSoTimeout(0);

            InputStream  clientIn  = clientSocket.getInputStream();
            InputStream  serverIn  = upstream.getInputStream();
            OutputStream serverOut = upstream.getOutputStream();

            Thread clientToServer = new Thread(
                    () -> relay(clientIn, serverOut, upstream), "tunnel-c2s-" + host);
            Thread serverToClient = new Thread(
                    () -> relay(serverIn, clientOut, clientSocket), "tunnel-s2c-" + host);

            clientToServer.start();
            serverToClient.start();

            try {
                clientToServer.join();
                serverToClient.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            ProxyLogger.log("CONNECT", hostPort, 200, false);
            StatsCollector.INSTANCE.record(hostPort, false, false);

        } catch (IOException e) {
            System.err.println("[HTTPS] 隧道建立失败：" + e.getMessage());
            sendConnectFailed(clientSocket);
            ProxyLogger.log("CONNECT", hostPort, 502, false);
            StatsCollector.INSTANCE.record(hostPort, false, false);
        } finally {
            if (serverSocket != null) {
                try { serverSocket.close(); } catch (IOException ignored) {}
            }
        }
    }

    private void relay(InputStream in, OutputStream out, Socket closeOnEnd) {
        byte[] buf = new byte[8192];
        try {
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                out.flush();
            }
        } catch (IOException ignored) {
        } finally {
            try { closeOnEnd.close(); } catch (IOException ignored) {}
        }
    }

    private void sendConnectFailed(Socket clientSocket) {
        try {
            OutputStream out = clientSocket.getOutputStream();
            out.write("HTTP/1.1 502 Bad Gateway\r\n\r\n"
                    .getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
        } catch (IOException ignored) {}
    }

    private String extractHost(HttpRequest request) {
        if ("CONNECT".equalsIgnoreCase(request.getMethod())) {
            String hostPort = request.getUrl();
            int colonIdx    = hostPort.lastIndexOf(':');
            return colonIdx > 0 ? hostPort.substring(0, colonIdx) : hostPort;
        }
        try { return new URL(request.getUrl()).getHost(); }
        catch (Exception e) { return request.getUrl(); }
    }

    private void sendBlockedResponse(OutputStream out, String host) throws IOException {
        String body = "<html><body><h1>403 Forbidden</h1>"
                + "<p>访问 " + host + " 已被代理服务器拦截</p></body></html>";
        String response = "HTTP/1.1 403 Forbidden\r\n"
                + "Content-Type: text/html; charset=UTF-8\r\n"
                + "Content-Length: " + body.getBytes(StandardCharsets.UTF_8).length + "\r\n"
                + "\r\n" + body;
        out.write(response.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    /**
     * 发送频率限制响应（HTTP 429）
     */
    private void sendRateLimitedResponse(OutputStream out) throws IOException {
        String body = "<html><body><h1>429 Too Many Requests</h1>"
                + "<p>请求过于频繁，请稍后再试</p></body></html>";
        String response = "HTTP/1.1 429 Too Many Requests\r\n"
                + "Content-Type: text/html; charset=UTF-8\r\n"
                + "Retry-After: 60\r\n"
                + "Content-Length: " + body.getBytes(StandardCharsets.UTF_8).length + "\r\n"
                + "\r\n" + body;
        out.write(response.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private void sendErrorResponse(OutputStream out, int code, String msg) throws IOException {
        String body = "<html><body><h1>" + code + "</h1><p>" + msg + "</p></body></html>";
        String response = "HTTP/1.1 " + code + " Error\r\n"
                + "Content-Type: text/html; charset=UTF-8\r\n"
                + "Content-Length: " + body.getBytes(StandardCharsets.UTF_8).length + "\r\n"
                + "\r\n" + body;
        out.write(response.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }
}
