# Contributing to LinkWork

Thank you for your interest in contributing to LinkWork! This document outlines the process for contributing to this project.

Issues and pull requests may be written in **English or Chinese (中文)**.

---

## Table of Contents

- [Code of Conduct](#code-of-conduct)
- [Getting Started](#getting-started)
- [Branch Strategy](#branch-strategy)
- [Commit Convention](#commit-convention)
- [DCO Sign-off](#dco-sign-off)
- [Pull Request Process](#pull-request-process)
- [Code Style](#code-style)
- [Reporting Bugs](#reporting-bugs)
- [Suggesting Features](#suggesting-features)

---

## Code of Conduct

By participating in this project, you agree to abide by the [Code of Conduct](CODE_OF_CONDUCT.md). Please read it before contributing.

---

## Getting Started

1. Fork the repository and clone your fork locally.
2. Set up the development environment (see the relevant component's `README.md`).
3. Create a new branch from `main` for your changes.
4. Make your changes, commit with DCO sign-off, and open a Pull Request.

---

## Branch Strategy

| Branch | Purpose |
|--------|---------|
| `main` | Protected branch — always releasable |
| `feature/<name>` | New features |
| `fix/<name>` | Bug fixes |
| `docs/<name>` | Documentation updates |
| `chore/<name>` | Tooling, CI, dependency updates |

**Rules:**

- Never commit directly to `main`.
- All changes go through a Pull Request.
- Feature branches should be short-lived and focused on a single change.

---

## Commit Convention

We follow [Conventional Commits](https://www.conventionalcommits.org/en/v1.0.0/).

```
<type>(<scope>): <short description>

[optional body]

[optional footer(s)]
Signed-off-by: Your Name <your@email.com>
```

### Types

| Type | When to use |
|------|------------|
| `feat` | A new feature |
| `fix` | A bug fix |
| `docs` | Documentation changes only |
| `style` | Formatting changes (no logic change) |
| `refactor` | Code refactoring (no feature/bug change) |
| `test` | Adding or updating tests |
| `chore` | Tooling, CI, dependency updates |
| `perf` | Performance improvements |

**Examples:**

```
feat(server): add task retry policy configuration
fix(executor): prevent sandbox escape via symlink traversal
docs: update quick-start guide for Docker Compose v2
chore(deps): bump Spring Boot to 3.4.3
```

---

## DCO Sign-off

LinkWork uses the [Developer Certificate of Origin (DCO)](https://developercertificate.org/) instead of a CLA. You must sign off every commit:

```bash
git commit -s -m "feat: your change description"
```

This adds a `Signed-off-by` trailer to your commit, certifying that you have the right to submit the code under the project's license.

**Configuring Git to auto sign-off:**

```bash
git config --global user.name "Your Name"
git config --global user.email "your@email.com"
```

The [DCO GitHub App](https://github.com/apps/dco) will automatically check all commits in a PR. PRs with unsigned commits will be blocked from merging.

---

## Pull Request Process

1. **One purpose per PR** — keep changes focused and reviewable.
2. **Fill in the PR template** — describe what changed and why.
3. **CI must pass** — all checks (lint, tests, build) must be green.
4. **At least 1 maintainer review** is required before merging.
5. **Squash or rebase** — maintain a clean commit history on `main`.
6. **Link related issues** — use `Closes #<issue>` in the PR description.

---

## Code Style

Each component enforces its own linter. CI will check these automatically.

| Component | Language | Linter |
|-----------|----------|--------|
| `linkwork-server` | Java | [Checkstyle](https://checkstyle.org/) |
| `linkwork-executor` | Go | [golangci-lint](https://golangci-lint.run/) |
| `linkwork-agent-sdk` | Go | [golangci-lint](https://golangci-lint.run/) |
| `linkwork-mcp-gateway` | Go | [golangci-lint](https://golangci-lint.run/) |
| `linkwork-web` | TypeScript | [ESLint](https://eslint.org/) |

Run the linter locally before submitting a PR:

```bash
# Java
mvn checkstyle:check

# Go
golangci-lint run ./...

# TypeScript
pnpm lint
```

---

## Reporting Bugs

Use the [Bug Report](.github/ISSUE_TEMPLATE/bug_report.yml) issue template. Please include:

- A clear description of the problem
- Steps to reproduce
- Expected vs. actual behavior
- Version information (component version, OS, Docker/K8s version)
- Relevant logs (sanitized of sensitive data)

**Security vulnerabilities must not be reported via public issues.** See [SECURITY.md](SECURITY.md) for the responsible disclosure process.

---

## Suggesting Features

Use the [Feature Request](.github/ISSUE_TEMPLATE/feature_request.yml) issue template. Please describe:

- The problem you're trying to solve
- Your proposed solution
- Any alternatives you considered

---

## Questions?

For general questions and discussions, open a [GitHub Discussion](https://github.com/hellogroup-oss/LinkWork/discussions).
