# LinkWork — AGENTS.md

> AI Agent 协作入口文档。所有参与本项目的 AI Agent 必须先阅读此文件。

---

## 核心原则 (Core Principles)

1. **文档必须包含中文 (Bilingual Docs)**：所有面向用户的文档（README、docs/、注释中的关键说明）必须提供中文内容，可采用中英双语或纯中文。治理文件（CONTRIBUTING、CODE_OF_CONDUCT、SECURITY）保持英文。
2. **安全第一 (Security First)**：零容忍安全漏洞。容器逃逸、注入、越权等问题必须立即修复。详见 [SECURITY.md](SECURITY.md)。
3. **Design First**：修改代码前先更新 `docs/` 下的设计文档。文档是唯一的真理来源。
4. **不吞异常 (No Silent Failures)**：精准捕获异常，禁止空 catch；兜底层必须打日志 + 告警。
5. **不重复造轮子 (Anti-Wheel-Reinventing)**：优先使用项目内已有工具类和组件。

---

## 项目结构 (Project Structure)

```
LinkWork/
├── AGENTS.md                 # 本文件 — Agent 协作入口
├── README.md                 # 英文项目介绍
├── README_zh-CN.md           # 中文项目介绍
├── CONTRIBUTING.md           # 贡献指南
├── CODE_OF_CONDUCT.md        # 行为准则 (Contributor Covenant v2.1)
├── SECURITY.md               # 安全漏洞报告流程
├── CHANGELOG.md              # 变更日志
├── LICENSE                   # Apache License 2.0
├── .github/                  # GitHub 基础设施
│   ├── ISSUE_TEMPLATE/       # Issue 模板 (bug / feature)
│   ├── pull_request_template.md
│   └── CODEOWNERS
├── docs/                     # 项目文档（中文）
│   ├── quick-start.md        # 快速开始
│   ├── architecture.md       # 架构设计
│   ├── deployment.md         # 部署指南
└── └── extension-guide.md    # 扩展开发指南
```

---

## Agent 工作流 (Workflow)

1. **Context** — 阅读本文件 + `docs/` + 相关组件的 README，理解现有架构。
2. **Design** — 先更新设计文档，再动代码。
3. **Implement** — 保持风格统一，不吞异常，原子化提交，遵循 [CONTRIBUTING.md](CONTRIBUTING.md)。
4. **Verify** — 本地构建 + lint + 测试通过后再提交。
5. **Close** — 更新 CHANGELOG / 任务状态，确保文档闭环。

---

## 分支与提交规范

详见 [CONTRIBUTING.md](CONTRIBUTING.md)，要点：

- 分支：`master`（保护分支）→ `feature/*` / `fix/*` / `docs/*` / `chore/*`
- 提交：[Conventional Commits](https://www.conventionalcommits.org/) + DCO sign-off (`git commit -s`)
- PR：至少 1 个 maintainer review，CI 必须通过

---

## 文档索引

| 文档 | 路径 | 说明 |
|------|------|------|
| 快速开始 | `docs/quick-start.md` | 5 分钟 Docker Compose 启动 |
| 架构设计 | `docs/architecture.md` | 系统架构与数据流 |
| 部署指南 | `docs/deployment.md` | Docker Compose / K8s 生产部署 |
| 扩展开发 | `docs/extension-guide.md` | 自定义 Skill / MCP Tool / Role |

---

## 安全红线

- 容器逃逸、命令注入、路径穿越 → 立即修复，不可降级
- 敏感信息（密钥、Token）→ 环境变量注入，禁止硬编码、禁止日志输出
- 高风险操作 → 必须走审批流，不可绕过
- 安全漏洞报告 → 仅通过 [GitHub Private Vulnerability Reporting](https://github.com/hellogroup-oss/LinkWork/security/advisories/new)，禁止公开 Issue
