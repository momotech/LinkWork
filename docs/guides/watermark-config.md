# Watermark Configuration Guide

This guide explains how to configure LinkWork runtime watermark and provenance metadata.

---

## Environment Variables

| Variable | Default | Required | Description |
|----------|---------|----------|-------------|
| `LINKWORK_WATERMARK_NAME` | `LinkWork` | No | Product name injected into runtime prompt, logs, MCP headers and provenance files |
| `LINKWORK_WATERMARK_OWNER` | `momotech` | No | Owner/org label for attribution |
| `LINKWORK_WATERMARK_REPO_URL` | `https://github.com/momotech/LinkWork` | No | Official source repository URL |
| `LINKWORK_WATERMARK_POLICY_URL` | `https://github.com/momotech/LinkWork/blob/master/TRADEMARK_POLICY.md` | No | Trademark policy URL used in watermark disclosure |
| `LINKWORK_WATERMARK_SECRET` | empty | No | HMAC secret; when set, signed watermark is generated |
| `VITE_WATERMARK_PRODUCT` | `LinkWork` | No | Frontend visible watermark product label |
| `VITE_WATERMARK_OWNER` | `momotech` | No | Frontend visible watermark owner label |

### Example

```bash
LINKWORK_WATERMARK_NAME=LinkWork
LINKWORK_WATERMARK_OWNER=momotech
LINKWORK_WATERMARK_REPO_URL=https://github.com/momotech/LinkWork
LINKWORK_WATERMARK_POLICY_URL=https://github.com/momotech/LinkWork/blob/master/TRADEMARK_POLICY.md
LINKWORK_WATERMARK_SECRET=replace-with-strong-random-secret
VITE_WATERMARK_PRODUCT=LinkWork
VITE_WATERMARK_OWNER=momotech
```

---

## Runtime Effects

- `linkwork-agent-sdk`:
  - Injects watermark identity block into system prompt append.
  - Emits `WATERMARK_ATTACHED` event in runtime log stream.
- `linkwork-executor`:
  - Writes `LINKWORK_PROVENANCE.json` into deliverables before archiving/commit.
  - Adds watermark metadata to task events.
  - Includes watermark id in auto commit message.
- `linkwork-mcp-gateway`:
  - Injects `X-LinkWork-*` headers when proxying requests.
  - Adds `X-LinkWork-Signature` when `LINKWORK_WATERMARK_SECRET` is configured.
- `linkwork-web`:
  - Shows visible watermark label in sidebar footer.

---

## Verification Checklist

1. Task runtime stream contains event type `WATERMARK_ATTACHED`.
2. Task output path contains file `LINKWORK_PROVENANCE.json`.
3. MCP upstream receives `X-LinkWork-Product` / `X-LinkWork-Watermark` headers.
4. Web sidebar footer displays `LinkWork OSS · <owner>`.

---

## 中文摘要

- 配置 `LINKWORK_WATERMARK_*` 可把平台身份注入到 Prompt、日志、产物文件、MCP 请求头。
- 配置 `LINKWORK_WATERMARK_SECRET` 后，会生成可验签的水印签名（HMAC-SHA256）。
- 前端通过 `VITE_WATERMARK_PRODUCT` / `VITE_WATERMARK_OWNER` 显示可见水印。
- 建议生产环境为每个部署环境使用不同 `LINKWORK_WATERMARK_SECRET`。

