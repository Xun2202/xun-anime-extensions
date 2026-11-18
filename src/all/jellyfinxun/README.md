# Jellyfin Xun

独立的 Jellyfin 来源，与原 Jellyfin 扩展共存。固定来源 ID：`6636044655764960105`。电影、视频和剧集使用普通播放账户浏览、搜索和原画播放；媒体库列表来自用户接口，不要求管理员权限。

## 配置

1. 在 Anikku 扩展管理中安装并信任 **Jellyfin Xun**，打开来源设置。
2. 在 **服务器地址** 填写 HTTPS 地址，例如 `https://media.example.org`。支持自定义端口、IP SAN 证书和路径前缀。
3. 点 **Jellyfin 账号**，填入普通播放账号和密码。登录后，**媒体库** 会列出 Jellyfin 返回的可访问视频库。可以选择一个库或浏览全部。
4. 打开来源开始播放。原画直传要求客户端支持视频的编码。

扩展默认使用系统信任。如果服务器使用私有 CA，请在登录前打开 **服务器证书**，选择 **私有 CA**，填写管理员提供的 PEM 证书和 SHA-256 指纹后保存。修改正在使用的地址或证书时，设置会在确认后保存并退出本机登录。

证书链、有效期、IP SAN/主机名及导入 CA 的 SHA-256 均按正常 TLS 规则校验。私有信任仅用于配置的 HTTPS 主机、端口和路径前缀，客户端不跟随重定向。更改连接设置会清空本机登录、媒体库选择和播放链接。

封面由来源的 HTTPS 客户端取得。视频和可外部读取的文本字幕通过同一客户端连接 NAS，再从仅绑定 `127.0.0.1` 的随机端口以流方式交给播放器或下载器。播放器拿到的是随机、不可猜测的本机链接；没有 NAS 密码、会话或可控的上游 URL。Range、HEAD、Content-Length 和 Content-Range 被保留，不先缓存整段视频。

本机链接在应用退出或清除连接后失效。已有下载队列若保存了旧本机链接，需要重新解析视频。点开已登录的 **Jellyfin 账号** 可以重新登录。选择 **退出登录** 并确认后，扩展会清除本机登录并关闭活动转发。可在 Jellyfin 管理页撤销服务器会话。

## 范围与待验证项

- Anikku 的本地观看进度按应用原有机制保存。本扩展尚未向 Jellyfin 回传播放器的开始、进度、暂停及结束事件。不要把 Range 字节偏移换算成观看进度。
- 字幕仅转发服务器允许外部读取的文本字幕；不做烧录、视频转码或远程链接直跳。
- 尚需在实际鸿蒙/卓易通设备上验证起播、拖动、字幕、后台恢复和下载；JVM 测试不能替代真机结果。
- 新来源 ID 独立且稳定，已有原 Jellyfin 收藏需用户自行迁移。试用 debug APK 与仓库正式签名不同，正式版发布后可能需要卸载试用扩展再安装；来源 ID 和宿主保存的收藏不因此改变。

## 连接诊断

点 **测试连接** 检查 HTTPS 和服务状态。**最近连接诊断** 显示发生错误的阶段和错误代码，支持复制。报告只包含连接方式、是否填写证书和账号等状态，以及错误代码和处理提示。

例如 `CA_FINGERPRINT_MISMATCH` 表示粘贴的 CA 与指纹不一致，`TLS_CERTIFICATE` 表示证书链验证失败，`NETWORK_TIMEOUT` 表示网络连接或读取超时，`HTTP_401` 表示账号认证失败。

支持复制时被折行或空格替换的 PEM/指纹文本，仍严格核对同一个 CA 的 SHA-256 和正常 TLS 证书链，不自动信任其他证书。密码中的换行会在发送前提示清除。

填写私有 CA 时，插件直接连接已配置的服务器。使用系统信任时沿用系统代理设置。手机上的 VPN 分流由系统控制。

## Quick setup

Open **Jellyfin Xun** source settings in Anikku. Enter your **Server address** (HTTPS), then open **Jellyfin account** to sign in with your username and password. After sign-in, choose a library under **Media library**. For a private CA, open **Server certificate**, select **Private CA**, and save the CA certificate and SHA-256 fingerprint before signing in.

## 验证

```sh
./gradlew :src:all:jellyfinxun:spotlessCheck :src:all:jellyfinxun:testDebugUnitTest :src:all:jellyfinxun:assembleDebug
```

测试生成临时 CA、服务器证书和合成视频，不使用真实 NAS 密码或私钥。覆盖正确 CA、错误指纹、错误 CA、过期服务器证书、主机名不匹配、跨主机请求、HTTP 降级重定向、本机能力链接访问限制、范围读取及 HEAD。

实现参考了 Secozzi/aniyomi-extensions 的 Jellyfin API 使用方式，以及本仓 RouXun 的回环媒体转发方式。使用本仓现有扩展框架和许可。
