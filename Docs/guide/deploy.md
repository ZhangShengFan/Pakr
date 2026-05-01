# 部署说明

## 注意事项

- GitHub Actions 免费账号每月有 **2000 分钟**额度，单次构建约消耗 **3~5 分钟**
- 未配置 Keystore Secrets 时自动使用临时 Debug Key 签名，**不同次打包签名不一致，无法升级覆盖安装**
- 打包历史记录保存在浏览器本地，清除缓存后会丢失

## 自定义域名

在 Cloudflare Pages 项目设置中 → **Custom domains** → 添加你的域名即可，SSL 自动配置。

## 更新部署

主仓库有新提交时，只需在 Cloudflare Pages 控制台手动触发重新部署，或开启 **Auto Deployment** 让每次 push 自动更新。

## 项目结构

```
Pakr/
├── .github/workflows/
│   ├── build.yml              # 主构建流程
│   └── gen-keystore.yml       # 生成签名 Keystore
├── Scripts/
│   └── process_icon.py        # 图标处理脚本
├── index.html                 # 前端页面
├── _worker.js                 # API 接口（与前端合并部署）
└── app/                       # Android 项目源码
    └── src/main/java/com/webviewapp/
        ├── MainActivity.kt
        ├── SplashActivity.kt
        ├── TopProgressBar.kt
        └── IOSSpinnerView.kt
```
