# LinkWork 文档


## 快速入口

- **[快速开始](./quick-start_zh-CN.md)** — 了解 LinkWork 的组成结构并启动平台服务

---

## 核心概念

深入理解 LinkWork 的设计理念和核心模型。

| 文档 | 说明 |
|------|------|
| [岗位模型](./concepts/workstation_zh-CN.md) | Workstation → Instance → Task 三层模型，容器化运行 |
| [Skills 系统](./concepts/skills_zh-CN.md) | 声明式技能定义、版本管理、构建时锁定 |
| [MCP 工具](./concepts/mcp-tools_zh-CN.md) | 标准化外部工具接入、统一代理与鉴权 |
| [Harness Engineering](./concepts/harness-engineering_zh-CN.md) | 一岗位一镜像，构建时固化、运行时只读 |

---

## 架构设计

了解系统架构与关键技术决策。

| 文档 | 说明 |
|------|------|
| [系统架构总览](./architecture/overview_zh-CN.md) | 系统上下文、组件关系、技术栈 |
| [核心组件](./architecture/components_zh-CN.md) | 五大组件的职责与协作方式 |
| [数据流与实时通信](./architecture/data-flow_zh-CN.md) | 任务生命周期、事件推送、数据归档 |
| [安全架构](./architecture/security_zh-CN.md) | 多层安全模型、策略引擎、审批与审计 |

---

## 使用指南

从零开始部署和扩展 LinkWork。

| 文档 | 说明 |
|------|------|
| [部署指南](./guides/deployment_zh-CN.md) | K8s 生产部署、Harbor 镜像仓库、基础设施要求 |
| [扩展开发](./guides/extension_zh-CN.md) | 完整能力体系、自定义岗位、Skills、MCP、文件管理、Git 项目 |
| [水印配置指南](./guides/watermark-config_zh-CN.md) | 运行时身份水印、产物溯源元数据与校验方法 |

---

## 实践案例

通过真实场景理解 LinkWork 的使用方式。

| 文档 | 说明 |
|------|------|
| [文献追踪员](./examples/literature-tracker_zh-CN.md) | 完整岗位配置案例，使用 Tavily MCP 搜索 |

---

## 组件文档

每个组件仓库维护各自的详细文档（API Reference、协议规范等）：

| 组件 | 说明 | 文档位置 |
|------|------|---------|
| [linkwork-server](../linkwork-server/) | 核心后端 — 任务调度、岗位管理 | `linkwork-server/docs/` |
| [linkwork-executor](../linkwork-executor/) | 安全执行器 — 命令执行、策略引擎 | `linkwork-executor/docs/` |
| [linkwork-agent-sdk](../linkwork-agent-sdk/) | Agent 运行时 — LLM 引擎、Skills 编排 | `linkwork-agent-sdk/docs/` |
| [linkwork-mcp-gateway](../linkwork-mcp-gateway/) | MCP 工具网关 — 工具发现、鉴权 | `linkwork-mcp-gateway/docs/` |
| [linkwork-web](../linkwork-web/) | 前端参考实现 — 任务面板、岗位配置 | `linkwork-web/docs/` |

> 组件文档随各组件代码开源后逐步发布。
