# TavernChat IM 可靠性传输功能代码评审

> 评审范围：`src/main/kotlin/com/tavernchat/core/{channel,model,reliability}`
> 评审日期：2026-07-22
> 测试现状：`./gradlew test` 通过（共 6 个用例全绿），但用例为非确定性弱网模拟 + 虚拟时间快进，覆盖度有限。

---

## 0. 架构概览

项目把可靠性逻辑与传输通道解耦，整体是一个**内存态的可靠传输引擎原型**：

- **通道层（channel）**：`MessageChannel` 接口 + `InMemoryMessageChannel`（无丢包）、`UnreliableMessageChannel`（按 `lossRate` 随机丢包）。仅做"按 receiver 路由 + 回调 listener"，**不保证到达**（接收者未注册 listener 时直接丢弃并打日志）。
- **模型层（model）**：`Message`（含 `id` 唯一标识、`seqId` 序列号、`type` 区分 TEXT/ACK）。
- **可靠性层（reliability）**：
  - `ReliableSender`：超时重发 + ACK 取消重传协程（`ConcurrentHashMap<id, Job>`）。
  - `ReliableReceiver`：收到 TEXT 自动回 ACK + 去重 + 乱序重组后交付上层。
  - `Deduplicator`：基于 LRU `LinkedHashMap` 的滑动窗口去重（容量 50）。
  - `SequenceReorderer`：小顶堆 `PriorityQueue` 按 `seqId` 重组连续序列，带防死锁强制跳过。
  - `RetryStrategy`：固定间隔 / 指数退避两种策略。

设计思路是正确的，分层清晰。**但整条链路 100% 在内存中，没有任何持久化、没有连接生命周期管理**——这从根本上限制了"可靠性"能到达的程度。

---

## 1. 消息可靠投递机制（ACK 确认 + 重传策略）

**结论：基本实现。**

### 已实现
- ✅ **ACK 闭环**：`ReliableReceiver.onMessageReceived` 收到 TEXT 即调用 `sendAck`（`ReliableReceiver.kt:48`）；`ReliableSender` 在 `init` 中注册为 listener，收到 `type==ACK 且 receiver==senderId` 的回执后 `job?.cancel()` 取消重传闹钟（`ReliableSender.kt:74-84`）。语义是 **at-least-once**（发送方无限重发直到收到 ACK）。
- ✅ **超时重传**：每次 `sendReliable` 启动一个 `while(isActive)` 协程，按 `retryStrategy.nextDelay()` 挂起等待后重发（`ReliableSender.kt:44-65`）。
- ✅ **退避策略可插拔**：`FixedDelayRetryStrategy` 与 `ExponentialBackoffRetryStrategy`，后者满足 README 宣称的"指数退避"。
- ✅ **重复消息仍补发 ACK**：接收端去重命中后依然回 ACK（`ReliableReceiver.kt:39-42`），避免发送端因 ACK 丢失而盲重发——这是对的。

### 不足与潜在问题
1. ⚠️ **初始 ACK 竞态（关键缺陷）**：`ReliableSender.sendReliable` 先 `channel.send(message)`（`ReliableSender.kt:41`），**之后**才把重传 Job 写入 `pendingAcks`（`ReliableSender.kt:68`）。而在 `InMemoryMessageChannel` / `UnreliableMessageChannel` 中，`send` 是**同步回调** `onMessageReceived` 的，于是"首次发送触发的 ACK"会在 Job 注册之前就被处理，`pendingAcks[id]` 此时为 `null`，导致这条 ACK 被丢弃。后果：**即使在完全可靠的通道上，首条消息也必然至少多一次重传**，依赖接收端去重来兜底。正确做法应是"先占位/先注册待确认项，再发送"。
2. ⚠️ **重发次数语义偏差**：`FixedDelayRetryStrategy` 中 `retryCount` 从 0 计，实际总发送次数 = 1（首发）+ `maxRetries`（重发），即 `maxRetries=5` 时是 6 次投递。命名把 maxRetries 当"最大重发次数"而非"总尝试次数"，阅读易误解，但不影响正确性。
3. ⚠️ **指数退避无 jitter、无上限**：`baseDelayMs * 2.0.pow(retryCount)`（`RetryStrategy.kt:42`）无随机抖动，多消息同时超时易产生"重试风暴"；且无最大延迟封顶。
4. ⚠️ **ACK 自身不保可靠**：ACK 也走同一个会丢包的通道，丢失后触发重发（设计上 OK，at-least-once），但意味着在弱网下 ACK 风暴会放大。
5. ⚠️ **无发送幂等保护 / 无取消 API**：`sendReliable` 对同一个 `message.id` 重复调用会注册多个独立 Job；且没有提供"主动取消某条消息重传""关闭发送器清理 `pendingAcks`"的对外方法，协程作用域被取消时 `pendingAcks` 残留（泄漏）。

---

## 2. 消息顺序保证

**结论：基本实现（单发送方、单会话、内存态下成立）。**

### 已实现
- ✅ `SequenceReorderer` 用 `PriorityQueue`（小顶堆）按 `seqId` 收集乱序消息，仅当堆顶 `seqId == expectedSeqId` 时连续开闸交付（`SequenceReorderer.kt:55-59`）。`ReliableEngineFullTest.testReordering` 验证倒序 3→2→1 最终按 1→2→3 交付，通过。
- ✅ **防死锁**：缓冲区超过 `maxBufferSize(50)` 时强制跳过缺口、放行现存最小号并前移 `expectedSeqId`（`SequenceReorderer.kt:61-66`）。
- ✅ **去重先于重组**：`ReliableReceiver` 先用 `id` 去重，再进 reorderer，重复消息不会污染缓冲区。

### 不足与潜在问题
1. ⚠️ **`seqId` 由调用方生成，可靠性层不托管**：`ReliableSender.sendReliable` 直接透传 `message`，**从不分配 / 维护序列号**。当前测试里的 `seqId` 是手工写死在 `Message` 上的（`ReliableEngineFullTest.kt:57-59`）。生产环境若没有全局单调的序列号分配器（且跨进程/重启后仍连续），顺序保证立刻失效。
2. ⚠️ **单一全局序列流，未按发送方/会话隔离**：每个 `ReliableReceiver` 只有一个 `expectedSeqId`（`SequenceReorderer.kt:26`），把所有发送者（如 Alice、Carol）的消息混进同一个 seqId 空间。若 Alice 发 1、Carol 发 1，接收端会把它们当同一序列处理，造成跨会话排序错乱。**应当按 (sender, conversationId) 维护独立序列流。**
3. ⚠️ **缺口被强制跳过 = 永久丢失**：防死锁分支一旦触发，`expectedSeqId` 被前移，那条永久丢失的消息再也无法交付（后续到达 `seqId < expectedSeqId` 直接丢弃，`SequenceReorderer.kt:45-47`）。这是"保活不保全"的取舍，应明确告知业务层（例如回调一个"消息缺失"事件），目前是静默丢弃。
4. ⚠️ **重启后序列号归零**：`expectedSeqId` 与缓冲区全在内存，`reset()` 默认回到 `DEFAULT_START_SEQ_ID=1`（`ReliabilityConfig.kt:18`）。进程重启后，重发的旧消息会被当作"过期"丢弃或重新交付，与持久化缺失叠加（见 §5）。

---

## 3. 离线消息处理

**结论：基本未实现。**

- ❌ 通道层在接收者未注册 listener 时**直接丢弃并打日志**：`InMemoryMessageChannel.kt:22`、 `UnreliableMessageChannel.kt:29`（"投递失败！接收者不在线"）。没有任何 store-and-forward 缓冲。
- ❌ `ReliableReceiver` / `ReliableSender` 都没有"接收者离线"的概念，也没有离线消息库、拉取接口、或上线后补推机制。
- ❌ 连锁后果：若 Bob 不在线，`channel.send` 静默丢包 → 无 ACK → 发送端按 `maxRetries` 重发耗尽后**宣告失败并丢消息**（`ReliableSender.kt:51-55`）。在 IM 场景里，这是最严重的可靠性缺口之一：用户 A 发的消息在用户 B 离线期间会彻底消失。
- 💡 即便是当前内存原型，也可加一层"离线队列"：接收者未注册时把消息暂存到内存 Map，注册（上线）后再投递——这能立刻把"离线丢消息"降级为"内存态可恢复"，作为向持久化演进的过渡。

---

## 4. 连接断开后的消息恢复策略

**结论：未实现。**

- ❌ 没有连接状态机、没有心跳、没有断线检测、没有重连（reconnect）逻辑。当前 `MessageChannel` 是内存回调，根本不存在"断开/恢复"的概念。
- ❌ `ReliableSender` 的重传完全依赖 `scope` 协程存活。`delay` 在 `while(isActive)` 中；一旦协程作用域被取消（类比"连接关闭/进程切后台"），所有在途重传 Job 一并死亡，**没有"断线期间挂起、恢复后继续重发未确认消息"的设计**。
- ❌ 没有"会话断点续传"：恢复连接后不会重新发送那些尚未收到 ACK 的消息；也不会与对方做"已收到最大 seqId"的对账。
- 💡 可行的演进方向：① 引入 `ConnectionState`（`CONNECTED/DISCONNECTED`）；② 发送端维护"已发未确认"队列，断线时暂停、重连后从队首续发；③ 接收端上线时上报"已确认最大 seqId"，发送端补发缺口。

---

## 5. 去重（幂等）机制

**结论：基本实现，但窗口有限且依赖上游配合。**

### 已实现
- ✅ `Deduplicator` 基于 LRU `LinkedHashMap`，容量上限 + 自动淘汰最老条目（`Deduplicator.kt:13-24`），消除了魔法数字。
- ✅ `ReliableEngineFullTest.testDeduplication` 验证同 id 连发 3 次，UI 仅交付 1 次，通过。
- ✅ `isDuplicate("")` 直接返回 false（`Deduplicator.kt:32`），防止空 id 误判。

### 不足与潜在问题
1. ⚠️ **LRU 窗口外会重新放行重复**：容量仅 50（`ReliabilityConfig.kt:9`）。若某消息在 ack 往返 + 重发期间被挤出窗口，再次到达会被当作"新消息"再次交付 UI → **产生重复展示**。高并发/长延迟下窗口过小风险放大。
2. ⚠️ **去重强依赖调用方提供唯一 `id`**：`Message.id` 默认值为 `""`（`Message.kt:19`）。若发送方忘记赋值或用重复 id，去重失效、ACK 匹配也失效（`ACK.content` 为空）。可靠性层**应自动生成 UUID** 作为默认 `id`，而非把责任甩给调用方。
3. ⚠️ **无持久化、重启即清零**：`Deduplicator` 与 `SequenceReorderer`、`pendingAcks` 全是内存态。进程重启后，重发的旧消息因 id 不在窗口而被重复交付——与 §2.4、§4 的"重启/断线恢复"缺陷同源：**整引擎缺乏持久化底座（WAL/本地 DB）**。
4. ⚠️ **去重 key 仅 `message.id`，未结合 (sender, conversation)**：跨会话若巧合出现相同 id（尤其在未自动生成 UUID 时）会误去重。`id` 与 `seqId` 的语义也可进一步区分（全局唯一 id vs 会话内有序 seqId）。

---

## 6. 测试覆盖评估

- ✅ 现有用例覆盖了"弱网下最终送达""去重""乱序重组"三个核心happy-path。
- ⚠️ `testReliableSendWithPacketLoss` 使用 `Random` 非确定性丢包，**用例可能偶发抖动/不稳定**（建议注入可种子化随机源或固定丢包序列）。
- ❌ 缺失关键边界用例：
  - 永久丢一条中间消息 → 防死锁强制跳过行为；
  - 缓冲区溢出（`>50`）压力测试；
  - 离线（接收者未注册）时发送方最终失败、且消息不可恢复；
  - 断线→重连后续传；
  - `message.id` 为空 / 重复时的行为；
  - 多发送方混合 seqId 的排序错乱。
- ❌ `testReordering` 绕过了 `ReliableSender`（直接 `channel.send`），未验证"弱网 + 重传 + ACK + 重组"端到端串起来的真实路径。

---

## 7. 综合评分

| 可靠性维度 | 状态 | 评分 | 一句话 |
|---|---|---|---|
| ACK 确认 + 超时重传 | 基本实现 | 🟡 中 | 闭环成立，但存在首包 ACK 竞态与无上限退避 |
| 消息顺序保证 | 基本实现（受限） | 🟡 中 | 单发送方内存态 OK，缺序列号托管与按会话隔离 |
| 离线消息处理 | 未实现 | 🔴 差 | 接收者不在线即静默丢消息 |
| 断线恢复策略 | 未实现 | 🔴 差 | 无连接生命周期、无续传、无对账 |
| 去重（幂等） | 基本实现 | 🟡 中 | LRU 窗口有限、依赖上游生成 id、无持久化 |
| 持久化底座 | 缺失 | 🔴 差 | 全内存，重启/断线即丢失状态 |

---

## 8. 优先改进建议

**P0（决定"可靠"是否成立）**
1. 修复首包 ACK 竞态：在 `channel.send` **之前**先注册 `pendingAcks[id]`（可先放一个占位/启动 Job 后再 send）。
2. 补上**离线消息**与**断线恢复**：引入连接状态机 + 已发未确认队列 + 上线/重连后补发与 seqId 对账。
3. 落地**持久化底座**（本地 DB/WAL）：`pendingAcks`、`Deduplicator` 窗口、`SequenceReorderer` 的 `expectedSeqId` 都应可恢复，否则 at-least-once 在重启后退化。

**P1（正确性增强）**
4. 可靠性层**自动生成 UUID** 作为 `Message.id` 默认值，ACK 匹配不再依赖调用方。
5. 序列号由引擎托管，并按 `(sender, conversationId)` 维护独立有序流；缺口强制跳过时回调"消息缺失"事件而非静默丢弃。
6. 指数退避加 **jitter** 与最大延迟封顶，避免重试风暴。

**P2（健壮性/可观测）**
7. 扩大去重窗口或改为基于"已确认最大 seqId"的精确去重，消除 LRU 窗口外重复。
8. 提供 `cancel(messageId)` / `close()` 等对外 API，清理 `pendingAcks`，避免协程作用域取消后的残留。
9. 测试改造：可种子化随机源、补充永久丢包/溢出/离线/多发送方/重启恢复等边界用例，覆盖端到端真实路径。

> 总体判断：当前代码是一个**思路正确、分层清晰的教学级/原型级可靠传输骨架**，核心机制（ACK、重传、去重、重组）的 happy-path 已跑通；但"离线""断线恢复""持久化"三块 IM 可靠性的硬骨头基本空白，且存在首包 ACK 竞态、序列号未托管、跨发送方排序错乱等真实缺陷。距离生产可用的"可靠 IM"仍有较大差距，建议按 P0→P1→P2 顺序补齐。
