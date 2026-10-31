package com.zifang.z.watermark.core.service;

import com.zifang.z.watermark.core.domain.WatermarkPayload;
import com.zifang.z.watermark.core.domain.WatermarkType;
import com.zifang.z.watermark.core.spi.Watermarker;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * 图片隐写水印（**LSB — Least Significant Bit**）。
 *
 * <p><b>原理</b>：把载荷编码成比特流，逐位写入像素的**最低有效位**。
 * 人眼对单个 bit 的明暗变化不敏感 ⇒ 视觉上几乎无差别。
 *
 * <p><b>算法口径**：
 * <ol>
 *   <li>载荷 → UTF-8 字节 → 比特流（每像素的 R 通道最低位放 1 bit）；</li>
 *   <li>比特流前置 <b>32 bit 长度头</b> ⇒ 提取时知道读多少位（否则读到噪点也认不出）；</li>
 *   <li>像素遍历顺序：**逐行、只取 R 通道**（不碰 G/B，避免色彩偏移）；</li>
 *   <li>嵌入位置：从图像**中部**开始（抗裁剪：裁掉边缘不丢水印），
 *       可容量 = (像素总数 - 起点) × 1 bit。</li>
 * </ol>
 *
 * <p><b>为什么 M1 用 PNG 而不是 JPEG</b>：JPEG 有损压缩会把 LSB 噪点全冲掉
 * ⇒ 水印必丢。PNG 无损 ⇒ LSB 可保留。**这是硬约束，不是偏好**。
 *
 * @author zifang
 */
public class ImageStegoWatermarker implements Watermarker {

    /** 长度头比特数（32 bit = 4 字节）。 */
    private static final int HEADER_BITS = 32;

    /** 从图像中部开始的嵌入起点比例（0.5 = 从中间开始）。 */
    private static final double START_RATIO = 0.5;

    @Override
    public Object embed(Object content, WatermarkPayload payload) {
        if (content == null || payload == null) {
            return content;
        }
        byte[] bytes = toBytes(content);
        if (bytes == null) {
            return content;
        }
        BufferedImage image = readImage(bytes);
        if (image == null) {
            return content;
        }
        byte[] data = payload.toPayloadString().getBytes(StandardCharsets.UTF_8);
        byte[] framed = withHeader(data);

        int start = (int) (image.getWidth() * image.getHeight() * START_RATIO);
        long capacity = ((long) image.getWidth() * image.getHeight() - start);
        if (capacity < framed.length * 8L) {
            // 容量不足 ⇒ 不嵌入（**不能截断载荷**：截断出来的水印验不出原始信息）
            return content;
        }

        int bitIndex = 0;
        for (int idx = start; idx < image.getWidth() * image.getHeight() && bitIndex < framed.length * 8; idx++) {
            int x = idx % image.getWidth();
            int y = idx / image.getWidth();
            int rgb = image.getRGB(x, y);
            int bit = (framed[bitIndex / 8] >> (7 - (bitIndex % 8))) & 1;
            // 只改 R 通道最低位。
            //
            // ⚠ **必须先清零再置位**（0xFFFEFFFF 掩掉 R 的最低位）。
            // 若写成 `(rgb & 0x00FFFFFF) | (bit << 16)`，**或运算无法把已是 1 的最低位清掉**
            // ⇒ 写 0 的位置（白底 R=255，最低位本来就是 1）保留原值 ⇒ 长度头读出 0xFFFFFFFF
            // ⇒ 提取必失败。**这是「或」与「或非」的经典陷阱。**
            int newRgb = (rgb & 0xFFFEFFFF) | (bit << 16);
            image.setRGB(x, y, newRgb);
            bitIndex++;
        }
        return writePng(image);
    }

    @Override
    public WatermarkPayload extract(Object content) {
        if (content == null) {
            return null;
        }
        byte[] bytes = toBytes(content);
        if (bytes == null) {
            return null;
        }
        BufferedImage image = readImage(bytes);
        if (image == null) {
            return null;
        }
        int start = (int) (image.getWidth() * image.getHeight() * START_RATIO);
        int total = image.getWidth() * image.getHeight();

        // 先读长度头
        byte[] header = new byte[HEADER_BITS / 8];
        int bitIndex = 0;
        for (int idx = start; idx < total && bitIndex < HEADER_BITS; idx++) {
            int x = idx % image.getWidth();
            int y = idx / image.getWidth();
            int rgb = image.getRGB(x, y);
            int bit = (rgb >> 16) & 1;
            header[bitIndex / 8] |= (byte) (bit << (7 - (bitIndex % 8)));
            bitIndex++;
        }
        int length = ((header[0] & 0xFF) << 24) | ((header[1] & 0xFF) << 16)
                | ((header[2] & 0xFF) << 8) | (header[3] & 0xFF);
        if (length <= 0 || length > 64 * 1024) {
            return null;
        }

        // 再读载荷本体
        //
        // ⚠ **位点必须与 embed 对齐**：本实现是 **1 bit / 像素**，所以长度头
        // （32 bit）实际占据 **32 个像素**，不是 4 个 ⇒ 本体起点 = start + 32 像素。
        // （写成 start + HEADER_BITS / 8 会偏移 28 像素 ⇒ 载荷读出来全是错位比特。）
        byte[] data = new byte[length];
        bitIndex = 0;
        int totalBits = length * 8;
        for (int idx = start + HEADER_BITS; idx < total && bitIndex < totalBits; idx++) {
            int x = idx % image.getWidth();
            int y = idx / image.getWidth();
            int rgb = image.getRGB(x, y);
            int bit = (rgb >> 16) & 1;
            data[bitIndex / 8] |= (byte) (bit << (7 - (bitIndex % 8)));
            bitIndex++;
        }
        return WatermarkPayload.parse(new String(data, StandardCharsets.UTF_8));
    }

    /** 给载荷前置 4 字节长度头。 */
    private static byte[] withHeader(byte[] data) {
        byte[] out = new byte[4 + data.length];
        out[0] = (byte) (data.length >>> 24);
        out[1] = (byte) (data.length >>> 16);
        out[2] = (byte) (data.length >>> 8);
        out[3] = (byte) data.length;
        System.arraycopy(data, 0, out, 4, data.length);
        return out;
    }

    /** 内容转字节（byte[] / String 路径皆可）。 */
    private static byte[] toBytes(Object content) {
        if (content instanceof byte[]) {
            return (byte[]) content;
        }
        if (content instanceof String) {
            return ((String) content).getBytes(StandardCharsets.ISO_8859_1);
        }
        return null;
    }

    /** 读图（PNG / JPEG 皆可）。 */
    private static BufferedImage readImage(byte[] bytes) {
        try (ByteArrayInputStream in = new ByteArrayInputStream(bytes)) {
            return ImageIO.read(in);
        } catch (IOException e) {
            return null;
        }
    }

    /** 写回 PNG（**必须 PNG** —— JPEG 会冲掉 LSB）。 */
    private static byte[] writePng(BufferedImage image) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            return null;
        }
    }

    @Override
    public WatermarkType type() {
        return WatermarkType.IMAGE_STEGANO;
    }
}
