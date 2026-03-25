# LinkWork Documentation


## Quick Start

- **[Quick Start](./quick-start.md)** — Understand LinkWork's components and launch the platform services

---

## Core Concepts

Dive deep into LinkWork's design philosophy and core models.

| Document | Description |
|----------|-------------|
| [Workstation Model](./concepts/workstation.md) | Workstation → Instance → Task three-tier model, containerized execution |
| [Skills System](./concepts/skills.md) | Declarative skill definitions, version management, build-time pinning |
| [MCP Tools](./concepts/mcp-tools.md) | Standardized external tool access, unified proxy and auth |
| [Harness Engineering](./concepts/harness-engineering.md) | One role, one image — build-time immutability, runtime read-only |

---

## Architecture

Understand the system architecture and key technical decisions.

| Document | Description |
|----------|-------------|
| [Architecture Overview](./architecture/overview.md) | System context, component relationships, tech stack |
| [Core Components](./architecture/components.md) | Responsibilities and collaboration of the five core components |
| [Data Flow & Real-time Communication](./architecture/data-flow.md) | Task lifecycle, event streaming, data archival |
| [Security Architecture](./architecture/security.md) | Multi-layer security model, policy engine, approvals and audit |

---

## Guides

Deploy and extend LinkWork from scratch.

| Document | Description |
|----------|-------------|
| [Deployment Guide](./guides/deployment.md) | K8s production deployment, Harbor image registry, infrastructure requirements |
| [Extension Guide](./guides/extension.md) | Full capability stack, custom roles, Skills, MCP, file management, Git projects |
| [Watermark Config Guide](./guides/watermark-config.md) | Runtime identity watermark, provenance metadata, and verification checklist |

---

## Examples

Understand how LinkWork is used through real-world scenarios.

| Document | Description |
|----------|-------------|
| [Literature Tracker](./examples/literature-tracker.md) | Complete role configuration example using Tavily MCP search |

---

## Component Documentation

Each component repository maintains its own detailed documentation (API Reference, protocol specs, etc.):

| Component | Description | Docs Location |
|-----------|-------------|---------------|
| [linkwork-server](../linkwork-server/) | Core backend — task scheduling, role management | `linkwork-server/docs/` |
| [linkwork-executor](../linkwork-executor/) | Secure executor — command execution, policy engine | `linkwork-executor/docs/` |
| [linkwork-agent-sdk](../linkwork-agent-sdk/) | Agent runtime — LLM engine, Skills orchestration | `linkwork-agent-sdk/docs/` |
| [linkwork-mcp-gateway](../linkwork-mcp-gateway/) | MCP tool gateway — tool discovery, auth | `linkwork-mcp-gateway/docs/` |
| [linkwork-web](../linkwork-web/) | Frontend reference — task dashboard, role config | `linkwork-web/docs/` |

> Component documentation will be published progressively as each component is open-sourced.
