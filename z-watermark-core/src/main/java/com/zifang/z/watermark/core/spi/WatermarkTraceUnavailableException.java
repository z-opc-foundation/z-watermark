package com.zifang.z.watermark.core.spi;

/**
 * 水印溯源台账不可用异常。
 *
 * <p><b>存在的唯一理由</b>：把「查不到记录」与「查不了台账」在**类型层面**分开。
 * 「查不到」返回 {@code null}（正常业务结果）；
 * 「查不了」抛本异常（基础设施故障）。
 * 调用方据此返回不同 HTTP 状态与不同运维动作。
 *
 * @author zifang
 */
public class WatermarkTraceUnavailableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * 构造。
     *
     * @param message 说明
     */
    public WatermarkTraceUnavailableException(String message) {
        super(message);
    }

    /**
     * 构造。
     *
     * @param message 说明
     * @param cause   根因
     */
    public WatermarkTraceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
