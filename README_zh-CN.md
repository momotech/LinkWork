<div align="center">

# 🪢 LinkWork

### 让 AI 像员工一样工作

**开源的企业级 AI 劳动力平台 — 岗位 · 技能 · 工具 · 安全 · 调度，一站式管理你的 AI 团队**

[English](./README.md) | 中文

</div>

---

## 这是什么

LinkWork 是一个开源的 **AI 劳动力管理平台**。

你可以像经营一家公司一样管理 AI：设立**岗位**，为每个岗位装配**技能**，授权可用的**工具**，设定**安全策略**，安排**排班计划** — 然后让 AI 员工在各自独立的容器中 7x24 运行，实时追踪进度，高风险操作自动拦截审批。

不是一个聊天机器人，不是一个个人助手，而是一个**企业级的 AI 团队管理系统**。

> 给 AI 发工资之前，先给它一个岗位、一套技能、一条安全红线。

## 核心设计理念

### 每个 AI 员工都是一个容器化服务

AI 员工不是一个跑在宿主机上的进程。每个 AI 员工在独立的 **Docker / K8s 容器**中运行，拥有：

- **隔离的执行环境** — 文件系统、网络、进程完全隔离，员工之间互不干扰
- **专属的资源配额** — CPU、内存按需分配，防止单个员工拖垮整个集群
- **持久化的工作空间** — 任务产出、中间状态、长期记忆跨会话保留
- **固定的技能配置** — 像安装 App 一样为员工装配能力，重启不丢失
- **策略化的命令边界** — 策略引擎控制每个员工能执行什么、不能执行什么

像管理微服务集群一样管理 AI 团队 — 全面复用 K8s 云原生生态：

- **Volcano Gang 调度** — 基于 [Volcano](https://volcano.sh/) 调度器，PodGroup 保证多副本 AI 员工原子化调度，优先级队列（critical / high / normal / low）精细管控资源分配
- **Sidecar 执行隔离** — 生产环境采用双容器模式（Agent + Runner），AI 推理与命令执行进程级隔离，安全边界清晰
- **弹性扩缩容** — 根据任务队列深度动态 Scale Up/Down，空闲超时自动缩容至零，节约计算资源
- **资源治理** — `ai-worker` Namespace 隔离 + ResourceQuota 配额管控，防止单个岗位占用过多集群资源
- **故障自愈** — 容器 OOM、进程崩溃由 K8s 自动重启；残留工作目录下次启动时自动清理

### Skills & 工具市场：AI 能力的 App Store

LinkWork 将 AI 能力拆解为三层可治理的模块，像 App Store 一样管理：

**岗位 (Role)** — 一个完整的 AI 员工定义
> 包含人设、职责描述、可用 Skills 列表和工具权限。创建一个"前端开发工程师"岗位，任何 AI 模型实例化后都能直接上岗。

**Skills** — 可装卸的能力模块
> 声明式定义，每个 Skill 独立 Git 分支管理，构建时按 commit SHA 锁定版本注入容器。"代码审查"、"数据分析"、"文档撰写"都是独立 Skill，按需组合安装到不同岗位。

**MCP 工具** — 标准化的外部能力接入
> 兼容 [Model Context Protocol](https://modelcontextprotocol.io/) 标准。数据库查询、API 调用、文件操作、浏览器控制……通过统一的工具总线接入，自动代理、鉴权、计量。

**岗位 → Skills → 工具**，三层解耦、自由组合、**权限可控** — 企业管理员决定哪些岗位可用哪些 Skills 和工具，而不是 AI 自己随意安装。

## 核心能力

- **容器化服务编排** — 每个 AI 员工独立容器运行，K8s 原生调度，弹性扩缩容、故障自愈
- **AI 岗位管理** — 定义岗位职责与能力边界，AI 员工开箱即用、换人不换岗
- **Skills 市场** — 声明式 Skills，Git 分支管理，构建时按 commit SHA 锁定版本内嵌到镜像
- **MCP 工具总线** — 兼容 [MCP 协议](https://modelcontextprotocol.io/)标准，统一代理、鉴权、用量统计
- **任务编排与实时追踪** — 下发任务，WebSocket 流式查看执行过程，全程可观测
- **安全审批流** — 风险分级策略引擎，高风险操作自动拦截，人工确认后继续
- **定时排班** — Cron 驱动，AI 员工按排班表自动执行，无需人工触发
- **向量记忆** — 基于 Milvus 的长期记忆，跨任务知识沉淀与语义检索
- **多模型支持** — 兼容 OpenAI 接口标准，自由切换底层模型

## Harness Engineering：一岗位一镜像

AI Agent 的成功率不只取决于模型能力 — **执行环境的确定性同样决定性**。LinkWork 采用 **「一岗位一镜像」** 范式：Skills、MCP 工具、安全策略全部在**构建时固化到容器镜像**，运行时只读不写，彻底杜绝环境漂移。

### 构建时固化，而非运行时拉取

每次岗位构建，调度引擎自动执行完整的装配流程：

1. **Skills 注入** — 按岗位配置从 Git 仓库 clone 对应分支，锁定 commit SHA，写入 `/opt/agent/skills/`
2. **MCP 配置固化** — 生成 MCP 工具描述写入 `/opt/agent/mcp.json`，权限 0440 只读
3. **安全策略内嵌** — Cedar 策略文件打包进镜像，约束层启动时加载
4. **版本快照记录** — BuildRecord 中记录每个 Skill 的 name + commit SHA，每个 MCP Server 的配置版本

配置变更 → 必须重新构建镜像。这是**刻意的设计选择**：保证每个运行中的 AI 员工环境完全可预测、可复现。

### 上下文注入 (Context Priming)

任务启动时，SDK 自动完成执行上下文装配：

- **Skills 同步** — 将 `/opt/agent/skills/` 同步到工作目录 `<cwd>/.claude/skills/`，Runtime 按官方标准路径加载
- **Git 仓库准备** — 按任务配置自动 clone/fetch/checkout 到工作分支，AI 员工直接在真实代码仓库中工作
- **三层 Prompt 策略** — 平台 Prompt + 岗位 Prompt + 用户 Soul，构建完整的任务背景

AI 员工不是从零开始理解任务，而是**带着完整环境和上下文上岗**。

### 构建失败快速暴露 (Fail-fast)

配置了 Skills 但 Git clone 失败 → 中断构建。配置了 MCP 但生成失败 → 中断构建。**绝不静默跳过**，问题在构建阶段就暴露出来，而不是让 AI 员工带着残缺能力运行。

> 一岗位一镜像：环境即代码、版本可锁定、构建可复现、问题早暴露。

## 企业级产出保证

对企业而言，AI 能"做事"远远不够 — 产出必须**可交付、可追溯、可约束**。LinkWork 将产出保证作为一等公民：

### 结构化交付

每个任务有明确的交付模式：

- **Git 模式** — 任务启动前自动 clone/checkout 工作分支，执行完毕后自动 commit/push 并创建 Merge Request。产出即代码，走标准 Code Review 流程
- **OSS 模式** — 产出文件自动归档到对象存储，按 `user_id/task_id` 结构化存储，持久可访问

不是一段聊天记录，而是**可入库、可合并、可部署的工程交付物**。

### 全链路事件审计

每个任务从创建到完成，全程结构化事件流：

`TASK_ASSIGNED → WORKSPACE_PREPARED → SKILLS_LOADED → SKILL_SELECTED → SKILL_REFERENCED → TASK_OUTPUT_READY → WORKSPACE_ARCHIVED → TASK_COMPLETED`

每一次 LLM 调用、每一条命令执行、每一个工具请求，全部写入 Redis Stream 带时间戳记录。**AI 做了什么、用了哪个 Skill、调了哪个工具** — 完整可追溯，满足合规与审计要求。

### 安全约束层不可绕过

所有 AI 行为意图必须经过 Constraint Layer 检查：

- **Cedar 策略引擎** — 声明式安全策略，权限检查在执行前强制拦截
- **命令代理 (zzd)** — 所有 Shell 命令透明经过安全执行器，AI 员工全程不感知，无法绕过
- **高风险操作审批** — 自动拦截，人工确认后继续

> 企业不需要一个"大概能用"的 AI — 需要的是可交付、可审计、可约束的工程化生产力。

## 架构概览

```mermaid
graph TB
    User["用户 / API"]
    Web["linkwork-web<br/>前端交互层"]
    Server["linkwork-server<br/>核心调度引擎"]
    Skills["Skills Engine<br/>声明式 Skills · 版本锁定 · 构建时内嵌"]
    Gateway["linkwork-mcp-gateway<br/>MCP 工具代理"]
    SDK["linkwork-agent-sdk<br/>Agent 运行时"]
    Executor["linkwork-executor<br/>安全执行器"]
    LLM["LLM 服务<br/>OpenAI / 私有模型"]
    Tools["MCP 工具生态"]
    K8s["K8s 集群<br/>容器编排 · 资源隔离"]

    User --> Web
    Web -->|"REST / WebSocket"| Server
    Server -->|任务分发| SDK
    Server -->|Skills 编排| Skills
    Server -->|工具路由| Gateway
    Server -->|容器管理| K8s
    Skills -->|能力注入| SDK
    SDK -->|LLM 调用| LLM
    SDK -->|命令执行| Executor
    Gateway --> Tools
    K8s -.->|运行环境| SDK
    K8s -.->|运行环境| Executor
```

**工作流程**：用户创建任务 → 调度引擎在 K8s 集群中分配容器 → Agent 运行时在隔离环境中启动 → 调用 LLM 推理、通过执行器安全执行命令 → MCP 网关代理外部工具调用 → 全程实时回传执行状态。

## 与个人 AI Agent 的区别

OpenClaw 等项目是优秀的个人 AI 助手 — 跑在你的笔记本上，一个 Agent 帮你处理日常事务。LinkWork 解决的是不同层级的问题：

| | 个人 AI 助手（如 OpenClaw） | LinkWork |
|---|-------------------------|----------|
| **定位** | 个人效率工具 | 企业劳动力平台 |
| **规模** | 单人单 Agent | 多团队、多 AI 员工并行 |
| **运行环境** | 本地单机 | K8s 集群，容器隔离 |
| **能力管理** | 社区插件，自由安装 | 岗位 → Skills → 工具，三层治理 |
| **安全** | 依赖用户自觉 | 审批流 + 策略引擎 + 审计 |
| **部署** | `npm install -g` | Docker Compose / K8s |

> 个人助手解决"我的效率"，LinkWork 解决"组织的效能"。

## 组件一览

| 组件 | 说明 | 仓库 | 状态 |
|------|------|------|------|
| **linkwork-server** | 核心后端 — 任务调度、岗位管理、审批、Skills 与工具注册 | [GitHub](https://github.com/glowdan/linkwork-server) | 开源中 |
| **linkwork-executor** | 安全执行器 — 容器内命令执行、策略引擎、SSH 隔离 | [GitHub](https://github.com/glowdan/linkwork-executor) | 即将开源 |
| **linkwork-agent-sdk** | Agent 运行时 — LLM 引擎、Skills 编排、MCP 集成 | [GitHub](https://github.com/glowdan/linkwork-agent-sdk) | 即将开源 |
| **linkwork-mcp-gateway** | MCP 工具网关 — 工具发现、请求代理、鉴权、用量统计 | [GitHub](https://github.com/glowdan/linkwork-mcp-gateway) | 即将开源 |
| **linkwork-web** | 前端参考实现 — 任务面板、岗位配置、Skills 市场 | [GitHub](https://github.com/glowdan/linkwork-web) | 即将开源 |

## 开源路线图

LinkWork 采用**分批开源**策略，确保每个组件独立可用、文档完备：

| 阶段 | 组件 | 说明 | 预计时间 |
|------|------|------|---------|
| 第一批 | linkwork-server | 后端核心，含完整调度引擎和 Demo 启动器 | 2026 年 3 月下旬 |
| 第二批 | linkwork-executor + linkwork-agent-sdk | 执行层 — 安全执行器 + Agent 运行时 | 2026 年 3 月下旬 |
| 第三批 | linkwork-mcp-gateway + linkwork-web | 接入层 — MCP 工具网关 + 前端参考实现 | 2026 年 3 月底 |

> 计划于 2026 年 4 月 1 日前完成全部组件开源。关注本仓库获取最新动态。

## 许可证

[Apache License 2.0](./LICENSE)

## 关注我们

项目计划于 2026 年 4 月 1 日前完成全部开源。如果你对企业级 AI 劳动力管理感兴趣：

- 点个 **Star** 追踪最新进展
- **Watch** 本仓库获取发布通知
- 欢迎在 Issues 中提出想法和建议

---

<div align="center">

**LinkWork** — 不是给你一个 AI 助手，而是给你一支 AI 团队

</div>
