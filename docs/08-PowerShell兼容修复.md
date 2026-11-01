# PowerShell 5.1 / 7 脚本兼容修复

日期：2026-11-01。

用户报错：Invoke-RestMethod 找不到 Form 参数。原因是 Windows PowerShell 5.1 不支持该参数，请求尚未发送到 Java。

修复：

- scripts/http-client.ps1：统一使用 .NET HttpClient 上传 multipart 和发送 JSON，返回明确 HTTP 状态和响应正文，正确释放连接和消息对象。
- check-retrieval.ps1、check-ingestion.ps1：移除 Form、SkipHttpErrorCheck 及 utf8NoBOM 参数依赖；保留 HTTP 错误码，并显示服务端错误详情。
- JSON/临时文本使用 .NET UTF-8 写入；含中文脚本使用 UTF-8 BOM，以便旧版正确读取。
- 保持仅标题测试文件的原有换行，避免在已有测试库产生同名同版本内容冲突。
- 模型密钥输入文档改用跨版本 AsSecureString，不再依赖 MaskInput。

实际验证使用独立 ecommerce_rag_ingestion_test 库和 18081 端口，没有调用真实模型或修改用户运行中的 8081 服务：

1. Windows PowerShell 5.1.26100.9711 执行完整入库脚本通过。
2. PowerShell 7 执行完整入库脚本通过。
3. 两种终端执行检索脚本，上传及任务查询成功，未配置密钥的测试进程正确显示 503 / MODEL_NOT_CONFIGURED。

尚未验证该脚本在真实智谱调用后的完整结果；继续在用户配置密钥的 Java 服务上执行 check-retrieval.ps1 完成验收。本轮没有修改 Java，无需重启用户服务。
