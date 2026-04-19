# 网页转 APK 工具

> 填写网址和基本信息，3~5 分钟自动生成可安装的 Android APK。
> 无需本地安装任何工具，全程云端完成编译、签名、打包。

**在线体验：** [apk-c1m.pages.dev](https://apk-c1m.pages.dev)

---

## 功能特性

| 功能 | 说明 |
|------|------|
| 🔨 全自动构建 | 提交表单触发 GitHub Actions，自动完成编译 → 签名 → 打包 |
| 📊 精确进度 | 实时解析 Actions job steps，显示精确百分比和当前步骤 |
| 📱 全屏 WebView | 沉浸式全屏，iOS 风格加载动画，进度条颜色跟随网页主题色 |
| 🔏 Release 签名 | 自动 Keystore 签名，可直接侧载安装，支持版本升级覆盖 |
| 📦 多架构输出 | 同时生成 `arm64-v8a` + `armeabi-v7a` 两个 APK，体积约 4MB |
| 🌐 支持任意网址 | HTTP / HTTPS 均可，支持 Cookie、文件上传、摄像头权限 |
| ⬇️ 下载支持 | 网页内触发的文件下载通过系统 DownloadManager 保存到本地 |
| 🔗 复制下载链接 | 打包完成后可一键复制下载链接，便于手机直接安装 |
| 🔄 下拉刷新 | 页面滚动到顶部时下拉可刷新 WebView 内容 |
| ⌨️ 键盘适配 | 软键盘弹出时 WebView 内容自动上移，表单不被遮挡 |
| 🗂️ 表单记忆 | 自动记住上次填写内容，打包历史最多保留 5 条 |

---

## 项目结构

```
APK/
├── .github/workflows/
│   ├── build.yml              # 主构建流程
│   └── gen-keystore.yml       # 生成签名 Keystore
├── Frontend/
│   └── index.html             # 前端页面（部署到 Cloudflare Pages）
├── Worker/
│   ├── worker.js              # Cloudflare Worker API
│   └── wrangler.toml          # Worker 配置
├── Scripts/
│   └── process_icon.py        # 图标处理脚本
└── app/                       # Android 项目源码
    └── src/main/java/com/webviewapp/
        ├── MainActivity.kt
        ├── SplashActivity.kt
        ├── TopProgressBar.kt
        └── IOSSpinnerView.kt
```

---

## 部署流程

### 前置要求

- GitHub 账号（用于 Actions 构建）
- Cloudflare 账号（用于 Pages + Worker）

---

### 第一步 — Fork 仓库

点击右上角 **Fork**，将本仓库 Fork 到你自己的账号下。

---

### 第二步 — 生成签名 Keystore

进入你 Fork 的仓库 → **Actions** → **gen-keystore** → **Run workflow**

运行完成后在 Actions 日志里复制输出的 Base64 Keystore 字符串，备用。

---

### 第三步 — 配置 GitHub Secrets

进入仓库 → **Settings** → **Secrets and variables** → **Actions** → **New repository secret**

| Secret 名称 | 说明 |
|-------------|------|
| `KEYSTORE_BASE64` | 上一步输出的 Base64 Keystore 字符串 |
| `KEYSTORE_PASSWORD` | Keystore 密码（gen-keystore 时设置的） |
| `KEY_ALIAS` | Key 别名（默认 `release`） |
| `KEY_PASSWORD` | Key 密码（同 Keystore 密码） |
| `GH_PAT` | GitHub PAT（需要 `repo` + `workflow` 权限） |

---

### 第四步 — 部署 Cloudflare Worker

Worker 负责接收前端请求、触发 Actions、查询构建状态、转发下载链接。

#### 方案 A：Dashboard 部署（推荐）

1. [Cloudflare Dashboard](https://dash.cloudflare.com) → **Workers & Pages** → **Create** → **Worker**
2. 随意取名（如 `apk-builder-api`）→ **Deploy**
3. 进入 Worker → **Edit Code** → 粘贴 `Worker/worker.js` 全部内容 → **Deploy**
4. **Settings** → **Variables and Secrets** 添加以下变量：

   | 变量名 | 值 | 类型 |
   |--------|----|------|
   | `GITHUB_OWNER` | 你的 GitHub 用户名 | Text |
   | `GITHUB_REPO` | `APK` | Text |
   | `ALLOWED_ORIGIN` | `*` | Text |
   | `GH_PAT` | 你的 GitHub PAT | Secret |

5. 记录 Worker URL：`https://apk-builder-api.<子域>.workers.dev`

#### 方案 B：Wrangler CLI 部署

```bash
npm install -g wrangler && wrangler login

# 编辑 Worker/wrangler.toml，填入你的 GitHub 信息
cd Worker
wrangler secret put GH_PAT   # 粘贴 PAT 回车确认
wrangler deploy
```

---

### 第五步 — 部署前端到 Cloudflare Pages

#### 方案 A：连接 Git 仓库（推荐，提交后自动同步）

1. Dashboard → **Workers & Pages** → **Create** → **Pages** → **Connect to Git**
2. 授权并选择你 Fork 的仓库，填写构建配置：

   | 配置项 | 值 |
   |--------|----|
   | Framework preset | None |
   | Build command | （留空） |
   | Build output directory | `Frontend` |

3. **Save and Deploy**

#### 方案 B：直接上传文件

**Workers & Pages** → **Create** → **Pages** → **Upload assets** → 上传 `Frontend/index.html`

---

### 第六步 — 更新 Worker 地址

编辑 `Frontend/index.html` 顶部常量，改为你自己的 Worker URL：

```js
const WORKER = 'https://apk-builder-api.<你的子域>.workers.dev';
```

提交后 Pages 自动重新部署（方案 A），或手动重新上传（方案 B）。

---

### 第七步 — 验证

打开前端页面，填写测试信息，点击「开始打包」，等待 3~5 分钟后下载 APK 安装验证。

---

## 构建流程

```
提交表单
   │
   ▼
Cloudflare Worker ──→ 触发 GitHub Actions (workflow_dispatch)
   │
   ▼
GitHub Actions
   ├── 1. Inject parameters   注入 URL / 包名 / 版本号等参数
   ├── 2. Process icon        下载图标，生成多尺寸 mipmap
   ├── 3. Build APK           Gradle 编译（arm64 + armeabi-v7a）
   └── 4. Sign & Upload       zipalign + apksigner 签名，上传 Artifact
   │
   ▼
前端轮询 /status，精确进度百分比实时更新
   │
   ▼
构建完成 → 显示下载按钮 + 复制链接按钮
```

---

## 注意事项

- GitHub Actions 免费账号每月有 **2000 分钟**额度，单次构建约消耗 **3~5 分钟**
- 未配置 Keystore Secrets 时自动使用临时 Debug Key 签名，**不同次打包签名不一致，无法升级覆盖安装**
- Worker 的 `GITHUB_TOKEN` 环境变量即为 `GH_PAT`，两者等价

---

## License

MIT
