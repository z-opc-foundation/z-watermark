package com.zifang.z.watermark.core;

import com.zifang.z.watermark.core.domain.WatermarkPayload;
import com.zifang.z.watermark.core.domain.WatermarkType;
import com.zifang.z.watermark.core.service.WatermarkService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 水印引擎单测。
 *
 * @author zifang
 */
class WatermarkServiceTest {

    private final WatermarkService service = new WatermarkService();

    private WatermarkPayload payload() {
        return new WatermarkPayload("op-001", "tenant-9", "C20261031001", "trace-abc-123",
                1767225600000L);
    }

    // ---------------- 显式文字水印 ----------------

    @Test
    @DisplayName("显式水印：首尾各插入一行标记")
    void visibleTextWatermark() {
        String text = "合同正文内容";
        Object result = service.embed(WatermarkType.VISIBLE_TEXT, text, payload());

        String out = String.valueOf(result);
        assertTrue(out.contains("【水印："), "应含水印标记：" + out);
        assertTrue(out.contains("合同正文内容"));
        assertTrue(out.startsWith("【水印："), "标记应在首部");
    }

    @Test
    @DisplayName("显式水印：可提取回载荷")
    void visibleTextExtract() {
        Object watermarked = service.embed(WatermarkType.VISIBLE_TEXT, "正文", payload());
        WatermarkPayload extracted = service.extract(WatermarkType.VISIBLE_TEXT, watermarked);

        assertNotNull(extracted);
        assertEquals("op-001", extracted.getOperatorId());
        assertEquals("tenant-9", extracted.getTenantId());
        assertEquals("C20261031001", extracted.getBizNo());
        assertEquals("trace-abc-123", extracted.getTraceId());
    }

    @Test
    @DisplayName("显式水印：幂等（同一水印不重复叠加）")
    void visibleTextIdempotent() {
        String once = String.valueOf(service.embed(WatermarkType.VISIBLE_TEXT, "正文", payload()));
        String twice = String.valueOf(service.embed(WatermarkType.VISIBLE_TEXT, once, payload()));

        assertEquals(once, twice);
    }

    @Test
    @DisplayName("显式水印：无标记文本 ⇒ 提取为 null")
    void visibleTextExtractNull() {
        assertNull(service.extract(WatermarkType.VISIBLE_TEXT, "普通文本"));
    }

    // ---------------- 盲水印（文本） ----------------

    @Test
    @DisplayName("盲水印：嵌入后可提取回 traceId（肉眼不可辨）")
    void blindTextRoundTrip() {
        // ⚠ 载体容量约束：词间隔数 × 2 bit ≥ 载荷比特数。
        // traceId(13 字符) ⇒ 载荷 14 字节 + 2 字节头 = 128 bit ⇒ 需 64 个词间隔
        // ⇒ 文本至少 65 词。**短段落打不上盲水印是设计约束，不是 bug**。
        String text = "本 合同 由 甲乙 双方 于 二零二六年 十月 三十一日 签订 生效 条款 如下 "
                + "约定 双方 权利 义务 分明 特此 为据 兹 有 凭据 兹 签 字 盖章 后 生效 "
                + "并 自 签署 之 日 起 对 双方 产生 法律 约束力 任何 一方 不得 擅自 变更 "
                + "或 解除 本 合同 如有 争议 双方 应 友好 协商 解决 协商 不成 提交 "
                + "仲裁 委员会 仲裁 裁决 终局 对 双方 均有 约束力 双方 确认 已 充分 "
                + "阅读 并 理解 本 合同 全部 条款 内容 特 此 证明";
        WatermarkService.WatermarkVerifyResult r =
                service.embedAndVerify(WatermarkType.BLIND_TRACE, text, payload());

        assertTrue(r.isVerified(), "盲水印应可提取回 traceId");
        assertEquals("trace-abc-123", r.getExtracted().getTraceId());
    }

    @Test
    @DisplayName("盲水印：容量不足的短文本 ⇒ 原样返回（不嵌入半截水印）")
    void blindTextCapacityGuard() {
        String shortText = "本 合同 由 甲乙 双方 签订 生效 条款 如下 约定 权利 义务 分明";
        Object result = service.embed(WatermarkType.BLIND_TRACE, shortText, payload());

        assertEquals(shortText, result, "容量不足应原样返回");
        assertNull(service.extract(WatermarkType.BLIND_TRACE, result), "原样文本不应解出水印");
    }

    @Test
    @DisplayName("盲水印：正文词序不变（只改空格宽度）")
    void blindTextKeepsWords() {
        String text = "甲 方 权 利 义 务 条 款";
        String out = String.valueOf(service.embed(WatermarkType.BLIND_TRACE, text, payload()));

        assertEquals(text.replace(" ", ""), out.replace(" ", ""), "词内容不应改变");
    }

    @Test
    @DisplayName("盲水印：容量不足 ⇒ 不嵌入（返回原文）")
    void blindTextTooShort() {
        String shortText = "太短";
        Object result = service.embed(WatermarkType.BLIND_TRACE, shortText, payload());

        assertEquals(shortText, result, "容量不足应原样返回");
    }

    @Test
    @DisplayName("盲水印：无水印文本 ⇒ 提取为 null")
    void blindTextExtractNull() {
        assertNull(service.extract(WatermarkType.BLIND_TRACE,
                "普通 文本 没有 水印 内容 只有 几个 词 而已 不足 长度 提取 失败"));
    }

    // ---------------- 图片隐写 ----------------

    @Test
    @DisplayName("图片隐写：嵌入后可提取回 traceId")
    void imageStegoRoundTrip() {
        byte[] image = makeImage(200, 200);
        WatermarkService.WatermarkVerifyResult r =
                service.embedAndVerify(WatermarkType.IMAGE_STEGANO, image, payload());

        assertTrue(r.isVerified(), "图片隐写应可提取回 traceId");
        assertEquals("trace-abc-123", r.getExtracted().getTraceId());
    }

    @Test
    @DisplayName("图片隐写：视觉差异极小（像素平均差 ≤ 1）")
    void imageStegoVisuallyIdentical() {
        byte[] original = makeImage(120, 120);
        byte[] watermarked = (byte[]) service.embed(WatermarkType.IMAGE_STEGANO, original, payload());

        BufferedImage a = readImage(original);
        BufferedImage b = readImage(watermarked);
        long diff = 0;
        int n = 0;
        for (int y = 0; y < a.getHeight(); y++) {
            for (int x = 0; x < a.getWidth(); x++) {
                diff += Math.abs((a.getRGB(x, y) & 0xFF) - (b.getRGB(x, y) & 0xFF));
                n++;
            }
        }
        double avg = (double) diff / n;
        assertTrue(avg <= 1.0, "平均像素差应 ≤ 1，实际 " + avg);
    }

    @Test
    @DisplayName("图片隐写：无水印图片 ⇒ 提取为 null")
    void imageStegoExtractNull() {
        assertNull(service.extract(WatermarkType.IMAGE_STEGANO, makeImage(80, 80)));
    }

    @Test
    @DisplayName("图片隐写：非图片内容 ⇒ 原样返回不抛异常")
    void imageStegoInvalidContent() {
        String notImage = "这不是图片";
        Object result = service.embed(WatermarkType.IMAGE_STEGANO, notImage, payload());

        assertEquals(notImage, result);
    }

    // ---------------- 通用 ----------------

    @Test
    @DisplayName("三种水印类型均已注册")
    void supportsAllTypes() {
        assertEquals(3, service.supportedTypes().size());
        assertTrue(service.supportedTypes().contains(WatermarkType.VISIBLE_TEXT));
        assertTrue(service.supportedTypes().contains(WatermarkType.IMAGE_STEGANO));
        assertTrue(service.supportedTypes().contains(WatermarkType.BLIND_TRACE));
    }

    @Test
    @DisplayName("null 内容 ⇒ 不抛异常")
    void handlesNull() {
        assertNull(service.embed(WatermarkType.VISIBLE_TEXT, null, payload()));
        assertNull(service.extract(WatermarkType.VISIBLE_TEXT, null));
    }

    @Test
    @DisplayName("载荷序列化往返一致")
    void payloadRoundTrip() {
        WatermarkPayload p = payload();
        WatermarkPayload parsed = WatermarkPayload.parse(p.toPayloadString());

        assertNotNull(parsed);
        assertEquals("op-001", parsed.getOperatorId());
        assertEquals("trace-abc-123", parsed.getTraceId());
        assertEquals(p.getTimestamp(), parsed.getTimestamp());
    }

    @Test
    @DisplayName("载荷含空字段时仍可往返（用 - 占位）")
    void payloadWithNullFields() {
        WatermarkPayload p = new WatermarkPayload("op", null, null, "t-1", 100L);
        WatermarkPayload parsed = WatermarkPayload.parse(p.toPayloadString());

        assertNotNull(parsed);
        assertEquals("op", parsed.getOperatorId());
        assertNull(parsed.getTenantId());
        assertNull(parsed.getBizNo());
        assertEquals("t-1", parsed.getTraceId());
    }

    // ---------------- 辅助 ----------------

    private static byte[] makeImage(int w, int h) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, w, h);
        g.setColor(new Color(120, 180, 200));
        g.fillOval(w / 4, h / 4, w / 2, h / 2);
        g.dispose();
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(img, "png", out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static BufferedImage readImage(byte[] bytes) {
        try {
            return ImageIO.read(new java.io.ByteArrayInputStream(bytes));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
