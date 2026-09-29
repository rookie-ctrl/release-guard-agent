# Phase 6：RabbitMQ 任务调度与恢复

## 设计目标

Agent 执行可能持续数秒到数十秒，不能让 HTTP 请求线程承担完整模型和工具调用。Run 和 Outbox 事件先在同一 MySQL 事务提交，后台发布器再将 `runId` 投递到持久队列；Rabbit 消费者完成执行后才 ACK。

## 实现

- 新增 durable 队列 `release.agent.run` 和死信队列 `release.agent.run.dlq`。
- REST 消息提交只负责保存 Run/Message，然后投递轻量 `AgentRunJob(runId)`。
- `t_agent_outbox` 保存待投递事件；发送成功标记 PUBLISHED，失败指数退避，10 次后转为 DEAD 供人工检查。
- `AgentRunConsumer` 同步处理单个 Run，异常消息不无限 requeue，进入 DLQ。
- Redis `SET NX EX` 创建每个 Run 的两分钟租约锁，Lua compare-and-delete 保证只释放自己的锁。
- 重复投递若 Run 已终态或等待确认则直接跳过；仍为 RUNNING 的 Run 可重新读取会话、按工具幂等记录复用已完成的只读结果。
- 审批确认或拒绝先持久化决定与观察消息，再重新投递 Run，继续生成评审结论。

## 崩溃恢复边界

Run 与工具步骤有持久化记录。消费者在只读调用后崩溃时，重复执行可由工具幂等键复用成功结果。外部写操作存在“外部系统已成功但本地确认事务未提交”的分布式不确定窗口；生产适配器必须把幂等键透传给审批平台，并对 RUNNING 状态调用设置人工核对/补偿流程。当前演示 Gateway 是本地 Mock。

Rabbit 发布成功但 Outbox 状态更新失败时会发生重复投递；消费者锁和工具幂等共同覆盖至少一次投递语义。

## ACK 与死信

正常完成、进入等待确认、或由 Agent 捕获并标记 FAILED 都视为已处理并 ACK。消费者边界无法处理的异常会拒绝消息并进入 DLQ，避免毒消息反复阻塞队列。

## 面试话术

> HTTP 只创建持久化 Run 并投递消息，Rabbit 消费者执行后确认消息。Redis 租约锁避免重复并行执行，数据库步骤和工具幂等记录提供恢复依据；外部写操作还需要下游幂等键，因为 MySQL 和 DevOps 平台之间没有分布式事务。
