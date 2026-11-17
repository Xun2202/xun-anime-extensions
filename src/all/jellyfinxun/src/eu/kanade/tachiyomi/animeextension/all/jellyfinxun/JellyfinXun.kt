package eu.kanade.tachiyomi.animeextension.all.jellyfinxun

import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import keiyoushi.utils.AnimeHttpLegacySource
import keiyoushi.utils.getPreferencesLazy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale

class JellyfinXun :
    AnimeHttpLegacySource(),
    ConfigurableAnimeSource {
    override val name = "Jellyfin Xun"
    override val id = 6636044655764960105L
    override val lang = "all"
    override val supportsLatest = true
    override val supportsRelatedAnimes = false

    private val preferences by getPreferencesLazy()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val main by lazy { Handler(Looper.getMainLooper()) }
    private var connection: Pair<Config, NasConnection>? = null
    private var proxy: NasStreamProxy? = null

    private data class Config(val server: String, val ca: String, val fingerprint: String)
    private fun config() = Config(setting(SERVER), setting(CA), setting(FINGERPRINT))
    private fun setting(key: String) = preferences.getString(key, "").orEmpty()

    override val baseUrl: String get() = setting(SERVER).trimEnd('/')
    override val client: OkHttpClient get() = current().client

    @Synchronized
    private fun current(): NasConnection {
        val wanted = config()
        connection?.takeIf { it.first == wanted }?.let { return it.second }
        closeConnection()
        val origin = NasConnection.parseEndpoint(wanted.server).toString()
        val created = NasConnection(wanted.server, wanted.ca, wanted.fingerprint) {
            if (setting(SESSION_ORIGIN) == origin && config() == wanted) setting(TOKEN) else ""
        }
        connection = wanted to created
        return created
    }

    @Synchronized
    private fun streamProxy(): NasStreamProxy {
        val active = current()
        return proxy ?: NasStreamProxy(active.client, active::sameOrigin).also { proxy = it }
    }

    @Synchronized
    private fun closeConnection() {
        proxy?.close()
        proxy = null
        connection?.second?.client?.let {
            it.dispatcher.cancelAll()
            it.connectionPool.evictAll()
        }
        connection = null
    }

    private fun clearSession() {
        preferences.edit().remove(TOKEN).remove(USER_ID).remove(SESSION_ORIGIN).remove(LIBRARIES).remove(LIBRARY).apply()
        closeConnection()
    }

    private fun userId(): String {
        val value = setting(USER_ID)
        check(value.isNotEmpty() && setting(TOKEN).isNotEmpty() && setting(SESSION_ORIGIN) == current().endpoint.toString()) {
            "请先在 Jellyfin Xun 设置中配置 HTTPS、证书并登录"
        }
        return JellyfinJson.id(value)
    }

    override fun headersBuilder(): Headers.Builder = Headers.Builder().add("Accept", "application/json")

    private fun request(url: HttpUrl): Request = Request.Builder().url(url).header("Accept", "application/json").build()
    private fun responseJson(response: Response): JsonObject = response.use {
        if (!it.isSuccessful) throw IOException("NAS 请求失败（HTTP ${it.code}），请检查连接、账户权限或重新登录")
        JellyfinJson.parse(it.body.string())
    }

    private fun getJson(url: HttpUrl): JsonObject = responseJson(client.newCall(request(url)).execute())

    private fun catalog(page: Int, query: String = "", latest: Boolean = false): Request {
        val uid = userId()
        require(page >= 1)
        val url = current().url("Users", uid, "Items").newBuilder()
            .addQueryParameter("Recursive", "true")
            .addQueryParameter("IncludeItemTypes", "Movie,Series,Video")
            .addQueryParameter("StartIndex", ((page - 1) * PAGE_SIZE).toString())
            .addQueryParameter("Limit", PAGE_SIZE.toString())
            .addQueryParameter("SortBy", if (latest) "DateCreated,SortName" else "SortName")
            .addQueryParameter("SortOrder", if (latest) "Descending" else "Ascending")
            .addQueryParameter("Fields", "Overview,Genres,Studios,People,DateCreated")
            .apply {
                setting(LIBRARY).takeIf(String::isNotEmpty)?.let { addQueryParameter("ParentId", JellyfinJson.id(it)) }
                if (query.isNotBlank()) addQueryParameter("SearchTerm", query)
            }.build()
        return request(url)
    }

    override fun popularAnimeRequest(page: Int) = catalog(page)
    override fun latestUpdatesRequest(page: Int) = catalog(page, latest = true)
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList) = catalog(page, query)
    override fun popularAnimeParse(response: Response) = parsePage(response)
    override fun latestUpdatesParse(response: Response) = parsePage(response)
    override fun searchAnimeParse(response: Response) = parsePage(response)

    private fun parsePage(response: Response): AnimesPage {
        val start = response.request.url.queryParameter("StartIndex")?.toIntOrNull() ?: 0
        val root = responseJson(response)
        val items = JellyfinJson.items(root)
        return AnimesPage(items.map(::anime), JellyfinJson.pageHasMore(root, start, items.size))
    }

    override fun animeDetailsRequest(anime: SAnime): Request = request(itemUrl(JellyfinJson.itemId(anime.url)))
    override fun animeDetailsParse(response: Response) = anime(responseJson(response)).apply { initialized = true }

    private fun itemUrl(id: String): HttpUrl = current().url("Users", userId(), "Items", JellyfinJson.id(id))

    private fun anime(item: JsonObject): SAnime = SAnime.create().apply {
        val id = JellyfinJson.id(item.text("Id"))
        url = "/Items/$id"
        title = item.text("Name")
        thumbnail_url = current().url("Items", id, "Images", "Primary").newBuilder()
            .addQueryParameter("maxWidth", "480")
            .apply { item.obj("ImageTags").text("Primary").takeIf(String::isNotBlank)?.let { addQueryParameter("tag", it) } }
            .build().toString()
        description = item.text("Overview")
        genre = item.array("Genres").mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.joinToString(", ")
        author = item.array("Studios").mapNotNull { (it as? JsonObject)?.text("Name") }.joinToString(", ")
        status = if (item.text("Status") == "Continuing") SAnime.ONGOING else SAnime.COMPLETED
    }

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> = withContext(Dispatchers.IO) {
        val id = JellyfinJson.itemId(anime.url)
        val item = getJson(itemUrl(id))
        val entries = if (item.text("Type") == "Series") {
            val episodes = current().url("Shows", id, "Episodes").newBuilder()
                .addQueryParameter("UserId", userId()).addQueryParameter("Fields", "DateCreated,Overview")
                .addQueryParameter("SortBy", "ParentIndexNumber,IndexNumber").build()
            JellyfinJson.items(getJson(episodes))
        } else {
            listOf(item)
        }
        entries.mapIndexed { index, entry ->
            SEpisode.create().apply {
                url = "/Items/${JellyfinJson.id(entry.text("Id"))}"
                name = if (entry.text("Type") == "Episode") {
                    "S${entry.int("ParentIndexNumber")} E${entry.int("IndexNumber")} · ${entry.text("Name")}"
                } else {
                    entry.text("Name")
                }
                episode_number = (index + 1).toFloat()
                date_upload = date(entry.text("PremiereDate").ifBlank { entry.text("DateCreated") })
            }
        }.reversed()
    }

    override fun episodeListParse(response: Response): List<SEpisode> = throw UnsupportedOperationException()

    override suspend fun getVideoList(episode: SEpisode): List<Video> = withContext(Dispatchers.IO) {
        userId()
        val id = JellyfinJson.itemId(episode.url)
        val item = getJson(itemUrl(id))
        val media = item.array("MediaSources").mapNotNull { it as? JsonObject }.firstOrNull()
        val height = (media?.array("MediaStreams") ?: item.array("MediaStreams"))
            .mapNotNull { it as? JsonObject }.firstOrNull { it.text("Type") == "Video" }?.int("Height") ?: 0
        val upstream = current().url("Videos", id, "stream").newBuilder()
            .addQueryParameter("static", "true")
            .apply { media?.text("Id")?.takeIf(String::isNotEmpty)?.let { addQueryParameter("MediaSourceId", JellyfinJson.id(it)) } }
            .build()
        val extension = media?.text("Container")?.lowercase()?.takeIf { it.matches(Regex("[a-z0-9]{1,8}")) } ?: "bin"
        val subtitles = media?.array("MediaStreams")?.mapNotNull { it as? JsonObject }?.filter {
            it.text("Type") == "Subtitle" && it.flag("SupportsExternalStream")
        }?.map { subtitle ->
            val codec = subtitle.text("Codec").lowercase().takeIf { it in listOf("srt", "ass", "ssa", "vtt") } ?: "vtt"
            val address = current().url("Videos", id, JellyfinJson.id(media.text("Id")), "Subtitles", subtitle.int("Index").toString(), "0", "Stream.$codec")
            Track(streamProxy().streamUrl(address, codec), subtitle.text("DisplayTitle").ifBlank { subtitle.text("Language").ifBlank { "字幕" } })
        }.orEmpty()
        listOf(Video(videoUrl = streamProxy().streamUrl(upstream, extension), videoTitle = "NAS HTTPS · 原画", resolution = height.takeIf { it > 0 }, headers = Headers.Builder().build(), subtitleTracks = subtitles))
    }

    override fun relatedAnimeListParse(response: Response): List<SAnime> {
        response.close()
        return emptyList()
    }

    private fun date(value: String): Long = runCatching { SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).parse(value.take(10))?.time ?: 0L }.getOrDefault(0L)

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val context = screen.context
        fun message(text: String) = main.post { Toast.makeText(context, text, Toast.LENGTH_LONG).show() }
        fun edit(keyName: String, label: String, description: String, multiline: Boolean = false, validate: (String) -> Unit = {}) {
            EditTextPreference(context).apply {
                key = keyName
                title = label
                summary = description
                text = setting(keyName)
                setOnBindEditTextListener { field ->
                    field.inputType = InputType.TYPE_CLASS_TEXT or if (multiline) InputType.TYPE_TEXT_FLAG_MULTI_LINE else InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                }
                setOnPreferenceChangeListener { _, value ->
                    val entered = (value as String).trim()
                    try {
                        if (entered.isNotEmpty()) validate(entered)
                        preferences.edit().putString(keyName, entered).apply()
                        clearSession()
                        true
                    } catch (_: Exception) {
                        message("配置格式不正确，请检查 HTTPS 地址、PEM 证书或完整 SHA-256 指纹")
                        false
                    }
                }
            }.also(screen::addPreference)
        }
        edit(SERVER, "NAS HTTPS 地址", "例如 https://公网IP:48273；也可填写同一 NAS 的内网 HTTPS 地址", validate = { NasConnection.parseEndpoint(it) })
        edit(CA, "当前 NAS 的 CA 公钥证书（PEM）", "从 NAS 可信文件粘贴完整 BEGIN CERTIFICATE 到 END CERTIFICATE；公有 CA 可留空", multiline = true, validate = {
            require(it.length < 16_384 && it.startsWith("-----BEGIN CERTIFICATE-----") && it.endsWith("-----END CERTIFICATE-----") && !it.contains("PRIVATE KEY"))
        })
        edit(FINGERPRINT, "CA 证书 SHA-256 指纹", "从 NAS 的 SHA256.txt 独立核对；支持带冒号格式", validate = {
            require(it.replace(":", "").replace(" ", "").matches(Regex("[0-9a-fA-F]{64}")))
        })
        edit(USERNAME, "播放账户", "使用 NAS 的普通播放账户，例如 watcher")
        val libraries = ListPreference(context).apply {
            key = LIBRARY
            title = "媒体库"
            summary = "%s"
            val known = runCatching { JellyfinJson.items(JellyfinJson.parse(setting(LIBRARIES))) }.getOrDefault(emptyList())
            entries = (listOf("全部可访问的媒体库") + known.map { it.text("Name") }).toTypedArray()
            entryValues = (listOf("") + known.map { it.text("Id") }).toTypedArray()
            value = setting(LIBRARY)
        }.also(screen::addPreference)
        EditTextPreference(context).apply {
            key = "login_password_in_memory"
            title = "验证证书并登录"
            summary = "输入本次密码并确认；成功后仅保存会话，不保存密码"
            setOnBindEditTextListener { it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
            setOnPreferenceChangeListener { passwordField, entered ->
                val pw = entered as String
                if (pw.isEmpty()) {
                    message("请先填写本次登录密码")
                    return@setOnPreferenceChangeListener false
                }
                passwordField.setEnabled(false)
                scope.launch {
                    try {
                        val selected = config()
                        val nas = current()
                        val body = buildJsonObject {
                            put("Username", setting(USERNAME))
                            put("Pw", pw)
                        }.toString().toRequestBody(JSON_TYPE)
                        val result = responseJson(nas.client.newCall(Request.Builder().url(nas.url("Users", "AuthenticateByName")).post(body).build()).execute())
                        check(!result.obj("User").obj("Policy").flag("IsAdministrator")) { "请改用普通播放账户" }
                        check(config() == selected) { "配置已变化，请重新登录" }
                        val session = result.text("AccessToken")
                        val uid = JellyfinJson.id(result.obj("User").text("Id"))
                        check(session.isNotEmpty()) { "NAS 未返回会话" }
                        preferences.edit().putString(TOKEN, session).putString(USER_ID, uid).putString(SESSION_ORIGIN, nas.endpoint.toString()).apply()
                        val roots = getJson(nas.url("Users", uid, "Items"))
                        val allowed = JellyfinJson.items(roots).filter { it.text("CollectionType") !in listOf("music", "photos", "books", "livetv") }
                        preferences.edit().putString(LIBRARIES, buildJsonObject { put("Items", JsonArray(allowed)) }.toString()).apply()
                        main.post {
                            libraries.entries = (listOf("全部可访问的媒体库") + allowed.map { it.text("Name") }).toTypedArray()
                            libraries.entryValues = (listOf("") + allowed.map { it.text("Id") }).toTypedArray()
                            Toast.makeText(context, "证书验证与登录成功", Toast.LENGTH_LONG).show()
                        }
                    } catch (_: Exception) {
                        clearSession()
                        message("登录失败：请核对 HTTPS 地址、CA 证书与指纹、普通播放账户及网络。未跳过证书校验。")
                    } finally {
                        main.post { passwordField.setEnabled(true) }
                    }
                }
                // A rejected preference change is never persisted by the host.
                false
            }
        }.also(screen::addPreference)
        SwitchPreferenceCompat(context).apply {
            key = "nas_clear_session_action"
            title = "清除登录与本机播放链接"
            summary = "点击即清除当前连接；此开关不保存状态"
            setDefaultValue(false)
            setOnPreferenceChangeListener { _, _ ->
                clearSession()
                message("已清除本机 NAS 会话")
                false
            }
        }.also(screen::addPreference)
    }

    companion object {
        private const val PAGE_SIZE = 40
        private const val SERVER = "nas_server"
        private const val CA = "nas_ca_pem"
        private const val FINGERPRINT = "nas_ca_sha256"
        private const val USERNAME = "nas_username"
        private const val TOKEN = "nas_session"
        private const val USER_ID = "nas_user_id"
        private const val SESSION_ORIGIN = "nas_session_origin"
        private const val LIBRARIES = "nas_libraries"
        private const val LIBRARY = "nas_library"
        private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
