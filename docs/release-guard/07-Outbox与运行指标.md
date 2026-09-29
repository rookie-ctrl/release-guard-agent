# Phase 7：事务 Outbox 与运行指标

## Outbox 解决的故障窗口

如果先提交 Agent Run，再直接发送 Rabbit 消息，数据库提交成功而 Rabbit 暂时不可用时，Run 会停在 RUNNING 却永远没人处理。若先发消息再提交数据库，消费者又可能抢在事务提交前看不到 Run。

## 实现

- `AgentSessionService` 在创建 Run、用户消息的同一事务里写入 `t_agent_outbox`。
- 审批确认/拒绝也在业务状态更新事务里写入继续执行事件。
- `AgentOutboxPublisher` 定时扫描 PENDING 记录，发送到 Rabbit 后标记 PUBLISHED。
- 发送失败采用指数退避，达到 10 次转为 DEAD，保留最后错误供运维检查。
- 发布后状态更新失败可能导致重复消息，因此消费者仍按至少一次投递设计，依赖 Run 锁与工具幂等。

## 指标

Actuator/Micrometer 记录 Agent Run 的开始、完成、失败、等待确认数量，以及各工具调用次数和耗时。工具名是有限注册集合，可用作低基数指标标签。Prometheus Registry 暴露抓取端点。

## 面试话术

> 我用事务 Outbox 解决数据库与 Rabbit 双写不一致：业务数据和待发事件同事务提交，发布器负责重试发送。它提供至少一次而非恰好一次，因此消费者仍必须幂等。指标可以区分模型执行失败、工具慢和审批等待，便于线上定位。
