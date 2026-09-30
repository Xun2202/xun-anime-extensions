# xun-anime-extensions

个人定制的 Anikku / Aniyomi 扩展仓库。构建基础设施来自 [yuzono/anime-extensions](https://github.com/yuzono/anime-extensions)（Apache-2.0），只保留了自己需要的扩展。

## 添加仓库

在 Anikku：`更多 → 设置 → 浏览 → 扩展仓库 → +`，粘贴：

```
https://raw.githubusercontent.com/Xun2202/xun-anime-extensions/repo/index.min.json
```

或点这里一键添加：[Anikku](https://intradeus.github.io/http-protocol-redirector/?r=anikku://add-repo?url=https://raw.githubusercontent.com/Xun2202/xun-anime-extensions/repo/index.min.json) · [Aniyomi](https://intradeus.github.io/http-protocol-redirector/?r=aniyomi://add-repo?url=https://raw.githubusercontent.com/Xun2202/xun-anime-extensions/repo/index.min.json)

APK 直接下载：[`repo` 分支](https://github.com/Xun2202/xun-anime-extensions/tree/repo/apk)

## 扩展列表

### Xvideos (Xun) — `src/all/xvideosxun`

相比官方仓库里的 Xvideos 扩展多出：

- **热门 / 最新** 两个标签（热门 = 月度 Best of，可在设置里切换当月/上月）
- **搜索筛选**：排序（相关度/上传日期/评分/时长/观看数/随机）、上传时间、时长、画质
- **浏览方式**（无搜索词时）：
  - 我的账户：我喜欢的视频 / 稍后观看 / 观看历史 —— 需要先在 WebView 里登录
  - 收藏夹 / 播放列表：粘贴列表 URL 或 ID
  - 频道 / 模特：粘贴主页 URL 或用户名
  - 分类（站内 38 个分类）
  - 标签
- **详情页**：上传者、出演模特、时长、画质、观看数、赞/踩、上传时间、标签
- **视频源**：HLS 按清晰度拆分（2160p/1440p/1080p/720p/…）+ MP4 直链，按设置的首选画质排序

#### 登录同步收藏

1. 在 Anikku 里打开本源的浏览页，点右上角 **在 WebView 中打开**
2. 登录 XVideos 账户，登录成功后返回
3. 搜索页 → 筛选 → **我的账户** 选「我喜欢的视频」/「稍后观看」/「观看历史」→ 筛选
4. 站上的「Favorites」列表：在网页端把列表设为公开（或直接复制列表 URL），填到 **收藏夹 / 播放列表** 里

## 开发

- 新扩展放在 `src/<lang>/<name>/`，结构与 yuzono 仓库一致（`build.gradle` + `AndroidManifest.xml` + `res/` + `src/`）
- push 到 `main` 后 GitHub Actions 自动构建、签名并把产物推到 `repo` 分支
- 改动扩展后记得把 `build.gradle` 里的 `extVersionCode` +1，否则 App 不会提示更新

签名相关 secrets：`SIGNING_KEY`（base64 的 jks）、`ALIAS`、`KEY_STORE_PASSWORD`、`KEY_PASSWORD`。

根目录的 `repo.json` 会被原样发布到 `repo` 分支，App 添加仓库时会读取它；`signingKeyFingerprint` 是签名证书的 SHA-256（`keytool -list -v` 里的值去掉冒号转小写），换签名密钥时必须同步更新。
