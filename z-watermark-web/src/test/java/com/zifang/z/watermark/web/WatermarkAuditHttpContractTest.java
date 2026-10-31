package com.zifang.z.watermark.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.zifang.z.watermark.core.domain.WatermarkPayload;
import com.zifang.z.watermark.core.domain.WatermarkTrace;
import com.zifang.z.watermark.core.domain.WatermarkType;
import com.zifang.z.watermark.core.service.WatermarkService;
import com.zifang.z.watermark.core.spi.WatermarkTraceUnavailableException;
import com.zifang.z.watermark.web.client.AuditTraceHttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ★★ z-watermark ↔ z-audit **真实 HTTP 往返闭环**（M2 #22）。
 *
 * <p><b>为什么必须单独测这一段</b>：本仓已有的
 * {@link WatermarkTraceE2ETest} 把审计域基址指向一个**没人监听的端口**
 * ⇒ 它验的是「台账挂了时的降级口径」（503 而不是 404）。
 * 而 {@code z-audit-web} 那边验的是「本域自己写库 + 自己反查」。
 * <b>中间那一段 —— 「水印域的 HTTP 客户端」与「审计域的 HTTP 端点」之间的契约 ——
 * 没人验过</b>：
 * <ul>
 *   <li>URL 前缀拼错（{@code /z-team/api/audit/trace}）；</li>
 *   <li>请求体字段名对不上（{@code bizNo} vs {@code targetId}）；</li>
 *   <li>响应体字段名对不上（客户端读 {@code trace.traceId}，服务端给 {@code bizNo}）；</li>
 *   <li>状态码语义错（404 当故障 / 200 当没记录）。</li>
 * </ul>
 * 这些错**全都不会让任何单测变红** —— 只有真跑一遍 HTTP 才暴露。
 *
 * <p><b>本测试怎么做</b>：用 JDK 内置 {@link HttpServer} 起一个**真的 HTTP 服务端**
 * （真 socket、真 JSON、真状态码），语义对齐 {@code AuditTraceController}
 * （POST 写入 + eventId 幂等 / GET 三态），然后让真实的
 * {@link AuditTraceHttpClient} 打过去 ⇒ 验完整往返。
 *
 * <p>⚠️ 服务端用内存 Map 而不是真库 —— 真库落库由 z-audit-web 的
 * {@code AuditTraceApiE2ETest} 负责（那边直接查表验证）。
 * 本测试负责的是**两域之间的 HTTP 契约**，两段各管各的，不重复。
 *
 * @author zifang
 */
class WatermarkAuditHttpContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 审计域替身服务（真 HTTP）。 */
    private HttpServer server;

    /** 服务端基址。 */
    private String baseUrl;

    /** 服务端台账（traceId → 记录）。 */
    private final Map<String, Map<String, Object>> ledger = new LinkedHashMap<>();

    /** 服务端收到的 POST 次数（验幂等）。 */
    private int postCount;

    @BeforeEach
    void startServer() throws Exception {
        postCount = 0;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/z-team/api/audit/trace", this::handleTraceSafe);
        server.setExecutor(null);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    // ---------------- 完整闭环 ----------------

    @Test
    @DisplayName("★★ 闭环：embed ⇒ HTTP 落台账 ⇒ extract ⇒ 按追踪码反查命中（真 HTTP 往返）")
    void embedThenLookupOverRealHttp() {
        AuditTraceHttpClient client = new AuditTraceHttpClient(baseUrl);
        WatermarkService service = new WatermarkService();
        service.setTraceRecorder(client);
        service.setTraceQuery(client);

        WatermarkPayload payload =
                new WatermarkPayload("op-001", "tenant-9", "C-2026-001", null, 0L);

        // ---- 1. 打水印（自动补追踪码 + 经 HTTP 落台账）----
        WatermarkService.TracedWatermarkResult traced =
                service.embedTraced(WatermarkType.VISIBLE_TEXT, "合同正文", payload);

        assertTrue(traced.isVerified(), "水印自检应通过");
        assertTrue(traced.isTraceRecorded(), "★ 台账应写成（真 HTTP 写通了）");
        assertNull(traced.getTraceError(), "★ 不应有缺口：" + traced.getTraceError());
        String traceId = traced.getTraceId();
        assertNotNull(traceId);
        assertEquals(1, postCount, "应向审计域 POST 了一次");
        assertTrue(ledger.containsKey(traceId), "★ 台账里应有这个追踪码");

        // ---- 2. 校验服务端收到的字段（**契约对不对**）----
        Map<String, Object> stored = ledger.get(traceId);
        assertEquals("op-001", stored.get("operatorId"), "操作人字段名要对");
        assertEquals("tenant-9", stored.get("tenantId"), "租户字段名要对");
        assertEquals("C-2026-001", stored.get("bizNo"), "★ 单号字段名要对（bizNo）");
        assertEquals("WM:" + traceId, stored.get("eventId"), "★ 幂等键口径要对");

        // ---- 3. 从带水印的内容里提取追踪码（模拟泄密后拿到文件）----
        WatermarkPayload extracted =
                new WatermarkService().extract(WatermarkType.VISIBLE_TEXT, traced.getWatermarked());
        assertNotNull(extracted, "应能提取出水印");
        assertEquals(traceId, extracted.getTraceId(), "★ 水印里的码必须与台账里的码一致");

        // ---- 4. 反查（真 HTTP GET）----
        WatermarkService.TraceLookupResult lookup = service.trace(extracted.getTraceId());
        assertTrue(lookup.isFound(), "★ 拿水印里的码必须能反查到台账");
        assertFalse(lookup.isUnavailable());
        assertNotNull(lookup.getTrace());
        assertEquals("op-001", lookup.getTrace().getOperatorId());
        assertEquals("C-2026-001", lookup.getTrace().getBizNo(),
                "★ 反查回来的单号要和写进去的一致（字段名契约）");
    }

    @Test
    @DisplayName("★★ 幂等：同一追踪码重复打水印 ⇒ 审计域只留一条（HTTP 侧幂等键生效）")
    void repeatedEmbedDoesNotDuplicateOverHttp() {
        AuditTraceHttpClient client = new AuditTraceHttpClient(baseUrl);
        WatermarkService service = new WatermarkService();
        service.setTraceRecorder(client);
        service.setTraceQuery(client);

        WatermarkPayload payload =
                new WatermarkPayload("op-001", "tenant-9", "C-1", "trace-fixed-1", 0L);

        service.embedTraced(WatermarkType.VISIBLE_TEXT, "合同正文", payload);
        service.embedTraced(WatermarkType.VISIBLE_TEXT, "合同正文", payload);

        assertEquals(2, postCount, "客户端应发了两次");
        assertEquals(1, ledger.size(), "★ 审计域只应留一条（eventId 幂等）");
    }

    @Test
    @DisplayName("★★ 三态：台账里没有 ⇒ 客户端判「查不到」（null），**不是**「查不了」")
    void notFoundIsNullNotException() {
        AuditTraceHttpClient client = new AuditTraceHttpClient(baseUrl);

        WatermarkTrace found = client.find("trace-never-existed");

        assertNull(found, "★ 404 ⇒ 查不到（返回 null），不能抛异常");
    }

    @Test
    @DisplayName("★★ 三态：审计域报 5xx ⇒ 客户端抛「查不了」（不可与查不到混淆）")
    void serverErrorMeansUnavailable() throws Exception {
        // 单独起一个「总是 500」的服务
        HttpServer broken = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        broken.createContext("/z-team/api/audit/trace", exchange -> {
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });
        broken.start();
        try {
            AuditTraceHttpClient client =
                    new AuditTraceHttpClient("http://127.0.0.1:" + broken.getAddress().getPort());

            assertThrows(WatermarkTraceUnavailableException.class,
                    () -> client.find("trace-1"),
                    "★ 5xx 必须判「查不了」（抛异常），不能当成「查不到」");
        } finally {
            broken.stop(0);
        }
    }

    @Test
    @DisplayName("★ 写侧：审计域 5xx ⇒ record 返回 false（**不外抛**，主链路不拖垮）")
    void recordFailureDoesNotThrow() throws Exception {
        HttpServer broken = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        broken.createContext("/z-team/api/audit/trace", exchange -> {
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });
        broken.start();
        try {
            AuditTraceHttpClient client =
                    new AuditTraceHttpClient("http://127.0.0.1:" + broken.getAddress().getPort());
            WatermarkTrace trace = new WatermarkTrace();
            trace.setTraceId("trace-x");
            trace.setEventId("WM:trace-x");

            assertFalse(client.record(trace), "写失败应返回 false");
        } finally {
            broken.stop(0);
        }
    }

    // ---------------- 审计域替身（语义对齐 AuditTraceController） ----------------

    /**
     * ★ 处理器包装：{@link com.sun.net.httpserver.HttpHandler} 不允许抛受检异常 ⇒ 包一层。
     *
     * @param exchange HTTP 交换
     */
    private void handleTraceSafe(HttpExchange exchange) {
        try {
            handleTrace(exchange);
        } catch (Exception e) {
            try {
                respond(exchange, 500, "{\"ok\":false,\"message\":\"" + e.getMessage() + "\"}");
            } catch (Exception ignored) {
                exchange.close();
            }
        }
    }

    /**
     * 处理 {@code /z-team/api/audit/trace}（POST 写 / GET 反查）。
     *
     * @param exchange HTTP 交换
     * @throws Exception 处理失败
     */
    private void handleTrace(HttpExchange exchange) throws Exception {
        String method = exchange.getRequestMethod();
        if ("POST".equals(method)) {
            handlePost(exchange);
            return;
        }
        if ("GET".equals(method)) {
            handleGet(exchange);
            return;
        }
        exchange.sendResponseHeaders(405, -1);
        exchange.close();
    }

    /** 写入。 */
    private void handlePost(HttpExchange exchange) throws Exception {
        postCount++;
        String raw = readBody(exchange);
        JsonNode node = MAPPER.readTree(raw);
        String traceId = node.get("traceId") == null ? null : node.get("traceId").asText();
        if (traceId == null || traceId.isEmpty()) {
            respond(exchange, 400, "{\"ok\":false,\"message\":\"traceId 不能为空\"}");
            return;
        }
        String eventId = node.get("eventId") == null || node.get("eventId").asText().isEmpty()
                ? "WM:" + traceId : node.get("eventId").asText();
        // ★ 幂等：同 eventId 只留一条（对齐审计域真实现）
        if (!ledger.containsKey(traceId)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("traceId", traceId);
            row.put("eventId", eventId);
            row.put("operatorId", text(node, "operatorId"));
            row.put("tenantId", text(node, "tenantId"));
            row.put("bizNo", text(node, "bizNo"));
            row.put("watermarkType", "WATERMARK");
            row.put("remark", text(node, "remark"));
            row.put("occurredAt", node.get("occurredAt") == null
                    ? System.currentTimeMillis() : node.get("occurredAt").asLong());
            ledger.put(traceId, row);
        }
        respond(exchange, 200, "{\"ok\":true,\"traceId\":\"" + traceId
                + "\",\"eventId\":\"" + eventId + "\"}");
    }

    /** 反查（三态）。 */
    private void handleGet(HttpExchange exchange) throws Exception {
        String path = exchange.getRequestURI().getPath();
        String traceId = path.substring(path.lastIndexOf('/') + 1);
        Map<String, Object> row = ledger.get(traceId);
        if (row == null) {
            // ★ 404 = 台账里没有（**不是故障**）
            respond(exchange, 404, "{\"ok\":false,\"found\":false,\"unavailable\":false,"
                    + "\"message\":\"台账中没有该追踪码的记录\",\"trace\":null}");
            return;
        }
        String body = MAPPER.writeValueAsString(row);
        respond(exchange, 200, "{\"ok\":true,\"found\":true,\"unavailable\":false,"
                + "\"message\":\"已找到溯源记录\",\"trace\":" + body + "}");
    }

    /** 读请求体。 */
    private static String readBody(HttpExchange exchange) throws Exception {
        try (InputStream in = exchange.getRequestBody()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** 响应。 */
    private static void respond(HttpExchange exchange, int status, String body) throws Exception {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json;charset=UTF-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    /** 取字符串字段。 */
    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    /** 保留给后续扩展（当前未用）。 */
    @SuppressWarnings("unused")
    private List<String> unusedPlaceholder() {
        return new ArrayList<>();
    }
}
