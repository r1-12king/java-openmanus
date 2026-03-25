# Java OpenManus — 下一步工作方向

## 当前完成状态

| 模块 | 状态 |
|------|------|
| BaseAgent 状态机 + 主循环 | ✅ 完成 |
| ManusAgent ReAct (think + act) | ✅ 完成 |
| 工具层（WebSearch / File / Bash / StrReplace / Terminate） | ✅ 完成 |
| MySQL 运行记录持久化 | ✅ 完成 |
| Redis 结果缓存 | ✅ 完成 |
| REST API（同步） | ✅ 完成 |

---

## 优先级 P0 — 核心体验提升

### 1. 异步执行 + SSE 流式输出

**现状**：`POST /api/agent/run` 同步阻塞，Agent 跑完才返回，长任务超时风险高。

**目标**：
- `POST /api/agent/run` 立即返回 `taskId`
- `GET /api/agent/task/{taskId}/stream` — SSE 实时推送每步日志
- `GET /api/agent/task/{taskId}/status` — 轮询任务状态

**涉及改动**：
- `AgentService` 改为异步（`@Async` + `CompletableFuture`）
- `AgentRun` 增加 `taskId` 字段
- 新增 `SseController` 推送步骤事件

---

### 2. Memory 持久化（跨重启多轮对话）

**现状**：`ChatMemory` 是内存对象，服务重启后历史丢失，无法真正多轮对话。

**目标**：
- 新增 `agent_memory` 表，存储每条消息（role + content + session_id + seq）
- 启动 Agent 前按 `session_id` 从 MySQL 恢复历史消息
- 支持跨重启的持续对话

---

## 优先级 P1 — 能力扩展

### 3. PlanningFlow（多 Agent 协作）

对应 OpenManus `planning_flow.py`，实现任务规划与执行分离：

```
用户请求
   ↓
PlannerAgent（生成结构化计划 JSON）
   ↓
ExecutorAgent（逐步执行，每步更新计划状态）
   ↓
最终汇总结果
```

**新增文件**：
- `agent/PlannerAgent.java`
- `flow/PlanningFlow.java`
- `schema/Plan.java`（计划数据结构）

---

### 4. 更多工具

| 工具 | 实现方案 | 优先级 |
|------|---------|--------|
| `BrowserTool` | Playwright Java，支持截图/点击/填表 | P1 |
| `HttpRequestTool` | OkHttp，调用任意 HTTP API | P1 |
| `AskHumanTool` | 暂停 Agent，等待用户输入后继续 | P2 |
| `PythonExecuteTool` | 通过 BashTool 执行 Python 脚本 | P2 |

---

### 5. 工具权限沙箱

**现状**：`BashTool` 和 `FileOperatorTool` 缺乏安全限制。

**目标**：
- Bash：命令白名单过滤 + 超时强制终止
- File：严格限制在 `workspace-dir` 内，防路径穿越
- 新增 `ToolSecurityConfig` 统一管理权限策略

---

## 优先级 P2 — 生态与扩展

### 6. 前端 UI

简单的 Web 界面，方便调试和演示：
- 任务输入框
- 实时步骤日志（对接 SSE）
- 历史记录列表（按 session_id 分组）

**技术选型**：Vue3 + Vite（轻量）或直接用 Thymeleaf 内嵌

---

### 7. 多 LLM / 模型切换

- 请求体增加可选 `model` 字段，支持按任务指定模型
- `AppConfig` 支持注册多个 `ChatModel` Bean
- 建议：规划任务用强模型（gpt-4o），执行任务用快模型（gpt-4o-mini）

---

### 8. MCP（Model Context Protocol）支持

LangChain4j 原生支持 MCP，接入后可扩展海量外部工具：

```
java-openmanus
   └── MCP Client
         ├── Filesystem MCP Server
         ├── GitHub MCP Server
         └── 自定义 MCP Server
```

---

## 建议推进顺序

```
[第一阶段]  异步执行 + SSE  →  Memory 持久化
[第二阶段]  BrowserTool + HttpRequestTool  →  工具沙箱
[第三阶段]  PlanningFlow  →  前端 UI
[第四阶段]  多 LLM  →  MCP 支持
```

---

*最后更新：2026-03-25*
