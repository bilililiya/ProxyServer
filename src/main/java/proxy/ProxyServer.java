package proxy;

import cache.CacheManager;
import filter.AccessController;
import filter.HeaderModifier;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 代理服务器核心：
 * 1. 用 ServerSocket 监听客户端连接
 * 2. 每来一个客户端，从线程池里取一个线程去处理
 */
public class ProxyServer {

    private final int port;
    private final ExecutorService    threadPool;
    private final CacheManager       cacheManager;
    private final AccessController   accessController;
    private final HeaderModifier     headerModifier;   // 拓展功能 E

    public ProxyServer(int port) {
        this.port             = port;
        this.threadPool       = Executors.newFixedThreadPool(50);
        this.cacheManager     = new CacheManager();
        this.accessController = new AccessController();
        this.headerModifier   = new HeaderModifier();
    }

    public CacheManager getCacheManager() {
        return cacheManager;
    }

    public void start() {
        try (ServerSocket serverSocket = new ServerSocket(port)) {
            System.out.println("[服务器] 开始监听，等待连接...");
            while (true) {
                Socket clientSocket = serverSocket.accept();
                threadPool.submit(new ProxyHandler(
                        clientSocket, cacheManager, accessController, headerModifier));
            }
        } catch (IOException e) {
            System.err.println("[服务器] 启动失败：" + e.getMessage());
        } finally {
            threadPool.shutdown();
        }
    }
}