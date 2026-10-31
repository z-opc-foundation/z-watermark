package com.zifang.z.watermark.core;

import com.zifang.z.watermark.core.domain.WatermarkPayload;
import com.zifang.z.watermark.core.domain.WatermarkTrace;
import com.zifang.z.watermark.core.domain.WatermarkType;
import com.zifang.z.watermark.core.service.WatermarkService;
import com.zifang.z.watermark.core.spi.WatermarkTraceQuery;
import com.zifang.z.watermark.core.spi.WatermarkTraceRecorder;
import com.zifang.z.watermark.core.spi.WatermarkTraceUnavailableException;
import com.zifang.z.watermark.core.support.WatermarkTraceIds;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ★ 水印溯源闭环单测（M2 #22）。
 *
 * <p>验证口径：
 * <ol>
 *   <li>追踪码**单一来源**：补出来的码要回写进载荷，且与水印里提取的一致；</li>
 *   <li>打水印 ⇒ 台账里能查到，且操作人 / 租户 / 单号对得上；</li>
 *   <li>盲水印往返 ⇒ 提取出的码能反查到同一条记录；</li>
 *   <li>台账写失败 ⇒ **不拖垮打水印**，但必须标出缺口；</li>
 *   <li>反查三态：查到 / 台账没有 / 台账不可用 —— 严格区分。</li>
 * </ol>
 *
 * @author zifang
 */
class WatermarkTraceClosureTest {

    /** 内存台账（记录写进去的溯源记录）。 */
    private static class MemoryLedger implements WatermarkTraceRecorder, WatermarkTraceQuery {

        private final List<WatermarkTrace> traces = new ArrayList<>();
        /** 写入是否失败。 */
        private boolean writeFails;
        /** 查询是否不可用。 */
        private boolean queryUnavailable;
        /** 查不到的追踪码集合。 */
        private final List<String> missing = new ArrayList<>();

        @Override
        public boolean record(WatermarkTrace trace) {
            if (writeFails) {
                return false;
            }
            traces.add(trace);
            return true;
        }

        @Override
        public WatermarkTrace find(String traceId) {
            if (queryUnavailable) {
                throw new WatermarkTraceUnavailableException("台账服务不可达");
            }
            if (missing.contains(traceId)) {
                return null;
            }
            for (WatermarkTrace t : traces) {
                if (t.getTraceId().equals(traceId)) {
                    return t;
                }
            }
            return null;
        }

        @Override
        public String name() {
            return "memory-ledger";
        }
    }

    /** 盲水印用的长文本（需要约 65 词）。 */
    private static String longText() {
        return "本 合同 由 甲乙 双方 于 二零二六年 十月 三十一日 签订 生效 条款 如下 "
                + "约定 双方 权利 义务 分明 特此 为据 兹 有 凭据 兹 签 字 盖章 后 生效 "
                + "并 自 签署 之 日 起 对 双方 产生 法律 约束力 任何 一方 不得 擅自 变更 "
                + "或 解除 本 合同 如有 争议 双方 应 友好 协商 解决 协商 不成 提交 "
                + "仲裁 委员会 仲裁 裁决 终局 对 双方 均有 约束力 双方 确认 已 充分 "
                + "阅读 并 理解 本 合同 全部 条款 内容 特 此 证明";
    }

    private WatermarkService serviceWith(MemoryLedger ledger) {
        WatermarkService service = new WatermarkService();
        service.setTraceRecorder(ledger);
        service.setTraceQuery(ledger);
        return service;
    }

    // ---------------- 追踪码单一来源 ----------------

    @Test
    @DisplayName("闭环：★ 载荷没给追踪码 ⇒ 自动补短码并**回写载荷**（单一来源）")
    void generatesAndWritesBackTraceId() {
        MemoryLedger ledger = new MemoryLedger();
        WatermarkService service = serviceWith(ledger);
        WatermarkPayload payload = new WatermarkPayload("op-001", "tenant-9", "C-1", null, 0L);

        WatermarkService.TracedWatermarkResult r =
                service.embedTraced(WatermarkType.VISIBLE_TEXT, "合同正文", payload);

        assertNotNull(r.getTraceId(), "应补出追踪码");
        assertTrue(r.isTraceIdGenerated(), "应标记是本次生成的");
        assertEquals(r.getTraceId(), payload.getTraceId(),
                "★ 追踪码必须回写进载荷（否则水印里的码与台账里的码不是同一个）");
        assertEquals(r.getTraceId(), ledger.traces.get(0).getTraceId(),
                "台账里的码必须与水印里的码一致");
    }

    @Test
    @DisplayName("闭环：载荷已给追踪码 ⇒ 不覆盖（尊重调用方上下文）")
    void keepsCallerTraceId() {
        MemoryLedger ledger = new MemoryLedger();
        WatermarkService service = serviceWith(ledger);
        WatermarkPayload payload =
                new WatermarkPayload("op-001", "tenant-9", "C-1", "trace-given-1", 1767225600000L);

        WatermarkService.TracedWatermarkResult r =
                service.embedTraced(WatermarkType.VISIBLE_TEXT, "合同正文", payload);

        assertEquals("trace-given-1", r.getTraceId());
        assertFalse(r.isTraceIdGenerated());
        assertEquals("trace-given-1", ledger.traces.get(0).getTraceId());
    }

    @Test
    @DisplayName("闭环：★ 追踪码必须短（盲水印载体容量是硬约束）")
    void traceIdIsShortEnoughForBlindWatermark() {
        String traceId = WatermarkTraceIds.next();

        assertTrue(traceId.length() <= 15,
                "追踪码 " + traceId.length() + " 字符 ⇒ 盲水印要 " + (traceId.length() + 1) * 4 + 8
                        + " 个词间隔，太长；应为短码");
        assertTrue(WatermarkTraceIds.isUsable(traceId));
    }

    @Test
    @DisplayName("闭环：追踪码生成器 ⇒ 两次生成不同（同毫秒也不同）")
    void traceIdsAreUnique() {
        String a = WatermarkTraceIds.next();
        String b = WatermarkTraceIds.next();

        assertFalse(a.equals(b), "追踪码必须唯一");
    }

    // ---------------- 落台账 ----------------

    @Test
    @DisplayName("闭环：打水印 ⇒ 台账里出现对应记录（操作人 / 租户 / 单号对得上）")
    void writesTraceToLedger() {
        MemoryLedger ledger = new MemoryLedger();
        WatermarkService service = serviceWith(ledger);
        WatermarkPayload payload =
                new WatermarkPayload("op-001", "tenant-9", "C-2026-001", null, 1767225600000L);

        service.embedTraced(WatermarkType.VISIBLE_TEXT, "合同正文", payload);

        assertEquals(1, ledger.traces.size(), "应写一条溯源记录");
        WatermarkTrace t = ledger.traces.get(0);
        assertEquals("op-001", t.getOperatorId());
        assertEquals("tenant-9", t.getTenantId());
        assertEquals("C-2026-001", t.getBizNo());
        assertEquals("VISIBLE_TEXT", t.getWatermarkType());
    }

    @Test
    @DisplayName("闭环：★ 台账写失败 ⇒ 水印照常返回，但**标出缺口**（不静默）")
    void ledgerWriteFailureDoesNotBreakWatermark() {
        MemoryLedger ledger = new MemoryLedger();
        ledger.writeFails = true;
        WatermarkService service = serviceWith(ledger);
        WatermarkPayload payload = new WatermarkPayload("op-001", "tenant-9", "C-1", null, 0L);

        WatermarkService.TracedWatermarkResult r =
                service.embedTraced(WatermarkType.VISIBLE_TEXT, "合同正文", payload);

        assertTrue(r.isVerified(), "水印本身应成功");
        assertNotNull(r.getWatermarked(), "内容应照常返回（水印是主链路）");
        assertFalse(r.isTraceRecorded(), "台账确实没写成");
        assertNotNull(r.getTraceError(), "★ 必须说明原因，不能静默吞掉");
    }

    @Test
    @DisplayName("闭环：台账出口抛异常 ⇒ 不外抛（安全组件不拖垮主链路）")
    void ledgerExceptionIsSwallowed() {
        WatermarkService service = new WatermarkService();
        service.setTraceRecorder(new WatermarkTraceRecorder() {
            @Override
            public boolean record(WatermarkTrace trace) {
                throw new IllegalStateException("台账炸了");
            }

            @Override
            public String name() {
                return "broken";
            }
        });
        WatermarkPayload payload = new WatermarkPayload("op-001", "tenant-9", "C-1", null, 0L);

        WatermarkService.TracedWatermarkResult r =
                service.embedTraced(WatermarkType.VISIBLE_TEXT, "合同正文", payload);

        assertTrue(r.isVerified());
        assertFalse(r.isTraceRecorded());
        assertTrue(r.getTraceError().contains("异常"), r.getTraceError());
    }

    @Test
    @DisplayName("闭环：未配置台账出口 ⇒ 明确标出「没写台账」")
    void noRecorderReportsGap() {
        WatermarkService service = new WatermarkService();
        WatermarkPayload payload = new WatermarkPayload("op-001", "tenant-9", "C-1", null, 0L);

        WatermarkService.TracedWatermarkResult r =
                service.embedTraced(WatermarkType.VISIBLE_TEXT, "合同正文", payload);

        assertFalse(r.isTraceRecorded());
        assertNotNull(r.getTraceError());
    }

    // ---------------- 反查三态 ----------------

    @Test
    @DisplayName("反查：★ 盲水印往返 ⇒ 提取出的码能反查到同一条记录")
    void blindWatermarkRoundTripTracesBack() {
        MemoryLedger ledger = new MemoryLedger();
        WatermarkService service = serviceWith(ledger);
        WatermarkPayload payload = new WatermarkPayload("op-001", "tenant-9", "C-1", null, 0L);

        WatermarkService.TracedWatermarkResult r =
                service.embedTraced(WatermarkType.BLIND_TRACE, longText(), payload);
        assertTrue(r.isVerified(), "盲水印自检应通过");

        // 从带水印的内容里提取追踪码（模拟泄密后拿到文件）
        WatermarkPayload extracted = service.extract(WatermarkType.BLIND_TRACE, r.getWatermarked());
        assertNotNull(extracted, "应能提取出水印");
        assertEquals(r.getTraceId(), extracted.getTraceId());

        // 拿提取出的码反查台账
        WatermarkService.TraceLookupResult lookup = service.trace(extracted.getTraceId());
        assertTrue(lookup.isFound(), "应查到");
        assertFalse(lookup.isUnavailable());
        assertEquals("op-001", lookup.getTrace().getOperatorId());
        assertEquals("tenant-9", lookup.getTrace().getTenantId());
    }

    @Test
    @DisplayName("反查：★ 台账里没有 ⇒ found=false 且 **unavailable=false**（合规缺口 ≠ 故障）")
    void notFoundIsNotUnavailable() {
        MemoryLedger ledger = new MemoryLedger();
        WatermarkService service = serviceWith(ledger);

        WatermarkService.TraceLookupResult lookup = service.trace("never-existed");

        assertFalse(lookup.isFound());
        assertFalse(lookup.isUnavailable(), "★「查不到」不能报成「查不了」");
        assertNotNull(lookup.getMessage());
    }

    @Test
    @DisplayName("反查：★ 台账不可用 ⇒ unavailable=true（与「查不到」严格区分）")
    void unavailableIsDistinctFromNotFound() {
        MemoryLedger ledger = new MemoryLedger();
        ledger.queryUnavailable = true;
        WatermarkService service = serviceWith(ledger);

        WatermarkService.TraceLookupResult lookup = service.trace("trace-1");

        assertFalse(lookup.isFound());
        assertTrue(lookup.isUnavailable(), "★「查不了」必须能判出来");
    }

    @Test
    @DisplayName("反查：未配置查询出口 ⇒ unavailable=true（能力缺失，不是查不到）")
    void noQueryMeansUnavailable() {
        WatermarkService service = new WatermarkService();

        WatermarkService.TraceLookupResult lookup = service.trace("trace-1");

        assertFalse(lookup.isFound());
        assertTrue(lookup.isUnavailable());
    }

    @Test
    @DisplayName("反查：空追踪码 ⇒ notFound（不是 unavailable）")
    void emptyTraceIdIsNotFound() {
        MemoryLedger ledger = new MemoryLedger();
        WatermarkService service = serviceWith(ledger);

        WatermarkService.TraceLookupResult lookup = service.trace("");

        assertFalse(lookup.isFound());
        assertFalse(lookup.isUnavailable());
    }

    // ---------------- 幂等 ----------------

    @Test
    @DisplayName("闭环：★ eventId 幂等键 = WM:<traceId>（同一码重复写只留一条）")
    void eventIdIsIdempotentKey() {
        assertEquals("WM:trace-1", WatermarkTrace.defaultEventId("trace-1"));
        assertNull(WatermarkTrace.defaultEventId(null));
        assertNull(WatermarkTrace.defaultEventId(""));
    }
}
