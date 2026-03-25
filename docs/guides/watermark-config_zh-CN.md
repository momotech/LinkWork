# 水印配置指南

本文说明如何为 LinkWork 配置运行时身份水印与产物溯源信息。

---

## 环境变量

| 变量 | 默认值 | 必填 | 说明 |
|------|--------|------|------|
| `LINKWORK_WATERMARK_NAME` | `LinkWork` | 否 | 注入到运行时提示词、日志、MCP 请求头、产物溯源文件中的产品名 |
| `LINKWORK_WATERMARK_OWNER` | `momotech` | 否 | 归属方/组织标识 |
| `LINKWORK_WATERMARK_REPO_URL` | `https://github.com/momotech/LinkWork` | 否 | 官方源码仓库地址 |
| `LINKWORK_WATERMARK_POLICY_URL` | `https://github.com/momotech/LinkWork/blob/master/TRADEMARK_POLICY.md` | 否 | 商标策略地址 |
| `LINKWORK_WATERMARK_SECRET` | 空 | 否 | HMAC 密钥；配置后会生成带签名的水印 |
| `VITE_WATERMARK_PRODUCT` | `LinkWork` | 否 | 前端可见水印产品名 |
| `VITE_WATERMARK_OWNER` | `momotech` | 否 | 前端可见水印归属方 |

### 示例

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

## 生效范围

- `linkwork-agent-sdk`：
  - 在系统提示词附加身份水印块。
  - 在日志流中写入 `WATERMARK_ATTACHED` 事件。
- `linkwork-executor`：
  - 归档/提交前写入 `LINKWORK_PROVENANCE.json`。
  - 任务事件自动附带水印元数据。
  - Git 自动提交信息包含水印 ID。
- `linkwork-mcp-gateway`：
  - 代理请求自动注入 `X-LinkWork-*` 请求头。
  - 配置密钥后附带 `X-LinkWork-Signature`。
- `linkwork-web`：
  - 侧栏底部显示可见水印标签。

---

## 校验清单

1. 任务日志流出现事件类型 `WATERMARK_ATTACHED`。
2. 任务输出目录包含 `LINKWORK_PROVENANCE.json`。
3. MCP 上游服务能收到 `X-LinkWork-Product` / `X-LinkWork-Watermark` 请求头。
4. Web 侧栏底部展示 `LinkWork OSS · <owner>`。

---

## 运行建议

- 生产环境建议为每个环境（dev/staging/prod）使用不同 `LINKWORK_WATERMARK_SECRET`。
- 若需要对外审计，可将 `LINKWORK_PROVENANCE.json` 与任务日志一起归档。
- 对外分发时请配合仓库根目录 `NOTICE` 与 `TRADEMARK_POLICY.md` 使用。

