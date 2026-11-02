# 最小 Agent 工具循环

日期：2026-11-02。使用 DeepSeek 原生 tool_calls；两个只读工具复用现有业务服务。

阶段说明：本文记录最初的两个只读工具版本。当前已扩展第三个 prepare_support_ticket 草稿工具，它会持久化草稿但不创建正式工单，见 [工单写操作控制](15-咨询工单确认与幂等验收.md)。

## 流程与边界

1. 接收原始问题与当前商品 ID，校验输入和商品是否存在。
2. 模型选择 query_product_knowledge 或 prepare_recommendation。
3. Java 校验工具名白名单、调用 ID、JSON 参数。两个工具都要求空对象，业务参数从本次请求上下文传入，模型不能改写商品范围或预算。
4. 执行业务，追加 assistant.tool_calls 和 role=tool、tool_call_id、JSON 结果，再次调用模型结束流程。
5. 返回业务服务原始结果与调用记录；不用编排模型最后生成的文本覆盖商品事实。

query_product_knowledge 复用 QaService 的 RAG 问答、原文引用和错误处理。未选商品或问题提到其他商品时追问，不跨商品检索。

prepare_recommendation 复用 RequirementService，只产生 NEED_CONFIRMATION 和待核对条件。页面点击“核对推荐条件”后，将条件带到原有推荐表单，用户确认后才调用结构化筛选接口。Agent 没有支付、退款、创建工单等写工具。

原生协议依据：[DeepSeek Tool Calls](https://api-docs.deepseek.com/guides/tool_calls/)。本实现关闭思考模式，未使用 beta strict 模式，参数合法性仍由 Java 校验。

## 接口与页面

```http
POST /api/agent
Content-Type: application/json

{"productId":"BP-A","message":"这款背包的电脑仓尺寸是多少？"}
```

返回 status、message、requestId、trace、toolCalls、maxToolCalls；问答带 qa，选购任务带 criteria。业务工具失败返回 TOOL_ERROR/errorCode，页面显示错误，不把故障当作“资料未写”。编排模型本身失败由统一错误处理返回 HTTP 错误。

商品问答页勾选“客服自动选工具”使用该接口，关闭时仍走原来的直接问答。历史不作为新请求上下文，不代表已实现多轮记忆。

## 有限执行与故障

- 每个请求最多接受 3 次工具调用尝试、最多 4 次编排模型请求；只顺序接受单个工具调用，不支持并行工具批次。
- 非法 JSON、未注册工具、非空参数可得到错误回传，模型可在剩余额度内修正。
- 已取得业务结果后，重复工具请求不再次执行业务。
- 业务上游失败不会自动重试；编排模型单次连接超时 5 秒、读取超时 20 秒。内部 RAG 与需求识别继承各自已有 HTTP 超时。
- 尚无整个 Agent 请求的统一墙钟截止时间；工具调用次数上限不等同于总耗时上限。没有持久断点恢复。
- 编排末轮故障时本请求返回服务错误，尚未实现保留业务结果供下次恢复。
- 日志仅记请求 ID、工具名、结果码、次数和耗时，不记录密钥或完整用户问题。trace 展示工具执行事实，不展示模型推理内容。

## 验证

自动测试覆盖结果回传协议、固定业务结果不被模型编造文本覆盖、推荐确认边界、非法参数修正、调用上限、业务故障、缺商品/错商品、重复调用去重、未修正工具错误不误报为缺信息。现有自动测试合计 44 条通过，5 条数据库集成测试按开关跳过；前端类型检查与构建通过。

IDEA 服务重启后已完成真实验收：商品问答、推荐条件准备、未选商品、问题商品与所选商品不一致四例均通过，均实际调用一次业务工具。问答引用仅来自 BP-A，推荐准备返回150元条件，没有绕过确认执行筛选。

浏览器联调通过：开启“客服自动选工具”，尺寸问题展示业务工具记录与引用；输入150元推荐请求，点击“核对推荐条件”后预算自动带入150，用户确认后仅得到BP-B。该页面无未捕获脚本异常。本机截图保存在 .runtime/agent-knowledge.png 和 .runtime/agent-recommendation.png，公开演示截图为 docs/images/agent-loop.png。

这些验证只覆盖固定问题，不代表模型工具选择准确率或整体 Agent 质量。正常成功路径通常增加两次编排模型请求，业务服务还会调用自身模型，不能将工具调用次数误认为总模型调用次数；成本分析尚未实现。

真实模型与页面验证需启动包含新接口的 DemoApplication，根目录执行：

```powershell
.\scripts\check-agent.ps1
```

该脚本产生真实模型调用费用，检查商品问答、选购条件准备、未选商品和商品范围不一致四例。结果保存到被 Git 忽略的 .runtime/agent-results.json。实际验收状态以该文件和当前交付记录为准，不能用模拟测试证明真实工具选择效果。

## 阅读顺序与复盘

ToolCallingClient → DeepSeekToolCallingClient → AgentService → QaService / RequirementService → AgentController → App.tsx。

一道题：模型返回了 tool_calls，是模型已经执行了工具，还是应用还要做什么？
