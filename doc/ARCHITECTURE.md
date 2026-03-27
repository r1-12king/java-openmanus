# Java OpenManus — 架构与调用链文档

## 技术栈

| 层次 | 技术 |
|------|------|
| 运行时 | Java 17 |
| 框架 | Spring Boot 3.1.3 |
| LLM 接入 | LangChain4j 1.11.0（OpenAI 兼容接口） |
| 持久化 | MySQL 8 + MyBatis Plus 3.5.5 |
| 缓存 | Redis + Spring Data Redis |
| HTTP 客户端 | OkHttp 4.12（WebSearch 工具） |
| 构建 | Maven 3.6.3 |

---

## 整体架构分层

```
┌─────────────────────────────────────────────────────┐
│                   HTTP 客户端                        │
└────────────────┬────────────────┬───────────────────┘
                 │ REST           │ SSE
┌────────────────▼────────────────▼───────────────────┐
│              Controller 层                           │
│   AgentController    TaskController    PlanningFlowController │
│   /api/agent/run     /api/agent/task  /api/agent/plan  │
└────────────────────────────┬────────────────────────┘
                             │
┌────────────────────────────▼────────────────────────┐
│              Service 层                              │
│   AgentService    MemoryService    SseEmitterService  │
└──────┬────────────────┬───────────────────┬─────────┘
       │                │                   │
┌──────▼──────┐  ┌──────▼──────┐   ┌────────▼───────┐
│  Agent 层   │  │   Mapper    │   │   Redis 缓存    │
│             │  │             │   │                │
│ ManusAgent  │  └──────┬──────┘   └────────────────┘
│ BaseAgent   │         │
│ PlannerAgent│         ▼
│ ExecutorAgent│  ┌──────▼──────┐
└──────┬──────┘  │    MySQL     │
       │         │ agent_runs   │
┌──────▼──────┐  │agent_memory  │
│  Tool 层    │  └─────────────┘
│ToolCollection│
│ 5 个工具    │
└──────┬──────┘
       │
┌──────▼──────┐
│  LLM（通义  │
│  qwen3-max）│
└─────────────┘
```

---

## 包结构说明

```
com.openmanus/
├── OpenManusApplication.java       Spring Boot 启动入口
│
├── config/
│   ├── AppConfig.java             Bean 注册：ChatModel、ToolCollection、
│   │                             PlannerAgent、ExecutorAgent(prototype)、
│   │                             PlanningFlow Bean
│   └── MyBatisPlusConfig.java      MetaObjectHandler 自动填充 createdAt
│                                   ThreadPoolTaskExecutor
│
├── controller/
│   ├── AgentController.java        任务提交（ReAct 模式）+ 会话查询接口
│   ├── TaskController.java         SSE 流 + 任务状态查询接口
│   └── PlanningFlowController.java  规划模式专用 Controller
│
├── service/
│   ├── AgentService.java           接口：任务异步提交（两种模式）
│   ├── AgentServiceImpl.java       实现，含 submitTask / submitPlanningTask
│   ├── MemoryService.java          接口：ChatMessage ↔ MySQL 序列化/反序列化
│   ├── MemoryServiceImpl.java      实现
│   └── SseEmitterService.java      SSE 事件存储与推送
│
├── agent/
│   ├── BaseAgent.java              状态机、主循环、卡死检测、Memory 管理
│   ├── ManusAgent.java             ReAct 实现（think + act，单 Agent 全权执行）
│   ├── PlannerAgent.java           规划 Agent：分析请求 → 生成结构化 Plan（JSON）
│   └── ExecutorAgent.java          执行 Agent：按 PlanStep 逐步执行，支持 SSE 推送
│
├── flow/
│   └── PlanningFlow.java           规划-执行两阶段流程编排
│                                   plan() → executePlan() → 汇总结果
│
├── tool/
│   ├── BaseTool.java               工具接口（getSpec / execute）
│   ├── ToolCollection.java         工具注册 + 分发执行
│   ├── WebSearchTool.java          DuckDuckGo 搜索
│   ├── FileOperatorTool.java       文件读写操作
│   ├── BashTool.java               Shell 命令执行
│   ├── StrReplaceEditorTool.java   文件内字符串替换
│   └── TerminateTool.java          任务终止信号
│
├── entity/
│   ├── AgentRun.java               运行记录（task_id、session_id、状态、结果）
│   └── AgentMemory.java            对话消息持久化（role、content、tool_calls）
│
├── mapper/
│   ├── AgentRunMapper.java
│   └── AgentMemoryMapper.java
│
└── schema/
    ├── AgentState.java             枚举：IDLE / RUNNING / FINISHED / ERROR
    ├── ToolResult.java             record：success + output + error
    ├── Plan.java                   任务计划 record：planId、steps[]、status、result
    ├── RunRequest.java             POST /run 请求 DTO
    ├── RunResponse.java            POST /run 响应 DTO
    ├── SessionResultResponse.java  GET /session 响应 DTO
    └── TaskStatusResponse.java     GET /task/status 响应 DTO
```

---

## 两种执行模式对比

Java OpenManus 支持两种 Agent 执行模式，**共存于同一代码库**，通过不同 Controller 路由区分：

| 维度 | ReAct 模式（ManusAgent） | 规划模式（PlanningFlow） |
|------|--------------------------|--------------------------|
| 入口 | `POST /api/agent/run` | `POST /api/agent/plan/run` |
| Agent | `ManusAgent`（单一 Agent 全权决策） | `PlannerAgent` + `ExecutorAgent`（规划与执行分离） |
| LLM 调用次数 | 每个任务多次（循环直到 terminate） | 规划 1 次 + 每步骤各多次（think-act） |
| 执行计划 | 无结构化计划，LLM 自行决定下一步 | 先由 LLM 生成 JSON 计划，再按步骤执行 |
| 适用场景 | 开放式复杂任务，步骤不固定 | 步骤清晰、可预分解的结构化任务 |
| 透明度 | 低（用户看不到执行计划） | 高（计划生成后 SSE 推送 Plan 内容） |

---

## API 接口一览

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/agent/run` | 异步提交 ReAct 模式任务，立即返回 taskId |
| GET  | `/api/agent/task/{taskId}/stream` | SSE 实时推送每步执行日志（所有模式共用） |
| GET  | `/api/agent/task/{taskId}/status` | 轮询任务状态 |
| GET  | `/api/agent/session/{sessionId}` | 获取会话最新结果（Redis/MySQL） |
| GET  | `/api/agent/history/{sessionId}` | 获取会话所有历史运行记录 |
| GET  | `/api/agent/health` | 健康检查 |
| POST | `/api/agent/plan/run` | 异步提交**规划模式**任务，立即返回 taskId |
| GET  | `/api/agent/plan/{taskId}` | 查询规划任务详情（AgentRun） |

---

## 主调用链：POST /api/agent/run（ReAct 模式）

```
HTTP POST /api/agent/run
  │
  ▼
AgentController.run(RunRequest)
  │  校验 request 字段非空
  ▼
AgentService.submitTask(request, sessionId)
  │  生成 taskId（UUID）
  │  写入 agent_runs（status=RUNNING）
  │  向 ThreadPoolTaskExecutor 提交异步任务
  │  立即返回 RunResponse（taskId + streamUrl）
  │
  └──── [异步线程] executeTask(taskId, runId, request, sessionId)
            │
            ├─ MemoryService.countSavedMessages(sessionId)
            │    └── SELECT COUNT FROM agent_memory WHERE session_id=?
            │
            ├─ [有历史] MemoryService.loadMemory(sessionId)
            │    └── SELECT * FROM agent_memory ORDER BY seq → 反序列化为 ChatMessage
            │    └── ManusAgent.initMemory(messages)
            │
            ├─ ManusAgent.setStepListener((step, content) → SSE 推送)
            │
            ├─ ManusAgent.run(request)
            │    └── [循环，最多 maxSteps 次]
            │         ├─ BaseAgent 主循环
            │         │    currentStep++
            │         │    stepResult = step()
            │         │    stepListener.accept(step, stepResult) → SSE sendStep
            │         │    isStuck() 检测 → 注入恢复 prompt
            │         │
            │         └─ ManusAgent.step() = think() + act()
            │              │
            │              ├── THINK
            │              │    buildMessages() → [SystemMsg] + memory
            │              │    model.chat(messages + toolSpecs)
            │              │    → AiMessage 写入 memory
            │              │
            │              └── ACT（有 tool_calls 时）
            │                   遍历 ToolExecutionRequest
            │                   ToolCollection.execute(toolName, jsonArgs)
            │                     └── BaseTool.execute(args) → ToolResult
            │                   observation 截断 → 写入 memory
            │                   检测 TerminateTool 信号 → state=FINISHED
            │
            ├─ MemoryService.saveNewMessages(sessionId, memory, prevCount)
            │    └── INSERT INTO agent_memory (新增消息)
            │
            ├─ 更新 agent_runs（status=COMPLETED，result，steps）
            │
            ├─ Redis SET session:result:{sessionId} TTL=24h
            │
            └─ SseEmitterService.sendComplete(taskId, result)
                 └── 推送 {"type":"complete","result":"..."} 关闭所有 emitter
```

---

## 规划模式调用链：POST /api/agent/plan/run

```
HTTP POST /api/agent/plan/run
  │
  ▼
PlanningFlowController.run(PlanRunRequest)
  │  校验 request 字段非空
  ▼
AgentService.submitPlanningTask(request, sessionId)
  │  生成 taskId + sessionId
  │  写入 agent_runs（status=RUNNING）
  │  向 ThreadPoolTaskExecutor 提交异步任务
  │  立即返回 PlanRunResponse（taskId + streamUrl）
  │
  └──── [异步线程] executePlanningTask(taskId, runId, request, sessionId)
            │
            ├─ PlanningFlow.builder()
            │    .plannerAgent(...)   ← AppConfig 中注入的原型 Bean
            │    .executorAgent(...)  ← AppConfig 中注入的原型 Bean
            │    .sseEmitterService(...)
            │    .agentRunMapper(...)
            │    .build()
            │
            ├─ flow.setTaskId(taskId) / setRunId(runId) / setSessionId(sessionId)
            │
            └─ flow.execute(request)
                 │
                 │ ========== 第一阶段：规划 ==========
                 ├─ sendEvent("planning", "Generating execution plan...")
                 │
                 ├─ PlannerAgent.plan(request)
                 │    ├── buildMessages: [PLANNER_SYSTEM_PROMPT] + [UserMessage(request)]
                 │    ├── model.chat(messages, toolSpecs)
                 │    │    └── LLM 返回 JSON：{title, steps:[{index,description,tool_name}]}
                 │    ├── extractJson() 脱去 markdown 包裹
                 │    ├── parsePlan() → Plan（状态：STATUS_READY）
                 │    │    └── Plan.create(planId, request).withTitle().withSteps()
                 │    │
                 │    └── 失败时 fallbackPlan()：从原始文本提取信息
                 │
                 ├─ sendPlanEvent(plan) → SSE {type:"plan", data:{planId,title,stepCount,steps}}
                 ├─ sendEvent("planned", "Plan generated with N steps")
                 │
                 │ ========== 第二阶段：执行计划 ==========
                 ├─ executorAgent.setPlan(plan.withStatus(STATUS_EXECUTING))
                 │
                 ├─ executorAgent.setExecutionListener((stepIndex, result) → {
                 │      updatedPlan = plan.withStepStatus(stepIndex, STEP_COMPLETED, result)
                 │      sendStepEvent(stepIndex, result)
                 │   })
                 │
                 └─ for (i = 0; i < plan.steps().size(); i++) {
                        sendEvent("step_start", "Starting step i: ...")
                        │
                        ├─ executorAgent.executeStep(i)
                        │    ├── 将步骤描述构建为 UserMessage，加入 memory
                        │    ├── 调用 step()（继承自 BaseAgent）
                        │    │    ├── model.chat(messages + toolSpecs) → AiMessage
                        │    │    ├── 无 tool_calls？→ 返回文本，步骤完成
                        │    │    └── 有 tool_calls？
                        │    │         ├── 遍历 ToolExecutionRequest
                        │    │         ├── toolCollection.execute(toolName, args)
                        │    │         ├── observation 写入 memory（ToolExecutionResultMessage）
                        │    │         ├── 检测 terminate 信号 → state=FINISHED
                        │    │         └── 重新调用 model.chat（继续思考）
                        │    │
                        │    └── notifyExecution(i, result) → 触发 listener → SSE 推送
                        │
                        └─ finalResults.append(stepResult)
                    }
                 │
                 │ ========== 第三阶段：汇总 ==========
                 ├─ plan.withStatus(STATUS_COMPLETED).withResult(finalResults)
                 │
                 ├─ 更新 agent_runs（status=COMPLETED，result，steps_taken）
                 │
                 └─ sendCompleteEvent(result, completedPlan)
                      └── SSE {type:"plan_complete", data:{result, plan}}

SSE 推送事件类型：
  {"type":"step",     "step":N, "content":"..."}    每步开始/结束
  {"type":"plan",     "data":{planId,title,stepCount,steps}}  计划生成
  {"type":"plan_complete", "data":{result,plan}}     计划执行完成
  {"type":"error",    "message":"..."}               异常
```

---

## SSE 调用链：GET /api/agent/task/{taskId}/stream

```
HTTP GET /api/agent/task/{taskId}/stream
  │
  ▼
TaskController.stream(taskId)
  │
  ▼
SseEmitterService.subscribe(taskId)
  │
  ├─ 创建 SseEmitter（超时 10 分钟）
  │
  ├─ 重放 eventStore 中已有的历史事件
  │    （应对客户端晚于任务启动才连接的情况）
  │
  └─ 注册 emitter 到 emitters[taskId]
       客户端保持连接，实时接收：
         {"type":"step",         "step":N, "content":"..."}
         {"type":"plan",         "data":{...}}          （规划模式）
         {"type":"plan_complete", "data":{...}}          （规划模式）
         {"type":"complete",               "result":"..."}
         {"type":"error",                  "message":"..."}
```

---

## ReAct 执行模型（ManusAgent）

```
用户请求
   │
   ▼
THINK ──── LLM(messages + toolSpecs)
   │              │
   │         有 tool_calls？
   │         /           \
  否          是          （直接回答）
   │           │               │
   ▼           ▼           state=FINISHED
FINISHED    ACT
            遍历 tool_calls
            执行工具 → observation
            写回 memory
               │
               ▼
            检测 terminate？
            /          \
           是            否
           │              │
       state=FINISHED   下一轮 THINK
```

---

## 规划-执行模型（PlanningFlow）

```
用户请求
   │
   ▼
┌─ PlannerAgent.plan() ─────────────────────────────────────┐
│  LLM 生成 JSON Plan                                         │
│  {title:"...", steps:[{index,description,tool_name}]}         │
│  └── Plan(STATUS_READY)                                     │
└──────────────────────────────────────────────────────────────┘
   │
   ▼
ExecutorAgent.setPlan(plan.withStatus(STATUS_EXECUTING))
   │
   ▼
for each PlanStep ──────────────────────────────────────────
│  buildStepRequest(step) → UserMessage                        │
│  step() = think() + act()                                    │
│    ├── 无 tool_calls？→ 步骤完成                             │
│    └── 有 tool_calls？                                      │
│         逐个执行工具 → observation → 继续 think               │
│    检测 terminate → state=FINISHED                          │
│                                                             │
│  withStepStatus(i, STEP_COMPLETED, result)                   │
│  SSE 推送: {type:"step", step:i+1, content:result}            │
└──────────────────────────────────────────────────────────────┘
   │
   ▼
Plan(STATUS_COMPLETED).withResult(汇总结果)
   │
   ▼
SSE: {type:"plan_complete", data:{result, plan}}
```

---

## 数据库表结构

### agent_runs（运行记录）

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT PK | 自增主键 |
| task_id | VARCHAR(36) UNIQUE | 单次异步任务 ID |
| session_id | VARCHAR(36) | 会话 ID（多轮对话标识） |
| request | TEXT | 用户请求原文 |
| result | LONGTEXT | 最终结果（规划模式含各步骤汇总） |
| steps_taken | INT | 实际执行步数 |
| status | VARCHAR(20) | RUNNING / COMPLETED / ERROR |
| created_at | DATETIME(6) | 创建时间 |
| completed_at | DATETIME(6) | 完成时间 |

### agent_memory（对话记忆）

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT PK | 自增主键 |
| session_id | VARCHAR(36) | 所属会话 |
| seq | INT | 消息在会话中的顺序 |
| role | VARCHAR(20) | user / assistant / tool |
| content | LONGTEXT | 消息文本 |
| tool_calls | LONGTEXT | assistant 工具调用 JSON |
| tool_call_id | VARCHAR(200) | tool 结果对应的调用 ID |
| tool_name | VARCHAR(200) | tool 结果对应的工具名 |
| created_at | DATETIME(6) | 创建时间 |

---

## 关键设计决策

| 决策 | 原因 |
|------|------|
| ManusAgent / ExecutorAgent 使用 prototype scope | 每次请求独立的 memory，避免上下文污染 |
| 手动实现 ReAct 循环（不用 AiServices）| 完整控制 think/act 每一步，便于 SSE 逐步推送 |
| ThreadPoolTaskExecutor 替代 @Async | 避免 Spring 代理自调用问题，线程池参数可配置 |
| SSE eventStore 内存缓存 | 支持客户端晚连接时重放历史事件，不丢步骤 |
| Memory 只存 user/assistant/tool 消息 | system prompt 由配置管理，不污染持久化数据 |
| Redis 缓存最新结果（TTL 24h） | 高频查询走缓存，避免重复查 MySQL |
| Plan 使用 immutable record + withXxx() 方法 | 状态不可变，并发安全，SSE 推送时方便追踪版本 |
| 两种 Agent 模式共存 | ReAct 模式灵活，PlanningFlow 模式透明；不同场景选不同模式 |
| PlannerAgent 的 JSON fallback | LLM 输出格式不稳定时兜底，确保不因解析失败崩溃整个任务 |

---

*最后更新：2026-03-27*
