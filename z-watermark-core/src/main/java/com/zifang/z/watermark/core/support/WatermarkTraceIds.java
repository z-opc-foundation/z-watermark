package com.zifang.z.watermark.core.support;

import java.security.SecureRandom;

/**
 * 水印追踪码生成器（**短码**）。
 *
 * <p><b>★ 为什么追踪码必须短</b>：盲水印（{@code BLIND_TRACE}）的载体容量是**硬约束** ——
 * 载荷 = 1 字节版本号 + traceId 的 UTF-8 字节 + 2 字节长度头，
 * 每个词间隔只承载 2 比特。按本仓实现：
 * <pre>
 *   traceId 13 字符 ⇒ 载荷 14 字节 ⇒ 加头 16 字节 = 128 比特 ⇒ 需 64 个词间隔（约 65 词）
 *   traceId 32 字符 ⇒ 载荷 33 字节 ⇒ 加头 35 字节 = 280 比特 ⇒ 需 140 个词间隔（约 141 词）
 * </pre>
 * UUID 去横线是 32 字符 ⇒ 盲水印要 141 个词才装得下 ⇒
 * **绝大多数正文根本打不上盲水印**（容量不足时按口径「原样返回」⇒ 等于没打）。
 * ⇒ 追踪码必须**定长短码**，本类给 13 字符（时间戳 base36 + 随机尾巴）。
 *
 * <p><b>短码够不够唯一</b>：13 位 base36 ≈ 4.7×10<sup>20</sup> 组合空间，
 * 其中前 8 位是毫秒时间戳 ⇒ 同毫秒内的区分靠后 5 位 base36 随机（≈ 6 千万种/毫秒）。
 * 作为「一次出域动作的追踪码」足够。
 *
 * <p><b>⚠️ 追踪码是敏感数据</b>：拿到水印内容的人反查得到操作人与单号 ⇒
 * 不要把追踪码当「公开 ID」用（不要拿它做主键对外暴露）。
 *
 * @author zifang
 */
public final class WatermarkTraceIds {

    /** 追踪码长度（定长）。 */
    public static final int DEFAULT_LENGTH = 13;

    /** base36 字符表。 */
    private static final char[] ALPHABET =
            "0123456789abcdefghijklmnopqrstuvwxyz".toCharArray();

    /** 随机源（SecureRandom ⇒ 追踪码不可预测，防枚举）。 */
    private static final SecureRandom RANDOM = new SecureRandom();

    private WatermarkTraceIds() {
    }

    /**
     * 生成一个追踪码（默认 13 字符）。
     *
     * @return 追踪码
     */
    public static String next() {
        return next(DEFAULT_LENGTH);
    }

    /**
     * 生成一个指定长度的追踪码。
     *
     * @param length 长度（小于 1 ⇒ 用默认长度）
     * @return 追踪码
     */
    public static String next(int length) {
        int len = length < 1 ? DEFAULT_LENGTH : length;
        StringBuilder sb = new StringBuilder(len);
        // 前段：毫秒时间戳的 base36（定长补 0 ⇒ 同长度可排序）
        String ts = Long.toString(System.currentTimeMillis(), 36);
        int pad = len - (len / 3);
        for (int i = ts.length(); i < pad; i++) {
            sb.append('0');
        }
        sb.append(ts);
        // 后段：随机尾巴
        while (sb.length() < len) {
            sb.append(ALPHABET[RANDOM.nextInt(ALPHABET.length)]);
        }
        return sb.substring(0, len);
    }

    /**
     * 判断追踪码是否「看起来可用」（非空、长度在盲水印容量可承受范围内）。
     *
     * <p>⚠️ 本方法**只做形态判断**，不保证一定能嵌进去 ——
     * 真正的容量判据取决于载体有几个词间隔（由 {@code BlindTextWatermarker} 判定）。
     *
     * @param traceId 追踪码
     * @return true = 形态正常
     */
    public static boolean isUsable(String traceId) {
        if (traceId == null || traceId.isEmpty()) {
            return false;
        }
        // 盲水印载荷 = 1 + 字节数；加上 2 字节头按 2 bit/间隔 ⇒ 约 (1 + len) * 4 + 8 个间隔
        // 上限取 64 个间隔（≈ 65 词）⇒ 追踪码约 15 字符以内
        return traceId.length() <= 15;
    }
}
