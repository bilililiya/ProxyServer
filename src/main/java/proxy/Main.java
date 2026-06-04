package proxy;

import log.StatsCollector;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 启动入口
 * 运行方式：java -jar http-proxy.jar [端口号]
 * 默认端口：8888
 */
public class Main {

    /** 统计报告打印间隔（秒） */
    private static final int STATS_INTERVAL_SEC = 60;

    public static void main(String[] args) {
        int port = 8888;
        if (args.length > 0) {
            try {
                port = Integer.parseInt(args[0]);
            } catch (NumberFormatException e) {
                System.err.println("[错误] 端口号格式不对，使用默认端口 8888");
            }
        }

        System.out.println("=== HTTP 代理缓存服务器启动 ===");
        System.out.println("监听端口：" + port);
        System.out.println("浏览器代理设置：127.0.0.1:" + port);
        System.out.println("统计报告每 " + STATS_INTERVAL_SEC + " 秒打印一次（也可随时按回车手动触发）");

        ProxyServer server = new ProxyServer(port);

        // 拓展功能 C：定时打印统计报告
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "stats-reporter");
            t.setDaemon(true);  // 守护线程，主程序退出时自动结束
            return t;
        });
        scheduler.scheduleAtFixedRate(
                () -> StatsCollector.INSTANCE.printReport(server.getCacheManager().size()),
                STATS_INTERVAL_SEC,
                STATS_INTERVAL_SEC,
                TimeUnit.SECONDS
        );

        // 拓展功能 C：按回车手动打印统计（方便演示）
        Thread manualStats = new Thread(() -> {
            try {
                java.io.BufferedReader br = new java.io.BufferedReader(
                        new java.io.InputStreamReader(System.in));
                System.out.println("（按回车键可随时打印当前统计）");
                while (true) {
                    br.readLine();
                    StatsCollector.INSTANCE.printReport(server.getCacheManager().size());
                }
            } catch (Exception ignored) {}
        }, "manual-stats");
        manualStats.setDaemon(true);
        manualStats.start();

        server.start();
    }
}