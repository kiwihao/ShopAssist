# ShopAssist：DeepSeek + 本地 BGE-M3

主线：Ollama 运行 bge-m3:567m（1024 维），DeepSeek 使用 deepseek-flash 生成回答。
本地向量服务不需要智谱 Key；聊天使用 DEEPSEEK_API_KEY。两个模型互相独立。

## IDEA 开发

1. 启动 Docker Desktop，等待 Linux 引擎就绪。
2. 项目根目录执行 `.\scripts\start-local.ps1`。第一次下载 Ollama 镜像和约 1.2GB 模型，后续复用持久化卷。
3. 在 IDEA 的 DemoApplication 环境变量中删除旧 BIGMODEL_API_KEY，添加 DEEPSEEK_API_KEY。
4. 重新运行 DemoApplication，然后执行 `.\scripts\check-retrieval.ps1 -RetryFailed`。

如果 IDEA 配置过 EMBEDDING_PROVIDER，设为 ollama 或删除覆盖值。本地默认 OLLAMA_BASE_URL=http://127.0.0.1:11434。
CPU 模式为通用默认，未启用 GPU；速度需实测。模型初始化使用 Ollama CLI，启动依赖使用健康检查和成功完成条件。

## 克隆后的容器启动（后端镜像构建与启动尚待实测）

```powershell
Copy-Item .env.example .env
# 编辑 .env，填入自己的 DEEPSEEK_API_KEY，勿提交该文件
.\scripts\start-local.ps1 -ContainerBackend
```

该命令先准备依赖与模型，再执行 `docker compose --profile app up -d --build backend`。
若 IDEA 占用 8081，在 .env 设置 APP_PORT=8082，容器接口变为 http://127.0.0.1:8082。
容器通过 Compose 显式传入 .env 中的 Key；IDEA 不自动读取 .env，仍需运行配置环境变量。

Compose 当前包含数据库、模型和 Java 后端。2026-11-02 已增加独立运行的 React 页面，启动步骤见 [前端说明](../web/README.md)，前端尚未打包进 Compose。
端口仅绑定本机，尚无登录鉴权。数据库和模型有持久化卷；执行 docker compose --profile app stop 停止并保留数据。

## 问答接口

`POST /api/qa`，JSON 为 `{"productId":"BP-A","question":"电脑仓尺寸是多少？"}`。
先召回证据，再调用 DeepSeek；服务端检查 JSON 格式及引用 ID 真实存在。返回 status、answer、citations、requestId。
无证据时直接说明不足；模型错误和非法引用返回明确错误。引用 ID 存在不代表语义结论一定正确，仍需评测。

## 模型变更

旧智谱 INDEX_FAILED 任务可直接 -RetryFailed，无需删除。若已有 READY 的智谱向量，应上传新文档版本重新索引；系统按 embedding_space 过滤，不混用。
本地空间标识使用 provider + 模型标签 + 1024 维，不随宿主机/容器地址变化。权重更新时须换标签并重建；目前未自动检测同名标签权重变更。

## 已验证与限制

- 2026-11-01：27 条自动测试通过，1 条数据库集成测试默认跳过。新增测试确认上游 HTTP 503 转为 CHAT_HTTP_503，问答服务透传故障，不能伪装成资料不足。
- Docker 已恢复，PostgreSQL 与 Ollama 均健康；镜像 ollama/ollama:0.13.5 已运行，bge-m3:567m 已下载（本机模型 ID 790764642607）。此前启动故障为历史排错记录。
- 两款商品各 6 个片段已使用真实本地模型向量化、保存并检索。DeepSeek 真实问答完成 7 个在线用例，包含尺寸、清洗、未知信息、追问和不存在商品；见 [首次真实问答验收](11-首次真实问答验收.md)。
- 2026-11-02：页面已实现并通过真实接口联调，详见 [页面验收](12-商品问答页面验收.md)。尚未完成后端容器镜像构建、完整 Compose 应用启动、正式效果评测与调用成本统计。当前验证的是 IDEA 服务连接容器数据库与向量模型，以及独立前端访问该服务。

Ollama 协议依据 https://docs.ollama.com/api/embed ，设置 truncate=false，校验数量、维度和有限非零数值。
DeepSeek 协议依据 https://api-docs.deepseek.com/api/create-chat-completion/ ，关闭思考并要求 JSON 输出。
镜像固定 Ollama 0.13.5、pgvector 0.8.2-pg17；Temurin 21 使用版本标签尚未锁 digest。首次启动需要网络，不能离线克隆即用。
