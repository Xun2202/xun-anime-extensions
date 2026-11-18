package eu.kanade.tachiyomi.animeextension.all.jellyfinxun

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.BuildConfig
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import keiyoushi.utils.AnimeHttpLegacySource
import keiyoushi.utils.getPreferencesLazy
import kotlinx.coroutines.CancellationException
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
import java.lang.ref.WeakReference
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

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
    private val connectionActionInProgress = AtomicBoolean()
    private var refreshSettings: WeakReference<Runnable>? = null
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

    @Synchronized
    private fun changeConnection(vararg values: Pair<String, String>) {
        if (values.isNotEmpty() && values.all { (key, value) -> setting(key) == value }) return
        preferences.edit().apply {
            for ((key, value) in values) putString(key, value)
            remove(TOKEN).remove(USER_ID).remove(SESSION_ORIGIN).remove(LIBRARIES).remove(LIBRARY)
            remove(DIAGNOSTIC).remove(DIAGNOSTIC_CODE)
        }.apply()
        closeConnection()
    }

    private fun signedIn(): Boolean = setting(TOKEN).isNotEmpty() && setting(USER_ID).isNotEmpty() &&
        runCatching { NasConnection.parseEndpoint(setting(SERVER)).toString() == setting(SESSION_ORIGIN) }.getOrDefault(false)

    private fun userId(): String {
        val value = setting(USER_ID)
        check(value.isNotEmpty() && setting(TOKEN).isNotEmpty() && setting(SESSION_ORIGIN) == current().endpoint.toString()) {
            "请先在 Jellyfin Xun 的来源设置中登录 / Sign in from the Jellyfin Xun source settings"
        }
        return JellyfinJson.id(value)
    }

    override fun headersBuilder(): Headers.Builder = Headers.Builder().add("Accept", "application/json")

    private fun request(url: HttpUrl): Request = Request.Builder().url(url).header("Accept", "application/json").build()
    private fun responseJson(response: Response): JsonObject = response.use {
        if (!it.isSuccessful) throw NasHttpException(it.code)
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
        val quality = if (Locale.getDefault().language == "zh") "原画" else "Original quality"
        listOf(Video(videoUrl = streamProxy().streamUrl(upstream, extension), videoTitle = "Jellyfin · $quality", resolution = height.takeIf { it > 0 }, headers = Headers.Builder().build(), subtitleTracks = subtitles))
    }

    override fun relatedAnimeListParse(response: Response): List<SAnime> {
        response.close()
        return emptyList()
    }

    private fun date(value: String): Long = runCatching { SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).parse(value.take(10))?.time ?: 0L }.getOrDefault(0L)

    private fun checkServer(connection: NasConnection) {
        val info = responseJson(connection.client.newCall(request(connection.url("System", "Info", "Public"))).execute())
        check(info.text("Version").isNotBlank() && info.flag("StartupWizardCompleted")) { "目标不是已初始化的 Jellyfin HTTPS 服务" }
    }

    private fun authenticate(connection: NasConnection, username: String, password: String): JsonObject {
        val body = buildJsonObject {
            put("Username", username)
            put("Pw", password)
        }.toString().toRequestBody(JSON_TYPE)
        val request = Request.Builder().url(connection.url("Users", "AuthenticateByName")).post(body).build()
        val result = responseJson(connection.client.newCall(request).execute())
        check(!result.obj("User").obj("Policy").flag("IsAdministrator")) { "请改用普通播放账户" }
        check(result.text("AccessToken").isNotEmpty()) { "NAS 未返回会话" }
        return result
    }

    @Synchronized
    private fun saveLogin(selected: Config, username: String, endpoint: HttpUrl, token: String, uid: String, allowed: List<JsonObject>) {
        check(config() == selected && setting(USERNAME) == username) { "配置已变化，请重新登录" }
        preferences.edit().apply {
            putString(TOKEN, token)
            putString(USER_ID, uid)
            putString(SESSION_ORIGIN, endpoint.toString())
            putString(LIBRARIES, buildJsonObject { put("Items", JsonArray(allowed)) }.toString())
            if (allowed.none { it.text("Id") == setting(LIBRARY) }) remove(LIBRARY)
        }.apply()
        closeConnection()
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val context = screen.context
        val chinese = context.resources.configuration.locales[0].language == "zh"
        fun text(zh: String, en: String) = if (chinese) zh else en
        fun message(value: String) = Toast.makeText(context, value, Toast.LENGTH_LONG).show()

        fun section(label: String) = PreferenceCategory(context).apply {
            title = label
            isIconSpaceReserved = false
        }.also(screen::addPreference)

        val connectionSettings = section(text("连接", "Connection"))
        val securitySettings = section(text("证书与诊断", "Certificates & diagnostics"))

        fun action(category: PreferenceCategory, label: String, click: () -> Unit) = Preference(context).apply {
            title = label
            isIconSpaceReserved = false
            setOnPreferenceClickListener {
                click()
                true
            }
        }.also(category::addPreference)

        val server = EditTextPreference(context).apply {
            key = SERVER
            title = text("服务器地址", "Server address")
            dialogTitle = title
            dialogMessage = text("输入 Jellyfin 的 HTTPS 地址，例如 https://media.example.org", "Enter the Jellyfin HTTPS address, such as https://media.example.org")
            isPersistent = false
            isIconSpaceReserved = false
            setOnBindEditTextListener {
                it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                it.imeOptions = it.imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            }
        }.also(connectionSettings::addPreference)
        val account = EditTextPreference(context).apply {
            key = USERNAME
            title = text("Jellyfin 账号", "Jellyfin username")
            dialogTitle = title
            isPersistent = false
            isIconSpaceReserved = false
            setOnBindEditTextListener {
                it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                it.imeOptions = it.imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            }
        }.also(connectionSettings::addPreference)
        val login = Preference(context).apply { isIconSpaceReserved = false }.also(connectionSettings::addPreference)
        val libraries = ListPreference(context).apply {
            key = LIBRARY
            title = text("媒体库", "Media library")
            dialogTitle = title
            isPersistent = false
            isIconSpaceReserved = false
        }.also(connectionSettings::addPreference)
        val logout = Preference(context).apply {
            title = text("退出登录", "Sign out")
            isIconSpaceReserved = false
        }.also(connectionSettings::addPreference)

        val certificate = Preference(context).apply {
            title = text("服务器证书", "Server certificate")
            isIconSpaceReserved = false
        }.also(securitySettings::addPreference)
        val test = Preference(context).apply {
            title = text("测试连接", "Test connection")
            summary = text("检查 HTTPS 和 Jellyfin 服务", "Check HTTPS and the Jellyfin server")
            isIconSpaceReserved = false
        }.also(securitySettings::addPreference)
        val diagnostic = action(securitySettings, text("最近连接诊断", "Last connection report")) {
            val report = setting(DIAGNOSTIC).takeIf { setting(DIAGNOSTIC_CODE).isNotBlank() }
            showDiagnostic(context, text("最近连接诊断", "Last connection report"), report, text("尚未测试或登录", "Run a connection test or sign in first"), text("复制", "Copy"))
        }

        fun refresh() {
            val busy = connectionActionInProgress.get()
            val signedIn = signedIn()
            val allLibraries = text("全部可访问的媒体库", "All accessible libraries")
            val known = runCatching { JellyfinJson.items(JellyfinJson.parse(setting(LIBRARIES))) }.getOrDefault(emptyList())
            server.text = setting(SERVER)
            server.summary = setting(SERVER).ifBlank { text("填写 HTTPS 地址", "Enter an HTTPS address") }
            account.text = setting(USERNAME)
            account.summary = setting(USERNAME).ifBlank { text("填写普通播放账号", "Enter a playback account") }
            login.title = if (signedIn) text("重新登录", "Sign in again") else text("登录", "Sign in")
            login.summary = if (busy) {
                text("正在连接…", "Connecting…")
            } else if (signedIn) {
                text("已登录", "Signed in")
            } else {
                text("输入 Jellyfin 密码", "Enter your Jellyfin password")
            }
            libraries.entries = (listOf(allLibraries) + known.map { it.text("Name") }).toTypedArray()
            libraries.entryValues = (listOf("") + known.map { it.text("Id") }).toTypedArray()
            libraries.value = setting(LIBRARY).takeIf { it in libraries.entryValues }.orEmpty()
            libraries.summary = if (signedIn) libraries.entry ?: allLibraries else text("登录后可选", "Available after sign-in")
            logout.isVisible = signedIn
            certificate.summary = when {
                setting(CA).isNotBlank() && setting(FINGERPRINT).isNotBlank() -> text("私有 CA", "Private CA")
                setting(CA).isNotBlank() || setting(FINGERPRINT).isNotBlank() -> text("需要补全私有 CA 和指纹", "Complete the private CA and fingerprint")
                else -> text("使用系统信任，可配置私有 CA", "System trust · Optional private CA")
            }
            diagnostic.summary = when (val code = setting(DIAGNOSTIC_CODE)) {
                "" -> text("尚未测试或登录", "No connection result yet")
                "OK" -> text("最近一次连接成功", "Last connection succeeded")
                else -> code
            }
            listOf(server, account, certificate, logout).forEach { it.setEnabled(!busy) }
            login.setEnabled(!busy && setting(SERVER).isNotBlank() && setting(USERNAME).isNotBlank())
            libraries.setEnabled(!busy && signedIn && known.isNotEmpty())
            test.setEnabled(!busy && setting(SERVER).isNotBlank())
        }

        val updateScreen = Runnable { refresh() }
        refreshSettings = WeakReference(updateScreen)

        fun connect(password: String? = null) {
            if (!connectionActionInProgress.compareAndSet(false, true)) return
            val selected = config()
            val username = setting(USERNAME)
            updateScreen.run()
            scope.launch {
                var stage = LoginStage.CONFIGURATION
                var error: Exception? = null
                try {
                    if (password != null && username.isBlank()) throw NasConfigurationException(ConfigurationIssue.USERNAME_EMPTY)
                    var token = ""
                    val proposed = NasConnection(selected.server, selected.ca, selected.fingerprint) { token }
                    try {
                        stage = LoginStage.HTTPS
                        checkServer(proposed)
                        if (password != null) {
                            stage = LoginStage.AUTHENTICATION
                            val result = authenticate(proposed, username, password)
                            token = result.text("AccessToken")
                            val uid = JellyfinJson.id(result.obj("User").text("Id"))
                            stage = LoginStage.LIBRARIES
                            val roots = responseJson(proposed.client.newCall(request(proposed.url("Users", uid, "Items"))).execute())
                            val allowed = JellyfinJson.items(roots).filter { it.text("CollectionType") !in listOf("music", "photos", "books", "livetv") }
                            saveLogin(selected, username, proposed.endpoint, token, uid, allowed)
                        }
                    } finally {
                        proposed.client.connectionPool.evictAll()
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    error = failure
                } finally {
                    withContext(Dispatchers.Main) {
                        connectionActionInProgress.set(false)
                        if (config() == selected && setting(USERNAME) == username) {
                            val failed = error?.let { ConnectionDiagnostics.failure(stage, it, chinese) }
                            val report = ConnectionDiagnostics.report(BuildConfig.VERSION_NAME, stage, selected.ca.isNotBlank(), selected.fingerprint.isNotBlank(), username.isNotBlank(), error, chinese)
                            preferences.edit().putString(DIAGNOSTIC, report).putString(DIAGNOSTIC_CODE, failed?.code ?: "OK").apply()
                            message(failed?.advice ?: if (password == null) text("连接成功", "Connection succeeded") else text("登录成功", "Signed in"))
                        }
                        refreshSettings?.get()?.run()
                        if (refreshSettings?.get() !== updateScreen) updateScreen.run()
                    }
                }
            }
        }

        server.setOnPreferenceChangeListener { _, value ->
            val entered = (value as String).trim()
            try {
                val endpoint = if (entered.isBlank()) "" else NasConnection.parseEndpoint(entered).toString().trimEnd('/')
                val previous = runCatching { NasConnection.parseEndpoint(setting(SERVER)).toString().trimEnd('/') }.getOrDefault(setting(SERVER))
                if (endpoint != previous) changeConnection(SERVER to endpoint)
                updateScreen.run()
            } catch (error: NasConfigurationException) {
                message(ConnectionDiagnostics.failure(LoginStage.CONFIGURATION, error, chinese).advice)
            }
            false
        }
        account.setOnPreferenceChangeListener { _, value ->
            changeConnection(USERNAME to (value as String).trim())
            refresh()
            false
        }
        libraries.setOnPreferenceChangeListener { _, value ->
            preferences.edit().putString(LIBRARY, value as String).apply()
            refresh()
            false
        }
        login.setOnPreferenceClickListener {
            val password = dialogField(context, text("密码", "Password"), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
            password.isSaveEnabled = false
            formDialog(context, text("登录 Jellyfin", "Sign in to Jellyfin"), listOf(password), text("登录", "Sign in")) {
                val entered = password.text.toString()
                if (entered.isEmpty() || '\n' in entered || '\r' in entered) {
                    password.error = text("请输入密码，去掉复制时带入的换行", "Enter your password without line breaks")
                    false
                } else {
                    password.text.clear()
                    connect(entered)
                    true
                }
            }
            true
        }
        certificate.setOnPreferenceClickListener {
            val pem = dialogField(context, text("CA 公钥证书（PEM）", "CA certificate (PEM)"), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE).apply {
                minLines = 4
                maxLines = 6
                setText(setting(CA))
            }
            val sha = dialogField(context, text("SHA-256 指纹", "SHA-256 fingerprint"), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS).apply {
                setText(setting(FINGERPRINT))
            }
            val systemTrust = if (setting(CA).isNotBlank() || setting(FINGERPRINT).isNotBlank()) text("恢复系统信任", "Use system trust") else null
            formDialog(context, text("私有 CA", "Private CA"), listOf(pem, sha), text("保存", "Save"), systemTrust, onNeutral = {
                changeConnection(CA to "", FINGERPRINT to "")
                refresh()
            }) {
                try {
                    val certificate = NasConnection.normalizePem(pem.text.toString())
                    val fingerprint = NasConnection.normalizeFingerprint(sha.text.toString())
                    NasConnection.parseCa(certificate, fingerprint)
                    val previousPem = runCatching { NasConnection.normalizePem(setting(CA)) }.getOrDefault(setting(CA))
                    val previousSha = runCatching { NasConnection.normalizeFingerprint(setting(FINGERPRINT)) }.getOrDefault(setting(FINGERPRINT))
                    if (certificate != previousPem || fingerprint != previousSha) changeConnection(CA to certificate, FINGERPRINT to fingerprint)
                    refresh()
                    true
                } catch (error: Exception) {
                    val failed = ConnectionDiagnostics.failure(LoginStage.CONFIGURATION, error, chinese)
                    if (failed.code in listOf("CA_FINGERPRINT_FORMAT", "CA_FINGERPRINT_MISMATCH")) sha.error = failed.advice else pem.error = failed.advice
                    false
                }
            }
            true
        }
        test.setOnPreferenceClickListener {
            connect()
            true
        }
        logout.setOnPreferenceClickListener {
            changeConnection()
            refresh()
            message(text("已退出本机登录", "Signed out on this device"))
            true
        }
        refresh()
    }

    private fun dialogField(context: Context, label: String, type: Int) = EditText(context).apply {
        hint = label
        inputType = type
        imeOptions = imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
    }

    private fun formDialog(context: Context, title: String, fields: List<EditText>, positive: String, neutral: String? = null, onNeutral: () -> Unit = {}, confirm: () -> Boolean) {
        val padding = (24 * context.resources.displayMetrics.density).toInt()
        val form = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding / 2, padding, padding / 2)
            fields.forEach { addView(it, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT) }
        }
        val builder = AlertDialog.Builder(context)
            .setTitle(title)
            .setView(ScrollView(context).apply { addView(form) })
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(positive, null)
        if (neutral != null) builder.setNeutralButton(neutral) { _, _ -> onNeutral() }
        val dialog = builder.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if (confirm()) dialog.dismiss()
        }
    }

    private fun showDiagnostic(context: Context, title: String, report: String?, empty: String, copy: String) {
        val padding = (24 * context.resources.displayMetrics.density).toInt()
        val body = TextView(context).apply {
            text = report ?: empty
            setTextIsSelectable(true)
            setPadding(padding, padding / 2, padding, padding / 2)
        }
        val builder = AlertDialog.Builder(context)
            .setTitle(title)
            .setView(ScrollView(context).apply { addView(body) })
            .setNegativeButton(android.R.string.ok, null)
        if (report != null) {
            builder.setPositiveButton(copy) { _, _ ->
                context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(title, report))
            }
        }
        builder.show()
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
        private const val DIAGNOSTIC = "nas_connection_diagnostic"
        private const val DIAGNOSTIC_CODE = "nas_connection_diagnostic_code"
        private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
