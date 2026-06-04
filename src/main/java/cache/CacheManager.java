package cache;

import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 缓存管理器 - 支持 LRU 淘汰策略
 * 
 * 使用 LinkedHashMap 的访问顺序模式实现 LRU：
 * - accessOrder=true 时，每次 get() 会将条目移到链表末尾
 * - 最久未使用的条目在链表头部
 * - 插入新条目时自动触发淘汰逻辑
 */
public class CacheManager {

    private static final int MAX_ENTRIES = 500;

    /**
     * 使用 LinkedHashMap 实现 LRU：
     * - 第三个参数 true 表示按访问顺序排序（而非插入顺序）
     * - 重写 removeEldestEntry() 实现自动淘汰
     */
    private final LinkedHashMap<String, CacheEntry> lruCache = new LinkedHashMap<String, CacheEntry>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, CacheEntry> eldest) {
            // 不在此处自动淘汰，由 evictIfNeeded() 手动控制
            return false;
        }
    };

    /**
     * 获取缓存条目
     * LinkedHashMap 的 get() 会自动将访问的条目移到末尾（LRU 核心）
     */
    public synchronized CacheEntry get(String url) {
        CacheEntry entry = lruCache.get(url);
        if (entry == null) {
            return null;
        }
        if (entry.isExpired()) {
            lruCache.remove(url);
            return null;
        }
        return entry;
    }

    /**
     * 存入缓存条目
     * 先检查是否需要淘汰，再插入新条目
     */
    public synchronized void put(String url, CacheEntry entry) {
        evictIfNeeded();
        lruCache.put(url, entry);
    }

    /**
     * LRU 淘汰策略：
     * 1. 优先淘汰所有过期条目
     * 2. 如果仍然超过上限，从链表头部开始删除最久未使用的条目
     */
    private void evictIfNeeded() {
        if (lruCache.size() < MAX_ENTRIES) {
            return;
        }

        // 第一步：优先淘汰过期条目
        for (Iterator<Map.Entry<String, CacheEntry>> it = lruCache.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, CacheEntry> e = it.next();
            if (e.getValue().isExpired()) {
                it.remove();
            }
        }

        // 第二步：如果仍然满，从头部删除最久未使用的条目
        while (lruCache.size() >= MAX_ENTRIES && !lruCache.isEmpty()) {
            // LinkedHashMap 的迭代器按访问顺序返回，第一个就是最久未使用的
            Iterator<String> keys = lruCache.keySet().iterator();
            if (keys.hasNext()) {
                keys.next();
                keys.remove();
            } else {
                break;
            }
        }
    }

    /**
     * 获取当前缓存条目数
     */
    public synchronized int size() {
        return lruCache.size();
    }

    /**
     * 获取缓存命中率统计（可选功能）
     */
    public synchronized String getStats() {
        return String.format("缓存大小: %d / %d", lruCache.size(), MAX_ENTRIES);
    }
}
