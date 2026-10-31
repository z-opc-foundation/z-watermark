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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 水印端点验收（**真起 Spring 上下文**）。
 *
 * @author zifang
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "z.base.db.watermark.url=jdbc:h2:mem:zwm_e2e;MODE=MySQL;DB_CLOSE_DELAY=-1",
                "z.base.db.watermark.username=sa",
                "z.base.db.watermark.password="
        })
class WatermarkApiE2ETest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private TestRestTemplate rest;

    @Test
    @DisplayName("打水印：显式文字 ⇒ 自检通过 + 内容带标记")
    void embedVisibleText() throws Exception {
        ResponseEntity<String> r = embed("VISIBLE_TEXT", "合同正文");

        assertTrue(r.getStatusCode().is2xxSuccessful(), "状态码：" + r.getStatusCode());
        JsonNode node = MAPPER.readTree(r.getBody());
        assertTrue(node.get("verified").asBoolean(), "自检应通过");
        assertTrue(node.get("content").asText().contains("【水印："));
    }

    @Test
    @DisplayName("打水印：盲水印 ⇒ 需足够长度，自检通过")
    void embedBlindTrace() throws Exception {
        // 盲水印载体容量约束：traceId(13 字符) ⇒ 需约 64 个词间隔 ⇒ 65 词
        String text = "本 合同 由 甲乙 双方 于 二零二六年 十月 三十一日 签订 生效 条款 如下 "
                + "约定 双方 权利 义务 分明 特此 为据 兹 有 凭据 兹 签 字 盖章 后 生效 "
                + "并 自 签署 之 日 起 对 双方 产生 法律 约束力 任何 一方 不得 擅自 变更 "
                + "或 解除 本 合同 如有 争议 双方 应 友好 协商 解决 协商 不成 提交 "
                + "仲裁 委员会 仲裁 裁决 终局 对 双方 均有 约束力 双方 确认 已 充分 "
                + "阅读 并 理解 本 合同 全部 条款 内容 特 此 证明";

        ResponseEntity<String> r = embed("BLIND_TRACE", text);

        JsonNode node = MAPPER.readTree(r.getBody());
        assertTrue(node.get("verified").asBoolean(), "盲水印自检应通过");
    }

    @Test
    @DisplayName("提取水印：显式文字 ⇒ 拿回 traceId")
    void extractVisibleText() throws Exception {
        ResponseEntity<String> embedResp = embed("VISIBLE_TEXT", "合同正文");
        JsonNode embedded = MAPPER.readTree(embedResp.getBody());
        String content = embedded.get("content").asText();

        ResponseEntity<String> r = extract("VISIBLE_TEXT", content);

        JsonNode node = MAPPER.readTree(r.getBody());
        assertTrue(node.get("found").asBoolean());
        assertEquals("trace-abc-123", node.get("traceId").asText());
    }

    @Test
    @DisplayName("提取水印：普通文本 ⇒ 找不到")
    void extractPlainText() throws Exception {
        ResponseEntity<String> r = extract("VISIBLE_TEXT", "普通文本没有水印");

        JsonNode node = MAPPER.readTree(r.getBody());
        assertFalse(node.get("found").asBoolean());
    }

    @Test
    @DisplayName("未知水印类型 ⇒ 400")
    void unknownTypeRejected() throws Exception {
        ResponseEntity<String> r = embed("NOT_A_TYPE", "内容");

        assertEquals(400, r.getStatusCode().value());
    }

    @Test
    @DisplayName("健康检查可路由")
    void healthEndpoint() {
        ResponseEntity<String> r = rest.getForEntity("/api/watermark/health", String.class);

        assertTrue(r.getStatusCode().is2xxSuccessful(), "状态码：" + r.getStatusCode());
    }

    // ---------------- 辅助 ----------------

    /**
     * 构造 JSON body。
     *
     * <p>⚠ **必须用 Jackson 序列化，不能手工拼字符串** ——
     * 显式水印的内容**首尾各插一行** ⇒ 含换行符 ⇒ 手工拼进 JSON 会破坏报文。
     */
    private static String jsonBody(Object bean) throws Exception {
        return MAPPER.writeValueAsString(bean);
    }

    private ResponseEntity<String> embed(String type, String content) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", type);
        body.put("content", content);
        body.put("operatorId", "op-001");
        body.put("tenantId", "tenant-9");
        body.put("bizNo", "C-1");
        body.put("traceId", "trace-abc-123");
        body.put("timestamp", 1767225600000L);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange("/z-team/api/watermark/embed", HttpMethod.POST,
                new HttpEntity<>(jsonBody(body), headers), String.class);
    }

    private ResponseEntity<String> extract(String type, String content) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", type);
        body.put("content", content);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange("/z-team/api/watermark/extract", HttpMethod.POST,
                new HttpEntity<>(jsonBody(body), headers), String.class);
    }
}
