<div align="center">

# Pakr — 网页一键打包 APK

**填写网址和应用信息，3~5 分钟自动生成可安装的 Android APK。**  
无需本地环境，全程云端完成编译、签名、打包。

[![License: MIT](https://img.shields.io/badge/License-MIT-green.svg)](LICENSE)
[![Android](https://img.shields.io/badge/Platform-Android-3ddc84.svg?logo=android&logoColor=white)](https://developer.android.com)
[![Cloudflare Pages](https://img.shields.io/badge/Hosted_on-Cloudflare_Pages-f6821f.svg?logo=cloudflare&logoColor=white)](https://pages.cloudflare.com)
[![GitHub Actions](https://img.shields.io/badge/CI-GitHub_Actions-2088ff.svg?logo=githubactions&logoColor=white)](https://github.com/features/actions)

**[🚀 在线体验](https://apk.091224.xyz)　·　[📖 使用文档](https://apk.091224.xyz/Docs)**

</div>

---

## 功能特性

| 功能 | 说明 |
|------|------|
| 🔨 全自动构建 | 触发 GitHub Actions，自动完成编译 → 签名 → 打包 |
| 📊 实时进度 | 显示精确百分比和当前步骤，随时掌握构建进展 |
| 📱 全屏 WebView | 沉浸式全屏 |
| 🔏 Release 签名 | 自动 Keystore 签名，支持版本升级覆盖安装 |
| 📦 多架构输出 | 同时生成 arm64-v8a + armeabi-v7a 两个 APK，体积约 4MB |
| 🌐 支持任意网址 | HTTP / HTTPS 均可，支持 Cookie、文件上传、摄像头权限 |
| ⬇️ 系统下载 | 网页触发的下载通过系统 DownloadManager 保存到本地 |
| 🔄 下拉刷新 | 滚动到顶部时下拉刷新页面 |
| ⌨️ 键盘适配 | 软键盘弹出时页面自动上移，表单不被遮挡 |
| 🗂️ 打包历史 | 记录最近打包记录，支持一键重新填入 |
| 🌙 深色模式 | 跟随系统深色/浅色模式，支持手动切换 |

---

## 架构说明

前后端合并部署在 **Cloudflare Pages**，无需单独部署 Worker。

```
浏览器
  │
  ▼
Cloudflare Pages（index.html + _worker.js）
  │  前端页面 + API 接口合二为一
  ▼
GitHub Actions ── 触发构建 / 查询进度 / 下载 APK
```

---

## 项目结构

```
Pakr/
├── .github/workflows/
│   ├── build.yml              # 主构建流程
│   └── gen-keystore.yml       # 生成签名 Keystore
├── Scripts/
│   └── process_icon.py        # 图标处理脚本
├── Docs/
│   └── index.html             # 使用文档站
├── index.html                 # 前端页面
├── _worker.js                 # API 接口（Cloudflare Pages Advanced Mode）
└── app/                       # Android 项目源码
    └── src/main/java/com/webviewapp/
        ├── MainActivity.kt
        ├── SplashActivity.kt
        ├── TopProgressBar.kt
        └── IOSSpinnerView.kt
```

---

## 快速部署

> 完整教程见 **[📖 文档 → 快速部署](https://apk.091224.xyz/Docs)**

### 前置要求

- GitHub 账号（用于 Actions 构建，免费）
- Cloudflare 账号（用于 Pages 托管，免费）

### 第一步 — Fork 仓库

点击右上角 **Fork**，将本仓库复制到你自己的账号下。

### 第二步 — 生成签名 Keystore

仓库 → **Actions** → **gen-keystore** → **Run workflow**

填写密码后运行，完成后复制日志中输出的 Base64 字符串备用。

### 第三步 — 配置 GitHub Secrets

仓库 → **Settings** → **Secrets and variables** → **Actions** → **New repository secret**

| Secret 名称 | 说明 |
|-------------|------|
| `KEYSTORE_BASE64` | 上一步输出的 Base64 Keystore 字符串 |
| `KEYSTORE_PASSWORD` | Keystore 密码 |
| `KEY_ALIAS` | Key 别名（默认 `release`） |
| `KEY_PASSWORD` | Key 密码（同 Keystore 密码） |
| `GH_PAT` | GitHub PAT（需要 `repo` + `workflow` 权限） |

### 第四步 — 部署到 Cloudflare Pages

1. [Cloudflare Dashboard](https://dash.cloudflare.com) → **Workers & Pages** → **Create** → **Pages** → **Connect to Git**
2. 选择 Fork 的仓库，构建配置如下：

   | 配置项 | 值 |
   |--------|----|
   | Framework preset | None |
   | Build command | （留空） |
   | Build output directory | `/` |

3. **Settings** → **Environment variables** 添加：

   | 变量名 | 值 |
   |--------|----|
   | `GITHUB_OWNER` | 你的 GitHub 用户名 |
   | `GITHUB_REPO` | `Pakr` |
   | `GH_PAT` | 你的 GitHub PAT |

4. **Save and Deploy**，部署完成后即可访问。

### 第五步 — 验证

打开 Pages 分配的域名，填写测试信息，点击「开始打包」，3~5 分钟后下载 APK 安装验证。

---

## 构建流程

```
提交表单
   │
   ▼
_worker.js ──→ 触发 GitHub Actions (workflow_dispatch)
   │
   ▼
GitHub Actions
   ├── 1. Inject parameters   注入 URL / 包名 / 版本号等参数
   ├── 2. Process icon        下载图标，生成多尺寸 mipmap
   ├── 3. Build APK           Gradle 编译（arm64 + armeabi-v7a）
   └── 4. Sign & Upload       zipalign + apksigner 签名，上传 Artifact
   │
   ▼
前端每 5 秒轮询 /api/status，实时更新进度百分比和步骤状态
   │
   ▼
构建完成 → 显示多架构下载按钮，记录到历史面板
```

---

## 注意事项

- GitHub Actions 免费账号每月有 **2000 分钟**额度，单次构建约消耗 **3~5 分钟**
- 未配置 Keystore Secrets 时自动使用临时 Debug Key 签名，**不同次打包签名不一致，无法升级覆盖安装**
- 打包历史记录保存在浏览器本地，清除缓存后会丢失

---

## License

[MIT](LICENSE)
