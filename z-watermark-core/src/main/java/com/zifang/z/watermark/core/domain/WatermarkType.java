package com.zifang.z.watermark.core.domain;

/**
 * 水印类型。
 *
 * <p><b>三种技术的定位不同，不是同一件事的三个档次</b>：
 * <ul>
 *   <li>{@link #VISIBLE_TEXT} —— <b>显式文字水印</b>：人眼可见，标注「这是谁的、什么时候导出的」。
 *       威慑作用最强（对方知道自己被标记了），但可被裁剪/涂抹去掉；</li>
 *   <li>{@link #IMAGE_STEGANO} —— <b>图片隐写</b>：把标识信息藏进像素里，人眼不可见。
 *       抗裁剪（信息不在边缘），但需要解码工具才能验；</li>
 *   <li>{@link #BLIND_TRACE} —— <b>盲水印溯源</b>：把追踪码嵌进内容里（文档用字符间距、图片用像素相位），
 *       **泄露后仍可反查来源**。这是「泄密追踪」的核心能力，也是最难去掉的一层。</li>
 * </ul>
 *
 * @author zifang
 */
public enum WatermarkType {

    /** 显式文字水印（人眼可见）。 */
    VISIBLE_TEXT("显式文字水印"),
    /** 图片隐写（人眼不可见）。 */
    IMAGE_STEGANO("图片隐写"),
    /** 盲水印溯源（泄露后可反查）。 */
    BLIND_TRACE("盲水印");

    private final String label;

    WatermarkType(String label) {
        this.label = label;
    }

    /** 中文标签。 */
    public String getLabel() {
        return label;
    }

    /**
     * 宽松解析类型字符串（接受枚举名、中文标签；不可识别返回 {@code null}）。
     *
     * @param code 类型字符串（可空）
     * @return 解析结果或 {@code null}
     */
    public static WatermarkType fromCode(String code) {
        if (code == null) {
            return null;
        }
        String s = code.trim();
        if (s.isEmpty()) {
            return null;
        }
        for (WatermarkType t : values()) {
            if (t.name().equalsIgnoreCase(s) || t.label.equals(s)) {
                return t;
            }
        }
        return null;
    }
}
