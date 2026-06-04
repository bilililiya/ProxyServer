package cache;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 基于 LRU（最近最少使用）策略的缓存管理器。
 * 用 LinkedHashMap（accessOrder=true）维护访问顺序，满时淘汰最久未访问的条目。
 * 用读写锁保证并发安全（允许多线程同时读，写时独占）。
 */
public class CacheManager {

    private static final int MAX_ENTRIES = 500;

    private final ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock();

    // accessOrder=true：每次 get/put 后该条目移到链表尾，头部就是最久未访问的
    private final LinkedHashMap<String, CacheEntry> lruMap =
            new LinkedHashMap<String, CacheEntry>(MAX_ENTRIES, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, CacheEntry> eldest) {
                    // 缓存满时自动淘汰头部（最久未访问）条目
                    return size() > MAX_ENTRIES;
                }
            };

    /** 查询缓存；过期则删除并返回 null */
    public CacheEntry get(String url) {
        // 先用读锁快速判断是否存在
        rwLock.readLock().lock();
        try {
            CacheEntry entry = lruMap.get(url);
            if (entry == null) return null;
            if (!entry.isExpired()) return entry;
        } finally {
            rwLock.readLock().unlock();
        }
        // 过期：升级为写锁删除（双重检查，防止重复删除）
        rwLock.writeLock().lock();
        try {
            CacheEntry entry = lruMap.get(url);
            if (entry != null && entry.isExpired()) {
                lruMap.remove(url);
            }
            return null;
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    /** 存入缓存；若 url 已存在则覆盖（并更新 LRU 顺序） */
    public void put(String url, CacheEntry entry) {
        rwLock.writeLock().lock();
        try {
            lruMap.put(url, entry);
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    /** 当前缓存条目数 */
    public int size() {
        rwLock.readLock().lock();
        try {
            return lruMap.size();
        } finally {
            rwLock.readLock().unlock();
        }
    }
}