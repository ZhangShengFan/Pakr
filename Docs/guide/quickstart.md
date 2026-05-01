# 快速开始

本页面介绍如何在 3 分钟内完成部署，开始使用 Pakr。

## 前置要求

- **GitHub 账号** — 用于 Actions 构建
- **Cloudflare 账号** — 用于 Pages 托管

## 第一步：Fork 仓库

点击右上角 **Fork**，将 [ZhangShengFan/Pakr](https://github.com/ZhangShengFan/Pakr) Fork 到你自己的账号下。

## 第二步：生成签名 Keystore

进入你 Fork 的仓库 → **Actions** → **gen-keystore** → **Run workflow**

填写密码后运行，完成后在 Actions 日志里复制输出的 **Base64 Keystore 字符串**，备用。

## 第三步：配置 GitHub Secrets

进入仓库 → **Settings** → **Secrets and variables** → **Actions** → **New repository secret**

| Secret 名称 | 说明 |
|-------------|------|
| `KEYSTORE_BASE64` | 上一步输出的 Base64 Keystore 字符串 |
| `KEYSTORE_PASSWORD` | Keystore 密码（gen-keystore 时设置的） |
| `KEY_ALIAS` | Key 别名（默认 `release`） |
| `KEY_PASSWORD` | Key 密码（同 Keystore 密码） |
| `GH_PAT` | GitHub PAT（需要 `repo` + `workflow` 权限） |

## 第四步：部署到 Cloudflare Pages

1. 进入 [Cloudflare Dashboard](https://dash.cloudflare.com) → **Workers & Pages** → **Create** → **Pages** → **Connect to Git**
2. 授权并选择你 Fork 的仓库，填写构建配置：

   | 配置项 | 值 |
   |--------|----|
   | Framework preset | None |
   | Build command | （留空） |
   | Build output directory | `/`（根目录） |

3. **Settings** → **Environment variables** 添加：

   | 变量名 | 值 |
   |--------|----|
   | `GITHUB_OWNER` | 你的 GitHub 用户名 |
   | `GITHUB_REPO` | `Pakr` |
   | `GH_PAT` | 你的 GitHub PAT |

4. 点击 **Save and Deploy**，等待部署完成。

## 第五步：验证

打开 Pages 分配的域名，填写测试信息，点击「开始打包」，等待 3~5 分钟后下载 APK 安装验证。
