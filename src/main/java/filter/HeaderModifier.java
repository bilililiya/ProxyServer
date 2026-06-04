package filter;

import model.HttpRequest;

/**
 * 请求头修改器（拓展功能 E）。
 * 在请求转发给目标服务器之前，对请求头进行统一处理：
 *   1. 添加自定义标识头 X-Proxy-By
 *   2. 过滤掉 Proxy-Connection 头（代理专用，不应转发给源服务器）
 *   3. 将 Connection: keep-alive 改为 Connection: close（避免长连接挂起）
 *   4. 模拟指定 User-Agent（可按需开启）
 *
 * 如需自定义规则，只需修改 apply() 方法中的逻辑即可。
 */
public class HeaderModifier {

    /** 自定义代理标识（写入 X-Proxy-By 头） */
    private static final String PROXY_TAG = "SimpleJavaProxy/1.0";

    /**
     * 对 HTTP 请求头进行修改，修改完毕后自动重建原始头字节。
     * 仅对明文 HTTP 请求调用；CONNECT 隧道不经过此方法。
     *
     * @param request 待修改的请求对象（会直接修改其 headers）
     */
    public void apply(HttpRequest request) {

        // 1. 添加代理标识头
        request.setHeader("X-Proxy-By", PROXY_TAG);

        // 2. 删除 Proxy-Connection（代理专用头，源服务器不理解）
        request.removeHeader("Proxy-Connection");

        // 3. 将连接模式改为 close，防止代理与源服务器之间保持长连接
        request.setHeader("Connection", "close");

        // 4. 可选：模拟特定 User-Agent（取消注释即可启用）
        // request.setHeader("User-Agent",
        //     "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
        //     + "AppleWebKit/537.36 (KHTML, like Gecko) "
        //     + "Chrome/124.0.0.0 Safari/537.36");

        // 修改完成后，重建 rawHeaders 使 toForwardBytes() 能反映新值
        request.rebuildRawHeaders();
    }
}