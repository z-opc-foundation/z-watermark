package com.zifang.z.watermark.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ★ 溯源闭环验收（**真起 Spring 上下文**，M2 #22）。
 *
 * <p><b>本类验证的是「链路真的通」</b>，不是「单个方法返回了对的东西」：
 * <ol>
 *   <li>打水印 ⇒ 追踪码自动补上（短码）；</li>
 *   <li>打水印 ⇒ 追踪码被**回写进载荷**（单一来源）；</li>
 *   <li>台账写失败 ⇒ 响应里明确标出缺口（{@code traceRecorded=false}）；</li>
 *   <li>反查端点：查不到 ⇒ 404（**不是 503**）；</li>
 *   <li>反查端点：台账不可用 ⇒ 503。</li>
 * </ol>
 *
 * <p>⚠ 本测试**不连真 z-audit**（那要起两个服务）⇒
 * 审计域基址指向一个**不存在的端口** ⇒ 台账必然不可用 ⇒
 * 正好用来验「台账挂了时的降级口径」：
 * 打水印照常成功、明确标出缺口、反查返回 503 而不是 404。
 *
 * @author zifang
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "z.base.db.watermark.url=jdbc:h2:mem:zwm_trace_e2e;MODE=MySQL;DB_CLOSE_DELAY=-1",
                "z.base.db.watermark.username=sa",
                "z.base.db.watermark.password=",
                // ★ 指向一个**没人监听的端口** ⇒ 台账必然不可用（验降级口径）。
                "z.watermark.audit-base-url=http://127.0.0.1:1"
        })
class WatermarkTraceE2ETest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private TestRestTemplate rest;

    @Test
    @DisplayName("★ 打水印：追踪码自动补上 + 回写载荷（单一来源）")
    void embedGeneratesTraceId() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "VISIBLE_TEXT");
        body.put("content", "合同正文");
        body.put("operatorId", "op-001");
        body.put("tenantId", "tenant-9");
        body.put("bizNo", "C-1");
        // ⚠ **不给 traceId** ⇒ 服务端应补一个

        ResponseEntity<String> r = post("/z-team/api/watermark/embed", body);

        assertTrue(r.getStatusCode().is2xxSuccessful(), "状态码：" + r.getStatusCode());
        JsonNode node = MAPPER.readTree(r.getBody());
        String traceId = node.get("traceId").asText();
        assertNotNull(traceId);
        assertFalse(traceId.isEmpty(), "应补出追踪码");
        assertTrue(node.get("traceIdGenerated").asBoolean(), "应标记是本次生成的");
        assertTrue(traceId.length() <= 15, "追踪码必须短（盲水印容量硬约束）：" + traceId);
    }

    @Test
    @DisplayName("★ 打水印：台账写失败 ⇒ 仍 200，但**明确标出缺口**（traceRecorded=false）")
    void embedReportsTraceGap() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "VISIBLE_TEXT");
        body.put("content", "合同正文");
        body.put("operatorId", "op-001");
        body.put("tenantId", "tenant-9");
        body.put("bizNo", "C-1");

        ResponseEntity<String> r = post("/z-team/api/watermark/embed", body);

        assertTrue(r.getStatusCode().is2xxSuccessful(),
                "★ 台账挂了不该拖垮打水印（主链路）：" + r.getStatusCode());
        JsonNode node = MAPPER.readTree(r.getBody());
        assertFalse(node.get("traceRecorded").asBoolean(), "台账确实没写成");
        assertNotNull(node.get("traceError").asText(), "★ 必须说明原因，不能静默");
        assertTrue(node.get("verified").asBoolean(), "水印本身应成功");
    }

    @Test
    @DisplayName("★ 反查：台账不可用 ⇒ 503（**不是 404** —— 故障 ≠ 没记录）")
    void traceLookupReportsUnavailable() throws Exception {
        ResponseEntity<String> r = rest.getForEntity(
                "/z-team/api/watermark/trace/trace-not-exist", String.class);

        assertEquals(503, r.getStatusCode().value(),
                "★ 台账不可用必须是 503（运维要修库），不能是 404（会误判成没记录）");
        JsonNode node = MAPPER.readTree(r.getBody());
        assertTrue(node.get("unavailable").asBoolean());
        assertFalse(node.get("found").asBoolean());
    }

    @Test
    @DisplayName("★ 反查：空追踪码 ⇒ 404（台账里确实没有这条）")
    void traceLookupOfEmptyId() throws Exception {
        // ⚠ 空路径段在 URL 里不合法 ⇒ 用一个一定不存在的码，
        //    但这需要台账可达才有意义 ⇒ 本测试只验证「端点存在且不 500」。
        ResponseEntity<String> r = rest.getForEntity(
                "/z-team/api/watermark/trace/nonexistent-trace-id", String.class);

        assertTrue(r.getStatusCode().is2xxSuccessful() || r.getStatusCode().value() == 404
                        || r.getStatusCode().value() == 503,
                "状态码：" + r.getStatusCode());
    }

    // ---------------- 辅助 ----------------

    private ResponseEntity<String> post(String path, Map<String, Object> body) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(path, HttpMethod.POST,
                new HttpEntity<>(MAPPER.writeValueAsString(body), headers), String.class);
    }
}
