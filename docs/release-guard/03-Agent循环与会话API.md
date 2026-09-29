# Phase 3：Agent 循环与会话 API

## Agent 执行循环

`AgentRunner` 使用 LangChain4j `ChatModel` 发起带 `ToolSpecification` 的请求。模型返回普通文本时结束本轮；返回 Tool Execution Request 时由 `ToolRegistry` 找到白名单工具，执行后把 `ToolExecutionResultMessage` 加回上下文，再请求模型继续判断。

每轮执行受配置限制：最多 8 次模型决策、最多 6 次工具调用、运行时限 30 秒。超出限制会把 Run 标记为失败，并发布 `runFailed` 事件。

## 会话 API

- `POST /api/agent/sessions` 创建会话。
- `POST /api/agent/sessions/{sessionId}/messages` 提交新一轮用户消息并返回 `runId`。
- `GET /api/agent/runs/{runId}/events` 订阅 SSE 事件，可传 `Last-Event-ID` 恢复遗漏事件。
- `POST /api/agent/confirmations/{confirmationId}/decision` 对高风险审批作出决定。

消息采用 POST JSON，避免 EventSource GET 查询串的长度和鉴权 Header 限制。Agent 在 Spring 异步线程池运行，提交事务完成后才启动任务，避免消费者抢先读取未提交的 Run。

## 事件持久化

每个事件先保存到 `t_agent_event`，再推送到 Reactor replay sink。SSE 订阅时先读取数据库历史事件，再连接实时流，并用序号过滤两段之间的重复事件。应用重启后，已终态 Run 仍可回放历史事件。

## 确认流程

Agent 触发高风险工具时创建十分钟有效的确认请求，随机明文令牌只随 SSE 返回一次，数据库只存 SHA-256。确认 API 校验令牌、到期时间和 Run 状态；确认后才执行高风险工具，拒绝则把决定作为观察结果交还 Agent。

## 长对话现状

当前先加载最近 12 条消息，超出窗口的摘要字段与 `ReleaseReview` 结构化上下文已经建模，下一阶段实现摘要更新及按服务、版本隔离。执行过程的工具调用不作为普通 assistant 文本伪装，最终答案才进入持久化对话历史。

## 面试话术

> 一个用户会话可以有多个 Run，每个 Run 又由持久化 Step 和事件组成。这样不仅能多轮追问，还能解释任务当前停在哪个工具、是否等待确认，并通过 Last-Event-ID 恢复 SSE 展示。
