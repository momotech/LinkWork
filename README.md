<div align="center">

# 🪢 LinkWork

### Make AI Work Like Your Team

**Open-source enterprise AI workforce platform — Roles · Skills · Tools · Security · Scheduling, all in one place**

English | [中文](./README_zh-CN.md)

</div>

---

## What Is LinkWork

LinkWork is an open-source **AI workforce management platform**.

You can run it like a company: create **roles**, equip each role with **skills**, authorize available **tools**, set **security policies**, arrange **shift schedules** — then let your AI workers run 24/7 in their own isolated containers, track progress in real time, and automatically intercept high-risk operations for human approval.

Not a chatbot. Not a personal assistant. An **enterprise-grade AI team management system**.

> Before paying AI a salary, give it a role, a skill set, and a security policy.

## Core Design Philosophy

### Every AI Worker Is a Containerized Service

An AI worker isn't a process running on the host machine. Each AI worker runs in an independent **Docker / K8s container** with:

- **Isolated execution environment** — Filesystem, network, and processes fully isolated between workers
- **Dedicated resource quotas** — CPU and memory allocated on demand, preventing any single worker from crashing the cluster
- **Persistent workspace** — Task outputs, intermediate state, and long-term memory preserved across sessions
- **Fixed skill configuration** — Install capabilities like apps — they persist across restarts
- **Policy-controlled command boundaries** — Policy engine governs what each worker can and cannot execute

Manage your AI team like a microservice cluster — fully leveraging the K8s cloud-native ecosystem:

- **Volcano Gang Scheduling** — Powered by [Volcano](https://volcano.sh/) scheduler, PodGroup ensures atomic scheduling of multi-replica AI workers, with priority queues (critical / high / normal / low) for fine-grained resource allocation
- **Sidecar Execution Isolation** — Production environments use dual-container mode (Agent + Runner), process-level isolation between AI reasoning and command execution, clear security boundaries
- **Elastic Scaling** — Dynamic Scale Up/Down based on task queue depth, auto scale-to-zero on idle timeout, conserving compute resources
- **Resource Governance** — `ai-worker` Namespace isolation + ResourceQuota management, preventing any single role from consuming excessive cluster resources
- **Self-healing** — K8s auto-restarts on container OOM or process crash; stale working directories auto-cleaned on next startup

### Skills & Tool Marketplace: The App Store for AI Capabilities

LinkWork breaks down AI capabilities into three governable layers, managed like an App Store:

**Role** — A complete AI worker definition
> Includes persona, job description, available Skills list, and tool permissions. Create a "Frontend Engineer" role, and any AI model instance can start working immediately.

**Skills** — Installable capability modules
> Declaratively defined. Each Skill is managed as an independent Git branch, version-pinned by commit SHA and injected into the container at build time. "Code Review", "Data Analysis", "Document Writing" are independent Skills, mix and match across roles.

**MCP Tool** — Standardized external capability access
> Compatible with the [Model Context Protocol](https://modelcontextprotocol.io/) standard. Database queries, API calls, file operations, browser control — all accessed through a unified tool bus with automatic proxy, auth, and metering.

**Role → Skills → Tool** — three decoupled layers, freely composable, **access-controlled**. Enterprise admins decide which roles can use which Skills and tools, rather than the AI installing whatever it wants.

## Key Features

- **Containerized Service Orchestration** — Each AI worker runs in its own container, K8s-native scheduling with elastic scaling and self-healing
- **AI Role Management** — Define job responsibilities and capability boundaries; swap workers without changing roles
- **Skills Marketplace** — Declarative Skills managed via Git branches, version-pinned by commit SHA and embedded at build time
- **MCP Tool Bus** — Compatible with [MCP protocol](https://modelcontextprotocol.io/) standard, unified proxy, auth, and usage metering
- **Task Orchestration & Real-time Tracking** — Dispatch tasks, watch execution via WebSocket streaming, fully observable
- **Security Approval Workflow** — Risk-tiered policy engine, high-risk operations auto-intercepted, proceed only after human confirmation
- **Scheduled Shifts** — Cron-driven, AI workers execute on schedule without manual triggering
- **Vector Memory** — Milvus-based long-term memory, cross-task knowledge accumulation and semantic retrieval
- **Multi-model Support** — Compatible with OpenAI API standard, freely switch underlying models

## Harness Engineering: One Role, One Image

AI Agent success isn't just about model capability — **execution environment determinism is equally decisive**. LinkWork adopts a **"One Role, One Image"** paradigm: Skills, MCP tools, and security policies are all **baked into the container image at build time**. Runtime is read-only — no drift, no surprises.

### Build-time Immutability, Not Runtime Fetching

Each role build triggers a complete assembly pipeline:

1. **Skills injection** — Clone corresponding Git branches per role config, pin to commit SHA, write to `/opt/agent/skills/`
2. **MCP config baking** — Generate MCP tool descriptors, write to `/opt/agent/mcp.json` (permission 0440, read-only)
3. **Security policy embedding** — Cedar policy files packaged into the image, loaded by the Constraint Layer at startup
4. **Version snapshot recording** — BuildRecord captures each Skill's name + commit SHA, each MCP Server's config version

Config change → must rebuild the image. This is a **deliberate design choice**: every running AI worker's environment is fully predictable and reproducible.

### Context Priming

At task startup, the SDK automatically assembles the execution context:

- **Skills sync** — Sync `/opt/agent/skills/` to the working directory `<cwd>/.claude/skills/`, Runtime loads via the official standard path
- **Git repo preparation** — Auto clone/fetch/checkout to the task branch per config; AI workers operate directly in real code repositories
- **Three-tier Prompt strategy** — Platform Prompt + Role Prompt + User Soul, building complete task background

AI workers don't start from zero — they **arrive on the job with full environment and context**.

### Fail-fast on Build Failures

Skills configured but Git clone fails → build aborted. MCP configured but generation fails → build aborted. **Never silently skipped** — problems surface at build time, not when an AI worker runs with broken capabilities.

> One Role, One Image: environment as code, versions pinned, builds reproducible, failures exposed early.

## Enterprise-grade Output Guarantees

For enterprises, AI that can "do stuff" isn't enough — outputs must be **deliverable, traceable, and constrained**. LinkWork treats output guarantees as a first-class citizen:

### Structured Delivery

Every task has a clear delivery mode:

- **Git mode** — Auto clone/checkout a working branch before task start, auto commit/push and create Merge Request after completion. Output is code, going through standard Code Review workflows
- **OSS mode** — Output files auto-archived to object storage, structured by `user_id/task_id`, persistently accessible

Not a chat transcript — **engineering deliverables ready to merge and deploy**.

### Full Event Audit Trail

Every task from creation to completion, structured event stream throughout:

`TASK_ASSIGNED → WORKSPACE_PREPARED → SKILLS_LOADED → SKILL_SELECTED → SKILL_REFERENCED → TASK_OUTPUT_READY → WORKSPACE_ARCHIVED → TASK_COMPLETED`

Every LLM call, every command execution, every tool request — all written to Redis Stream with timestamps. **What the AI did, which Skill it used, which tool it called** — fully traceable, meeting compliance and audit requirements.

### Inescapable Security Constraint Layer

All AI behavioral intents must pass through the Constraint Layer:

- **Cedar policy engine** — Declarative security policies, permission checks enforced before execution
- **Command proxy (zzd)** — All shell commands transparently routed through the secure executor; AI workers are completely unaware and cannot bypass it
- **High-risk operation approval** — Auto-intercepted, proceeds only after human confirmation

> Enterprises don't need AI that "probably works" — they need deliverable, auditable, constrained engineering productivity.

## Architecture

```mermaid
graph TB
    User["User / API"]
    Web["linkwork-web<br/>Frontend"]
    Server["linkwork-server<br/>Core Engine"]
    Skills["Skills Engine<br/>Declarative Skills · Version Pinning · Build-time Embed"]
    Gateway["linkwork-mcp-gateway<br/>MCP Tool Proxy"]
    SDK["linkwork-agent-sdk<br/>Agent Runtime"]
    Executor["linkwork-executor<br/>Secure Executor"]
    LLM["LLM Services<br/>OpenAI / Private Models"]
    Tools["MCP Tool Ecosystem"]
    K8s["K8s Cluster<br/>Orchestration · Isolation"]

    User --> Web
    Web -->|"REST / WebSocket"| Server
    Server -->|"Task Dispatch"| SDK
    Server -->|"Skill Orchestration"| Skills
    Server -->|"Tool Routing"| Gateway
    Server -->|"Container Mgmt"| K8s
    Skills -->|"Capability Injection"| SDK
    SDK -->|"LLM Calls"| LLM
    SDK -->|"Command Exec"| Executor
    Gateway --> Tools
    K8s -.->|"Runtime Env"| SDK
    K8s -.->|"Runtime Env"| Executor
```

**How it works**: User creates a task → Core engine allocates a container in the K8s cluster → Agent runtime starts in an isolated environment → Calls LLM for reasoning, securely executes commands through the executor → MCP gateway proxies external tool calls → Execution status streams back in real time.

## How It Differs from Personal AI Agents

Projects like OpenClaw are excellent personal AI assistants — running on your laptop, one Agent handling your daily tasks. LinkWork addresses a different level of the problem:

| | Personal AI Assistants (e.g. OpenClaw) | LinkWork |
|---|--------------------------------------|----------|
| **Positioning** | Personal productivity tool | Enterprise workforce platform |
| **Scale** | Single user, single Agent | Multi-team, multiple AI workers in parallel |
| **Runtime Env** | Local single machine | K8s cluster, container isolation |
| **Capability Mgmt** | Community plugins, self-install | Role → Skill → Tool, three-tier governance |
| **Security** | Relies on user discretion | Approval workflow + policy engine + audit |
| **Deployment** | `npm install -g` | Docker Compose / K8s |

> Personal assistants solve "my productivity". LinkWork solves "organizational effectiveness".

## Components

| Component | Description | Repo | Status |
|-----------|-------------|------|--------|
| **linkwork-server** | Core backend — task scheduling, role management, approvals, Skills & tool registry | [GitHub](https://github.com/glowdan/linkwork-server) | Open-sourcing |
| **linkwork-executor** | Secure executor — in-container command execution, policy engine, SSH isolation | [GitHub](https://github.com/glowdan/linkwork-executor) | Coming soon |
| **linkwork-agent-sdk** | Agent runtime — LLM engine, Skills orchestration, MCP integration | [GitHub](https://github.com/glowdan/linkwork-agent-sdk) | Coming soon |
| **linkwork-mcp-gateway** | MCP tool gateway — tool discovery, request proxy, auth, usage metering | [GitHub](https://github.com/glowdan/linkwork-mcp-gateway) | Coming soon |
| **linkwork-web** | Frontend reference — task dashboard, role config, Skills marketplace | [GitHub](https://github.com/glowdan/linkwork-web) | Coming soon |

## Open-source Roadmap

LinkWork follows a **phased open-source** strategy, ensuring each component is independently usable and well-documented:

| Phase | Components | Description | ETA |
|-------|-----------|-------------|-----|
| Phase 1 | linkwork-server | Backend core with full scheduling engine and demo launcher | Late March 2026 |
| Phase 2 | linkwork-executor + linkwork-agent-sdk | Execution layer — secure executor + Agent runtime | Late March 2026 |
| Phase 3 | linkwork-mcp-gateway + linkwork-web | Access layer — MCP tool gateway + frontend reference implementation | End of March 2026 |

> All components are planned to be fully open-sourced before April 1, 2026. Watch this repo for updates.

## License

[Apache License 2.0](./LICENSE)

## Stay Connected

All components are planned to be fully open-sourced before April 1, 2026. If you're interested in enterprise AI workforce management:

- **Star** this repo to track progress
- **Watch** for release notifications
- Feel free to share ideas and suggestions in Issues

---

<div align="center">

**LinkWork** — Not just an AI assistant. An AI team.

</div>
