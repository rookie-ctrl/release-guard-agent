# GitHub / PR 接入计划

## 目标与用户流程

上传公司的发布规范或代码规范 → 在页面填 GitHub PR 链接 → 创建 PR 评审会话 → Agent 调用 GitHub 读取变更、必要代码上下文和 CI → 检索知识库 → 输出中文风险说明与代码依据 → 在同一会话继续追问。

PR 模式无需填写演示服务名和版本。服务名称可在问题中提供，用于知识库过滤。本次支持公开 PR，匿名读取或使用本机环境变量 `GITHUB_TOKEN` 提高 API 额度。

## 步骤与验收

1. **GitHub 读取基础**：严格解析 github.com PR 链接；实现有超时和响应大小限制的只读 REST 客户端，区分鉴权、限流、不可见仓库和网络故障。记录至 `docs/release-guard/09-GitHub读取基础.md`。
2. **PR 和代码工具**：读取 PR 元数据、分页文件列表、单文件 diff、指定行范围上下文、Checks 与 Commit Status；固定 head SHA / diff 基线 SHA，标明缺失和截断。记录至 `10-PR变更与上下文工具.md`。
3. **Agent 与长对话接入**：PR 信息存入会话数据库，工具从会话取仓库和 SHA；PR 模式只开放 GitHub 读取和知识检索工具，避免误用 Mock 发布数据。记录至 `11-PR会话与Agent流程.md`。
4. **页面和配置**：增加 PR 输入、模式说明、新会话操作；保留演示发布模式。提供本地 Token 配置和私有仓库拒绝说明。记录至 `12-PR页面与配置.md`。
5. **测试与联调**：测试链接校验、分页、fork 上下文、SHA 变化、二进制/大文件、CI 未运行、鉴权和限流；编译、重启并验证公开 PR API。记录至 `13-PR接入验证.md`。

每步完成后先更新对应实现文档，再执行下一步。最终文档记录实际验证结果和仍需外部配置的部分。

## 数据范围

- 本次接入 GitHub.com 的只读 REST API，只支持公开 PR；私有 PR 必须先实现应用登录和访问控制，后端主动拒绝读取。
- 读取 PR 元信息、diff、必要源文件片段和 CI 状态；不会执行仓库中的代码。
- GitHub CI 结果不等同于测试覆盖率、生产监控或数据库回滚证明。无法获取的数据标为未知。
- 本次不添加评论、合并 PR 或创建真实发布审批；后续如需这些功能需独立设计写操作确认机制。
- PR 内容属于待分析资料，不能作为指挥 Agent 的系统指令。
- PR 更新后重新创建会话，确保评审结论能对应明确的提交。

## 接口参考

- [PR 和文件列表](https://docs.github.com/en/rest/pulls/pulls)
- [仓库文件内容](https://docs.github.com/en/rest/repos/contents)
- [Checks](https://docs.github.com/en/rest/checks/runs)
- [Commit Status](https://docs.github.com/en/rest/commits/statuses)
- [比较提交与 merge base](https://docs.github.com/en/rest/commits/commits#compare-two-commits)

## 执行状态

- 步骤 1至5 的实现、测试和文档已完成：43 项测试通过，Flyway V5 迁移成功，真实公开 PR 完整评审与同会话追问通过。
- 页面启动与工具展示验证通过；完整页面评审重跑受 GitHub 匿名配额耗尽限制。已修复并验证明确的中文限流提示，等待额度恢复或本机配置 GITHUB_TOKEN 即可继续。详情见步骤 5 验证记录，不把受限页面测试记作成功。
- 私有仓库因缺少应用访问控制暂不开放；GitHub 写操作、生产数据接入和长时间压测不属于本次完成范围。
