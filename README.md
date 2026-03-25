# OpenManus (Java 版)

基于 Java + LangChain4j 实现的 AI Agent 框架，参考 [OpenManus](https://github.com/mannaandpoop/OpenManus)，支持 ReAct 架构、异步执行、SSE 实时推送和多轮对话记忆持久化。

## 快速开始

### 1. 克隆项目

```bash
git clone <your-repo-url>
cd java-openmanus
```

### 2. 配置

复制配置模板并填写你的凭据：

```bash
cp src/main/resources/application.yml.example src/main/resources/application.yml
```

编辑 `application.yml`，填入以下信息：

| 配置项 | 说明 |
|--------|------|
| `spring.datasource.password` | MySQL 数据库密码 |
| `spring.data.redis.*` | Redis 连接信息 |
| `llm.api-key` | 阿里云 Dashscope API Key（从百炼平台获取） |
| `llm.model` | 使用的模型，如 `qwen3-max` |

### 3. 创建数据库

```sql
CREATE DATABASE openmanus CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

USE openmanus;

CREATE TABLE agent_runs (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_id VARCHAR(64) NOT NULL,
    session_id VARCHAR(64) NOT NULL,
    request TEXT,
    result TEXT,
    steps_taken INT DEFAULT 0,
    status VARCHAR(32) DEFAULT 'RUNNING',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    completed_at DATETIME,
    INDEX idx_task_id (task_id),
    INDEX idx_session_id (session_id)
);

CREATE TABLE agent_memory (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id VARCHAR(64) NOT NULL,
    seq INT NOT NULL,
    role VARCHAR(32),
    content TEXT,
    tool_calls TEXT,
    tool_call_id VARCHAR(64),
    tool_name VARCHAR(128),
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_session_seq (session_id, seq)
);
```

> 数据库初始化脚本位于 `src/main/resources/db/init.sql`

### 4. 启动

```bash
./mvnw spring-boot:run
# 或
mvn spring-boot:run
```

启动后访问控制台：**http://localhost:8080**

## API

| 方法 | 路径 | 说明 |
|------|------|------|
| `POST` | `/api/agent/run` | 提交任务（异步） |
| `GET` | `/api/agent/session/{sessionId}` | 获取会话结果 |
| `GET` | `/api/agent/history/{sessionId}` | 获取会话历史 |
| `GET` | `/api/agent/task/{taskId}/stream` | SSE 事件流 |
| `GET` | `/api/agent/task/{taskId}/status` | 轮询任务状态 |
| `GET` | `/api/agent/health` | 健康检查 |

### 提交任务示例

```bash
curl -X POST http://localhost:8080/api/agent/run \
  -H "Content-Type: application/json" \
  -d '{"request": "搜索最新的人工智能发展趋势"}'
```

## 技术栈

- **Java 17** + **Spring Boot 3.1.3**
- **LangChain4j 1.11.0** — LLM 模型集成、Tool 调用
- **MyBatis Plus 3.5.5** — 数据库访问
- **Redis** — 会话结果缓存
- **SSE** — 实时事件推送
- **MySQL 8.x** — 记忆持久化

## 架构

```
Controller → AgentService → ManusAgent (ReAct)
                               ↓
                          ToolCollection → BashTool / WebSearchTool / FileOperator / ...
                               ↓
                    MemoryService → MySQL (agent_memory 表)
                               ↓
                   SseEmitterService → SSE 实时推送
```

详见 [ARCHITECTURE.md](ARCHITECTURE.md)
