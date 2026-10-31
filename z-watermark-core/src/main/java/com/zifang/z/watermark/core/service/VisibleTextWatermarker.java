package com.zifang.z.watermark.core.service;

import com.zifang.z.watermark.core.domain.WatermarkPayload;
import com.zifang.z.watermark.core.domain.WatermarkType;
import com.zifang.z.watermark.core.spi.Watermarker;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * 显式文字水印实现。
 *
 * <p><b>形态</b>：在文本首尾各插入一行标记（中间插入会导致正文断裂），
 * 格式为 {@code 【水印：操作人 | 租户 | 单号 | 时间】}。
 *
 * <p><b>为什么首尾都插</b>：只插首部 ⇒ 对方截图时容易裁掉；首尾都插 ⇒
 * 裁掉一半仍能留下证据。
 *
 * <p><b>提取口径</b>：用正则找 {@code 【水印：...】} 标记 ⇒ 解析载荷。
 * 提取是**尽力而为**：对方改了文字 ⇒ 提取失败（返回 null），这符合显式水印的定位
 * （它的价值是威慑与举证，不是抗破坏）。
 *
 * @author zifang
 */
public class VisibleTextWatermarker implements Watermarker {

    /** 水印标记前缀。 */
    public static final String MARK_PREFIX = "【水印：";

    /** 水印标记后缀。 */
    public static final String MARK_SUFFIX = "】";

    /** 标记正则（提取用）。 */
    private static final java.util.regex.Pattern MARK_PATTERN =
            java.util.regex.Pattern.compile("【水印：([^】]*)】");

    /** 时间格式。 */
    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    @Override
    public Object embed(Object content, WatermarkPayload payload) {
        if (content == null || payload == null) {
            return content;
        }
        String text = String.valueOf(content);
        String mark = buildMark(payload);
        if (text.contains(mark)) {
            // 幂等：已带同一水印 ⇒ 不重复叠加
            return text;
        }
        return mark + "\n" + text + "\n" + mark;
    }

    @Override
    public WatermarkPayload extract(Object content) {
        if (content == null) {
            return null;
        }
        java.util.regex.Matcher m = MARK_PATTERN.matcher(String.valueOf(content));
        if (!m.find()) {
            return null;
        }
        return parseMark(m.group(1));
    }

    /**
     * 构造标记文本。
     *
     * @param payload 载荷
     * @return 标记文本
     */
    private String buildMark(WatermarkPayload payload) {
        StringBuilder sb = new StringBuilder(MARK_PREFIX);
        sb.append(nvl(payload.getOperatorId())).append('|')
                .append(nvl(payload.getTenantId())).append('|')
                .append(nvl(payload.getBizNo())).append('|')
                .append(nvl(payload.getTraceId())).append('|')
                .append(TIME_FMT.format(Instant.ofEpochMilli(payload.getTimestamp())));
        return sb.append(MARK_SUFFIX).toString();
    }

    /**
     * 解析标记文本（去掉时间字段的格式化，只还原载荷字段）。
     *
     * @param inner 标记内部文本
     * @return 载荷（解析失败 ⇒ null）
     */
    private WatermarkPayload parseMark(String inner) {
        if (inner == null) {
            return null;
        }
        String[] parts = inner.split("\\|", -1);
        if (parts.length < 4) {
            return null;
        }
        WatermarkPayload p = new WatermarkPayload();
        p.setOperatorId(denvl(parts[0]));
        p.setTenantId(denvl(parts[1]));
        p.setBizNo(denvl(parts[2]));
        p.setTraceId(denvl(parts[3]));
        return p;
    }

    private static String nvl(String s) {
        return s == null || s.isEmpty() ? "-" : s;
    }

    private static String denvl(String s) {
        return s == null || s.isEmpty() || "-".equals(s) ? null : s;
    }

    @Override
    public WatermarkType type() {
        return WatermarkType.VISIBLE_TEXT;
    }
}
