# 总体设计

> 口径截至 2026-10-31 的 `main`。README 的「能力清单 / 三种技术的定位 / 测试 / 踩坑留档」
> 是逐条细节的权威位置，本文只讲**架构切分、三种技术的选型边界与不变式**。

## 1. 这仓解决什么问题

内容出域后要能反查「这份泄露来自谁」，有三种互不替代的手段：

```
(text|image, payload) ──▶ WatermarkService ──EnumMap 按 WatermarkType 派发──▶ Watermarker 实现
                                                        └──▶ embed / extract 成对
```

边界一句话：**只管「怎么打、怎么验」**——何时打、打哪种、留不留台账都由编排层 z-biz-security 决定。

## 2. 模块与依赖方向

```
z-watermark/
├── z-watermark-core/             纯 Java，不依赖 Spring，可脱离数据库单测
│   ├── domain/    WatermarkPayload（op/tenant/biz/trace/ts）· WatermarkType
│   ├── spi/       Watermarker（embed / extract / type 三件套）
│   │              WatermarkTraceRecorder · WatermarkTraceQuery（溯源 SPI）
│   └── service/   WatermarkService（门面 + 类型路由）· VisibleTextWatermarker
│                  · ImageStegoWatermarker · BlindTextWatermarker
├── z-watermark-starter/          引擎装配（仅此一处，无 web/健康检查/数据源）
│   └── config/    WatermarkStarterAutoConfiguration（ObjectProvider 惰性接溯源 SPI）
└── z-watermark-web/              管控面部署：依赖 starter，只管部署关切
    ├── client/    AuditTraceHttpClient（实现两个溯源 SPI）
    ├── config/    WatermarkAutoConfiguration（SPI bean + 数据源 + 健康检查）
    │              · WatermarkModuleDataSource · WatermarkHealthIndicator
    └── api/       WatermarkController（/z-team/api/watermark/embed|extract）· WatermarkHealthController
```

结构约束：**新增一种水印技术 = 实现 `Watermarker` 并注册进 `WatermarkService` 的 EnumMap**，
不改门面、不改调用方；core 不依赖同族任何仓；
**引擎装配只有一处**（starter），web 不重复定义 `WatermarkService`；
溯源后端形态是部署事实，由宿主提供 SPI bean，starter 自动接线，不焊进引擎。

## 3. 三种技术不可互换

| 技术 | 人眼可见 | 抗裁剪 | 载体 | 用途 |
|---|---|---|---|---|
| `VISIBLE_TEXT` | ✅ | ❌（所以**首尾各插一行**） | 文字标记 | 威慑 + 举证 |
| `IMAGE_STEGANO` | ❌ | ✅（从图像**中部**开始嵌） | 像素 R 通道 LSB | 图片资产溯源 |
| `BLIND_TRACE` | ❌ | 中 | 词间空格宽度（宽 2~4 空格 / 窄 1 空格 ⇒ 每间隔 2 bit，前置 16 bit 长度头） | 泄密后凭拷贝反查来源 |

> **「盲」的定义**：提取时**不需要原文件做对比**，只凭带水印的内容本身就能解出追踪码。

## 4. 不变式（改代码必须守住）

| 不变式 | 实现位置 | 破掉的后果 |
|---|---|---|
| 图片隐写**只接受 PNG** | `ImageStegoWatermarker` | JPEG 有损压缩把 LSB 全冲掉 ⇒ 水印必丢（硬约束，不是偏好） |
| LSB 写入必须**先清零再置位**（`0xFFFEFFFF` 掩码） | `ImageStegoWatermarker` | `\|` 写 0 时保留原值 ⇒ 长度头读成 `0xFFFFFFFF` ⇒ 提取必失败 |
| 头长度按**像素**算（1 bit/像素 ⇒ 头占 32 像素） | `ImageStegoWatermarker#extract` | 误按 `/8` 换算 ⇒ 载荷起点偏移错 |
| 容量不足 ⇒ **不嵌入**，不嵌半截 | 三个 `Watermarker` | 截断的水印验不出原始信息，比没有更危险 |
| 显式水印**幂等不叠加** | `VisibleTextWatermarker` | 重复处理叠出多行水印 |
| 盲水印载荷**只带 traceId** + 2 字节头 | `BlindTextWatermarker` | 完整载荷 `op\|tenant\|biz\|trace\|ts` = 464 bit，30 词段落只有 58 bit ⇒ 装不下 |
| 分词必须**保留空格间隔**（`Matcher.find()` 手工切分） | `BlindTextWatermarker` | `String.split("( +)")` 会丢弃捕获组 ⇒ 拿不到空格数 ⇒ 解不出比特 |
| 无水印时 `extract` 返回 **null**（不抛） | 三个实现 | 调用方把「没水印」当系统故障 |

> ⚠ 短段落打不上盲水印是**设计约束**：traceId 约 13 字符需要约 64 个词间隔（≈65 词）。

## 5. 已知边界（不是 bug）

| 边界 | 影响 | 状态 |
|---|---|---|
| 水印台账缺失 | 打水印 ↔ 追踪码的关联要回查 z-audit | ❌ M2，需与 z-audit 打通 |
| 盲水印不带操作人/单号 | 只回答「属于哪条追踪码」 | 溯源靠 traceId 回查台账 |
| 不做 PNG ⇄ JPEG 转换 | 有损格式无法承载 LSB | 刻意不做 |

## 6. 相关文档

- 端口 / 环境变量 / 构建验证：[`_doc/002_deploy/00-deploy.md`](_doc/002_deploy/00-deploy.md)
- 复跑与实测读数：[`_doc/005_testing/001_验收复跑.md`](_doc/005_testing/001_验收复跑.md)
- 留痕侧口径：z-audit [`_doc/001_arch/00-overview.md`](../../../z-audit/_doc/001_arch/00-overview.md)
