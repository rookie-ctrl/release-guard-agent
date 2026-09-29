# 发布评审演示资料

建议启动应用后，在左侧依次上传：

1. `发布规范.md`，类型选“发布规范”，服务名留空。
2. `历史故障复盘-order-service.md`，类型选“历史事故”，服务名填 `order-service`。

如果需要用 HTTP 上传：

```powershell
curl.exe -F "file=@docs/demo/发布规范.md" -F "documentType=RELEASE_POLICY" http://localhost:8080/api/documents
curl.exe -F "file=@docs/demo/历史故障复盘-order-service.md" -F "documentType=INCIDENT" -F "serviceName=order-service" http://localhost:8080/api/documents
```

等待文档状态变为 `SUCCESS`，然后在右侧提问：

> 帮我评审 order-service v2.3.7 能不能发布。

Agent 应查询演示版本、测试报告、数据库变更、服务依赖、监控，再检索发布规则和历史事故。若提出创建审批单，前端会显示确认/拒绝按钮。
