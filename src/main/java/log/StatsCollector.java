package log;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 全局统计收集器（拓展功能 C）。
 * 线程安全，使用原子计数器 + ConcurrentHashMap。
 * 由 ProxyHandler 在每次请求完成后调用 record()，
 * 由 Main 中的定时线程定期调用 printReport() 打印报告。
 */
public class StatsCollector {

    /** 单例 */
    public static final StatsCollector INSTANCE = new StatsCollector();
    private StatsCollector() {}

    // 全局计数器
    private final AtomicLong totalRequests  = new AtomicLong();
    private final AtomicLong cacheHits      = new AtomicLong();
    private final AtomicLong cacheMisses    = new AtomicLong();
    private final AtomicLong blockedCount   = new AtomicLong();

    /** key=URL, value=访问次数（用于热门资源统计） */
    private final ConcurrentHashMap<String, AtomicLong> urlHits = new ConcurrentHashMap<>();

    // 记录一次请求

    /**
     * 每处理完一个请求，由 ProxyHandler 调用。
     *
     * @param url       请求的 URL / host:port
     * @param cacheHit  是否命中缓存
     * @param blocked   是否被黑名单拦截
     */
    public void record(String url, boolean cacheHit, boolean blocked) {
        totalRequests.incrementAndGet();
        if (blocked) {
            blockedCount.incrementAndGet();
            return;  // 被拦截的不计入缓存统计，也不计入热门资源
        }
        if (cacheHit) {
            cacheHits.incrementAndGet();
        } else {
            cacheMisses.incrementAndGet();
        }
        // 热门资源计数
        urlHits.computeIfAbsent(url, k -> new AtomicLong()).incrementAndGet();
    }

    // 统计报告

    /** 打印统计报告到控制台 */
    public void printReport(int cacheSize) {
        long total   = totalRequests.get();
        long hits    = cacheHits.get();
        long misses  = cacheMisses.get();
        long blocked = blockedCount.get();
        long cacheable = hits + misses;
        double hitRate = cacheable > 0 ? hits * 100.0 / cacheable : 0.0;

        System.out.println("\n╔══════════════════════════════════════════╗");
        System.out.println("║          代理服务器统计报告               ║");
        System.out.println("╠══════════════════════════════════════════╣");
        System.out.printf ("║  总请求数     ：%6d                      ║%n", total);
        System.out.printf ("║  缓存命中     ：%6d                      ║%n", hits);
        System.out.printf ("║  缓存未命中   ：%6d                      ║%n", misses);
        System.out.printf ("║  缓存命中率   ：%5.1f%%                  ║%n", hitRate);
        System.out.printf ("║  当前缓存条目 ：%6d                      ║%n", cacheSize);
        System.out.printf ("║  被拦截请求   ：%6d                      ║%n", blocked);
        System.out.println("╠══════════════════════════════════════════╣");
        System.out.println("║  热门资源 TOP 5                          ║");
        System.out.println("╠══════════════════════════════════════════╣");

        // 取访问次数最多的 5 个 URL
        List<Map.Entry<String, AtomicLong>> sorted = new ArrayList<>(urlHits.entrySet());
        sorted.sort(Comparator.comparingLong(e -> -e.getValue().get()));
        int rank = 1;
        for (Map.Entry<String, AtomicLong> e : sorted) {
            if (rank > 5) break;
            // 超长 URL 截断显示
            String u = e.getKey();
            if (u.length() > 32) u = u.substring(0, 29) + "...";
            System.out.printf("║  %d. %-32s %4d ║%n", rank++, u, e.getValue().get());
        }
        if (sorted.isEmpty()) {
            System.out.println("║  （暂无数据）                           ║");
        }
        System.out.println("╚══════════════════════════════════════════╝\n");
    }

    /**
     * 获取统计数据（用于 Web API）
     */
    public StatsData getStatsData() {
        long total   = totalRequests.get();
        long hits    = cacheHits.get();
        long misses  = cacheMisses.get();
        long blocked = blockedCount.get();
        long cacheable = hits + misses;
        double hitRate = cacheable > 0 ? hits * 100.0 / cacheable : 0.0;

        // 获取热门资源 TOP 5
        List<Map.Entry<String, AtomicLong>> sorted = new ArrayList<>(urlHits.entrySet());
        sorted.sort(Comparator.comparingLong(e -> -e.getValue().get()));
        
        List<ResourceInfo> topResources = new ArrayList<>();
        int rank = 1;
        for (Map.Entry<String, AtomicLong> e : sorted) {
            if (rank > 5) break;
            topResources.add(new ResourceInfo(e.getKey(), e.getValue().get()));
            rank++;
        }

        return new StatsData(total, hits, misses, blocked, hitRate, topResources);
    }

    /**
     * 统计数据封装类
     */
    public static class StatsData {
        public final long totalRequests;
        public final long cacheHits;
        public final long cacheMisses;
        public final long blockedCount;
        public final double hitRate;
        public final List<ResourceInfo> topResources;

        public StatsData(long totalRequests, long cacheHits, long cacheMisses,
                        long blockedCount, double hitRate, List<ResourceInfo> topResources) {
            this.totalRequests = totalRequests;
            this.cacheHits = cacheHits;
            this.cacheMisses = cacheMisses;
            this.blockedCount = blockedCount;
            this.hitRate = hitRate;
            this.topResources = topResources;
        }
    }

    /**
     * 资源访问信息
     */
    public static class ResourceInfo {
        public final String url;
        public final long count;

        public ResourceInfo(String url, long count) {
            this.url = url;
            this.count = count;
        }
    }
}