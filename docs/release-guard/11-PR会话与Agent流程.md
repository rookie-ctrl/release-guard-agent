# 步骤 3：PR 会话与 Agent 流程

创建会话可传 pullRequestUrl。后端先验证真实 PR，再把仓库、head、目标分支和 merge base SHA 保存到 t_agent_session.pull_request_context_json（Flyway V5）。

GitHubAgentTool 根据 runId 找到会话，自动注入固定 PR 快照。模型参数只包含文件路径、页码、行号或 HEAD/BASE，不能指定其他仓库或任意提交。工具复用原来的参数校验、超时、幂等审计、持久化和运行指标。

ToolRegistry 按模式提供白名单：PR 会话只开放五个 github 工具与 searchKnowledge；演示发布会话保留原 Mock 工具。Runner 执行时再次验证白名单。

PR 提示词无需服务名或版本，要求先读取元信息、文件和 CI，再按风险读取 diff、上下文和规范。未读取内容、缺失 CI、截断片段必须明确披露，禁止把 Mock 或未知生产数据写成真实依据。默认中文。

每次工具结果作为 TOOL 历史记录存入会话；后续追问能看到证据，摘要保留文件、行号、提交和待查内容。PR 身份独立存储，不会因摘要压缩丢失。超长工具结果使用合法 JSON 包装片段及 truncated 标志。

PR 审查涉及更多工具，执行预算调整为最多12轮、20次工具调用和180秒。仍有每个工具的独立时限；GitHub API 错误不做盲目自动重试。

下一步增加页面的 PR 入口和配置说明。
