# z-watermark

> **水印引擎** —— 显式文字 / 图片隐写 / 盲水印溯源，三种技术成对提供「打 + 验」。
> 边界一句话：**只做「怎么打、怎么验」，不决定何时打、不存台账。**

安全线四层能力栈的溯源层。同族：`z-sense` · `z-mask` · `z-audit` · `z-security`（编排）。

立项文档：lead `002_项目需求/001_特性需求/015_z-security_数据安全线`。

## 📋 基本信息

| 项 | 值 |
|---|---|
| 层 | L3 能力仓（可被 Maven Central 口径消费；**本轮不发制品**） |
| 坐标 | `io.github.yuku123:z-watermark`，版本 `1.0.0` |
| parent | `io.github.yuku123:z-boot-parent:1.2.2`（Java 17 / Spring Boot 3.3.13 火车） |
| 包名 | `com.zifang.z.watermark.core.*` |
| 端口 | 8096 |
| 模块 | `z-watermark-core` · `z-watermark-starter` · `z-watermark-web` |

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
├── z-watermark-starter/
│   └── src/main/java/com/zifang/z/watermark/starter/config/
│       └── WatermarkStarterAutoConfiguration   引擎装配（仅此一处）：ObjectProvider 惰性接溯源 SPI
└── z-watermark-web/
    └── src/main/java/com/zifang/z/watermark/web/
        ├── client/    AuditTraceHttpClient（跨域落台账 + 反查，实现两个溯源 SPI）
        ├── config/    WatermarkAutoConfiguration（只管部署关切：SPI bean + 数据源 + 健康检查）
        │              · WatermarkModuleDataSource · WatermarkHealthIndicator
        └── api/       WatermarkController · WatermarkHealthController
```

> **引擎装配只有一处**：`WatermarkService` 由 starter 装配（`AutoConfiguration.imports`），
> web 依赖 starter、不重复定义；溯源后端是 HTTP 还是别的，由宿主提供 SPI bean，
> starter 用 `ObjectProvider` 自动接上——部署事实不焊进引擎。

## 🧪 测试

```bash
export JAVA_HOME=/Users/zifang/Library/Java/JavaVirtualMachines/corretto-17.0.19/Contents/Home
mvn -f z-watermark-core/pom.xml test       # 31 例
mvn -f z-watermark-starter/pom.xml test    # 3 例
mvn -f z-watermark-web/pom.xml test        # 15 例
```

> **CI**：push / PR 自动跑 GitHub Actions（`.github/workflows/ci.yml`：JDK 17 + `mvn -B -ntp verify`）。

**core 31 例**（原 17 + 溯源 14）：显式水印首尾插入 / 提取 / 幂等 / 无水印返回 null、
盲水印往返 / 词序不变 / 容量不足不嵌入 / 无水印返回 null、
图片隐写往返 / 视觉差异 ≤ 1（平均像素差）/ 无水印返回 null / 非图片原样返回、
载荷往返（含空字段占位）、null 安全；
**溯源 14 例**（`WatermarkTraceClosureTest`）：追踪码自动补 + 回写载荷、尊重调用方给的码、
短码长度约束、两次生成不同、落台账字段对得上、**台账写失败仍返回内容并标出缺口**、
出口抛异常不外抛、未配置出口标缺口、**盲水印往返后提取的码能反查到同一条**、
notFound ≠ unavailable、unavailable 独立可判、无查询出口 ⇒ unavailable、
空追踪码 ⇒ notFound、`WM:<traceId>` 幂等键。

**starter 3 例**（嵌入式装配，宿主模拟应用放 `com.example.*` 无关包树 ⇒ 组件扫描不可能兜底）：
纯宿主（无 SPI）只靠 `AutoConfiguration.imports` 装出引擎且真能打/取（往返一致）、
宿主自供溯源 SPI ⇒ 引擎自动接上并调用（不用改 starter、不用手工 set）、
**imports 完整性逐行 `Class.forName` 纯机械校验**（正向用例抓不住包名笔误——Spring 侧有容错，这个才判得红）。

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

---

<!-- z-opc-foundation-sibling-registry:v1 -->
## 🔗 兄弟仓全景（z-opc-foundation 组织）

本仓隶属于 [z-opc-foundation](https://github.com/orgs/z-opc-foundation/repositories) 组织——一人公司基座的全部自研件（现存 61 仓，含私有仓；口径 2026-11-24 GitHub org API 实读）。

| 仓 | 一句话说明 |
|---|---|
| [z-agent](https://github.com/z-opc-foundation/z-agent) | 云端 Agent 平台的库与 starter——ReAct/Plan 实现 + 注册中心 + REST 网关 + 只读控制面 |
| [z-agent-kernel](https://github.com/z-opc-foundation/z-agent-kernel) | Agent 框架基座——消息/工具/记忆/provider/会话 SPI + 轻量默认实现（纯 POJO 无 Spring） |
| [z-asset](https://github.com/z-opc-foundation/z-asset) | 企业资产台账——实物设备全生命周期 + 域名/证书/许可到期提醒 |
| [z-audit](https://github.com/z-opc-foundation/z-audit) | 审计与合规留痕——事件模型 + 哈希链 + 事件出口 SPI + 留存期策略 |
| [z-biz](https://github.com/z-opc-foundation/z-biz) | 商务外壳域——客户/合同/票据/对账四聚合 |
| [z-biz-erp](https://github.com/z-opc-foundation/z-biz-erp) | 进销存 ERP——商品/客商/采购/销售/库存流水/应收应付核销（z-biz 业务线） |
| [z-biz-security](https://github.com/z-opc-foundation/z-biz-security) | 业务数据安全防护入口——场景化策略注册 + 安全线四段流水线对外编排 |
| [z-boot](https://github.com/z-opc-foundation/z-boot) | Spring Boot Starter 聚合仓 + 版本权威（火车制）——唯一消费入口 z-boot-parent |
| [z-bot](https://github.com/z-opc-foundation/z-bot) | 本地 agent 应用——终端 CLI + HTTP/SSE web 控制台 + 多通道 gateway（飞书/钉钉/webhook） |
| [z-cache](https://github.com/z-opc-foundation/z-cache) | RESP2 协议兼容的分布式内存缓存——Java 8 + Netty，Redis 客户端零修改直连 |
| [z-camuda](https://github.com/z-opc-foundation/z-camuda) | 【已退役·归档】基于 Camunda 的工作流封装层（原 z-wf 改名，与现行 z-wf 无共同历史） |
| [z-cognition](https://github.com/z-opc-foundation/z-cognition) | 业务认知域/认知舱——术语/概念/证据链 + memory/kb/ontology 渐进式模块 |
| [z-config](https://github.com/z-opc-foundation/z-config) | 分布式配置中心 + 服务注册中心（类 Nacos）——Netty 长连接推送 + React 控制台 |
| [z-ctc](https://github.com/z-opc-foundation/z-ctc) | 4A 中心——认证/授权/账户/审计，多租户 JWT + SSO 跨子域 |
| [z-dms](https://github.com/z-opc-foundation/z-dms) | 数据源管控底座——统一登记、统一建池、统一探活、变更统一推送 |
| [z-env](https://github.com/z-opc-foundation/z-env) | 本地中间件与基础设施环境——31 个 docker-compose 场景 + make 一键运维 |
| [z-etl](https://github.com/z-opc-foundation/z-etl) | ETL 引擎——DataX 式线性作业 + Flink 式 DAG 流管道（键控状态/水位/窗口） |
| [z-ext](https://github.com/z-opc-foundation/z-ext) | Java 扩展点框架——@ExtPoint/@ExtImpl 运行期选实现，五档侵入方式 |
| [z-frontend-common](https://github.com/z-opc-foundation/z-frontend-common) | 全仓共享前端基座——AppLayout/LoginPage 等前端规范的唯一承载 |
| [z-frontend-markdown](https://github.com/z-opc-foundation/z-frontend-markdown) | Markdown 通用共享组件包（npm）——MarkdownLiveEditor/NoteMarkdown/MermaidBlock 单一真源 |
| [z-frontend-render](https://github.com/z-opc-foundation/z-frontend-render) | 平台共享前端渲染引擎——后端 schema 是唯一事实源，报表与低代码共用 |
| [z-graph](https://github.com/z-opc-foundation/z-graph) | 独立图数据库——MVCC 版本链存储 + OpenCypher 子集 + Bolt 风格协议 + React 控制台 |
| [z-gw](https://github.com/z-opc-foundation/z-gw) | 自研高性能 API 网关——Netty 手写运行时，Route/Predicate/Filter 模型可嵌 Spring Boot |
| [z-indexer](https://github.com/z-opc-foundation/z-indexer) | 代码索引服务——仓库注册表 + 定时重建索引 + 符号/调用链查询 |
| [z-introduce](https://github.com/z-opc-foundation/z-introduce) | 组织仓库展示站——全仓实测元数据汇总成可搜索静态站点 |
| [z-lc](https://github.com/z-opc-foundation/z-lc) | 低代码平台——DB 驱动通用 CRUD 引擎 + 报表/模型设计框架 |
| [z-llm-agent](https://github.com/z-opc-foundation/z-llm-agent) | CLI Agent 服务化——把本机 CLI agent（CC/Hermes/Qoder 等）暴露成 OpenAI 兼容对话 API |
| [z-llm-gateway](https://github.com/z-opc-foundation/z-llm-gateway) | LLM 网关——OpenAI/Anthropic 双协议面 + 多 vendor 凭据池、限流与 token 记账 |
| [z-llm-msghub](https://github.com/z-opc-foundation/z-llm-msghub) | msgHub 中心消息路由——WebSocket 中心枢纽 + Agent 侧客户端 SDK |
| [z-mask](https://github.com/z-opc-foundation/z-mask) | 脱敏与遮蔽引擎——按敏感命中位置遮蔽，策略即数据 |
| [z-mcp](https://github.com/z-opc-foundation/z-mcp) | MCP 服务端 + 上游聚合网关——Java 8 手写协议层，Spring Boot 自动装配 |
| [z-meta](https://github.com/z-opc-foundation/z-meta) | 元数据管理平台——租户/应用/接口清单登记 + @BizModel 业务模型采集 |
| [z-mill](https://github.com/z-opc-foundation/z-mill) | 资料磨坊——全格式文档转 Markdown/JSON/纯文本/HTML（对标 MinerU） |
| [z-mist](https://github.com/z-opc-foundation/z-mist) | KMS 分布式密钥/秘密管理平台——集中加密存储 + Netty 协议 + starter 嵌入 |
| [z-mq](https://github.com/z-opc-foundation/z-mq) | 自研分布式消息队列——NameServer+Broker+Store，形状对标 RocketMQ、自有 Netty 协议 |
| [z-msg](https://github.com/z-opc-foundation/z-msg) | 多通道消息 + WebSocket 实时下发基础设施——短信/邮件/站内信/Webhook/IM 平台 |
| [z-observer](https://github.com/z-opc-foundation/z-observer) | 可观测性域——指标/日志/链路 + 告警引擎 |
| [z-opc](https://github.com/z-opc-foundation/z-opc) | 一人公司统一后端+统一前端——单 JVM all-in-one 承载协作/Agent/RPA/交易/资产等业务域与自研中间件 |
| [z-opc-foundation-lead](https://github.com/z-opc-foundation/z-opc-foundation-lead) | 组织治理大脑——事实表、强制规范、跨仓工具集、新机器恢复钥匙（本清单权威持有处） |
| [z-opcs](https://github.com/z-opc-foundation/z-opcs) | 任务撮合/服务市场——用户付费发布诉求，持有者接单完成，金额自动瓜分 |
| [z-ops](https://github.com/z-opc-foundation/z-ops) | 个人公司运维平台——部署单元/镜像构建/WebShell/K8s 巡检 + Ops Agent 执行器 |
| [z-oss](https://github.com/z-opc-foundation/z-oss) | 对象存储服务——自持 local 后端 + aliyun/s3 对接层，starter 注入 OssTemplate |
| [z-product](https://github.com/z-opc-foundation/z-product) | 产品设计信息汇总仓（开发域 PM 工件） |
| [z-qa](https://github.com/z-opc-foundation/z-qa) | 步骤式 API 链路测试平台——用例/计划/执行/报告/质量看板（对标 TestRail·MeterSphere） |
| [z-rpa](https://github.com/z-opc-foundation/z-rpa) | RPA 产品皮——应用市场/需求中心（执行归 z-script，触发归 z-schedule） |
| [z-rpc](https://github.com/z-opc-foundation/z-rpc) | Java 8 + Netty RPC 框架——微内核 + SPI 插件化，形状参照 Dubbo/gRPC |
| [z-schedule](https://github.com/z-opc-foundation/z-schedule) | 分布式任务调度中枢（对标 XXL-Job）——选主 + 时间环排期，starter 嵌入宿主 |
| [z-script](https://github.com/z-opc-foundation/z-script) | 多 DSL 脚本平台 + Mock 平台——沙箱执行/版本灰度/可注册 MCP Tool，starter 内嵌 |
| [z-security](https://github.com/z-opc-foundation/z-security) | 数据安全域编排层——场景→策略→识别→脱敏→水印→审计 四段流水线编排 |
| [z-sense](https://github.com/z-opc-foundation/z-sense) | 敏感识别与分类分级引擎——数据安全线底座（规则目录 + 可插拔识别器 + 分类分级） |
| [z-skill](https://github.com/z-opc-foundation/z-skill) | Skill 平台聚合器——归一化各家 Skill 形状，再按各家 API 形状发回 |
| [z-surl](https://github.com/z-opc-foundation/z-surl) | 短网址服务——短码生成/解析/命中统计，starter 一行集成 |
| [z-task](https://github.com/z-opc-foundation/z-task) | 任务协作平台——项目空间/看板/任务卡片（看板范式，对标 Trello·Teambition） |
| [z-team](https://github.com/z-opc-foundation/z-team) | 数字员工协同域——全栈独立仓，自 z-opc 毕业 |
| [z-trade](https://github.com/z-opc-foundation/z-trade) | 交易域独立仓——自 z-opc reactor 迁出 |
| [z-util](https://github.com/z-opc-foundation/z-util) | 底层 Java 工具库——89 个模块 POM，Java 8/17 口径，按需引用按需升级 |
| [z-vector](https://github.com/z-opc-foundation/z-vector) | 自研 Java 向量数据库——嵌入式+可独立部署，ANN+Payload 过滤，兼容 Qdrant REST |
| [z-voice-demo](https://github.com/z-opc-foundation/z-voice-demo) | 语音链路 demo——流式识别→kernel LLM→流式 TTS→可打断 的契约验证实现 |
| [z-watermark](https://github.com/z-opc-foundation/z-watermark) | 水印引擎——显式文字/图片隐写/盲水印，三种技术成对「打+验」 **（本仓）** |
| [z-webide](https://github.com/z-opc-foundation/z-webide) | 企业级 WebIDE 后端——Git 仓/共享目录秒变 code-server 实例，全生命周期管理 |
| [z-wf](https://github.com/z-opc-foundation/z-wf) | 自研流程引擎——不依赖 Camunda/Flowable，定义层与运行时全自研 |

**已退役仓**（迁至 [z-trash](https://github.com/z-trash) 组织，archived 只读）：

| 仓 | 说明 |
|---|---|
| [z-agent-proxy](https://github.com/z-trash/z-agent-proxy) | 三方 coding agent 适配代理（Aider/Cursor → kernel Agent SPI），2026-10-26 退役 |
| [z-kb](https://github.com/z-trash/z-kb) | 自研知识库系统（对标 LightRAG/RAGFlow），2026-11 并入 z-cognition 认知舱后退役 |
| [z-ontology](https://github.com/z-trash/z-ontology) | 本体层（TBox/ABox/RDFS 推理），并入 z-cognition 后退役 |

另有 [z-report](https://github.com/z-opc-foundation/z-report)（报表域，2026-10-11 撤销并入 z-lc（远端已删除））。

总清单权威持有处在 [z-opc-foundation-lead](https://github.com/z-opc-foundation/z-opc-foundation-lead) `008_组织规范/005_兄弟仓清单.md`。
