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
- 设 `CANARY_LIVE=1`，只跑两个 `*LiveCanaryTest`：抓几页公开页面，用插件自己的解析器做断言。
- 站点不可达，或者返回 Cloudflare 挑战页：该用例记为 skipped，**不算失败**。
- 定时运行要**连续两次**失败才开 issue。标题固定为 `[canary] XvXun / RouXun 解析器与站点当前结构不匹配`，标签 `canary`。中间只失败一次（站点抽风）不会开。
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
