# ReleaseGuard 面试复习总结：第 79—88 题

整理日期：2026-10-11  
复习进度：第 79—88 题已讨论，下一题为第 89 题。  
整理依据：本次讨论与已核对的 ReleaseGuard 本地代码。  
原始题库：[Java 后端面试题：200 题（仅问题）](D:/workspace/project/release-guard-agent/docs/Java后端面试题_200题_仅问题.md)。

本文件按原题号对应问题和回答，保留代码补充与当前实现边界。第 86 题对应聊天中编号写为“6”的工具白名单问题。

## 79. 请介绍 ReleaseGuard 的完整业务流程，从创建 PR 审查会话到用户看到最终结论。

### 面试回答

ReleaseGuard 是面向公开 GitHub PR 的变更风险评审系统，整体流程是：创建会话、提交审查任务、异步调度、Agent 收集证据、生成结论，再通过 SSE 展示进度和结果。

用户提供 PR 链接后，系统创建 Session，保存 PR 快照及 head、base、merge-base SHA，给后续代码读取和证据引用提供明确的版本依据。

用户提交审查问题时，系统创建 Run，将 Run、用户消息和待投递的 Outbox 事件在同一个事务中保存。Outbox 投递器通过 RabbitMQ 调度任务，消费者结合 Redis 执行锁和任务状态检查，降低重复执行风险。

AgentRunner 组织上下文和当前模式允许的工具描述，调用模型。模型提出工具调用请求，后端负责校验和执行。工具读取 PR 信息、diff、源码和 CI，并检索企业规范；知识检索采用 BM25 与 kNN 双路召回，再通过 RRF 融合结果。

执行过程中，系统保存步骤、工具审计、会话消息和 SSE 事件。前端看到任务进度；模型返回最终回答后，系统保存结果，将 Run 改为 COMPLETED，并发送最终文本和完成事件。

用户可以在同一 Session 中继续追问，通过近期消息窗口和历史摘要延续上下文。如果 PR 发生更新，当前设计要求创建新会话，避免混用不同版本的证据。

### 要记住的细节

- 创建 Session 和提交审查问题是两个动作；提交问题时才创建 Run 并异步调度。
- 当前 AgentRunner 使用非流式 ChatModel。SSE 展示的是执行事件，最终文本生成完后整段发送。
- 工具结果被截断时需要说明检查范围，不能把有限信息描述成全面审查。
- 执行锁、状态检查和结果复用用于降低重复执行风险，不能直接宣称实现了严格的“只执行一次”。

## 80. 为什么需要 Agent？与固定流程“读取 diff 后调用一次模型”相比，它解决了什么问题？

### 面试回答

引入 Agent 的主要原因是：审查所需要的信息往往无法在开始时一次确定，需要根据已有发现动态补充证据。

diff 展示了修改内容，但不一定包含调用方、事务边界、配置、测试结果和企业规范。只读取 diff 后调用一次模型，可能因为缺少上下文而漏报或误判。

Agent 通过“分析已有信息、决定补充什么、调用工具、根据结果继续分析”的闭环，解决三个问题：按需补充源码上下文、组合代码与 CI 及规范证据、控制输入模型的上下文规模。

例如，PR 修改库存扣减方法，模型可能继续读取调用方，判断事务边界及失败处理，而不需要一开始就加载整个仓库。

固定流程也能实现这些能力。如果任务步骤稳定、信息完整，固定流程更简单、更容易测试。我选择 Agent，是因为不同 PR 的信息需求和追问路径差异较大。模型负责提出下一步信息需求，后端仍然控制是否允许执行以及执行限制。

### 要记住的细节

- Agent 的价值是动态收集证据，不是天然保证结论正确。
- 是否提高审查质量，需要用具体案例和评估数据验证。
- 工具权限、超时、调用次数和任务状态仍由后端控制。

## 81. Session、Run、Step 分别表示什么？它们之间是什么关系，为什么需要分开保存？

### 面试回答

Session 是会话层，承载围绕某个 PR 的持续讨论，保存 PR 上下文、对话历史和摘要。

Run 是执行层，表示一次审查或追问任务，保存本次执行的状态、结果、错误和耗时。同一个 Session 可以产生多次 Run。

Step 是步骤层，记录一次 Run 内部的具体执行过程，用于查看步骤顺序、输入输出、状态和耗时。当前执行器会保存工具调用、人工确认、最终回答和错误等步骤。

关系是：一个 Session 对应多个 Run，一个 Run 对应多个 Step。

分开保存，是因为会话和执行的生命周期不同；一次任务结束或失败，不代表会话结束。同时，Run 用于管理异步任务，Step 用于追踪进度和定位问题，便于审计并为恢复提供依据。

### 补充：保存新消息时，Session ID 和 Run ID 相同，为什么不覆盖？

AgentMessage 有独立的自增主键：

```java
@Id
@GeneratedValue(strategy = GenerationType.IDENTITY)
private Long id;

private String sessionId;
private String runId;
```

新建 `AgentMessage` 时，没有设置主键 `id`，其值为 null。保存时会生成新的自增主键。相同的 sessionId 和 runId 只表示归属相同，不表示消息主键相同。

| 消息主键 id | sessionId | runId | 内容 |
|---|---|---|---|
| 101 | S1 | R1 | 用户的问题 |
| 102 | S1 | R1 | githubDiff 的结果 |
| 103 | S1 | R1 | searchKnowledge 的结果 |

查询出已有消息并修改保存，才会更新对应记录。另一个需要区分的概念是：保存的 Step 数量不等于模型推理轮数，一轮模型响应可能产生多个工具调用步骤。

## 82. 一次 Run 有哪些状态？正常完成、失败、取消和超时分别如何转换？

### 面试回答

项目定义了七种 Run 状态：

| 状态 | 含义 | 当前情况 |
|---|---|---|
| CREATED | 任务已创建，等待调度 | 已使用 |
| RUNNING | 正在执行 | 已使用 |
| WAITING_USER_INPUT | 等待用户补充信息 | 预留 |
| WAITING_CONFIRMATION | 等待高风险操作确认 | 已使用 |
| COMPLETED | 执行完成，最终回答已保存 | 已使用 |
| FAILED | 执行失败，包括异常、超过限制或超时 | 已使用 |
| CANCELLED | 任务已取消 | 已定义，完整取消流程未实现 |

正常流程是 CREATED → RUNNING → COMPLETED。消费者开始处理时进入 RUNNING，模型不再提出工具调用请求时，系统进入最终回答处理并完成任务。

高风险工具的流程是 RUNNING → WAITING_CONFIRMATION → RUNNING。用户同意后执行对应工具；拒绝则跳过该操作并记录拒绝结果。两种情况都会重新调度同一个 Run，让模型继续分析，拒绝某个操作不等于取消整个任务。

执行异常、推理轮数超限、工具调用次数超限或检测到超时，会进入 FAILED，保存错误信息和错误 Step，发布失败事件并结束事件流。

当前没有独立的 TIMED_OUT 状态，超时归入 FAILED。CANCELLED 已定义，执行入口会跳过取消状态的任务，但还没有取消接口及执行中取消检查。

### 要记住的细节

- 总时限在每轮推理开始前检查，不能保证截止时刻立即打断正在进行的模型调用。
- COMPLETED、FAILED、CANCELLED 表示这次执行已经结束；后续追问创建新的 Run。
- 如果补齐取消，需要在执行关键位置检查取消信号，并用带旧状态条件的更新，避免取消状态被完成或失败覆盖。这是后续设计，当前尚未实现。

## 83. LangChain4j 在项目中负责哪些事情？哪些执行逻辑由你自己的后端代码控制？

### 面试回答

LangChain4j 主要负责模型接入、消息与工具调用的数据结构，以及向量模型调用。

通过 ChatModel 和 StreamingChatModel 等统一接口调用模型，获取回答、工具调用请求和响应中的 TokenUsage。项目自己的 ChatModelManager 和策略类负责选择模型提供方。

通过 SystemMessage、UserMessage、AiMessage 表达消息，通过 ToolSpecification 描述工具及参数，用 ToolExecutionRequest 接收调用请求，再用 ToolExecutionResultMessage 将结果交回模型。

知识库部分使用 EmbeddingModel 生成向量，但 Elasticsearch 检索、BM25 与 kNN 召回以及 RRF 融合由业务代码实现。

后端自己的代码控制 Agent 循环、工具白名单与参数校验、风险门禁、超时和重试、结果复用、任务状态、历史摘要及持久化、Outbox 和 RabbitMQ 调度、Redis 执行锁，以及 SSE 事件发布。

因此，LangChain4j 提供模型交互能力，执行流程由自己的 AgentRunner 组织。

### 补充：非流式 ChatModel 和 SSE 有什么区别？

SSE 是后端持续向前端发送消息的通道；模型流式调用是后端在生成过程中陆续收到文字片段。两者不是同一个概念。

当前实现的过程是：前端实时看到“开始审查、正在调用工具、工具已返回”等进度；模型生成完整回答后，后端再把整段文本发送给前端。

```text
当前实现：实时执行进度 → 等模型生成完整回答 → 整段发送最终文本
文字流式：模型生成一段 → 立即转发一段 → 前端回答逐渐增长
```

代码中的最终文本事件虽然叫 `token`，实际携带的是完整的 `finalAnswer`，不能因此描述为逐 Token 输出。

## 84. 请描述一次完整的 Tool Calling 流程，模型与后端分别在哪些时刻参与。

### 面试回答

后端先组织系统提示、用户问题和历史上下文，并提供当前模式允许的工具描述。

模型根据这些信息，返回最终回答或工具调用请求。如果需要读取 diff，会返回工具名及参数，模型本身没有直接执行工具。

后端收到请求后，检查模式规则、工具是否注册、调用次数和风险等级。普通调用进入执行服务校验参数；高风险调用先等待人工确认。

执行服务检查可复用结果，否则调用具体工具，并控制等待时限及按配置进行的重试，保存执行结果和耗时。

后端将工具结果封装成 ToolExecutionResultMessage，加入当前上下文，再调用模型。模型可以继续请求其他工具，或者返回最终回答。后端负责循环、限制和状态转换。

当前一轮模型响应可以包含多个工具调用请求，后端依次处理；模型不再返回工具请求时进入最终回答处理。

### 补充：工具结果处理代码逐句说明

```java
ToolResult result = toolExecutionService.execute(
        runId, request.name(), request.arguments());
String resultJson = objectMapper.writeValueAsString(result.value());
if (resultJson.length() > MAX_TOOL_RESULT_CHARS) {
    resultJson = objectMapper.writeValueAsString(Map.of(
            "truncated", true,
            "reason", "工具结果超过上下文限制，只显示片段，不能视为完整检查",
            "preview", resultJson.substring(0, 5000)));
}
persistStep(run, AgentStepType.TOOL_CALL, ExecutionStatus.SUCCESS,
        request.arguments(), resultJson, result.durationMs());
eventService.publish(runId, "toolResult", Map.of(
        "tool", request.name(),
        "result", result.value(),
        "durationMs", result.durationMs(),
        "replayed", result.replayed()));
messages.add(ToolExecutionResultMessage.from(request, resultJson));
AgentMessage observation = new AgentMessage();
observation.setSessionId(run.getSessionId());
observation.setRunId(runId);
observation.setRole(MessageRole.TOOL);
observation.setContent(request.name() + " " + request.arguments() + "\n" + resultJson);
messageRepository.save(observation);
```

| 语句或字段 | 作用 |
|---|---|
| `execute(runId, name, arguments)` | 按工具名和参数进行校验及执行；也可能直接复用已有成功结果 |
| `result.value()` | 工具实际返回的数据 |
| `result.invocationId()` | 工具调用记录 ID |
| `result.durationMs()` | 记录的执行耗时；复用时来自原有记录 |
| `result.replayed()` | 是否复用了已有成功结果 |
| `writeValueAsString(...)` | 将 Java 对象转换成 JSON 字符串 |
| `resultJson.length() > MAX_TOOL_RESULT_CHARS` | 判断输出是否超过字符串长度限制；当前阈值为 12000，不是 Token 数量 |
| `Map.of(...)` | 构造带截断标记、原因和预览的结果对象 |
| `"truncated", true` | 明确说明内容被截断 |
| `"reason", ...` | 提醒模型不能声称已经完整检查 |
| `substring(0, 5000)` | 保留原始 JSON 文本的前 5000 个字符作为预览 |
| `persistStep(...)` | 保存工具调用步骤、输入输出和耗时，并更新 Run 的步骤序号 |
| `eventService.publish(...)` | 保存工具结果事件，并尝试推送给前端 |
| `messages.add(...)` | 将与原调用对应的结果消息加入内存列表，供下一轮模型调用使用 |
| `new AgentMessage()` | 创建一条新的数据库会话消息，此时主键 id 为 null |
| `setSessionId(...)` | 关联所属会话 |
| `setRunId(...)` | 关联所属执行任务 |
| `setRole(TOOL)` | 标记消息来自工具 |
| `setContent(...)` | 保存“工具名 参数JSON + 换行 + 结果JSON” |
| `messageRepository.save(...)` | 保存会话消息，供后续查询、上下文重建和摘要使用 |

要区分四种用途：

| 位置 | 用途 | 使用的数据 |
|---|---|---|
| Step | 执行追踪 | 可能截断的 resultJson |
| SSE 事件 | 前端进度与工具结果 | 原始 result.value() |
| 内存 messages | 下一轮模型输入 | 可能截断的 resultJson |
| AgentMessage | 数据库会话历史 | 工具名、参数和可能截断的 resultJson |

截断只改变 resultJson，没有改变 result.value()。当前 SSE 事件仍然携带原始结果。preview 是文本片段，不保证是完整的原始 JSON，但包裹它的外层 JSON 是合法序列化的结果。

SUCCESS 表示这次工具步骤成功，不表示整个 Run 已经完成。代码按顺序执行，任一步抛出异常时，后续尚未执行的语句不会继续，异常进入外层失败处理。

### 补充：eventService.publish 后如何到达前端？

前端先提交问题，从普通 HTTP 响应取得 runId，再建立另一个 SSE 请求：

```javascript
const source = new EventSource(
    '/api/agent/runs/' + encodeURIComponent(runId) + '/events'
);
```

后端 Controller 返回 `Flux<ServerSentEvent<String>>`，Spring 将流中的事件写入 SSE 连接。

publish 的核心过程是：

```java
event = eventRepository.saveAndFlush(event);
stream.tryEmitNext(toSse(event));
return event;
```

先保存事件，再通过 tryEmitNext 放入当前 Run 对应的内存事件流。真正触发流产生新数据的是 tryEmitNext；return event 只是返回给调用 publish 的 Java 代码，不是直接返回给浏览器。

```text
工具执行完成
→ publish 保存事件
→ tryEmitNext 放入事件流
→ Controller 返回的 Flux 产生事件
→ Spring 写入 SSE 连接
→ 浏览器 EventSource 接收
→ toolResult 监听函数更新页面
```

前端监听 toolResult，解析 event.data，再用工具名称、耗时和结果更新页面。订阅接口还会先读取历史事件，处理事件先于前端连接产生的情况。

当前实时事件流保存在后端实例的内存中；这段直接推送链路依赖发布和订阅使用同一实例内的事件流，数据库另外承担历史补读。

## 85. 模型生成工具名和参数以后，后端如何校验？如果工具不存在或参数不合法，会怎样？

### 面试回答

后端首先检查工具是否允许在当前模式使用，再通过 ToolRegistry 查找已注册实现。工具不存在或模式不允许，会抛出 ToolExecutionException。

风险等级来自后端工具定义。禁止工具拒绝执行，高风险工具进入人工确认，真正执行时仍然要校验参数。

当前 parseAndValidate 检查：能否解析 JSON、结果是否为 JSON 对象、必填字段是否存在且非空，以及是否包含不允许的额外字段。校验在实际工具执行前发生，不合法参数不会触发外部接口调用。

错误进入 AgentRunner 的统一处理，将 Run 改为 FAILED，保存错误信息和错误 Step，并发送 runFailed 事件。

### 要记住的细节

- 当前不会把参数错误交回模型进行自动修正，而是直接失败。
- 工具执行重试发生在参数校验通过之后，与参数错误修复不是同一件事。
- 当前没有完整执行 JSON Schema 的类型、长度和范围约束，不能描述成所有参数都已经严格校验。

## 86. 工具白名单如何维护？仅在提示词中声明允许使用的工具，是否足够？

### 面试回答

工具白名单在后端维护，分为已注册工具集合和当前模式规则两层。

工具实现 AgentTool 并作为 Spring Bean 注册，ToolRegistry 按工具名称建立不可修改的 Map。模型请求的名称必须能查找到已注册实现，否则拒绝执行。

allowedInMode 控制当前模式：GitHub PR 模式允许名称以 github 开头的工具，以及 searchKnowledge；演示模式排除 GitHub 工具。

这套规则在两个时刻使用：调用模型前只提供当前允许的工具描述；模型返回请求后，后端再次检查，再进行风险等级和参数校验。

新增工具时，要注册实现、定义名称及参数和风险等级，并检查模式规则。当前规则依赖命名约定，工具更多以后可以改成显式工具名集合或模式元数据，这是后续改进方向。

**仅在提示词中声明白名单不够，因为提示词不能保证模型一定遵守。模型可能生成不存在的工具名，也可能受到 PR 内容或文档中的指令干扰。如果后端不校验，就无法真正限制执行范围。**

是否执行工具，最终由后端规则决定。

## 87. 风险分级如何定义？哪些操作需要人工确认，确认结果如何与具体操作绑定？

### 面试回答

工具执行风险分为 READ_ONLY、LOW_RISK、HIGH_RISK、FORBIDDEN，等级由后端 ToolDefinition 配置，不由模型决定。

| 等级 | 执行策略与当前情况 |
|---|---|
| READ_ONLY | 读取 PR、diff、源码、CI、规范等，通过校验后执行 |
| LOW_RISK | 允许自动执行的低风险操作；当前预留，没有注册具体工具 |
| HIGH_RISK | 必须人工确认；当前例子是演示模式创建发布审批单 createReleaseApproval |
| FORBIDDEN | 禁止执行，即使确认也不放行；当前没有注册具体工具，但执行服务有拒绝逻辑 |

遇到 HIGH_RISK 调用时，系统创建 ConfirmationRequest，保存 confirmationId、sessionId、runId、toolName、argumentsJson、tokenHash、状态和过期时间，再把 Run 改为 WAITING_CONFIRMATION，通过 SSE 展示待确认操作。当前有效期为十分钟。

前端提交 confirmationId、token 和 approved，不重新提交工具名和参数。后端在事务中对确认记录加悲观写锁，检查状态仍为 PENDING、未过期、令牌哈希匹配，以及 Run 正在等待确认。

同意后，从数据库记录中取出原工具名和参数执行，而不是接受前端替换参数。执行成功后确认状态变为 EXECUTED。拒绝则不执行，状态变为 REJECTED，并记录拒绝信息。

两种情况都会恢复同一个 Run 并通过 Outbox 重新调度。确认仅授权这一次具体操作，不会放开整个会话的高风险权限。

### 要记住的细节

- 审批参数中的 riskLevel 是业务评审风险，不能改变工具执行风险；模型写成 LOW 也不能绕过 HIGH_RISK 门禁。
- GitHub PR 模式当前是只读工具和规范检索，人工确认主要用于演示审批单，不能宣称已经接入真实生产发布操作。
- ConfirmationRequest 保存令牌哈希，原始令牌通过确认事件发给前端；不要把这一点扩大描述为所有持久化数据都只含哈希。
- 当前 decide 尚未检查登录用户是否拥有对应 Session。令牌校验不能替代完整的用户权限校验。

绑定关系直接体现在：

```java
toolExecutionService.executeConfirmed(
        run.getId(),
        confirmation.getToolName(),
        confirmation.getArgumentsJson()
);
```

## 88. 如果模型反复调用同一个工具、不断产生新计划，系统怎样终止？

### 面试回答

我在后端设置推理轮数、累计工具调用次数和执行时长限制，不只依赖提示词要求模型停止。

当前 AgentRunner 一次连续执行的主循环最多十二轮。如果每轮都提出工具请求，始终不返回最终回答，循环结束后抛出异常。

同一个 Run 累计最多二十次工具调用请求。一轮模型响应可能包含多个调用，次数保存在 Run 中，人工确认恢复执行后继续累计。

从本次 execute 开始计时，每轮推理开始前检查是否超过一百八十秒。工具调用还有独立的等待时限。

重复请求相同工具和参数时，可以通过幂等键复用成功结果，减少实际外部调用。但复用仍然计入工具调用次数，不能绕过上限。

超过限制或检测到超时后，将 Run 改为 FAILED，保存错误原因和错误 Step，发布 runFailed 并结束事件流，不会把未完成审查标记成正常完成。

### 要记住的细节

| 限制 | 当前配置 | 作用范围 |
|---|---|---|
| 推理轮数 | 12 | 每次 execute 的主循环；确认后重新进入会重置 |
| 工具请求次数 | 20 | 同一个 Run 累计，包含复用成功结果的请求 |
| 执行时间检查 | 180 秒 | 从本次 execute 开始计时，在每轮开始前检查；确认后重新计时 |

- 当前没有独立的“不断生成计划”循环；不含工具请求的文本响应会进入最终回答处理。
- 当前没有专项的连续重复调用检测，靠现有上限终止；识别相同请求且没有新增信息、提前停止，是后续改进方向。
- 一百八十秒不是跨暂停和恢复的整个 Run 的统一 deadline，也不是在截止瞬间强制中断模型调用。
- 推理轮数、工具请求次数和数据库保存的 Step 数量是不同指标。

## 复习时必须保持准确的实现边界

1. SSE 实时显示执行进度，不等于模型逐 Token 输出；当前最终文本整段发送。
2. CANCELLED 和 WAITING_USER_INPUT 目前主要是预留状态，不能说完整流程已实现。
3. 参数检查覆盖 JSON 对象、必填项和额外字段，尚不是完整 Schema 验证。
4. 非法工具或参数当前直接导致 Run 失败，没有模型自动修正循环。
5. PR 模式工具只读；人工确认示例属于演示模式的审批单创建。
6. 确认绑定具体工具和参数，但当前还缺少确认用户与 Session 归属的授权检查。
7. 幂等结果复用减少重复外部调用，不代表模型不会重复请求，也不等于严格只执行一次。
8. 工具调用次数在 Run 上累计；推理轮数和计时按 execute 执行段计算。

## 代码对应位置

| 内容 | 文件 |
|---|---|
| 会话创建、Run 创建与 Outbox | [AgentSessionService](D:/workspace/project/release-guard-agent/src/main/java/com/interview/rag/agent/service/AgentSessionService.java) |
| 主循环、工具结果处理、状态流转 | [AgentRunner](D:/workspace/project/release-guard-agent/src/main/java/com/interview/rag/agent/service/AgentRunner.java) |
| Run 状态枚举 | [AgentStatus](D:/workspace/project/release-guard-agent/src/main/java/com/interview/rag/agent/domain/AgentStatus.java) |
| 工具注册与模式规则 | [ToolRegistry](D:/workspace/project/release-guard-agent/src/main/java/com/interview/rag/agent/tool/ToolRegistry.java) |
| 参数校验、结果复用、超时与重试 | [ToolExecutionService](D:/workspace/project/release-guard-agent/src/main/java/com/interview/rag/agent/tool/ToolExecutionService.java) |
| 工具结果结构 | [ToolResult](D:/workspace/project/release-guard-agent/src/main/java/com/interview/rag/agent/tool/ToolResult.java) |
| 工具风险等级 | [ToolRiskLevel](D:/workspace/project/release-guard-agent/src/main/java/com/interview/rag/agent/tool/ToolRiskLevel.java) |
| 演示工具及审批单工具定义 | [AgentToolsConfiguration](D:/workspace/project/release-guard-agent/src/main/java/com/interview/rag/agent/tool/AgentToolsConfiguration.java) |
| 确认记录结构 | [ConfirmationRequest](D:/workspace/project/release-guard-agent/src/main/java/com/interview/rag/agent/domain/ConfirmationRequest.java) |
| 确认决定与具体操作执行 | [AgentConfirmationService](D:/workspace/project/release-guard-agent/src/main/java/com/interview/rag/agent/service/AgentConfirmationService.java) |
| 会话消息独立主键 | [AgentMessage](D:/workspace/project/release-guard-agent/src/main/java/com/interview/rag/agent/domain/AgentMessage.java) |
| 事件持久化及 SSE 发布 | [AgentEventService](D:/workspace/project/release-guard-agent/src/main/java/com/interview/rag/agent/service/AgentEventService.java) |
| SSE 订阅接口 | [AgentController](D:/workspace/project/release-guard-agent/src/main/java/com/interview/rag/agent/controller/AgentController.java) |
| 前端 EventSource 订阅与事件处理 | [app.js](D:/workspace/project/release-guard-agent/src/main/resources/static/app.js) |
| 当前执行上限配置 | [application.yml](D:/workspace/project/release-guard-agent/src/main/resources/application.yml) |

本次复习停在第 88 题；下次从第 89 题“推理轮数、工具调用次数、工具等待时长和整个 Run 的执行时长，分别在哪一层限制？”继续。
