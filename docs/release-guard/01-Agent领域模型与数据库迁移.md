# Phase 1：Agent 领域模型与数据库迁移

## 要解决的问题

原 RAG 只保存问答统计，无法表达一个长生命周期的发布评审：多轮用户消息、一次评审运行、模型和工具执行步骤、审批等待、断线事件恢复都没有对应的持久化边界。

## 领域拆分

- `AgentSession`：跨多轮对话的会话，使用 `@Version` 乐观锁保护并发更新，并存储摘要。
- `AgentRun`：每次用户发言触发的一轮执行，独立跟踪状态、耗时、Token 和最终答案。
- `AgentMessage`：长期对话消息；后续记忆层按最近消息窗口读取。
- `AgentStep`：一次 Agent Run 中的规划、工具调用、观察、确认和最终回答步骤。
- `ToolInvocation`：工具输入、输出、风险等级、结果状态、重试和幂等键审计。
- `ConfirmationRequest`：待人工确认的高风险调用，保存参数快照、令牌摘要与过期时间。
- `ReleaseReview`：结构化保存服务、版本、风险及评审上下文，避免模型独自承担事实记忆。
- `AgentEvent`：SSE 事件持久化，序号唯一约束用于断线重放。

## 数据库策略

引入 Flyway `V1__agent_domain.sql`，新库会创建 RAG 原表和 Agent 表；现有库由 `baseline-on-migrate` 建立版本基线，迁移使用 `CREATE TABLE IF NOT EXISTS`，再由 Hibernate `validate` 检查实体结构。这样迁移脚本也能在已有开发库安全执行。

## 关键取舍

Session、Run 和 Step 分开建模。若只存一条“聊天记录”，无法区分一次评审失败与整个会话失败，也无法在重启后从具体工具步骤恢复。事件单独持久化，是为了让 SSE 断线重放不依赖进程内内存。

## 验收方式

- 空 MySQL 库执行 Flyway 后，Hibernate 验证通过。
- 现有开发库可 baseline 并补齐 Agent 表，不覆盖已有 RAG 数据。
- 唯一约束阻止同一 Run 步骤序号、事件序号和工具幂等键重复写入。
