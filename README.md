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
| 水印台账（打水印 ↔ 追踪码关联） | ✅ M2 #22 | `domain/WatermarkTrace` · `spi/WatermarkTraceRecorder` · `spi/WatermarkTraceQuery` |
| **★ 溯源闭环**（`embedTraced` = 打 + 验 + 落台账） | ✅ M2 #22 | `service/WatermarkService#embedTraced` |
| **★ 追踪码短码生成**（13 字符，盲水印容量约束） | ✅ M2 #22 | `support/WatermarkTraceIds` |
| **★ 三态反查**（查到 / 台账没有 / 台账不可用） | ✅ M2 #22 | `service/WatermarkService#trace` → `TraceLookupResult` |
| **★ 跨域 HTTP 出口**（落 z-audit + 反查） | ✅ M2 #22 | `web/client/AuditTraceHttpClient` |
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
│       ├── domain/    WatermarkPayload · WatermarkType · WatermarkTrace（溯源台账条目）
│       ├── spi/       Watermarker（embed / extract / type 成对）
│       │              WatermarkTraceRecorder · WatermarkTraceQuery · WatermarkTraceUnavailableException
│       ├── support/   WatermarkTraceIds（13 字符短追踪码）
│       └── service/   WatermarkService（门面，含 embedTraced / trace）
│                      · VisibleTextWatermarker · ImageStegoWatermarker · BlindTextWatermarker
└── z-watermark-web/
    └── src/main/java/com/zifang/z/watermark/web/
        ├── client/    AuditTraceHttpClient（跨域落台账 + 反查）
        ├── config/    WatermarkAutoConfiguration · WatermarkModuleDataSource · WatermarkHealthIndicator
        └── api/       WatermarkController · WatermarkHealthController
```

## 🧪 测试

```bash
export JAVA_HOME=/Users/zifang/Library/Java/JavaVirtualMachines/corretto-17.0.19/Contents/Home
mvn -f z-watermark-core/pom.xml test    # 31 例
mvn -f z-watermark-web/pom.xml test     # 15 例
```

**core 31 例**（原 17 + 溯源 14）：显式水印首尾插入 / 提取 / 幂等 / 无水印返回 null、
盲水印往返 / 词序不变 / 容量不足不嵌入 / 无水印返回 null、
图片隐写往返 / 视觉差异 ≤ 1（平均像素差）/ 无水印返回 null / 非图片原样返回、
载荷往返（含空字段占位）、null 安全；
**溯源 14 例**（`WatermarkTraceClosureTest`）：追踪码自动补 + 回写载荷、尊重调用方给的码、
短码长度约束、两次生成不同、落台账字段对得上、**台账写失败仍返回内容并标出缺口**、
出口抛异常不外抛、未配置出口标缺口、**盲水印往返后提取的码能反查到同一条**、
notFound ≠ unavailable、unavailable 独立可判、无查询出口 ⇒ unavailable、
空追踪码 ⇒ notFound、`WM:<traceId>` 幂等键。

**web 15 例**（原 6 + 降级 4 + 契约 5）：端点路由 + 自检；
**降级 4 例**（`WatermarkTraceE2ETest`，审计域基址指向没人监听的端口）：
打水印仍 200 且标出 `traceRecorded=false`、追踪码自动补且短、
反查返回 **503（不是 404）**；
**契约 5 例**（`WatermarkAuditHttpContractTest`，**真 HTTP 往返**）：
embed ⇒ POST 落台账 ⇒ extract ⇒ GET 反查命中、幂等只留一条、
404 ⇒ 判「查不到」（null）、5xx ⇒ 判「查不了」（抛异常）、写侧失败不外抛。

## 📌 踩坑留档

| 坑 | 现象 | 修法 |
|---|---|---|
| **LSB 或运算清不了位** | `(rgb & 0x00FFFFFF) \| (bit << 16)` 写 0 时保留原值 ⇒ 长度头读出 `0xFFFFFFFF` ⇒ 提取必失败 | 改 `0xFFFEFFFF` 掩码（**先清零再置位**）——「或」与「或非」的经典陷阱 |
| **1 bit/像素时头长换算错** | extract 读载荷本体起点写 `start + HEADER_BITS / 8`（4 像素），实际头占 **32 像素** | 改 `start + HEADER_BITS`（1 bit/像素 ⇒ 头长 = 头比特数） |
| **`String.split` 丢弃捕获组** | `"( +)".split("a b c", -1)` ⇒ `[a, b, c]`，空格没了 ⇒ 拿不到空格数就解不出比特 | 改 `Matcher.find()` 手工切分，保留间隔 |
| **盲水印容量不足** | 完整载荷 464 bit vs 段落 33 间隔 ⇒ embed 正确地「不嵌入」，但 extract 得 null ⇒ 往返失败 | 改紧凑载荷（只带 traceId） |
| **★ 追踪码用 UUID ⇒ 盲水印装不下** | UUID 去横线 32 字符 ⇒ 35 字节 = 280 bit ⇒ 需 **141 个词间隔** ⇒ 绝大多数正文打不上 | `WatermarkTraceIds` 出 **13 字符短码**（毫秒 base36 + 随机尾巴）⇒ 约 65 词即可 |
| **★ 跨域 HTTP 契约没人验** | 单测走内存台账**不过 HTTP** ⇒ URL 前缀拼错、字段名对不上、状态码语义错 —— **全都不会让任何单测变红** | `WatermarkAuditHttpContractTest` 用 JDK `HttpServer` 起真服务端钉住契约 |

## 文档目录

本项目文档统一收口在 `_doc/` 下（lead 002 规范八类固定编号表，桶按需存在）:

- [`_doc/001_arch/`](_doc/001_arch/) — 架构与不变式：
  - [`00-overview.md`](_doc/001_arch/00-overview.md) — 总体设计（三种技术不可互换对照、`Watermarker` SPI 扩展位、PNG/LSB/容量等八条不变式、已知边界）。
- [`_doc/002_deploy/`](_doc/002_deploy/) — 部署与运行：
  - [`00-deploy.md`](_doc/002_deploy/00-deploy.md) — 端口 8096、`Z_WATERMARK_*` 环境变量、`z.base.db.watermark.*` 键名模式、建表口径（本仓无 DDL）、构建验证与运行期注意。
- [`_doc/005_testing/`](_doc/005_testing/) — 验收复跑：
  - [`001_验收复跑.md`](_doc/005_testing/001_验收复跑.md) — 五仓复跑命令、实测读数、已知边界与生产执行清单。
