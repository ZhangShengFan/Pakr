# Pakr — 网页一键打包 APK (改为 BUILD_URL 模式)

> 本分支移除对 Cloudflare Pages 内置 Worker 的依赖，改为前端通过 `BUILD_URL` 直连一个构建端点（可自建代理或托管 API）。

功能与原仓库相同：填写网址和应用信息，触发 GitHub Actions 完成编译、签名与打包。

重要变更：
- 移除仓库内 Cloudflare Worker（Frontend/_worker.js 已删除）。
- 前端默认使用 `BUILD_URL = https://your-build-endpoint.example`，请替换为你自己的构建 API 地址。
- README 中已替换 Pages 部署说明，并新增如何自建一个简单代理的示例。

快速上手（前端配置）：
1. 打开 `Frontend/index.html`，找到常量 `BUILD_URL`，替换为你的构建端点（不要以 `/` 结尾），例如：

   const BUILD_URL = "https://api.example.com";

2. 构建端点应实现与原 Worker 相同的 API：
   - POST /build     -> 触发构建（返回 { run_id }）
   - GET  /status?run_id=... -> 返回运行���态与 progress/conclusion/artifacts
   - GET  /download?run_id=...&artifact_id=... -> 返回 APK（二进制或 ZIP）

下面给出一个最小 Node.js 代理示例供参考（请在服务器上运行并设置相应的环境变量）：

```js
// simple-proxy.js — minimal GitHub Actions trigger proxy
// 用法： node simple-proxy.js

const express = require('express');
const fetch = require('node-fetch');
const bodyParser = require('body-parser');

const GH = 'https://api.github.com';
const PORT = process.env.PORT || 3000;
const OWNER = process.env.GITHUB_OWNER; // 你的用户名
const REPO  = process.env.GITHUB_REPO || 'Pakr';
const TOKEN = process.env.GH_PAT; // 需要 repo + workflow 权限

if(!OWNER || !TOKEN) throw new Error('GITHUB_OWNER and GH_PAT required');

const app = express();
app.use(bodyParser.json());

app.post('/build', async (req, res) => {
  const { app_url, app_name, package_name, version_name, icon_url } = req.body;
  if(!app_url || !app_name || !package_name || !version_name) return res.status(400).json({ error: 'missing' });
  const r = await fetch(`${GH}/repos/${OWNER}/${REPO}/actions/workflows/build.yml/dispatches`, {
    method: 'POST', headers: { Authorization: `Bearer ${TOKEN}`, 'Content-Type': 'application/json' },
    body: JSON.stringify({ ref: 'main', inputs: { app_url, app_name, package_name, version_name, icon_url } })
  });
  if(r.status !== 204) return res.status(500).json({ error: 'trigger failed', detail: await r.text() });
  // TODO: 这里应当轮询 runs 找到最新 run_id，简化版直接返回 queued
  return res.status(200).json({ status: 'queued' });
});

app.get('/status', async (req, res) => {
  const runId = req.query.run_id;
  if(!runId) return res.status(400).json({ error: 'run_id required' });
  const r = await fetch(`${GH}/repos/${OWNER}/${REPO}/actions/runs/${runId}`, { headers: { Authorization: `Bearer ${TOKEN}`, Accept: 'application/vnd.github+json' }});
  const data = await r.json();
  return res.json(data);
});

app.get('/download', async (req, res) => {
  // 可实现：转发 GitHub artifact 下载或从 S3 直接返回
  res.status(501).send('Not implemented in example');
});

app.listen(PORT, ()=> console.log('proxy listening on', PORT));
```

请参阅仓库中的 `Frontend/index.html`，替换 BUILD_URL 后即可直接部署前端（静态站点）或本地打开进行测试。
