package proxy;

import cache.CacheManager;
import filter.AccessController;
import filter.HeaderModifier;
import filter.RateLimiter;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 代理服务器核心：
 * 1. 用 ServerSocket 监听客户端连接
 * 2. 每来一个客户端，从线程池里取一个线程去处理
 */
public class ProxyServer {

    private final int port;
    private final ExecutorService         threadPool;
    private final ScheduledExecutorService cleanupScheduler;  // 定期清理调度器
    private final CacheManager            cacheManager;
    private final AccessController        accessController;
    private final HeaderModifier          headerModifier;
    private final RateLimiter             rateLimiter;   // 拓展功能：频率限制

    public ProxyServer(int port) {
        this.port             = port;
        this.threadPool       = Executors.newFixedThreadPool(50);
        this.cleanupScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ratelimit-cleanup");
            t.setDaemon(true);
            return t;
        });
        this.cacheManager     = new CacheManager();
        this.accessController = new AccessController();
        this.headerModifier   = new HeaderModifier();
        this.rateLimiter      = new RateLimiter();
        
        System.out.println("[配置] " + rateLimiter.getConfigInfo());
        
        // 启动定期清理任务：每分钟清理一次过期的 IP 记录
        this.cleanupScheduler.scheduleAtFixedRate(
            rateLimiter::cleanup, 1, 1, TimeUnit.MINUTES);
    }

    public CacheManager getCacheManager() {
        return cacheManager;
    }

    public RateLimiter getRateLimiter() {
        return rateLimiter;
    }

    public void start() {
        try (ServerSocket serverSocket = new ServerSocket(port)) {
            System.out.println("[服务器] 开始监听，等待连接...");
            while (true) {
                Socket clientSocket = serverSocket.accept();
                threadPool.submit(new ProxyHandler(
                        clientSocket, cacheManager, accessController, headerModifier, rateLimiter));
            }
        } catch (IOException e) {
            System.err.println("[服务器] 启动失败：" + e.getMessage());
        } finally {
            threadPool.shutdown();
        }
    }
}
