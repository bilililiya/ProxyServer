package model;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * 解析后的 HTTP 请求模型。
 * 新增请求头修改能力：setHeader / removeHeader / rebuildRawHeaders，
 * 供 HeaderModifier 在转发前调用。
 */
public class HttpRequest {

    private String method;
    private String url;
    private String version;
    private String rawHeaders;                          // 原始请求头（含请求行）
    private final Map<String, String> headers = new HashMap<>();
    private byte[] body = new byte[0];

    //  原有 getter / setter

    public String getMethod()  { return method; }
    public void   setMethod(String method)   { this.method = method; }

    public String getUrl()     { return url; }
    public void   setUrl(String url)         { this.url = url; }

    public String getVersion() { return version; }
    public void   setVersion(String version) { this.version = version; }

    public String getRawHeaders()            { return rawHeaders; }
    public void   setRawHeaders(String raw)  { this.rawHeaders = raw; }

    public Map<String, String> getHeaders()  { return Collections.unmodifiableMap(headers); }

    public void addHeader(String line) {
        int idx = line.indexOf(':');
        if (idx > 0) {
            String name  = line.substring(0, idx).trim().toLowerCase();
            String value = line.substring(idx + 1).trim();
            headers.put(name, value);
        }
    }

    public byte[] getBody()              { return body; }
    public void   setBody(byte[] body)   { this.body = body != null ? body : new byte[0]; }

    public int getContentLength() {
        String cl = headers.get("content-length");
        if (cl == null) return 0;
        try { return Integer.parseInt(cl.trim()); }
        catch (NumberFormatException e) { return 0; }
    }

    //  新增：请求头修改接口（供 HeaderModifier 调用）

    /**
     * 添加或覆盖一个请求头（key 不区分大小写）。
     * 调用后须再调用 rebuildRawHeaders() 使修改生效于转发字节。
     */
    public void setHeader(String name, String value) {
        headers.put(name.toLowerCase(), value);
    }

    /**
     * 删除一个请求头（key 不区分大小写）。
     * 调用后须再调用 rebuildRawHeaders() 使修改生效于转发字节。
     */
    public void removeHeader(String name) {
        headers.remove(name.toLowerCase());
    }

    /**
     * 根据当前 headers map 重新构建 rawHeaders 字符串。
     * 必须在所有 setHeader / removeHeader 调用完毕后执行一次，
     * 之后 toForwardBytes() 才会使用修改后的头部。
     */
    public void rebuildRawHeaders() {
        StringBuilder sb = new StringBuilder();
        // 第一行：请求行
        sb.append(method).append(' ').append(url).append(' ').append(version).append("\r\n");
        // 其余头部
        for (Map.Entry<String, String> e : headers.entrySet()) {
            // 将 key 首字母大写（如 content-type → Content-Type），符合 HTTP 惯例
            sb.append(capitalize(e.getKey()))
                    .append(": ")
                    .append(e.getValue())
                    .append("\r\n");
        }
        sb.append("\r\n");
        this.rawHeaders = sb.toString();
    }

    // 转发字节

    /** 用于转发给目标服务器的完整请求字节（请求头 + 请求体） */
    public byte[] toForwardBytes() {
        byte[] headerBytes = rawHeaders.getBytes(StandardCharsets.ISO_8859_1);
        if (body.length == 0) return headerBytes;
        byte[] combined = new byte[headerBytes.length + body.length];
        System.arraycopy(headerBytes, 0, combined, 0, headerBytes.length);
        System.arraycopy(body, 0, combined, headerBytes.length, body.length);
        return combined;
    }
    // 工具方法

    /** "content-type" → "Content-Type" */
    private static String capitalize(String key) {
        StringBuilder sb = new StringBuilder(key.length());
        boolean nextUpper = true;
        for (char c : key.toCharArray()) {
            if (c == '-') {
                sb.append(c);
                nextUpper = true;
            } else {
                sb.append(nextUpper ? Character.toUpperCase(c) : c);
                nextUpper = false;
            }
        }
        return sb.toString();
    }
}