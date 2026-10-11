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
  - **我的某个收藏夹 → 每个视频一个条目**（v8）：登录后下拉列出你全部的收藏夹（首次打开筛选面板会后台加载几秒，点「重置」刷新），选一个 → 列表里的视频按网格展开、每页 27 个，**每个视频一个独立条目**；长按任意一个进入批量选择 → 全选 → 加入书架，就得到一条视频一个书架条目（可顺手建同名分类）。再输搜索词 = 只列出标题 / 上传者匹配的
  - 收藏夹 / 播放列表：粘贴列表 URL 或 ID，直接把列表里的视频按网格展开（每个视频一个条目，可单独打开 / 加书架 / 下载）；**再输入搜索词就是在这个列表内搜索**（标题或上传者包含全部关键词；会翻完整个列表，600 个视频约 20 秒）
  - 频道 / 模特：粘贴主页 URL 或用户名
  - Best of 月份：选任意一个月的月度精选（近 4 年）
  - 分类（站内 38 个分类）
  - 标签
- **详情页**：上传者、出演模特、时长、画质、观看数、好评/差评百分比、上传时间、标签
  - 点上传者 / 出演名字搜索（Anikku 的「作者 / 画师」点击）时，直接列出该频道 / 模特 / 用户主页的全部视频，而不是用名字做标题关键词搜索（站内搜索不匹配名字）。主页没有公开视频时会明确提示（v9）
  - **推荐**（Anikku 条目页底部）：单个视频用网站自带的 40 条「相关视频」；收藏夹条目则列出列表前 27 个视频，每个可直接点开为独立条目（v9）
- **同一个视频只有一个条目**（v9）：网站对同一视频有多种地址写法（列表页带随机跟踪段、收藏夹页带 `?pl=`、主页接口 `/prof-video-click/…`），统一归一为 `/video.{eid}/{slug}`，首页 / 搜索 / 收藏夹 / 推荐任何入口进去都是同一个书架条目
- **视频源**：HLS 按清晰度拆分（2160p/1440p/1080p/720p/…）+ MP4 直链，按设置的首选画质排序

#### 登录同步收藏

1. 在 Anikku 里打开本源的浏览页，点右上角 **在 WebView 中打开**
2. 登录 XVideos 账户（勾选"记住我"），登录成功后返回。Cookie 保存在 App 的 WebView 里，除非清除 App 数据或站点会话过期，不需要重复登录
3. 搜索页 → 筛选 → **我的账户** 选「我的收藏夹」→ 筛选，会列出你所有的收藏夹；点进任意一个，视频以剧集形式列出
4. 也可以选「我喜欢的视频」/「稍后观看」/「观看历史」直接浏览

#### 收藏夹视频太多（几百个）怎么办

- 收藏夹条目里的剧集顺序与网站列表一致（新加入的在上面）；Anikku 右上角筛选可按上传者过滤（「排除翻译组」，上传者填在了翻译组字段）、按图源 / 章节编号（= 加入收藏的先后）/ 上传日期 / 名称排序。
- 想让每个视频都是书架里独立的条目：浏览页 → 筛选 → 「我的某个收藏夹 → 每个视频一个条目」选中它 → 长按进入批量选择 → 全选 → 加入书架。以后收藏夹有新视频，再来这里选一次、只勾新的即可。
- 只想找某一个：同一处再输入搜索词，只列出标题 / 上传者匹配的视频。没登录或想看别人的公开列表：用下面的「收藏夹 / 播放列表 URL 或 ID」粘贴地址，效果相同（走网页，600 个约 20 秒）。

不做"在设置里填账号密码自动登录"：站点登录带人机验证和反爬，扩展无法可靠通过；把密码明文存进扩展设置也不安全。WebView 登录一次即可长期有效。

### RouXun（rou.video / 肉視頻 重做版）— `src/all/rouxun`

官方仓库的 RouVideo 扩展在 2026-10 之后**完全没有数据**：rou.video 从 Next.js（页面里带 `script#__NEXT_DATA__`）换成了 TanStack Start 服务端渲染，旧扩展依赖的那段 JSON 不存在了。本扩展是独立的新插件（可与官方 RouVideo 并存，书架条目互不影响），完全改用站点自己的接口：

- **数据**：调用站点 JS 用的服务端函数 `GET /_serverFn/<id>?payload=…`（payload 为 seroval 编码；需要 `x-tsr-serverFn: true` 头），列表 / 详情 / 标签 / 搜索 / 分类 / 首页 / 剧集都走这条路，不再解析 HTML
- **播放**：站点把 HLS 播放列表和每个分片都伪装成 PNG 图片（真 PNG 头 + 私有 `roUd` 块，块里才是 deflate 压缩的 m3u8 / 原始 TS）。mpv / ffmpeg 无法直接播放，所以扩展内置一个 **只监听 127.0.0.1 的本地解包代理**：播放时给播放器的是 `http://127.0.0.1:<随机端口>/hls/<id>/index.m3u8`，代理负责拉取、解包、改写分片地址并逐段剥掉 PNG 头。播放和下载（ffmpeg）都经过它，不占用外部端口，App 进程退出即关闭
- **热门** = 最多观看，**最新** = 最新发布；详情页：番号、时长、画质、观看 / 喜欢、发布日期、所属剧集、来源站点、标签；底部「推荐」= 站点的相关视频（若属于某部剧集，第一条是该剧集）
- **筛选**：
  - 排序（最新发布 / 最多观看 / 最多喜欢；搜索时默认按相关度）
  - 浏览方式（无搜索词时）：全部视频、首页精选、大家正在看、**剧集库**（每部剧集一个条目、按集列出，可直接追更）
  - 分类（站点 6 大类）、标签（站内全部子分类 + 题材标签，后台自动加载）、热门搜索；剧集状态 / 剧集排序只对剧集库生效
- **链接直达**：在浏览器打开 `https://rou.video/v/…`、`/s/…`、`/t/…` 可选用 Anikku 打开；搜索框里直接粘贴这类链接也可以
- **设置**：标题 / 标签使用站点提供的简体版（默认）还是繁体原文
- 已知限制：站点每个视频只有一个画质；服务端函数的 id 是站点源码的哈希，站点大改版时需要重新抓取（见 `RouXun.kt` 的 `companion object`）

## 开发

- 新扩展放在 `src/<lang>/<name>/`，结构与 yuzono 仓库一致（`build.gradle` + `AndroidManifest.xml` + `res/` + `src/`）
- push 到 `main` 后 GitHub Actions 自动构建、签名并把产物推到 `repo` 分支
- 改动扩展后记得把 `build.gradle` 里的 `extVersionCode` +1，否则 App 不会提示更新

签名相关 secrets：`SIGNING_KEY`（base64 的 jks）、`ALIAS`、`KEY_STORE_PASSWORD`、`KEY_PASSWORD`。

维护者须知：签名 keystore、密码以及完整的交接文档（CI 流程、站点接口、注意事项）都放在私有仓库 `Xun2202/keystores` 的 `xun-anime-extensions/` 目录（`HANDOFF.md`、`signing.json`，附校验与一键恢复 Secrets 的脚本），接手前先读它。旧仓库 `xun-anime-extensions-vault` 已于 2026-10-05 归档。本地副本在 `C:\Users\xun\.xun-anime-extensions`。

根目录的 `repo.json` 会被原样发布到 `repo` 分支，App 添加仓库时会读取它；`signingKeyFingerprint` 是签名证书的 SHA-256（`keytool -list -v` 里的值去掉冒号转小写），换签名密钥时必须同步更新。
