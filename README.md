# z-watermark

> **水印引擎** —— 显式文字 / 图片隐写 / 盲水印溯源，三种技术成对提供「打 + 验」。
> 边界一句话：**只做「怎么打、怎么验」，不决定何时打、不存台账。**

安全线四层能力栈的溯源层。同族：`z-sense` · `z-mask` · `z-audit` · `z-biz-security`（编排）。

立项文档：lead `002_项目需求/001_特性需求/015_z-biz-security_数据安全线`。

## 📋 基本信息

| 项 | 值 |
|---|---|
| 层 | L3 能力仓（可被 Maven Central 口径消费；**本轮不发制品**） |
| 坐标 | `io.github.yuku123:z-watermark`，版本 `1.0.0` |
| parent | `io.github.yuku123:z-boot-parent:1.2.0`（Java 17 / Spring Boot 3.3.13 火车） |
| 包名 | `com.zifang.z.watermark.core.*` |
| 端口 | 8096 |
| 模块 | `z-watermark-core` · `z-watermark-web` |

## 🎯 能力清单

| 能力 | 状态 | 代码位置 |
|---|---|---|
| 载荷模型（操作人 / 租户 / 单号 / traceId / 时间戳） | ✅ | `domain/WatermarkPayload` |
| 类型路由（EnumMap 派发） | ✅ | `service/WatermarkService` |
| 显式文字水印（首尾各插一行，幂等不叠加） | ✅ | `service/VisibleTextWatermarker` |
| 图片隐写（LSB，**PNG 硬约束**） | ✅ | `service/ImageStegoWatermarker` |
| 盲水印（词间空格宽度编码） | ✅ | `service/BlindTextWatermarker` |
| 「打 + 验」自检（`embedAndVerify`） | ✅ | `service/WatermarkService#embedAndVerify` |
| 载荷编解码往返 | ✅ | `domain/WatermarkPayload#toPayloadString` / `#parse` |
| 水印台账（打水印 ↔ 追踪码关联） | ❌ M2 | 需与 z-audit 打通 |
| 图片格式转换（PNG ⇄ JPEG） | ❌ | 有损压缩会冲掉 LSB，**不做** |

## 🔑 三种技术的定位（不可互换）

| 技术 | 人眼可见 | 抗裁剪 | 需要原文件 | 载体 | 用途 |
|---|---|---|---|---|---|
| `VISIBLE_TEXT` | ✅ | ❌ | ❌ | 文字标记 | 威慑 + 举证（首尾各插一行才不易裁干净） |
| `IMAGE_STEGANO` | ❌ | ✅ | ❌ | 像素 R 通道 LSB | 图片资产溯源 |
| `BLIND_TRACE` | ❌ | 中 | ❌ | 词间空格宽度 | 泄密后凭拷贝反查来源 |

> **「盲」的含义**：提取时**不需要原文件做对比**，只凭带水印的内容本身就能解出追踪码。

### 图片隐写：PNG 是硬约束

JPEG 有损压缩会把 LSB 噪点全冲掉 ⇒ **水印必丢**。PNG 无损 ⇒ LSB 可保留。
**这不是偏好，是硬约束。**

嵌入从图像**中部**开始（抗裁剪：裁掉边缘不丢水印），容量不足则**不嵌入**
（截断出来的水印验不出原始信息，比没有更危险）。

### 盲水印：载荷必须紧凑（载体容量是硬约束）

载体 = **词间空格宽度**（宽 = 2~4 空格 / 窄 = 1 空格 ⇒ 每间隔 2 bit），前置 16 bit 长度头。

> ⚠ **容量是死数**：30 词的段落只有 29 个间隔 ⇒ 最多 58 bit。
> 完整文本载荷 `op|tenant|biz|trace|ts` = 56 字节 = **464 bit** ⇒ **装不下**。
>
> ⇒ 盲水印只带 **traceId**（变长字节）+ 2 字节头，**不带操作人与单号** ——
> 溯源时拿 traceId 回查**追踪码台账**（z-audit 侧）还原完整上下文。
>
> 这正是盲水印的定位：**载荷小、抗破坏、可反查**。
>
> 按本实现，traceId 约 13 字符时需要 **约 64 个词间隔**（约 65 词）才能装下。
> **短段落打不上盲水印是设计约束，不是 bug。**

## 🏗️ 项目结构

```
z-watermark/
├── pom.xml
├── z-watermark-core/
│   └── src/main/java/com/zifang/z/watermark/core/
│       ├── domain/    WatermarkPayload · WatermarkType
│       ├── spi/       Watermarker（embed / extract / type 成对）
│       └── service/   WatermarkService（门面）· VisibleTextWatermarker
│                      · ImageStegoWatermarker · BlindTextWatermarker
└── z-watermark-web/
    └── src/main/java/com/zifang/z/watermark/web/
        ├── config/    WatermarkAutoConfiguration · WatermarkModuleDataSource · WatermarkHealthIndicator
        └── api/       WatermarkHealthController
```

## 🧪 测试

```bash
export JAVA_HOME=/Users/zifang/Library/Java/JavaVirtualMachines/corretto-17.0.19/Contents/Home
mvn -f z-watermark-core/pom.xml test
```

**17 例全绿**：显式水印首尾插入 / 提取 / 幂等 / 无水印返回 null、
盲水印往返 / 词序不变 / 容量不足不嵌入 / 无水印返回 null、
图片隐写往返 / 视觉差异 ≤ 1（平均像素差）/ 无水印返回 null / 非图片原样返回、
载荷往返（含空字段占位）、null 安全。

## 📌 踩坑留档

| 坑 | 现象 | 修法 |
|---|---|---|
| **LSB 或运算清不了位** | `(rgb & 0x00FFFFFF) \| (bit << 16)` 写 0 时保留原值 ⇒ 长度头读出 `0xFFFFFFFF` ⇒ 提取必失败 | 改 `0xFFFEFFFF` 掩码（**先清零再置位**）——「或」与「或非」的经典陷阱 |
| **1 bit/像素时头长换算错** | extract 读载荷本体起点写 `start + HEADER_BITS / 8`（4 像素），实际头占 **32 像素** | 改 `start + HEADER_BITS`（1 bit/像素 ⇒ 头长 = 头比特数） |
| **`String.split` 丢弃捕获组** | `"( +)".split("a b c", -1)` ⇒ `[a, b, c]`，空格没了 ⇒ 拿不到空格数就解不出比特 | 改 `Matcher.find()` 手工切分，保留间隔 |
| **盲水印容量不足** | 完整载荷 464 bit vs 段落 33 间隔 ⇒ embed 正确地「不嵌入」，但 extract 得 null ⇒ 往返失败 | 改紧凑载荷（只带 traceId） |
