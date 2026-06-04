package filter;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 请求频率限制器 - 防止单个 IP 滥用代理
 * 
 * 算法：固定时间窗口计数器
 * - 记录每个 IP 在当前窗口内的请求次数
 * - 超过阈值则拒绝请求，返回 429
 * - 窗口过期后自动重置计数
 * 
 * 配置参数：
 * - MAX_REQUESTS_PER_WINDOW: 每个窗口最大请求数
 * - WINDOW_SIZE_MS: 窗口大小（毫秒）
 */
public class RateLimiter {

    /** 每个时间窗口内允许的最大请求数 */
    private static final int MAX_REQUESTS_PER_WINDOW = 100;
    
    /** 时间窗口大小：60 秒 */
    private static final long WINDOW_SIZE_MS = 60_000L;

    /**
     * IP 请求记录
     * Key: IP 地址
     * Value: RequestRecord（请求计数 + 窗口起始时间）
     */
    private static class RequestRecord {
        long windowStartMs;  // 当前窗口起始时间
        int requestCount;    // 当前窗口内的请求数

        RequestRecord(long now) {
            this.windowStartMs = now;
            this.requestCount = 1;
        }

        /**
         * 尝试增加请求计数（线程安全）
         * @param now 当前时间戳
         * @return true=允许请求, false=超出限制
         */
        synchronized boolean tryIncrement(long now) {
            // 检查是否进入新窗口
            if (now - windowStartMs >= WINDOW_SIZE_MS) {
                // 重置窗口
                this.windowStartMs = now;
                this.requestCount = 1;
                return true;
            }
            
            // 同一窗口内，检查是否超限
            if (requestCount >= MAX_REQUESTS_PER_WINDOW) {
                return false;
            }
            
            // 未超限，增加计数
            this.requestCount++;
            return true;
        }

        /**
         * 获取剩余请求数
         */
        synchronized int getRemainingRequests(long now) {
            if (now - windowStartMs >= WINDOW_SIZE_MS) {
                return MAX_REQUESTS_PER_WINDOW;
            }
            return Math.max(0, MAX_REQUESTS_PER_WINDOW - requestCount);
        }

        /**
         * 获取窗口重置时间（秒）
         */
        long getResetTimeSeconds(long now) {
            long elapsed = now - windowStartMs;
            return Math.max(0, (WINDOW_SIZE_MS - elapsed) / 1000);
        }
    }

    /** IP → 请求记录的映射表（线程安全） */
    private final Map<String, RequestRecord> ipRecords = new ConcurrentHashMap<>();

    /**
     * 检查 IP 是否允许发送请求
     * 
     * @param clientIp 客户端 IP 地址
     * @return true=允许, false=频率超限
     */
    public boolean allowRequest(String clientIp) {
        long now = System.currentTimeMillis();
        
        // 获取或创建该 IP 的记录（ConcurrentHashMap 保证线程安全）
        RequestRecord record = ipRecords.computeIfAbsent(clientIp, k -> new RequestRecord(now));
        
        // 尝试增加请求计数（在记录级别同步，不同 IP 互不阻塞）
        boolean allowed = record.tryIncrement(now);
        
        if (!allowed) {
            System.out.println(String.format("[限流] IP %s 超出频率限制 (%d 次/%d秒)", 
                clientIp, MAX_REQUESTS_PER_WINDOW, WINDOW_SIZE_MS / 1000));
        }
        
        return allowed;
    }

    /**
     * 获取 IP 的剩余请求配额
     * 
     * @param clientIp 客户端 IP
     * @return 剩余可请求次数
     */
    public int getRemainingRequests(String clientIp) {
        long now = System.currentTimeMillis();
        RequestRecord record = ipRecords.get(clientIp);
        if (record == null) {
            return MAX_REQUESTS_PER_WINDOW;
        }
        return record.getRemainingRequests(now);
    }

    /**
     * 清理过期的 IP 记录（防止内存泄漏）
     * 建议定期调用（如每分钟一次）
     */
    public synchronized void cleanup() {
        long now = System.currentTimeMillis();
        int removed = 0;
        
        for (Map.Entry<String, RequestRecord> entry : ipRecords.entrySet()) {
            RequestRecord record = entry.getValue();
            // 如果窗口已过期且无新请求，删除记录
            if (now - record.windowStartMs >= WINDOW_SIZE_MS * 2) {
                ipRecords.remove(entry.getKey());
                removed++;
            }
        }
        
        if (removed > 0) {
            System.out.println("[限流] 清理过期记录: " + removed + " 条");
        }
    }

    /**
     * 获取当前监控的 IP 数量
     */
    public synchronized int getMonitoredIpCount() {
        return ipRecords.size();
    }

    /**
     * 获取限流配置信息
     */
    public String getConfigInfo() {
        return String.format("频率限制: %d 次/%d秒", MAX_REQUESTS_PER_WINDOW, WINDOW_SIZE_MS / 1000);
    }
}
