# SSE 流式输出原理 —— 学习记录

> 讨论背景:梳理问答链路时,对"大模型一个字一个字往外蹦(打字机效果)到底是怎么实现的"做的一次系统讲解。
> 本文记录:大白话比喻、协议本质、选型推演、项目代码走读、数量级估算、面试速答与话术。术语先给比喻再给名字。

## 1. 大白话:一条故意不结束的 HTTP 响应

普通 HTTP 像发短信:问一句,回一条,结束。SSE(Server-Sent Events,服务端推送事件)像**打电话:拨通后你不挂,对方一直说,说完了才挂**。

协议层 SSE 不是新协议,只是 HTTP 的一种使用约定,两件事:

1. 响应头:`Content-Type: text/event-stream`
2. body 按固定格式分帧,空行(`\n\n`)分隔一个事件:

```
event: token
data: 缓存穿透

event: done
data:
```

- `event:` 给事件命名(前端按名分发),`data:` 是载荷
- 可选 `id:`(事件序号,断点续传用)、`retry:`(重连间隔建议)

## 2. 选型推演:为什么不是轮询 / 长轮询 / WebSocket

| 方案 | 做法 | 问题 |
|---|---|---|
| 轮询 | 每秒问一次"有新 token 吗" | 20 秒的回答 = 20 个请求,平均延迟 0.5s,打字机变卡顿 |
| 长轮询 | 请求挂起等数据,拿到就重发 | 每次数据重新建请求、带游标,等于自己实现半个 SSE |
| WebSocket | 协议升级(101),双向全双工 | 能力过剩;脱离 HTTP 后 Nginx/负载均衡要特殊配置,心跳、重连全自己写 |
| **SSE** | 单向长响应 | 问答场景是"客户端问一句,服务端流式答"——**方向性决定协议** |

SSE 的杀手锏:走普通 HTTP,**鉴权 header、代理、负载均衡、HTTP/2 多路复用全部零成本复用**,浏览器 `EventSource` 内置自动重连。WebSocket 只在"客户端也要频繁推数据"(聊天室、游戏)时才值得付升级成本。

## 3. 代码走读:三层桥接

项目链路(`ChatController` → `ChatService.streamChat` → `index.html`):

```
DeepSeek HTTP 流 ──→ LangChain4j 回调线程 onPartialResponse(token)
                         │  sink.next(event("token", token))
                         ▼
                    Flux.create  ←── 桥接层
                         │
                         ▼
              Spring MVC ReactiveTypeHandler
                         │  ResponseBodyEmitter.send → AsyncContext 写字节
                         ▼
                  浏览器 EventSource 按 event 名分发
```

### 3.1 为什么是 Flux.create

- 事件源是 **push 模型**(LangChain4j 回调线程异步推),`Flux.generate` 只支持同步拉取,不适用
- `Flux.push` 不支持背压,`Flux.create` 两者兼顾,还能挂 `onCancel` 钩子
- 默认 OverflowStrategy=BUFFER(无界缓冲),**为什么不会 OOM**:生产者速率上限是 DeepSeek 输出速率(几十 token/s),单回答 token 总量有上限(几千),积压最多 KB 级——生产者的自然上限买断了缓冲需求

### 3.2 项目没有 webflux starter,为什么 Flux 能跑

pom 里只有 `spring-boot-starter-web`(Tomcat + Spring MVC),`Flux` 是 `spring-boot-starter-amqp` 传递进来的 reactor-core。能跑是因为 **Spring MVC 5.0 起支持响应式返回类型**:

- `ReactiveTypeHandler` 把 Flux 桥接成 `ResponseBodyEmitter`
- 底层走 **servlet 3.1 async**:Tomcat worker 线程在返回 Flux 后**立刻释放**,不等 20 秒的生成

数量级:默认 `maxThreads=200`,若同步等生成,200 线程 ÷ 15s/次 ≈ **13 并发 QPS 就打满**;async 之后瓶颈转移到带宽和 LLM/ES,而不是线程池。这是"长连接会不会打爆 Tomcat"的标准答案。

### 3.3 事件类型设计

`token` / `refs` / `cacheHit` / `done` / `error`:控制面(引用出处、缓存标记、结束信号)与数据面(文本增量)**分离**,前端按 `event` 名分发。对比 OpenAI 风格 `data: [DONE]` 哨兵字符串,命名事件更清晰、可扩展。

## 4. 数量级估算

- **打字机速度**:deepseek-chat 输出约 20-60 token/s,中文约 1 token ≈ 1.2-1.6 字 → 每秒 30-80 字,比人读得快,体验达标
- **事件协议开销**:每事件固定 21 bytes(`event: token\ndata: ` + `\n\n`),OpenAI 兼容流式一次回调吐 1-8 token。1000 token 回答按 2 token/片:500 事件 × 21B ≈ 10KB 开销 vs 3KB 有效载荷,放大约 4 倍。局域网无所谓;生产若在意,可攒批(每 50ms flush),但引入 flush 延迟——**逐 token 转发延迟最低,中小规模不值得攒批的复杂度**
- **首 token 延迟**:embedding 200-500ms + ES 检索 10-50ms + DeepSeek 首包 0.5-1s ≈ 1-2s 空白。项目在检索完成后**立刻发 `refs` 事件**,前端先显示引用条("正在查资料")而不是干等,顺手解决了空白期体验

## 5. 三个真实问题与取舍

### 5.1 断连后,后端还在继续生成(浪费 token)

前端关页面/断网时 sink 已 cancel,`sink.next` 变 no-op——但 LangChain4j 的生成**根本没停**,DeepSeek 继续吐,费用照收。1000 token 回答在第 100 token 断连,浪费 90% output。

翻过 LangChain4j 1.19 源码:`StreamingChatModel.chat()` 返回 `void`,**高层接口拿不到取消句柄**;`OpenAiStreamingResponseBuilder` 只是解析器。诚实结论:这个版本做不了干净取消,两条路:

1. 下沉到 `DefaultOpenAiClient` 自己管理流式订阅,持有取消句柄(复杂度上升)
2. 接受浪费,靠两层兜底:**每日 token 预算**(超限拒绝,见 [[Redis语义缓存-设计反思]] 的成本控制语境)+ **断线重发问题命中语义缓存**(0 token)

面试时说得出"我知道它还在生成,为什么,怎么修"比假装没有这个问题强。

### 5.2 前端把自动重连砍了

`index.html` 在 `error` 事件里直接 `es.close()`——EventSource 本来有 ~3s 自动重连,这里断了就终止。且服务端没发 `id:`,就算重连也只能从头重新生成(LLM 生成不可重放,断点续传意义有限)。

改进方向:**error 时自动重发一次问题** + 语义缓存 = 免费重连:重问 → embedding 命中缓存 → 直接拿回完整答案,0 token。这是项目已有组件组合出来的设计闭环。

### 5.3 部署坑:Nginx 缓冲与 EventSource 的 GET 限制

- Nginx 默认 `proxy_buffering on`,响应攒到 4-8KB 才吐 → SSE 变"卡 3 秒然后一次性喷出来"。解法:响应头 `X-Accel-Buffering: no`,或 Nginx 配 `proxy_buffering off`。演示直连 Tomcat 无此问题,生产必踩
- `EventSource` 只支持 GET、不能带自定义 header,`question` 走 query 参数,Tomcat 请求行默认上限 8KB → 超长问题 400。生产方案:fetch + ReadableStream 手动解析 SSE(POST JSON),或先 POST 建会话再 GET 订阅

## 6. 面试速答

**Q: 为什么不用 WebSocket?**
答:问答是单向推送,SSE 走普通 HTTP——代理、鉴权、重连零成本复用;WS 的协议升级、心跳、重连成本要用双向场景才付得起。

**Q: 项目没引 WebFlux,返回 Flux 能跑吗?**
答:能。Spring MVC 5.0+ 的 ReactiveTypeHandler 把 Flux 桥接成 ResponseBodyEmitter,servlet 3.1 async 释放 worker 线程——长连接不占线程,不打爆线程池。

**Q: 客户端断连了,后端还在生成吗?**
答:还在。LangChain4j 1.19 的 `chat()` 返回 void,拿不到取消句柄;靠每日 token 预算 + 重连命中语义缓存两层兜底,并能说清如何下沉到底层 client 实现真取消。

**Q: SSE 上线要注意什么?**
答:Nginx 关响应缓冲;EventSource 仅 GET 的 URL 长度限制(超长改 fetch 流式);无 `id:` 字段时重连从头发送。

## 7. 面试话术(一句话版)

> "SSE 本质是一条不结束的 HTTP 响应,问答是单向推送,选它是因为方向性决定协议——代理、鉴权、重连全部复用 HTTP 基础设施。实现上是 LangChain4j 回调桥接 Flux.create,Spring MVC 的 ReactiveTypeHandler 走 servlet async,线程不等生成,所以长连接打不爆线程池。断连后上游还在生成是我知道的取舍:LangChain4j 拿不到取消句柄,靠 token 预算和语义缓存兜底。"

## 8. 行动项

- [ ] (可选)断线重发:前端 `error` 事件自动重发一次问题,配合语义缓存实现 0 token 重连(§5.2)
- [ ] (可选)生产部署时 Nginx 关 `proxy_buffering` 或后端加 `X-Accel-Buffering: no`(§5.3)
- [x] 本次讨论已记录(本文档),并与 [[ES与kNN检索原理]]、[[RRF融合原理]]、[[Redis语义缓存-设计反思]] 互链
