# Security Policy

## Supported Versions

| Version | Supported |
|---------|-----------|
| 0.1.x   | Yes       |
| < 0.1   | No        |

## Reporting a Vulnerability

**Do NOT report security vulnerabilities via public GitHub Issues.**

LinkWork uses [GitHub Private Vulnerability Reporting](https://docs.github.com/en/code-security/security-advisories/guidance-on-reporting-and-writing-information-about-vulnerabilities/privately-reporting-a-security-vulnerability).

### How to Report

1. Go to the [Security Advisories](https://github.com/glowdan/LinkWork/security/advisories/new) page.
2. Click **"Report a vulnerability"**.
3. Fill in the details: description, impact, reproduction steps, and any suggested fix.

You do **not** need to disclose your email address publicly.

### Response Timeline

| Milestone | Target |
|-----------|--------|
| Acknowledgment | Within 48 hours |
| Initial assessment | Within 5 business days |
| Fix / mitigation plan | Within 14 days |
| Public disclosure | After fix is released |

### Scope

The following are **in scope**:

- Authentication and authorization bypass
- Remote code execution
- Container escape / sandbox bypass in `linkwork-executor`
- Injection vulnerabilities (SQL, command, path traversal)
- Secrets exposure in logs or API responses
- Privilege escalation

The following are **out of scope**:

- Vulnerabilities in third-party dependencies not yet patched upstream
- Issues requiring physical access to the server
- Social engineering attacks

### Disclosure Policy

We follow coordinated disclosure. We will credit reporters in the security advisory unless they prefer to remain anonymous.
