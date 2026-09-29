# 步骤 1：GitHub 读取基础

`PullRequestReference` 将 HTTPS github.com PR 链接规范化为仓库与 PR 编号。拒绝伪造域名、用户信息、其他协议和非法路径；支持 PR 的 files/commits 链接。

`GitHubClient` 只构造固定 api.github.com 的 GET 请求。Token 从环境变量读取，只作为 Authorization Header 发送，不保存至数据库或前端；客户端不自动跟随重定向。

使用 JDK HttpClient，单请求总等待上限 5 秒，响应订阅器限制 2 MiB，超出时取消读取。401、403、404、429 和网络问题有中文错误，不回显上游原始错误正文。分页只读取 Link 的 next 标志，下一页由本机构造路径。

`rag.github` 配置包含 token、api-version、timeout-seconds、max-response-bytes。Token 可空，公开资源可以匿名读取。

验证安排：后续使用本机 HTTP 测试服务器验证 Header、超时、响应大小和错误映射。下一步实现 PR 快照、文件分页、diff、代码片段和 CI 查询。
