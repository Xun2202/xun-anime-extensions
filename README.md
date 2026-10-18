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

### XvXun（定制版 Xvideos）— `src/all/xvideosxun`（v11，APK 14.11）

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

#### v11（2026-10-13）

网站一改版，旧选择器会静默给出空标题或空列表。v11 把解析抽出来，对不上就当场说明原因，并修掉「打开过 WebView 就被当成已登录」：

- HTML / JSON 解析在 `XvideosXunParser.kt`（纯 Kotlin，没有 android import；选择器和正则集中在一处）。
- 砖块多于 0、但主标题选择器命中率低于 50%：抛「网站可能已改版，请更新 XvXun 插件（标题标记不匹配）」。
- 热门 / 最新 / 分类 / 标签的第 1 页是 0 条、又不是登录墙：抛「…（没有找到视频列表）」。搜索和收藏夹 0 条是合法的，不报。
- 播放地址三条正则都空时，用页面 `ld+json` 里的 `contentUrl` 兜底。
- 页面上有 `video_related=` 但 JSON 解不出来：抛「…（相关视频数据不匹配）」。
- 登录判断不再看有没有 `session_token` cookie（没登录的访问也会下发同名 cookie，所以打开过 WebView 就被当成已登录，筛选器卡在「正在加载」）。改为以收藏夹 API 的 `metadata.isLogged` 为准，进程内缓存，cookie 一变就失效。没登录时筛选器显示「（未登录）」。曾经加过的「登录」开关设置项已去掉（cookie 变化会自动失效，开关多余）。
- 合成夹具测试 `XvideosXunParserTest`（13 个）+ 在线检查 `XvideosXunLiveCanaryTest`（默认不跑，见下面「金丝雀」）。

### RouXun（rou.video / 肉視頻 重做版）— `src/all/rouxun`（v4，APK 14.4）

官方仓库的 RouVideo 扩展在 2026-10 之后**完全没有数据**：rou.video 从 Next.js（页面里带 `script#__NEXT_DATA__`）换成了 TanStack Start 服务端渲染，旧扩展依赖的那段 JSON 不存在了。本扩展是独立的新插件（可与官方 RouVideo 并存，书架条目互不影响），完全改用站点自己的接口：

- **数据**：调用站点 JS 用的服务端函数 `GET /_serverFn/<id>?payload=…`（payload 为 seroval 编码；需要 `x-tsr-serverFn: true` 头），列表 / 详情 / 标签 / 搜索 / 分类 / 首页 / 剧集都走这条路，不再解析 HTML
- **播放**：站点把 HLS 播放列表和每个分片都伪装成 PNG 图片（真 PNG 头 + 私有 `roUd` 块，块里才是 deflate 压缩的 m3u8 / 原始 TS）。mpv / ffmpeg 无法直接播放，所以扩展内置一个 **只监听 127.0.0.1 的本地解包代理**：播放时给播放器的是 `http://127.0.0.1:<随机端口>/hls/<id>/index.m3u8`，代理负责拉取、解包、改写分片地址并逐段剥掉 PNG 头。播放和下载（ffmpeg）都经过它，不占用外部端口，App 进程退出即关闭
- **热门** = 最多观看，**最新** = 最新发布；详情页：番号、时长、画质、观看 / 喜欢、发布日期、所属剧集、来源站点、标签；底部「推荐」= 站点的「相關視頻」（若属于某部剧集，第一条是该剧集）；站点的「猜你想追 / 你可能也喜欢」是全站相同的推广位，不收录
- **筛选**：
  - 排序（最新发布 / 最多观看 / 最多喜欢；搜索时默认按相关度）
  - 浏览方式（无搜索词时）：全部视频、首页精选、大家正在看、**剧集库**（每部剧集一个条目、按集列出，可直接追更）
  - 分类（站点 6 大类）、标签（站内全部子分类 + 题材标签，后台自动加载）、热门搜索；剧集状态 / 剧集排序只对剧集库生效
- **链接直达**：在浏览器打开 `https://rou.video/v/…`、`/s/…`、`/t/…` 可选用 Anikku 打开；搜索框里直接粘贴这类链接也可以
- **设置**：标题 / 标签使用站点提供的简体版（默认）还是繁体原文
- 已知限制：站点每个视频只有一个画质。服务端函数的 id 是站点脚本里的哈希；v2 起启动时会自己从主脚本里找（见下），文件里的 8 个常量只做后备。大改版若连查找规则都不适用，会提示更新插件。

#### v4（2026-10-13）

- 修正片「加载一段时间后 loading failed」：本地代理用 `InetAddress.getLoopbackAddress()` 监听，而 Android 上它固定返回 IPv6 的 `::1`；播放列表写的是 `http://127.0.0.1:…`，播放器连 IPv4 被拒绝（模拟器日志 `Connection to tcp://127.0.0.1:… failed: Connection refused`，随后 `loading failed`）。桌面 JVM 的回环是 IPv4，所以以前的测试看不出来。现在固定监听字面量 `127.0.0.1`。
- 测试：`HlsProxyTest` 新增子进程用例（打开 `java.net.preferIPv6Addresses=true`，与 Android 同样的绑定行为），以及「`keep-alive` + `Range: bytes=0-` 拿到完整分片、`Content-Length` 与字节数一致」的原始套接字用例。本机 ffmpeg 经代理拷贝 30 秒、从 120 秒处 seek 都正常。

#### v3（2026-10-13）

- 推荐固定是一批 AI 短剧：v2 把详情里的 `moreSeries`（剧集页是 `more`）排在相关视频前面，而它是站点的「猜你想追 / 你可能也喜欢」推广位，三个不同视频返回的 id 完全一样；`relatedVideos` 才是按作品的「相關視頻」（三份交集为 0）。现在视频的推荐 = 所属剧集（若有）+ `relatedVideos`，推广位整段不收；剧集页的推荐因此为空，只剩选集。
- 播放：部分剧集的播放列表和分片不是 PNG，而是名叫 `.jpg` 的裸 m3u8 / 裸 TS，代理原样转发（只有 PNG 签名才解包）。签名播放列表带 `Cache-Control: public`，代理请求改为 `FORCE_NETWORK`，不让共享缓存重放过期签名；起播前再拉一次列表，失败直接报「rou.video 没有返回可播放的视频（HTTP n）」或「播放列表无法识别」，不再只看到播放器的 loading failed。
- 测试：新增 `HlsProxyTest`、`RouXunRelatedTest`；`RouXunLiveCanaryTest` 加断言：三部作品推荐前 5 条互不相同，剧集一集与单视频都能取回并解出 TS 分片。

#### v2（2026-10-13）

- 新文件 `RouXunServerFns.kt`：启动时，以及碰到 HTTP 500 时，从 `https://rou.video/` 找 `/assets/index-*.js`，按 `import("./<路由>-<hash>.js")` 后 200 字符内的 `ut("<64 位十六进制>")` 配对 7 个 id；视频详情的 id 取 `atob` 解码函数后面紧挨着的那个 `ut`。文件里写死的 8 个常量只在发现失败时用。配齐后存 `SharedPreferences`（键 `server_fn_ids`）。同一次启动（同一 epoch）只下载一次，`synchronized` 单飞。
- HTTP 500 且响应里没有 `x-tss-serialized`：重新发现并重试一次；还失败就抛「站点脚本已变化，请更新 RouXun 插件」。
- 热门 / 最新 / 首页精选的第 1 页是 0 条：抛「站点数据格式可能已变化，请更新 RouXun 插件」。
- 分类 id（`FN_CAT`）失败、退回写死分类时打 `Log.w`。详情里的 `moreSeries` 并进相关视频。
- 测试：`RouXunServerFnsTest`（14）+ `SerovalTest`（5）+ `RouXunLiveCanaryTest`（在线，默认不跑）。

### Hanime1.me Xun — `src/zh/hanime1xun`（v8，APK 14.8）

改自 [yuzono/anime-extensions](https://github.com/yuzono/anime-extensions) 的 `src/zh/hanime1` v7（Apache-2.0，说明在该目录的 `NOTICE`）。界面上的源名称仍是 **Hanime1.me**，语言 `zh`，没有改 `versionId`。Anikku 用 `MD5("hanime1.me/zh/1")` 的前 64 位（符号位清掉）当源 id，结果是 `2853734405579058222`，和原版一样。

和原版的差别：

- **横版、竖版卡片都解析**。3DCG 这类结果是横版 `.video-item-container`，裏番是竖版 `.search-videos`。按页面上的顺序收录，同一 URL 只留一条。`target=_blank`，或者链接不是本站 `/watch` 的赞助商，丢掉。
- **搜尋作者**（筛选里的勾选）会带 `type=artist`。作者卡变成一个作者合集，地址是 `/search?query=<作者名>`，可以加入书架。合集里每一部影片是一集，顺序跟站上一样、最新在前；作者发新片时，更新书架会多出一集。详情里写「作者合集：N 部影片」。普通影片详情页的作者名不动，在 Anikku 里点作者名仍然是用这个名字做关键词搜索。
- **分集**：视频页上的类型是裏番或泡麵番时，侧栏播放列表仍是这一部的各集。3DCG、MMD、2D動畫等其他类型只保留当前这一个 `watch?v=`，不再把「同作者的其他作品」当成剧集。页面上读不到类型时，仍按原版把整份播放列表当成分集。
- 有下一页：原来的 `rel=next`，以及文字是「下一頁」且链接不是 `#` 的跳页按钮。没选类型时请求里仍然不带 `genre`。类型下拉的后备名单改成站上的繁体名（泡麵番、3DCG、2.5D 等）。

安装：

1. **先卸载原来的 Hanime1.me 扩展，再装本版。** 源 id 相同，书架和观看记录会留着。两个同时装会冲突。
2. 仓库地址和上面一样。本扩展的包名是 `eu.kanade.tachiyomi.animeextension.zh.hanime1xun`。

已知限制：

- 站点自己的收录就不全。例如搜 vicineko，站上是 17 部，合集里也就是这 17 部。
- 想支持正版，请走作者本人的 Patreon / SubscribeStar。
- 站点有 Cloudflare。扩展仍依赖 App 里的 WebView 拦截，这一点和原版一样。
- 作者合集最多翻 30 页。

单元测试（不访问网站）：

```bash
./gradlew :src:zh:hanime1xun:testDebugUnitTest

### Rule34Video Xun — `src/en/rule34videoxun`（v11，APK 14.11）

改自 [yuzono/anime-extensions](https://github.com/yuzono/anime-extensions) 的 `src/en/rule34video` v10（Apache-2.0，说明在该目录 `NOTICE`），已修改。源在 App 里的名字仍是 `Rule34Video`、语言 `en`，生成的源 id 与原版相同。

和原版的差别：

- **作者合集**：筛选里「Search type」选 Models。有关键词时走站点的 `GET /models_json.php?q=<词>&page=<页>`；空白关键词浏览 `GET /models/`（第 2 页起 `?from=`）。结果是作者合集，地址 `/models/<slug>/`，标题是作者名，缩略图是头像。普通视频搜索的第 1 页，如果关键词和某个作者 slug 完全一致（例如 `vicineko`），最前面再插入这条作者合集。
- **追更**：作者合集可以加入书架。详情描述是「作者合集：N 个视频」。剧集是作者页分页里的视频（站点最新在前，最多 40 页），每集地址是视频页；作者上传新片就是新剧集。普通视频仍是一集。普通视频详情的作者填页面上的 model 名（没有则用上传者），Anikku 点名字会按这个名字搜索。
- **画质**：首选画质增加「最高可用」并作为默认。选项为页面上实际有的 2160p / 1440p / 1080p / 720p / 480p / 360p，标题写成 `2160p` 这种，下载画质选择器能从标题里认出高度。另有时长筛选，排序增加 Longest。作者页里的 WIP / 合集不过滤。
- **播放地址**：页面上的 `get_file` 链接带一次性的 `v-acctoken`。不带会话 cookie 时很快 403；带上打开视频页时的 cookie 和 Referer，同一链接可以反复 302。扩展在返回播放列表前跟一次跳转，把跳转后的 CDN 地址交给播放器，并去掉 `download=true`（否则响应是附件）。播放器只带 User-Agent、Referer、Accept，不带会话 cookie。

安装：如果已经装过原版 Rule34Video，先卸载再装这个。两边源 id 相同，卸载原版后书架条目还在，装上这个就能继续用。

已知限制：关键词搜索噪音大；作者页会带上 WIP、合集和错标的视频。CDN 在美国，大陆速度可能慢。想支持正版请走作者自己的 Patreon（ViciNeko）/ SubscribeStar（vicineko）。

本机测试（不访问网站时，在线那条会跳过）：

```bash
./gradlew :src:en:rule34videoxun:testDebugUnitTest
```

要打真实网站（只 Range 作者页最新一条的前 1 MB，不整段下载）：

```bash
CANARY_LIVE=1 ./gradlew :src:en:rule34videoxun:testDebugUnitTest --tests '*LiveCanary*'
```

### e621 — `src/all/e621`（v1，APK 14.1）

e621.net 的视频帖。不登录，只走公开 JSON（`/posts.json`、`/tags.json`）。用来补别的插件没有的成片：标签 `vicineko` 在 e621 上有 12 个帖子（2026-10 核对，9 个 webm、3 个 mp4），其中 `6365066`（Mona X Slime - An Intimate Offering，约 9 分钟，720p webm）是 Rule34Video / Hanime1 都没有的。

- **只收视频**。每个请求的标签里都带 `~type:webm ~type:mp4`。这是 e621 的 OR：单独 `type:webm` 只有 webm，加上 OR 之后 mp4 也会出现。普通搜索、热门、最新都是一个帖子一个条目、一集。
- **热门** = `order:score`，**最新** = 站点默认排序（新帖在前）。
- **筛选**：排序（默认 / 评分 / 收藏数 / 最新），以及一个附加标签文本框（原样拼进搜索）。
- **作者合集**：搜索框里的词如果和某个 artist 标签完全一致（category 1，用 `tags.json` 核对），结果第一条是「作者合集：vicineko」这种条目，地址 `/collection/artist/<标签>`，可以加入书架。也可以直接搜 `artist:vicineko`。剧集是该作者的全部视频帖，按发布时间排列（新的集号更大），集名是标题加时长。最多翻 10 页，每页 320 条。
- **清晰度**：原始文件（`file.url`），再加上 `sample.alternates` 里实际有的档（常见是 720p / 480p 样本，以及一份同分辨率的 MP4）。默认按分辨率从高到低。扩展设置里的「首选清晰度」可选最高 / 720p / 480p。
- **限速**：客户端每秒最多 2 个请求。User-Agent 固定为 `Anikku-e621-extension/1.0 (by Xun2202 on github)`，这是 e621 要求的描述性 UA。不需要登录。
- 匿名状态下被隐藏、`file.url` 为空的帖子会跳过，不会把列表弄崩。作者名用 artist 标签；`sound_warning`、`third-party_edit` 在站上虽然也是 artist 分类，但是警告标记，不写进作者，避免点作者时搜到警告标签。

已知限制：e621 的收录仍然有限（vicineko 就是这 12 个帖子）。样本档的像素高度经常不是标签上的 720 / 480，偏好按站点给的档位选。想支持正版请走作者本人的 Patreon（ViciNeko）/ SubscribeStar（vicineko）。

本机测试（不访问网站时，在线那条会跳过）：

```bash
./gradlew :src:all:e621:testDebugUnitTest
```

要打真实网站（只拉 `tags=vicineko` 的 JSON，不下视频文件）：

```bash
CANARY_LIVE=1 ./gradlew :src:all:e621:testDebugUnitTest --tests '*LiveCanary*'
```

### Kemono Xun — `src/all/kemonoxun`（v1）

Kemono（默认 `https://kemono.cr`）上的创作者视频，不登录。同一套接口也能切到 Coomer（`https://coomer.st`）。2026-10-18 核对：`kemono.su` / `kemono.party` 已经 NXDOMAIN；`kemono.st` 不是可用镜像。接口必须带 `Accept: text/css`，否则 403；正文仍是 JSON。

- **一条番 = 一个创作者**，地址 `/{service}/user/{id}`，标题是名字，作者是服务名（Patreon、Pixiv Fanbox……），封面是 `img.<主机>/icons/{service}/{id}`。**一集 = 一篇里至少有一个视频文件的帖子**，地址 `/{service}/user/{id}/post/{postId}`。视频后缀只认 mp4、m4v、mov、webm、mkv、avi。zip / rar / 7z / psd / pdf 不算，哪怕文件名里写了 mp4。一篇里有多个视频时仍是一集，播放列表里每条用文件名当清晰度（文件名里的 4K / 1080 会排到前面）。
- **热门**按收藏数，**最新**按 `updated`。都是先把创作者列表拉下来，在本地每页 50 人。Discord 直接丢掉。
- **搜索创作者**是名字的大小写不敏感子串。筛选：服务（点一次包含、再点排除）、排序（收藏数 / 最近更新 / 名称）。
- **近期视频帖**不按创作者折叠。2026-10-18 实测：不带词的 `/api/v1/posts` 最新 50 条里只有 2 条真视频，后面抽查的几页是 0；`q=mp4` 的第一页 50 条里有 39 条附件真是 mp4。站点没有「这个人发过视频」这种标记，创作者列表也不带帖子类型。所以这个模式把搜索词原样交给 `/api/v1/posts?q=`（搜索框空着就用 `mp4`），**每一条带视频的帖子单独成一张卡片**，打开只播这一帖。要追更，把创作者链接贴进搜索框。
- **粘贴链接**可以直接跳：`https://kemono.cr/patreon/user/49965584` 打开该创作者，帖子链接打开那一帖。Coomer 的链接也同样认，书架里存的是不带域名的路径，换站点不用重新添加。
- **扩展设置**：站点（kemono.cr / coomer.st）、自定义 https 地址（留空则用列表；不接受 http 或带路径）、每个创作者最多翻多少页帖子（默认 10 页 = 500 帖，上限 100 页）、播放前检测文件服务器（默认开）。某一页不足 50 条，或接口返回 `Offset N is bigger than total count`（HTTP 400），就停，不当成失败。
- **限速**大约每秒 1 个请求。遇到 429 会等 2 秒、4 秒、8 秒，最多再试 3 次。创作者全表约 10.8 万条、压缩后大约 3.4MB，内存和缓存目录各留一份，30 分钟内不重复下。
- 创作者在已加载的页里没有视频时，章节列表是空的，简介里会写「已加载的帖子里没有视频」。403、返回网页、超时，都会给出中英两句说明；403 / 验证页建议先用 WebView 打开站点。

已知限制：只收视频帖，图片帖和压缩包不会出现在章节里。播放地址优先用帖子详情里的 `server`，形如 `https://n4.kemono.cr/data/...?f=文件名`，没有 `server` 时才退回站点自己的 `/data/`。2026-10-18 从 check-host.net 的 12 个地区，以及一条中国电信家宽，测得 `n1`–`n4.kemono.cr` 和 `n*.coomer.st` 的 443 端口全部超时；公开讨论里这些文件服务器大约从 2026-04 起就经常整站宕机。`img.kemono.cr` 的图片缩略图正常，但不管视频。默认在播放前用大约 4 秒超时并行探测这些主机，5 分钟内记住结果。这一集的文件服务器全部连不上时，直接提示「文件服务器 … 当前连不上（站点故障，不是插件问题）」，不再让播放器空转。设置里可以关掉这项检测。简介里也会写明：章节能列出来，不代表能播放。第一次打开热门或搜索会下载整份创作者列表。登录后的收藏夹接口是 401，这个扩展不做。afdian 在筛选里，但当天的创作者表里是 0 人。

本机测试（不访问网站时，在线那条会跳过）：

```bash
./gradlew :src:all:kemonoxun:testDebugUnitTest
```

要打真实网站（拉 ViciNeko 的第一页帖子和帖子 `150370074` 的 JSON，不下视频文件）：

```bash
CANARY_LIVE=1 ./gradlew :src:all:kemonoxun:testDebugUnitTest --tests '*LiveCanary*'
```

要再对 4 个创作者的第一条视频帖做文件服务器探测（应打出「文件服务器连不上」并记下耗时，不下视频）：

```bash
KEMONO_PROBE_VIDEOS=1 ./gradlew :src:all:kemonoxun:testDebugUnitTest --tests '*livePlayableChain*'
```

## 鲁棒性 / 金丝雀

2026-10-13 按「全做」落地（`a9463c9` … `767dabf`，9 个提交）。目标：网站改版时不要再静默显示空标题 / 空列表，而是当场告诉用户去更新插件；同时在 GitHub 上每天自动看一眼公开页面，坏了先开 issue。

### 解析层

- **XvXun**：列表、详情、播放地址、相关视频的 HTML / JSON 解析在 `src/all/xvideosxun/src/.../XvideosXunParser.kt`。这个文件不 import Android，选择器和正则是集中的常量。扩展类只负责发请求、把字节交给解析器。改版时先改这里，再补一条夹具。
- **RouXun**：列表数据本来就不是 HTML。服务端函数 id 的发现在 `RouXunServerFns.kt`，payload 编解码仍在 `Seroval.kt`。写死的 8 个 id 是后备，不是主路径。

健康检查抛出的原文（给用户看的）见上面 v11 / v2。搜索 0 条、收藏夹 0 条、超大页码 0 条不算改版。

### 单元测试怎么跑

测试不能放进 `src/test`：`src/` 整个就是 main 源集。`gradle/build-logic` 的 `PluginExtensionLegacy` 把测试源集指到模块目录的 `test/`，资源指到 `test-resources/`，测试类路径是 `testImplementation(libs.bundles.common)` 加 JUnit。

本机（不访问网站，live 两条会跳过）：

```bash
./gradlew :src:all:xvideosxun:testDebugUnitTest :src:all:rouxun:testDebugUnitTest
```

2026-10-13 本机 34 个用例全过（其中 2 个 `*LiveCanaryTest` 因没设 `CANARY_LIVE` 而跳过）。

要在本机打真实网站（会用你的网络）：

```bash
CANARY_LIVE=1 ./gradlew \
  :src:all:xvideosxun:testDebugUnitTest \
  :src:all:rouxun:testDebugUnitTest \
  --tests '*LiveCanary*'
```

当时本机结果：XvXun 首页 48/48、最新 27/27、热门 27/27 标题命中，详情的 hls / high / low / contentUrl 都有，相关 40 条；RouXun 发现的 8 个 id 与文件里的常量一致，第 1 页 26 条全有标题。

### 金丝雀怎么工作

定义在 [`.github/workflows/canary.yml`](./.github/workflows/canary.yml)。**只在 GitHub Actions 上跑，不在用户手机上，不耗用户流量。**

- 每天 UTC 02:17（北京 10:17），也可以在 Actions 里手动触发。
- 设 `CANARY_LIVE=1`，只跑 `*LiveCanaryTest`：抓几页公开页面，用插件自己的解析器做断言。
- 站点不可达，或者返回 Cloudflare 挑战页：该用例记为 skipped，**不算失败**。
- 定时运行要**连续两次**失败才开 issue。标题固定为 `[canary] XvXun / RouXun / Rule34Video Xun / e621 / Kemono Xun 解析器与站点当前结构不匹配`（2026-10-18 加入 Kemono Xun 的在线用例后改名），标签 `canary`。中间只失败一次（站点抽风）不会开。
- 已经开着的同题 issue 上，后续失败会再评论一次，不重复开。
- 恢复通过后，定时运行自动评论并关闭该 issue。手动触发的成功运行不负责开关 issue（只有 `schedule` 事件会）。
- 测试报告上传为 artifact `canary-test-reports`，保留 14 天。

2026-10-13：`Build & publish repo` run 38363628954 成功，`repo` 分支索引已是 XvXun 14.11 / RouXun 14.2。手动触发的 `Canary` run 38363695647 成功，两条 live 用例在 GitHub runner 上真实执行（skipped=0，说明两个站点当时都能从 GitHub 访问）。

### 改版时的排查步骤

1. 看用户看到的那句原文，或 `[canary]` issue 里失败用例的名字：标题标记、没有视频列表、相关视频、站点脚本已变化、站点数据格式，对应上面的检查点。
2. 用浏览器或 `curl` 打开出问题的公开页（首页、最新、热门、一条详情；RouXun 再加首页 HTML 里的 `/assets/index-*.js`），对照 `XvideosXunParser` 的选择器 / 正则，或 `RouXunServerFns` 的 `import("./…")` + `ut("…")` 配对规则。
3. 把新页面存成 `test-resources/` 里的夹具，先让对应的单元测试红，再改解析器让它绿。`extVersionCode` +1。
4. 本机 `CANARY_LIVE=1` 跑一遍 `*LiveCanary*`，确认不是只对夹具过。
5. push 到 `main`（自动构建发布），再在 Actions 里手动跑一次 Canary。定时检查通过后，开着的 `[canary]` issue 会在下一次定时运行时自动关掉。

## 开发

- 新扩展放在 `src/<lang>/<name>/`，结构与 yuzono 仓库一致（`build.gradle` + `AndroidManifest.xml` + `res/` + `src/`）
- push 到 `main` 后 GitHub Actions 自动构建、签名并把产物推到 `repo` 分支
- 改动扩展后记得把 `build.gradle` 里的 `extVersionCode` +1，否则 App 不会提示更新

签名相关 secrets：`SIGNING_KEY`（base64 的 jks）、`ALIAS`、`KEY_STORE_PASSWORD`、`KEY_PASSWORD`。

维护者须知：签名 keystore、密码以及完整的交接文档（CI 流程、站点接口、注意事项）都放在私有仓库 `Xun2202/keystores` 的 `xun-anime-extensions/` 目录（`HANDOFF.md`、`signing.json`，附校验与一键恢复 Secrets 的脚本），接手前先读它。旧仓库 `xun-anime-extensions-vault` 已于 2026-10-05 归档。本地副本在 `C:\Users\xun\.xun-anime-extensions`。

根目录的 `repo.json` 会被原样发布到 `repo` 分支，App 添加仓库时会读取它；`signingKeyFingerprint` 是签名证书的 SHA-256（`keytool -list -v` 里的值去掉冒号转小写），换签名密钥时必须同步更新。
