# SuperAgent

基于 Spring Boot、Spring AI Alibaba、DashScope 和 Milvus 的智能业务助手系统。项目包含普通智能问答、知识库 RAG、多 Agent 智能运维分析、知识库文件管理等能力。

## 功能概览

- 智能问答：支持普通响应和 SSE 流式响应。
- RAG 知识库：支持上传 `txt`、`md`、`markdown`、`doc`、`docx`、`pdf`、`png`。
- 文档处理：文本/Markdown/Word/PDF 可提取文本并向量化，PNG 当前仅支持上传和管理，不做 OCR 检索。
- 向量检索：使用 DashScope `text-embedding-v4` 生成向量，Milvus 存储和检索。
- 多 Agent 运维：Supervisor、Planner、Executor 三个 Agent 协作完成告警分析报告。
- 工具调用：时间工具、Prometheus 告警查询、知识库检索、文档分页读取、腾讯云 CLS MCP/Mock 日志工具。
- 知识库管理页：文件列表、搜索、上传、刷新、重建索引、删除文件。
- 基础安全修复：API Key 环境变量化、上传文件名清洗、CORS 收敛、SSE 线程池限流、Markdown XSS 净化。

## 技术栈

| 模块     | 技术                                   |
| -------- | -------------------------------------- |
| 后端     | Java 17、Spring Boot 3.2.0             |
| Agent    | Spring AI Alibaba Agent Framework      |
| 模型     | 阿里云 DashScope Chat / Embedding      |
| 向量库   | Milvus 2.x                             |
| 持久化   | MySQL 8.x                              |
| 文档解析 | Apache POI、pdftotext                  |
| 前端     | Spring Boot Static HTML/CSS/JavaScript |
| 日志/MCP | Spring AI MCP Client、Tencent CLS MCP  |

## 目录结构

```text
.
├── src/main/java/org/example
│   ├── Main.java                         # Spring Boot 启动入口
│   ├── controller
│   │   ├── ChatController.java           # 对话、流式对话、AI Ops 接口
│   │   ├── FileUploadController.java     # 知识库文件管理接口
│   │   └── MilvusCheckController.java    # Milvus 健康检查
│   ├── service
│   │   ├── AiOpsService.java             # 多 Agent 编排
│   │   ├── ChatService.java              # ReactAgent 对话封装
│   │   ├── RagService.java               # RAG 生成服务
│   │   ├── DocumentProcessingService.java# 文档读取/PDF/Word/分页
│   │   ├── DocumentChunkService.java     # 文档切片
│   │   ├── VectorEmbeddingService.java   # DashScope 向量化
│   │   ├── VectorIndexService.java       # 文档入库/重建索引/删除索引
│   │   └── VectorSearchService.java      # Milvus 相似检索
│   ├── agent/tool
│   │   ├── DateTimeTools.java
│   │   ├── InternalDocsTools.java
│   │   ├── QueryMetricsTools.java
│   │   ├── QueryLogsTools.java
│   │   └── ReadDocumentTools.java
│   └── client/MilvusClientFactory.java   # Milvus collection 和索引初始化
├── src/main/resources
│   ├── application.yml
│   └── static                           # 前端页面
├── aiops-docs                           # 示例运维文档
├── uploads                              # 用户上传知识库文件
├── vector-database.yml                  # Milvus Docker Compose
└── OPTIMIZATIONS.md                     # 修复与优化记录
```

## RAG 流程

### 入库流程

```text
上传文件
  -> 保存到 uploads
  -> DocumentProcessingService 提取文本
  -> DocumentChunkService 按标题/段落切片
  -> DashScope text-embedding-v4 生成 1024 维向量
  -> VectorIndexService 写入 Milvus collection: biz
```

Milvus 字段：

- `id`：由文件路径和 chunk index 生成。
- `vector`：1024 维 FloatVector。
- `content`：chunk 原文。
- `metadata`：来源文件、扩展名、文件名、chunkIndex、totalChunks 等。

### 检索流程

```text
用户问题
  -> 生成 query embedding
  -> Milvus TopK 相似检索
  -> 返回 content/metadata/score
  -> 作为上下文给大模型或 Agent 工具使用
```

当前关键配置：

```yaml
dashscope:
  embedding:
    model: text-embedding-v4

document:
  chunk:
    max-size: 800
    overlap: 100

rag:
  top-k: 3
  model: qwen3-max
```

## 多 Agent 设计

AI Ops 使用三个 Agent：

- `ai_ops_supervisor`：调度者，决定下一步调用 Planner、Executor 或结束。
- `planner_agent`：规划者/再规划者，拆解任务、制定步骤、根据反馈修正计划，最终输出报告。
- `executor_agent`：执行者，只执行 Planner 当前计划的第一步，调用工具获取指标、日志、文档证据。

交互过程：

```text
POST /api/ai_ops
  -> ChatController
  -> AiOpsService 创建 Supervisor/Planner/Executor
  -> Supervisor 调 Planner 生成计划
  -> Planner 输出 decision=EXECUTE
  -> Supervisor 调 Executor 执行第一步
  -> Executor 调工具并写入 executor_feedback
  -> Supervisor 再调 Planner 重新判断
  -> Planner 输出 decision=FINISH
  -> 提取 planner_plan 作为最终告警分析报告
```

共享状态：

- `planner_plan`：Planner 输出的计划或最终报告。
- `executor_feedback`：Executor 的执行结果、证据和失败原因。

## 环境要求

- JDK 17+
- Maven 3.8+
- Docker Desktop
- MySQL 8.x
- DashScope API Key
- 可选：`pdftotext`，用于 PDF 文本抽取

Windows 如果安装了 TeX Live，通常会自带 `pdftotext`。如果 PDF 无法解析，请确认命令可用：

```powershell
where.exe pdftotext
```

## 配置

核心配置在 `src/main/resources/application.yml`。

必须设置环境变量：

```powershell
$env:DASHSCOPE_API_KEY="你的 DashScope API Key"
```

项目不再使用 SQLite。必须准备 MySQL 数据库：

```sql
CREATE DATABASE super_biz_agent
  DEFAULT CHARACTER SET utf8mb4
  DEFAULT COLLATE utf8mb4_unicode_ci;
```

默认连接本机 MySQL：

```yaml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/super_biz_agent?useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true
    username: root
    password: ""
```

也可以通过环境变量覆盖：

```powershell
$env:DB_JDBC_URL="jdbc:mysql://localhost:3306/super_biz_agent?useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true"
$env:DB_USERNAME="root"
$env:DB_PASSWORD="你的 MySQL 密码"
```

如果要永久设置 Windows 系统环境变量：

```powershell
[Environment]::SetEnvironmentVariable("DASHSCOPE_API_KEY", "你的 DashScope API Key", "User")
```

默认端口：

```text
http://localhost:9900
```

默认 Milvus：

```yaml
milvus:
  host: localhost
  port: 19530
```

启动时系统会自动创建 `sessions`、`session_messages`、`skills` 三张表。

日志编码：

```yaml
logging:
  charset:
    console: ${CONSOLE_LOG_CHARSET:GBK}
    file: UTF-8
```

Windows 控制台如出现乱码，可设置：

```powershell
$env:CONSOLE_LOG_CHARSET="GBK"
```

## 启动

### 1. 启动 Milvus

```powershell
docker compose -f vector-database.yml up -d
```

检查端口：

```powershell
Test-NetConnection localhost -Port 19530
```

### 2. 确认 MySQL 数据库已创建

```powershell
mysql -u root -p -e "CREATE DATABASE IF NOT EXISTS super_biz_agent DEFAULT CHARACTER SET utf8mb4 DEFAULT COLLATE utf8mb4_unicode_ci;"
```

### 3. 编译

```powershell
mvn clean compile
```

### 4. 启动后端

```powershell
mvn spring-boot:run
```

### 5. 打开页面

```text
http://localhost:9900
```

## Makefile 说明

项目提供了 `Makefile`，主要适合 Git Bash、Linux、macOS 环境：

```bash
make up       # 启动 Milvus
make start    # 后台启动 Spring Boot
make upload   # 上传 aiops-docs 下的 md 文档
make init     # 一键启动 Milvus、服务并上传文档
make down     # 停止 Milvus
```

在 PowerShell 下建议使用上一节的手动命令。

## API

### 智能问答

普通对话：

```http
POST /api/chat
Content-Type: application/json
```

```json
{
  "Id": "session-001",
  "Question": "帮我查询知识库里的 JVM 排查流程"
}
```

流式对话：

```http
POST /api/chat_stream
Content-Type: application/json
```

```json
{
  "Id": "session-001",
  "Question": "根据知识库说明 CPU 告警怎么排查"
}
```

清空会话：

```http
POST /api/chat/clear
```

查询会话：

```http
GET /api/chat/session/{sessionId}
```

### AI Ops

```http
POST /api/ai_ops
```

该接口会触发多 Agent 运维分析流程，SSE 流式返回最终报告。

### 知识库文件

上传文件：

```powershell
curl.exe -X POST http://localhost:9900/api/knowledge/files -F "file=@D:\docs\demo.pdf"
```

兼容旧接口：

```http
POST /api/upload
```

文件列表：

```http
GET /api/knowledge/files
```

重建索引：

```http
POST /api/knowledge/files/{fileName}/reindex
```

删除文件：

```http
DELETE /api/knowledge/files/{fileName}
```

### Milvus 健康检查

```http
GET /milvus/health
```

## 知识库使用说明

1. 打开 `http://localhost:9900`。
2. 点击左侧“知识库”。
3. 在知识库管理页上传文件。
4. 文本、Markdown、Word、PDF 会自动解析并写入向量库。
5. 如果文件内容变化，可以点击“重建索引”。
6. 删除文件时会同步删除对应 Milvus 索引。

注意：

- PDF 当前以后缀 `.pdf` 判断是否允许上传，文本抽取依赖 `pdftotext`。
- PNG 当前只作为文件保存和展示，不参与向量检索。
- Word 解析依赖 Apache POI。

## 主要工具

| 工具                  | 功能                                                 |
| --------------------- | ---------------------------------------------------- |
| `DateTimeTools`     | 获取当前时间                                         |
| `InternalDocsTools` | 查询知识库向量检索结果                               |
| `QueryMetricsTools` | 查询 Prometheus 告警                                 |
| `QueryLogsTools`    | Mock 日志查询，只有 `cls.mock-enabled=true` 时注册 |
| `ReadDocumentTools` | 分页读取本地文档内容                                 |

真实腾讯云 CLS 查询通过 MCP Client 接入：

```yaml
spring:
  ai:
    mcp:
      client:
        sse:
          connections:
            tencent-cls:
              url: https://mcp-api.tencent-cloud.com
```

## 常见问题

### 1. 启动时报 DASHSCOPE_API_KEY 缺失

确认环境变量已经设置，并且是在当前终端会话中设置：

```powershell
echo $env:DASHSCOPE_API_KEY
```

### 2. Milvus 连接失败

先启动向量数据库：

```powershell
docker compose -f vector-database.yml up -d
```

再检查：

```powershell
Test-NetConnection localhost -Port 19530
curl.exe http://localhost:9900/milvus/health
```

### 3. 9900 端口被占用

查找进程：

```powershell
netstat -ano | findstr :9900
```

结束对应 PID：

```powershell
taskkill /PID <PID> /F
```

### 4. PDF 上传成功但无法检索

上传只校验 `.pdf` 后缀，但检索需要 `pdftotext` 能正常抽取文本。请检查：

```powershell
where.exe pdftotext
```

### 5. 控制台中文乱码

PowerShell 设置：

```powershell
$env:CONSOLE_LOG_CHARSET="GBK"
```

IDEA 控制台通常可使用 UTF-8。

## 验证命令

```powershell
mvn -q clean compile
```

当前 README 对应的项目默认访问地址：

```text
http://localhost:9900
```
