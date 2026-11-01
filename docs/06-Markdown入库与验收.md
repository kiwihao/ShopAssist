# Markdown 入库：操作与验收

日期：2026-11-01。本轮完成商品种子、文档上传、后台解析分块和数据库保存；没有调用模型。

## 运行

先在旧服务终端 Ctrl+C 停止旧版，再启动新版。数据库启动后，Flyway 自动执行 db/migration 下尚未执行的迁移，无需删除已有卷。

```powershell
Set-Location D:\study\codex\program_two
docker compose up -d --wait
Set-Location server
.\mvnw.cmd package
java -jar target\ecommerce-rag-demo-0.0.1-SNAPSHOT.jar
```

V1 创建商品、文档、版本、任务和片段表；V2 插入 demo-v1 的两款商品快照。它不是通用 CSV 导入器，迁移中的种子已与 data/demo/products.csv 做接口核对。
已执行的迁移不要编辑；今后新增迁移文件升级表结构。此配置方式已对照 [Spring Boot Flyway 官方说明](https://docs.spring.io/spring-boot/3.5/how-to/data-initialization.html)。

当前仅适用于本地单应用实例。路径中的 admin 尚未接登录鉴权，保持服务只绑定 127.0.0.1。原文保存为数据库 source_text，依靠数据库卷持久化，本阶段不额外写文件存储。

## 手动上传和查看片段

另开 PowerShell 7，在项目根目录执行：

```powershell
Set-Location D:\study\codex\program_two
Invoke-RestMethod http://127.0.0.1:8081/api/products | Format-Table
$upload = Invoke-RestMethod -Uri http://127.0.0.1:8081/api/admin/documents -Method Post -Form @{
    productId = 'BP-A'
    version = '1'
    file = Get-Item .\data\demo\documents\BP-A-v1.md
}
$upload
Invoke-RestMethod "http://127.0.0.1:8081/api/admin/ingestion-jobs/$($upload.jobId)"
Invoke-RestMethod "http://127.0.0.1:8081/api/admin/ingestion-jobs/$($upload.jobId)/chunks" | ConvertTo-Json -Depth 5
```

后台约每秒取一个任务。上传后立刻查询可能仍为 PENDING 或 PROCESSING，稍后再次查询。
BP-B 使用 BP-B 商品 ID 和 BP-B-v1.md 文件。

| 接口 | 结果 |
| --- | --- |
| GET /api/products | 有效商品列表，字段为数据库 snake_case |
| GET /api/products/{id} | 商品详情，不存在返回 404 |
| POST /api/admin/documents | multipart：productId、version、file；202 返回 jobId、statusUrl |
| GET /api/admin/ingestion-jobs/{id} | 状态、版本、商品、片段数、哈希与错误码 |
| GET /api/admin/ingestion-jobs/{id}/chunks | 按 ordinal 排序的片段、标题、行范围、商品与版本 |

响应约定：上传返回 jobId，数据库查询视图目前保留 job_id、chunk_id 等 snake_case 字段。前端接入时若统一 DTO 命名，需要明确迁移，不静默修改现有调用。

## 状态与边界

`PENDING → PROCESSING → PARSED / FAILED`

PARSED 仅表示解析和分块已保存，不表示有向量、能检索或该版本已激活。上传不同版本可并存，当前没有有效版本切换或问答接口。
相同商品、文件名、版本和相同原始字节哈希的重复上传返回同一任务；相同版本不同内容返回 409。不同文件名视作不同文档。
失败任务重复提交相同文件仍返回原失败任务，不自动重试；后续再增加受控重试入口。进程中断留下的 PROCESSING 在启动时回到 PENDING，依靠事务和片段唯一约束避免半套结果；该恢复方式只适用于单实例。
运行期间若数据库不可用且无法写入失败状态，任务可能停留 PROCESSING，需要恢复数据库并重启单实例以重新处理。

仅支持 UTF-8 .md，最大 256KB；空文件、非法编码、版本非正数、文件元数据与上传参数不一致返回 400。超限返回 413，未知商品和任务返回 404。
文件名不能含目录路径。文件中 product_id 与 document_version 如存在就必须匹配参数；归属最终通过数据库外键绑定，不依赖模型判断。

分块先按 ATX 标题（# 至 ######）划分，保留标题路径和原文 1-based 行号，再以每块最多 800 个 Unicode 码点限制长度；当前无重叠、不按模型 token 计数。代码围栏中的 # 不当标题；完整 Markdown 表格语义、Setext 标题、PDF 和复杂语法后续扩展。
行号可定位原文行，超长单行分成多块时会共享行号；需要结合片段内容定位。标题内元数据等文本也会成为片段，目前未做噪声清洗实验。

## 自动验收

在项目根目录执行（PowerShell 7）：

更新：check-ingestion.ps1 现已兼容 Windows PowerShell 5.1 / PowerShell 7。上方手动 -Form 示例仍仅适用于 PowerShell 7；使用 5.1 时直接运行验收脚本。

```powershell
.\scripts\check-ingestion.ps1
```

脚本会上传两份样例，另创建失败样例，验证错误分支，并将成功任务编号保存在 .runtime/ingestion-results.json。可重复执行，不会重复创建相同版本的任务。不要在更改样例而仍沿用原版本时期待成功；应使用新版本并更新元数据。

停服务再重新启动后，验证原有任务和片段：

```powershell
.\scripts\check-ingestion.ps1 -VerifySaved
```

## 本次真实结果

测试使用同一 PostgreSQL 容器中的独立 ecommerce_rag_ingestion_test 数据库、HTTP 18081 端口。保留测试库用于复查；开发库尚未执行本轮业务迁移，首次启动新版时自动执行。

- Maven package 成功，8 条 JUnit 测试通过：5 条分块边界、3 条健康接口回归。
- 新库在已有 vector 扩展的情况下，Flyway V1/V2 正常迁移；重启验证迁移校验成功，种子不重复插入。
- HTTP 上传后，BP-A、BP-B 各 6 个片段，均为 PARSED；商品属性与 CSV 对齐。
- 重复上传任务 ID 不变；冲突内容 409；未知商品/任务 404；商品元数据串绑、版本 0、非法 UTF-8、非 md 扩展 400；超限 413。
- 仅标题文档异步变为 FAILED / NO_CONTENT，片段数 0。
- 应用重启后，两份成功文档的片段 ID 与数量不变。
- 用 SQL 将失败测试任务标为 PROCESSING 模拟中断，重启后恢复消费并重新得到 NO_CONTENT；这验证状态恢复路径，不等于真实中途崩溃或数据库故障注入测试。
- 未验证：并发压力、数据库在提交中断连、事务中途进程崩溃、权限隔离、模型调用与检索效果。

## 核心代码阅读

1. DocumentController → IngestionService：校验文件、商品归属、版本与哈希。
2. IngestionRepository：唯一版本、任务领取与事务保存。
3. IngestionWorker → MarkdownChunker：后台处理、标题分块与位置保存。

后续阶段：接 Embedding、保存向量、指定商品检索，再接带引用回答。
