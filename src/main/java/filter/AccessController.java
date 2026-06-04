package filter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 访问控制器 - 支持黑名单和白名单两种模式
 * 
 * 工作模式：
 * - 黑名单模式（默认）：允许所有网站，除了黑名单中的域名
 * - 白名单模式：只允许白名单中的域名，其他全部拦截
 * 
 * 配置文件：
 * - blacklist.txt: 黑名单域名列表
 * - whitelist.txt: 白名单域名列表
 * - access-mode.txt: 访问控制模式（blacklist/whitelist）
 */
public class AccessController {

    private final Set<String> blacklist = new HashSet<>();
    private final Set<String> whitelist = new HashSet<>();
    
    private final Path blacklistFile;
    private final Path whitelistFile;
    private final Path modeFile;
    
    private long blacklistLastModified = 0;
    private long whitelistLastModified = 0;
    private long modeLastModified = 0;
    
    /** 上次检查配置的时间戳 */
    private long lastCheckTime = 0;
    
    /** 配置检查间隔（毫秒），避免每次请求都检查文件 */
    private static final long RELOAD_CHECK_INTERVAL_MS = 5000;
    
    /** 访问控制模式：true=白名单模式, false=黑名单模式（默认） */
    private boolean whitelistMode = false;

    public AccessController() {
        this(Paths.get("blacklist.txt"), Paths.get("whitelist.txt"), Paths.get("access-mode.txt"));
    }

    public AccessController(Path blacklistFile, Path whitelistFile, Path modeFile) {
        this.blacklistFile = blacklistFile;
        this.whitelistFile = whitelistFile;
        this.modeFile = modeFile;
        initializeFiles();
        reloadAll();
    }

    /**
     * 初始化配置文件（如果不存在则创建默认文件）
     */
    private void initializeFiles() {
        try {
            // 创建黑名单文件
            if (!Files.exists(blacklistFile)) {
                Files.write(blacklistFile, List.of(
                    "# 黑名单域名，一行一个",
                    "# 黑名单模式下：这些网站将被禁止访问",
                    "www.blocked.com"
                ), StandardCharsets.UTF_8);
            }
            
            // 创建白名单文件
            if (!Files.exists(whitelistFile)) {
                Files.write(whitelistFile, List.of(
                    "# 白名单域名，一行一个",
                    "# 白名单模式下：只有这些网站可以访问",
                    "www.baidu.com",
                    "www.google.com",
                    "example.com"
                ), StandardCharsets.UTF_8);
            }
            
            // 创建模式配置文件
            if (!Files.exists(modeFile)) {
                Files.write(modeFile, List.of(
                    "# 访问控制模式",
                    "# blacklist - 黑名单模式（默认）：允许所有，除了黑名单",
                    "# whitelist - 白名单模式：只允许白名单中的网站",
                    "blacklist"
                ), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            System.err.println("[AccessController] 初始化配置文件失败：" + e.getMessage());
        }
    }

    /**
     * 重新加载所有配置（检测文件变化）
     */
    private void reloadAll() {
        reloadMode();
        reloadBlacklist();
        reloadWhitelist();
        
        String modeStr = whitelistMode ? "白名单" : "黑名单";
        int count = whitelistMode ? whitelist.size() : blacklist.size();
        System.out.println(String.format("[AccessController] 模式: %s | 规则数: %d", modeStr, count));
    }

    /**
     * 加载访问控制模式
     */
    private void reloadMode() {
        try {
            long mod = Files.getLastModifiedTime(modeFile).toMillis();
            if (mod == modeLastModified) {
                return;
            }
            
            List<String> lines = Files.readAllLines(modeFile, StandardCharsets.UTF_8);
            for (String line : lines) {
                line = line.trim().toLowerCase();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                whitelistMode = line.equals("whitelist");
                break;
            }
            
            modeLastModified = mod;
        } catch (IOException e) {
            System.err.println("[AccessController] 加载模式配置失败：" + e.getMessage());
        }
    }

    /**
     * 加载黑名单
     */
    private void reloadBlacklist() {
        try {
            long mod = Files.getLastModifiedTime(blacklistFile).toMillis();
            if (mod == blacklistLastModified) {
                return;
            }
            
            blacklist.clear();
            for (String line : Files.readAllLines(blacklistFile, StandardCharsets.UTF_8)) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                blacklist.add(line.toLowerCase());
            }
            blacklistLastModified = mod;
        } catch (IOException e) {
            System.err.println("[AccessController] 加载黑名单失败：" + e.getMessage());
        }
    }

    /**
     * 加载白名单
     */
    private void reloadWhitelist() {
        try {
            long mod = Files.getLastModifiedTime(whitelistFile).toMillis();
            if (mod == whitelistLastModified) {
                return;
            }
            
            whitelist.clear();
            for (String line : Files.readAllLines(whitelistFile, StandardCharsets.UTF_8)) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                whitelist.add(line.toLowerCase());
            }
            whitelistLastModified = mod;
        } catch (IOException e) {
            System.err.println("[AccessController] 加载白名单失败：" + e.getMessage());
        }
    }

    /**
     * 检查主机是否允许访问
     * 
     * @param host 目标主机名
     * @return true=允许访问, false=拦截
     */
    public boolean isAllowed(String host) {
        // 每隔一段时间检查配置文件是否有变化，避免每次请求都进行文件 I/O
        long now = System.currentTimeMillis();
        if (now - lastCheckTime > RELOAD_CHECK_INTERVAL_MS) {
            reloadAll();
            lastCheckTime = now;
        }
        
        String normalizedHost = host.toLowerCase();
        
        if (whitelistMode) {
            // 白名单模式：只有在白名单中的才允许
            boolean allowed = whitelist.contains(normalizedHost);
            if (!allowed) {
                System.out.println("[白名单拦截] " + host);
            }
            return allowed;
        } else {
            // 黑名单模式：只有在黑名单中的才拦截
            boolean blocked = blacklist.contains(normalizedHost);
            if (blocked) {
                System.out.println("[黑名单拦截] " + host);
            }
            return !blocked;
        }
    }

    /**
     * 获取当前模式描述
     */
    public String getModeDescription() {
        return whitelistMode ? "白名单模式" : "黑名单模式";
    }

    /**
     * 获取规则数量
     */
    public int getRuleCount() {
        return whitelistMode ? whitelist.size() : blacklist.size();
    }
}
