package com.zifang.z.watermark.core.service;

import com.zifang.z.watermark.core.domain.WatermarkPayload;
import com.zifang.z.watermark.core.domain.WatermarkType;
import com.zifang.z.watermark.core.spi.Watermarker;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 盲水印（**文本载体：字符间距编码**）。
 *
 * <p><b>原理</b>：用**字符间距**（空格数）承载比特 ——
 * 间距宽度分 4 档（1/2/3/4 个空格）⇒ 每档承载 2 比特。人眼看「宽一点窄一点」的差别不敏感
 * ⇒ 文本看起来正常，但内容里藏了可提取的信息。
 *
 * <p><b>为什么叫「盲」</b>：提取时**不需要原始文件**（不需要原文档做对比），
 * 只凭带水印的文本本身就能解出追踪码 ⇒ 泄密后手上只有一份拷贝也能反查来源。
 *
 * <p><b>编码约定</b>：
 * <ul>
 *   <li>比特载体 = <b>词间空格</b>（连续两个词之间的空格串），宽度 1~4 ⇒ 每间隔 <b>2 比特</b>；</li>
 *   <li>前置 <b>16 bit 长度头</b>（2 字节，大端）⇒ 提取时知道读多少字节；</li>
 *   <li>载荷本体 = <b>1 字节版本号 + traceId 的 UTF-8 字节</b>（<b>紧凑载荷</b>）；</li>
 *   <li>只改<b>已有词之间</b>的空格（不新增空格、不改单词本身）⇒ 文本内容与词数不变，
 *       排版几乎无感。</li>
 * </ul>
 *
 * <p><b>为什么载荷这么小（只带 traceId）</b>：载体容量极小 ——
 * 一个 N 词的段落只有 N-1 个间隔，N=60 的段落也只有 118 比特，
 * 塞不下完整的 {@code operatorId|tenantId|bizNo|traceId|timestamp} 文本载荷。
 * 所以盲水印只回答一个问题：**「这份内容属于哪条追踪码」**。
 * 完整上下文（操作人 / 单号 / 时间）用 traceId 回查<b>追踪码台账</b>（z-audit 侧）还原。
 * 这正是盲水印的定位：**载荷小、抗破坏、可反查**。
 *
 * <p><b>局限（必须写进文档）</b>：文档太短（词少）装不下载荷 ⇒ 嵌入失败返回原文。
 * 按本实现，traceId 约 13 字符时需要 <b>约 64 个词间隔</b>（约 65 词）才能装下。
 * <b>盲水印不能保证任意长度文档都能打上</b>。
 *
 * @author zifang
 */
public class BlindTextWatermarker implements Watermarker {

    /** 长度头比特数（16 bit = 2 字节）。 */
    private static final int HEADER_BITS = 16;

    /** 每个间隔承载的比特数（宽度 4 档 ⇒ 2 bit）。 */
    private static final int BITS_PER_GAP = 2;

    /** 间隔宽度档位对应的空格数（索引 = 档位值 0..3）。 */
    private static final int[] GAP_WIDTHS = {1, 2, 3, 4};

    /** 载荷版本号（当前 1）。 */
    private static final byte PAYLOAD_VERSION = 1;

    /**
     * 切分模式：**捕获分隔符**。
     *
     * <p>⚠ **不能用 {@code String.split}**：Java 的 {@code split} <b>丢弃捕获组</b>
     * （实测 {@code "( +)".split("a b c", -1)} ⇒ {@code [a, b, c]}，空格没了）⇒
     * 拿不到空格数量就无法解比特。本实现改用 {@link java.util.regex.Matcher} 手工切，保留间隔。
     */
    private static final java.util.regex.Pattern SPLIT_PATTERN =
            java.util.regex.Pattern.compile("( +)");

    @Override
    public Object embed(Object content, WatermarkPayload payload) {
        if (content == null || payload == null) {
            return content;
        }
        String text = String.valueOf(content);
        List<String> words = new ArrayList<>();
        List<Integer> gaps = new ArrayList<>();
        splitKeepingGaps(text, words, gaps);
        if (gaps.isEmpty()) {
            return content;
        }
        byte[] data = compactPayload(payload);
        byte[] framed = withHeader(data);
        int totalBits = framed.length * 8;
        int requiredGaps = ceilDiv(totalBits, BITS_PER_GAP);
        if (gaps.size() < requiredGaps) {
            // 容量不足 ⇒ 不嵌入（截断的水印反查不出真信息，比没有更危险）
            return content;
        }

        StringBuilder sb = new StringBuilder();
        sb.append(words.get(0));
        int bitIndex = 0;
        for (int g = 0; g < gaps.size(); g++) {
            int width;
            if (bitIndex < totalBits) {
                int b1 = (framed[bitIndex / 8] >> (7 - (bitIndex % 8))) & 1;
                int b2 = (framed[bitIndex / 8] >> (6 - (bitIndex % 8))) & 1;
                int level = (b1 << 1) | b2;
                width = GAP_WIDTHS[level];
                bitIndex += BITS_PER_GAP;
            } else {
                // 载荷用完之后的间隔：**保持原宽度**（不压扁原文排版）
                width = gaps.get(g);
            }
            for (int s = 0; s < width; s++) {
                sb.append(' ');
            }
            sb.append(words.get(g + 1));
        }
        return sb.toString();
    }

    /**
     * <b>紧凑载荷编码</b>（盲水印专用）。
     *
     * <p>结构：{@code [1 字节版本号][traceId 的 UTF-8 字节]}。
     *
     * <p>traceId 定长要求：无（变长）⇒ 载荷长度 = 1 + traceId 字节数。
     * 例：traceId = {@code trace-abc-123}（13 字符）⇒ 载荷 14 字节 ⇒
     * 加 2 字节头共 16 字节 = 128 比特 ⇒ 需要 64 个词间隔。
     *
     * <p>代价：盲水印**不带操作人与单号** —— 它只回答「这份内容是哪条追踪码的」，
     * 溯源时拿 traceId 回查<b>追踪码台账</b>（z-audit 侧）还原完整上下文。
     */
    private static byte[] compactPayload(WatermarkPayload payload) {
        byte[] trace = payload.getTraceId() == null
                ? new byte[0]
                : payload.getTraceId().getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[1 + trace.length];
        out[0] = PAYLOAD_VERSION;
        System.arraycopy(trace, 0, out, 1, trace.length);
        return out;
    }

    /**
     * 紧凑载荷解码（{@link #compactPayload} 的逆操作）。
     *
     * @param data 载荷字节
     * @return 载荷（traceId 还原；其它字段为空）
     */
    private static WatermarkPayload decodeCompact(byte[] data) {
        if (data == null || data.length < 2) {
            return null;
        }
        if (data[0] != PAYLOAD_VERSION) {
            return null;
        }
        String traceId = new String(data, 1, data.length - 1, StandardCharsets.UTF_8);
        WatermarkPayload p = new WatermarkPayload();
        p.setTraceId(traceId.isEmpty() ? null : traceId);
        return p;
    }

    @Override
    public WatermarkPayload extract(Object content) {
        if (content == null) {
            return null;
        }
        List<String> words = new ArrayList<>();
        List<Integer> gaps = new ArrayList<>();
        splitKeepingGaps(String.valueOf(content), words, gaps);
        int headerGaps = ceilDiv(HEADER_BITS, BITS_PER_GAP);
        if (gaps.size() < headerGaps) {
            return null;
        }
        // 读长度头
        int length = 0;
        for (int i = 0; i < headerGaps; i++) {
            int level = readLevel(gaps, i);
            length = (length << BITS_PER_GAP) | level;
        }
        if (length <= 0 || length > 64 * 1024) {
            return null;
        }
        int payloadGaps = ceilDiv(length * 8, BITS_PER_GAP);
        if (gaps.size() < headerGaps + payloadGaps) {
            return null;
        }
        byte[] data = new byte[length];
        for (int i = 0; i < length * 8; i++) {
            int gapIndex = headerGaps + (i / BITS_PER_GAP);
            int bit = (readLevel(gaps, gapIndex) >> (BITS_PER_GAP - 1 - (i % BITS_PER_GAP))) & 1;
            data[i / 8] |= (byte) (bit << (7 - (i % 8)));
        }
        return decodeCompact(data);
    }

    /** 读第 index 个间隔的档位值（0..3）。 */
    private static int readLevel(List<Integer> gaps, int index) {
        int width = gaps.get(index);
        for (int level = 0; level < GAP_WIDTHS.length; level++) {
            if (width == GAP_WIDTHS[level]) {
                return level;
            }
        }
        // 宽度超出 4 ⇒ 归到最宽档（信息可能已被破坏，但不至于崩）
        return GAP_WIDTHS.length - 1;
    }

    /** 向上取整除法。 */
    private static int ceilDiv(int a, int b) {
        return (a + b - 1) / b;
    }

    /**
     * 切分文本为「词列表 + 间隔列表」（**间隔保留空格数量**）。
     *
     * @param text  文本
     * @param words 词列表（出参）
     * @param gaps  间隔列表（出参；每项 = 该间隔的空格数）
     */
    private static void splitKeepingGaps(String text, List<String> words, List<Integer> gaps) {
        java.util.regex.Matcher m = SPLIT_PATTERN.matcher(text);
        int last = 0;
        while (m.find()) {
            words.add(text.substring(last, m.start()));
            gaps.add(m.group(1).length());
            last = m.end();
        }
        words.add(text.substring(last));
    }

    /** 给载荷前置 2 字节长度头（大端）。 */
    private static byte[] withHeader(byte[] data) {
        byte[] out = new byte[2 + data.length];
        out[0] = (byte) (data.length >>> 8);
        out[1] = (byte) data.length;
        System.arraycopy(data, 0, out, 2, data.length);
        return out;
    }

    @Override
    public WatermarkType type() {
        return WatermarkType.BLIND_TRACE;
    }
}
