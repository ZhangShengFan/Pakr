# 常见问题

## 打包失败怎么办？

进入你 Fork 的仓库 → **Actions**，找到对应的 workflow 运行记录，查看详细日志定位错误。

常见原因：
- `GH_PAT` 权限不足，需要 `repo` + `workflow` 两个权限
- Keystore Secrets 配置错误或未配置
- 目标网址无法访问

## APK 安装后无法升级覆盖？

说明两次打包使用了不同的签名。需要正确配置 Keystore Secrets（`KEYSTORE_BASE64`、`KEYSTORE_PASSWORD`、`KEY_ALIAS`、`KEY_PASSWORD`），确保每次签名一致。

## 打包历史丢了？

打包历史保存在浏览器 LocalStorage，清除浏览器缓存后会丢失，这是正常现象。

## Actions 额度用完了？

GitHub 免费账号每月 2000 分钟，单次构建约 3~5 分钟，每月约可打包 400~600 次。超出后需等下月重置或升级 GitHub 套餐。

## 支持 iOS 吗？

暂不支持，目前只生成 Android APK。

## 可以打包需要登录的网页吗？

可以，WebView 支持 Cookie，登录状态会保留在应用中。
