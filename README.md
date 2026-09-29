# ReleaseGuard

面向微服务研发团队的发布变更评审 Agent。它会围绕一次发布持续多轮对话，查询测试、代码变更、数据库脚本、服务依赖、监控和工程知识库，汇总风险；创建发布审批单前必须由用户确认。

## 演示场景

默认演示版本为 `order-service v2.3.7`，包含测试覆盖率不足、删除数据库字段、下游服务依赖该字段、没有回滚脚本等风险。DevOps 数据由 `MockDevOpsGateway` 提供，知识依据来自 Elasticsearch 文档库。

## 架构

```text
客户端
  ├── REST：创建会话、发送消息、确认高风险操作
  └── SSE：计划、工具调用、工具结果、回答、事件恢复
          ↓
Agent Session / Run / Step
          ↓
受控 Agent Runner ── LangChain4j ChatModel + Tool Calling
          ├── Tool Registry / 参数校验 / 风险门禁 / 幂等审计
          ├── DevOps Gateway（演示使用 Mock 实现）
          └── Knowledge Search Tool（BM25 + kNN + RRF）
          ↓
MySQL：会话、执行步骤、工具审计、审批、SSE 事件
Redis：语义向量与成本预算
RabbitMQ：文档解析与 Agent Run 持久任务队列
Elasticsearch：发布规范、故障复盘、回滚手册
```

## 本地启动

1. 复制 `.env.example` 为 `.env`，填写模型 API Key。
2. 启动依赖：`docker compose up -d`。
3. 使用 JDK 17 启动：`mvn17.cmd spring-boot:run`。
4. 打开 `http://localhost:8080` 管理知识库；Agent API 当前可直接调用。

`.env` 已加入 Git 忽略规则。不要提交真实密钥。

## Agent API

创建会话：

```http
POST /api/agent/sessions
Content-Type: application/json

{"userId":"demo-user"}
```

提交消息：

```http
POST /api/agent/sessions/{sessionId}/messages
Content-Type: application/json

{"message":"帮我评审 order-service v2.3.7 能不能发布"}
```

响应返回 `runId`。订阅执行事件：

```http
GET /api/agent/runs/{runId}/events
Accept: text/event-stream
Last-Event-ID: 4
```

事件包括 `runStarted`、`plan`、`toolCall`、`toolResult`、`confirmation`、`token`、`runCompleted` 和 `runFailed`。高风险审批确认令牌通过 `confirmation` 事件一次性返回，再提交到 `/api/agent/confirmations/{confirmationId}/decision`。

## 工程能力

- 模型只能调用注册工具；执行前验证参数结构和必填字段。
- 限制 Agent 决策轮数、工具调用次数及单轮执行时长。
- 工具调用记录参数、结果、风险、状态和耗时，成功结果支持幂等复用。
- 高风险审批动作需要后端确认状态和有效令牌。
- Session、Run、Step 和 SSE Event 分层持久化，支持长对话和事件重放。
- Flyway 版本化数据库迁移；Actuator 暴露健康状态和运行指标。

## 设计记录

- [ReleaseGuard 改造总计划](docs/ReleaseGuard改造总计划.md)
- [工程基线与改造边界](docs/release-guard/00-工程基线与改造边界.md)
- [Agent 领域模型与数据库迁移](docs/release-guard/01-Agent领域模型与数据库迁移.md)
- [统一工具执行框架](docs/release-guard/02-统一工具执行框架.md)
- [Agent 循环与会话 API](docs/release-guard/03-Agent循环与会话API.md)
- [知识文档分类与过滤检索](docs/release-guard/04-知识文档分类与过滤检索.md)
- [长对话记忆与摘要压缩](docs/release-guard/05-长对话记忆与摘要压缩.md)
- [RabbitMQ 任务调度与恢复](docs/release-guard/06-RabbitMQ任务调度与恢复.md)
- [事务 Outbox 与运行指标](docs/release-guard/07-Outbox与运行指标.md)
- [验证、故障注入与压测计划](docs/release-guard/08-验证、故障注入与压测计划.md)
- RAG 底层原理记录见 `docs/` 中的 ES/kNN、RRF、Embedding 入库和 SSE 文档。

## 当前实现范围

独立项目基线、Agent 持久化模型、Mock 发布数据、工具白名单与参数校验、LangChain4j Tool Calling 循环、多轮消息摘要、分类知识检索、事务 Outbox、RabbitMQ Run 调度、Redis 执行锁、SSE 事件重放和 Micrometer 指标已落代码。演示资料与操作步骤见 `docs/demo/README.md`。外部写工具的下游幂等透传和恢复压测仍需后续完善。
