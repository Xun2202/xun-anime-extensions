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

### XvXun（定制版 Xvideos）— `src/all/xvideosxun`

相比官方仓库里的 Xvideos 扩展多出：

- **热门 / 最新** 两个标签（热门 = 月度 Best of，可在设置里切换当月/上月）
- **搜索筛选**：排序（相关度/上传日期/评分/时长/观看数/随机）、上传时间、时长、画质
- **浏览方式**（无搜索词时）：
  - 我的账户：**我的收藏夹（列出全部列表）** / 我喜欢的视频 / 稍后观看 / 观看历史 —— 需要先在 WebView 里登录
    - 「我的收藏夹」走站内 `POST /api/playlists/alpha` 接口列出全部列表（含私密列表和"稍后观看"），每个列表一个条目；点进去用 `POST /api/playlists/list/{id}/{page}` 拉取全部视频，每个视频对应一个"剧集"，可以直接加入书架追更
  - 收藏夹 / 播放列表：粘贴列表 URL 或 ID，直接展开列表里的视频
  - 频道 / 模特：粘贴主页 URL 或用户名
  - Best of 月份：选任意一个月的月度精选（近 4 年）
  - 分类（站内 38 个分类）
  - 标签
- **详情页**：上传者、出演模特、时长、画质、观看数、赞/踩、上传时间、标签
- **视频源**：HLS 按清晰度拆分（2160p/1440p/1080p/720p/…）+ MP4 直链，按设置的首选画质排序

#### 登录同步收藏

1. 在 Anikku 里打开本源的浏览页，点右上角 **在 WebView 中打开**
2. 登录 XVideos 账户（勾选"记住我"），登录成功后返回。Cookie 保存在 App 的 WebView 里，除非清除 App 数据或站点会话过期，不需要重复登录
3. 搜索页 → 筛选 → **我的账户** 选「我的收藏夹」→ 筛选，会列出你所有的收藏夹；点进任意一个，视频以剧集形式列出
4. 也可以选「我喜欢的视频」/「稍后观看」/「观看历史」直接浏览

不做"在设置里填账号密码自动登录"：站点登录带人机验证和反爬，扩展无法可靠通过；把密码明文存进扩展设置也不安全。WebView 登录一次即可长期有效。

## 开发

- 新扩展放在 `src/<lang>/<name>/`，结构与 yuzono 仓库一致（`build.gradle` + `AndroidManifest.xml` + `res/` + `src/`）
- push 到 `main` 后 GitHub Actions 自动构建、签名并把产物推到 `repo` 分支
- 改动扩展后记得把 `build.gradle` 里的 `extVersionCode` +1，否则 App 不会提示更新

签名相关 secrets：`SIGNING_KEY`（base64 的 jks）、`ALIAS`、`KEY_STORE_PASSWORD`、`KEY_PASSWORD`。

维护者须知：签名 keystore、密码以及完整的交接文档（CI 流程、站点接口、注意事项）都放在私有仓库 `Xun2202/xun-anime-extensions-vault` 的 `HANDOFF.md` 里，接手前先读它。本地副本在 `C:\Users\xun\.xun-anime-extensions`。

根目录的 `repo.json` 会被原样发布到 `repo` 分支，App 添加仓库时会读取它；`signingKeyFingerprint` 是签名证书的 SHA-256（`keytool -list -v` 里的值去掉冒号转小写），换签名密钥时必须同步更新。
