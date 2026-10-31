# 部署与运行

## 1. 运行形态

`z-watermark-core` 是纯 Java 库（三种水印实现），编排层（z-biz-security）按坐标直接依赖并在自己进程内调用，
**不必起网络服务**。`z-watermark-web` 提供独立 REST 面（`ZWatermarkWebApplication`）：

| 项 | 值 |
|---|---|
| 端口 | `8096`（`server.port=${Z_WATERMARK_SERVER_PORT:8096}`） |
| Java / 框架 | 17 / Spring Boot 3.3.13 火车（parent `z-boot-parent:1.2.0`） |
| HTTP 面 | `POST /z-team/api/watermark/embed`（打，自带自检）· `POST /z-team/api/watermark/extract`（验）· `GET /api/watermark/health`（健康，**无 /z-team 前缀**） |
| 自动装配 | `META-INF/spring/...AutoConfiguration.imports` → `WatermarkAutoConfiguration` |
| 制品 | 本轮**不发 Maven Central** |

## 2. 环境变量

数据源键名模式 `z.base.db.watermark.*`（`ModuleDataSourceTemplate` 的权威前缀常量，缺省回落 `z.base.db.default.*`）。
**凭据一律走环境变量注入，不落明文**（lead 008 凭证管理规范）。

| 变量 | 默认 | 用途 |
|---|---|---|
| `Z_WATERMARK_SERVER_PORT` | `8096` | HTTP 端口 |
| `Z_WATERMARK_DB_HOST` | `127.0.0.1` | 数据源主机 |
| `Z_WATERMARK_DB_PORT` | `3306` | 数据源端口 |
| `Z_WATERMARK_DB_NAME` | `oc` | 库名（安全线共用单库，同 z-trade / z-biz / z-opcs） |
| `Z_WATERMARK_DB_USERNAME` | **空** | 必须注入，无默认值 |
| `Z_WATERMARK_DB_PASSWORD` | **空** | 必须注入，无默认值 |

连接池：`initial-size=2` / `min-idle=2` / `max-active=10`；`map-underscore-to-camel-case=true`。

显式给 `z.base.db.watermark.url` 即切直连模式（绕开键名模式拼装），本地验收走这条路到 H2：

```
z.base.db.watermark.url=jdbc:h2:mem:zwm_e2e;MODE=MySQL;DB_CLOSE_DELAY=-1
z.base.db.watermark.username=sa
z.base.db.watermark.password=
```

> ⚠ 本仓验收**全部在本地 H2 内存库**：不连生产 RDS、不在 `oc` 库建表、不对任何真实库写入。

## 3. 建表

**本仓无 DDL**：水印是无状态的「打 + 验」变换，不落库。安全线真正要建表的是
z-audit（哈希链台账 + 锚点）与 z-biz-security（策略台账），两者的 `init.sql` 在各自仓的
`_doc/002_deploy/`，属**人工执行动作**——清单见
[`_doc/005_testing/001_验收复跑.md`](_doc/005_testing/001_验收复跑.md) §4。

M2 打通溯源台账后本仓才会需要「打水印 ↔ 追踪码」的关联表；
**z-audit 是 `z_audit_event` 的唯一权威写者**（哈希链要求单写者），本域不自己写那张表。

## 4. 构建与验证

```bash
export JAVA_HOME=/Users/zifang/Library/Java/JavaVirtualMachines/corretto-17.0.19/Contents/Home

mvn -f z-watermark-core/pom.xml test   # 三种技术往返 + 幂等 + 容量不足不嵌入（用例数见 README）
mvn -f z-watermark-web/pom.xml  test   # 端点验收（真起上下文）
```

> ⚠ **必须 JDK 17**。本机默认 JDK 23 会让 Groovy 3.0.9 读不懂 class major 67 ⇒ **假红**。
> ⚠ 复跑命令**别接 `| tail; echo $?`** —— 取到的是 `tail` 的退出码 ⇒ **假绿**；先重定向到日志再取 `$?`。

`WatermarkApiE2ETest` 以 `WebEnvironment.RANDOM_PORT` 真起上下文并用 `TestRestTemplate` 打真 HTTP，
验收入库不需要先启服务。

## 5. 起进程这件事的当前状态

`z-watermark-web/pom.xml` **没有配 `spring-boot-maven-plugin`** —— 打出来的是普通 jar（无 repackage、无 `Main-Class`），
`mvn spring-boot:run` 与 `java -jar` 两条路**目前都走不通**。
要独立部署成进程，需要补 repackage 插件与镜像资产，届时按 lead 002 规范落 `002_deploy/` 与 `003_script/`。

## 6. 运行期注意

- **图片入参必须是 PNG**：JPEG 有损压缩会把 LSB 水印冲干净 ⇒ 打上去等于没打（架构文档的硬约束）。
- **盲水印有最短文本**：traceId 约 13 字符时需要约 64 个词间隔；短段落「打不上」是正确行为，
  不是故障，运维侧不要按错误告警。
