package eu.kanade.tachiyomi.animeextension.all.jellyfinxun

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.DialogInterface
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
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

    private fun availableLibraries(): List<JsonObject> = runCatching { JellyfinJson.videoLibraries(JellyfinJson.parse(setting(LIBRARIES))) }
        .getOrDefault(emptyList())

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
                setting(LIBRARY).takeIf { id -> id.isNotEmpty() && availableLibraries().any { it.text("Id") == id } }
                    ?.let { addQueryParameter("ParentId", it) }
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
    private fun saveLogin(username: String, session: PlaybackSession) {
        val keepLibrary = username == setting(USERNAME) && session.libraries.any { it.text("Id") == setting(LIBRARY) }
        preferences.edit().apply {
            putString(USERNAME, username)
            putString(TOKEN, session.token)
            putString(USER_ID, session.userId)
            putString(SESSION_ORIGIN, session.endpoint.toString())
            putString(LIBRARIES, buildJsonObject { put("Items", JsonArray(session.libraries)) }.toString())
            if (!keepLibrary) remove(LIBRARY)
        }.apply()
        closeConnection()
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        Settings(screen).build()
    }

    private class SignIn(val username: String, val password: String)
    private class PlaybackSession(val endpoint: HttpUrl, val token: String, val userId: String, val libraries: List<JsonObject>)

    private inner class Settings(private val screen: PreferenceScreen) {
        private val context = screen.context
        private val chinese = context.resources.configuration.locales[0].language == "zh"

        // Host builds can remove unused AppCompat methods during optimization.
        // Framework widgets stay available when this extension is loaded into a host app.
        private val dialogContext by lazy { AlertDialog.Builder(context).context }

        private val server = row(text("服务器地址", "Server address"), ::editServer)
        private val certificate = row(text("服务器证书", "Server certificate"), ::editCertificate)
        private val account = row(text("Jellyfin 账号", "Jellyfin account"), ::openAccount)
        private val libraries = ListPreference(context).apply {
            key = LIBRARY
            title = text("媒体库", "Media library")
            dialogTitle = title
            isPersistent = false
            isIconSpaceReserved = false
            setOnPreferenceChangeListener { _, value ->
                preferences.edit().putString(LIBRARY, value as String).apply()
                refresh()
                false
            }
        }
        private val test = row(text("测试连接", "Test connection"), ::testConnection)
        private val diagnostic = row(text("最近连接诊断", "Last connection report"), ::showDiagnostic)
        private val refreshAction = Runnable(::refresh)

        fun build() {
            section(text("服务器", "Server"), server, certificate)
            section(text("账号与媒体库", "Account & media library"), account, libraries)
            section(text("诊断", "Diagnostics"), test, diagnostic)
            refreshSettings = WeakReference(refreshAction)
            refresh()
        }

        private fun text(zh: String, en: String) = if (chinese) zh else en
        private fun dp(value: Int) = (value * context.resources.displayMetrics.density + 0.5f).toInt()
        private fun message(value: String) = Toast.makeText(context, value, Toast.LENGTH_LONG).show()

        private fun row(label: String, click: () -> Unit) = Preference(context).apply {
            title = label
            isIconSpaceReserved = false
            setOnPreferenceClickListener {
                click()
                true
            }
        }

        private fun section(label: String, vararg rows: Preference) {
            val category = PreferenceCategory(context).apply {
                title = label
                isIconSpaceReserved = false
            }
            screen.addPreference(category)
            rows.forEach(category::addPreference)
        }

        private fun refresh() {
            val busy = connectionActionInProgress.get()
            val selected = config()
            val configured = selected.server.isNotBlank()
            val signedIn = signedIn()
            val username = setting(USERNAME)
            server.summary = selected.server.ifBlank { text("填写 HTTPS 地址", "Enter an HTTPS address") }
            certificate.summary = when {
                selected.ca.isNotBlank() && selected.fingerprint.isNotBlank() -> text("私有 CA", "Private CA")
                selected.ca.isNotBlank() || selected.fingerprint.isNotBlank() -> text("私有 CA 待补全", "Private CA setup incomplete")
                else -> text("系统信任", "System trust")
            }
            account.summary = when {
                signedIn -> if (username.isBlank()) text("已登录", "Signed in") else text("$username · 已登录", "$username · Signed in")
                busy -> text("正在连接…", "Connecting…")
                else -> text("未登录", "Signed out")
            }
            val all = text("全部可访问的媒体库", "All accessible libraries")
            val known = availableLibraries()
            libraries.entries = (listOf(all) + known.map { it.text("Name") }).toTypedArray()
            libraries.entryValues = (listOf("") + known.map { it.text("Id") }).toTypedArray()
            libraries.value = setting(LIBRARY).takeIf { it in libraries.entryValues }.orEmpty()
            libraries.summary = libraries.entry ?: all
            libraries.isVisible = signedIn
            libraries.setEnabled(!busy && known.isNotEmpty())
            diagnostic.summary = when (val code = setting(DIAGNOSTIC_CODE)) {
                "" -> text("尚未测试或登录", "No connection result yet")
                "OK" -> text("最近一次连接成功", "Last connection succeeded")
                else -> code
            }
            server.setEnabled(!busy)
            certificate.setEnabled(!busy)
            account.setEnabled(!busy && configured)
            test.setEnabled(!busy && configured)
        }

        private fun refreshActiveScreen() {
            val visible = refreshSettings?.get()
            visible?.run()
            if (visible !== refreshAction) refresh()
        }

        private fun editServer() {
            val form = form()
            val address = field(
                form,
                text("HTTPS 地址", "HTTPS address"),
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI,
                setting(SERVER),
                "https://media.example.org",
            )
            showForm(text("服务器地址", "Server address"), form, text("保存", "Save"), address) { dialog ->
                try {
                    val entered = address.text.toString().trim()
                    val endpoint = if (entered.isBlank()) "" else NasConnection.parseEndpoint(entered).toString().trimEnd('/')
                    val previous = runCatching { NasConnection.parseEndpoint(setting(SERVER)).toString().trimEnd('/') }.getOrDefault(setting(SERVER))
                    if (endpoint == previous) {
                        dialog.dismiss()
                    } else {
                        saveConnectionChanges(dialog, text("更改服务器地址？", "Change server address?"), SERVER to endpoint)
                    }
                } catch (error: NasConfigurationException) {
                    address.error = ConnectionDiagnostics.failure(LoginStage.CONFIGURATION, error, chinese).advice
                }
            }
        }

        private fun openAccount() {
            if (!signedIn()) {
                openLogin()
                return
            }
            val actions = arrayOf(text("重新登录", "Sign in again"), text("退出登录", "Sign out"))
            AlertDialog.Builder(context)
                .setTitle(setting(USERNAME).ifBlank { text("Jellyfin 账号", "Jellyfin account") })
                .setItems(actions) { _, which ->
                    if (which == 0) openLogin() else confirmSignOut()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        private fun confirmSignOut() {
            confirm(
                text("退出 Jellyfin？", "Sign out of Jellyfin?"),
                text("此设备需要重新登录才能浏览和播放。", "Sign in again on this device to browse and play videos."),
                text("退出登录", "Sign out"),
            ) {
                changeConnection()
                refreshActiveScreen()
                message(text("已退出登录", "Signed out"))
            }
        }

        private fun openLogin() {
            val form = form()
            val username = field(form, text("账号", "Username"), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS, setting(USERNAME))
            val password = field(form, text("密码", "Password"), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD).apply {
                isSaveEnabled = false
            }
            val status = TextView(dialogContext).apply {
                accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
                visibility = View.GONE
            }.also(form::addView)
            val signInLabel = text("登录", "Sign in")
            showForm(
                text("登录 Jellyfin", "Sign in to Jellyfin"),
                form,
                signInLabel,
                if (username.text.isNullOrBlank()) username else password,
            ) { dialog ->
                val name = username.text.toString().trim()
                val enteredPassword = password.text.toString()
                if (name.isEmpty()) {
                    username.error = text("请输入账号", "Enter your username")
                    username.requestFocus()
                    return@showForm
                }
                if (enteredPassword.isEmpty() || '\n' in enteredPassword || '\r' in enteredPassword) {
                    password.error = text("请输入密码，去掉复制时带入的换行", "Enter your password without line breaks")
                    password.requestFocus()
                    return@showForm
                }
                val submit = dialog.getButton(DialogInterface.BUTTON_POSITIVE)
                fun showProgress(busy: Boolean) {
                    username.isEnabled = !busy
                    password.isEnabled = !busy
                    submit.isEnabled = !busy
                    submit.text = if (busy) text("正在登录…", "Signing in…") else signInLabel
                }
                val started = connect(SignIn(name, enteredPassword), { dialog.isShowing }) { failure ->
                    if (failure == null) {
                        password.text?.clear()
                        dialog.dismiss()
                        message(text("登录成功", "Signed in"))
                    } else {
                        showProgress(false)
                        status.text = failure.advice
                        status.visibility = View.VISIBLE
                        if (failure.code == "HTTP_401") password.requestFocus()
                    }
                }
                if (started) {
                    status.visibility = View.GONE
                    showProgress(true)
                }
            }
        }

        private fun editCertificate() {
            val form = form()
            val selected = config()
            val trustMode = RadioGroup(dialogContext).apply {
                orientation = RadioGroup.VERTICAL
            }.also(form::addView)
            val system = trustOption(trustMode, text("系统信任", "System trust"))
            val privateCa = trustOption(trustMode, text("私有 CA", "Private CA"))
            val custom = LinearLayout(dialogContext).apply { orientation = LinearLayout.VERTICAL }.also(form::addView)
            TextView(dialogContext).apply {
                text = text(
                    "从服务器管理员处获取 CA 公钥证书和指纹。",
                    "Get the CA certificate and fingerprint from your server administrator.",
                )
                setPadding(0, dp(4), 0, dp(12))
            }.also(custom::addView)
            val pem = field(
                custom,
                text("CA 公钥证书（PEM）", "CA certificate (PEM)"),
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS,
                selected.ca,
                text("粘贴完整证书", "Paste the complete certificate"),
                multiline = true,
            )
            val sha = field(
                custom,
                text("SHA-256 指纹", "SHA-256 fingerprint"),
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS,
                selected.fingerprint,
                text("64 个十六进制字符", "64 hexadecimal digits"),
            )
            val hasCustom = selected.ca.isNotBlank() || selected.fingerprint.isNotBlank()
            trustMode.check(if (hasCustom) privateCa else system)
            custom.visibility = if (hasCustom) View.VISIBLE else View.GONE
            trustMode.setOnCheckedChangeListener { _, checked ->
                if (checked == privateCa) {
                    custom.visibility = View.VISIBLE
                } else {
                    form.requestFocus()
                    context.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(form.windowToken, 0)
                    custom.visibility = View.GONE
                }
            }
            showForm(text("服务器证书", "Server certificate"), form, text("保存", "Save")) { dialog ->
                try {
                    val usePrivateCa = trustMode.checkedRadioButtonId == privateCa
                    val newPem = if (usePrivateCa) NasConnection.normalizePem(pem.text.toString()) else ""
                    val newFingerprint = if (usePrivateCa) NasConnection.normalizeFingerprint(sha.text.toString()) else ""
                    if (usePrivateCa) NasConnection.parseCa(newPem, newFingerprint)
                    val previousPem = runCatching { NasConnection.normalizePem(selected.ca) }.getOrDefault(selected.ca)
                    val previousFingerprint = runCatching { NasConnection.normalizeFingerprint(selected.fingerprint) }.getOrDefault(selected.fingerprint)
                    if (newPem == previousPem && newFingerprint == previousFingerprint) {
                        dialog.dismiss()
                    } else {
                        saveConnectionChanges(dialog, text("更改服务器证书？", "Change server certificate?"), CA to newPem, FINGERPRINT to newFingerprint)
                    }
                } catch (error: Exception) {
                    val failure = ConnectionDiagnostics.failure(LoginStage.CONFIGURATION, error, chinese)
                    val invalid = if (failure.code in listOf("CA_FINGERPRINT_FORMAT", "CA_FINGERPRINT_MISMATCH")) sha else pem
                    invalid.error = failure.advice
                    invalid.requestFocus()
                }
            }
        }

        private fun saveConnectionChanges(dialog: AlertDialog, title: String, vararg changes: Pair<String, String>) {
            fun save() {
                changeConnection(*changes)
                dialog.dismiss()
                refreshActiveScreen()
            }
            if (signedIn()) {
                confirm(
                    title,
                    text("保存后需要重新登录 Jellyfin。", "You'll need to sign in to Jellyfin again after saving."),
                    text("保存并退出登录", "Save and sign out"),
                    ::save,
                )
            } else {
                save()
            }
        }

        private fun confirm(title: String, detail: String, label: String, perform: () -> Unit) {
            AlertDialog.Builder(context)
                .setTitle(title)
                .setMessage(detail)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(label) { _, _ -> perform() }
                .show()
        }

        private fun testConnection() {
            connect { failure -> message(failure?.advice ?: text("连接成功", "Connection succeeded")) }
        }

        private fun connect(
            signIn: SignIn? = null,
            active: () -> Boolean = { true },
            complete: (ConnectionDiagnostics.Failure?) -> Unit,
        ): Boolean {
            if (!connectionActionInProgress.compareAndSet(false, true)) return false
            val selected = config()
            val usernameAtStart = setting(USERNAME)
            val tokenAtStart = setting(TOKEN)
            refreshActiveScreen()
            scope.launch {
                var stage = LoginStage.CONFIGURATION
                var error: Exception? = null
                var session: PlaybackSession? = null
                try {
                    var token = ""
                    val proposed = NasConnection(selected.server, selected.ca, selected.fingerprint) { token }
                    try {
                        stage = LoginStage.HTTPS
                        checkServer(proposed)
                        if (signIn != null) {
                            stage = LoginStage.AUTHENTICATION
                            val result = authenticate(proposed, signIn.username, signIn.password)
                            token = result.text("AccessToken")
                            val uid = JellyfinJson.id(result.obj("User").text("Id"))
                            stage = LoginStage.LIBRARIES
                            val roots = responseJson(proposed.client.newCall(request(proposed.url("Users", uid, "Items"))).execute())
                            session = PlaybackSession(proposed.endpoint, token, uid, JellyfinJson.videoLibraries(roots))
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
                        if (!active()) {
                            refreshActiveScreen()
                            return@withContext
                        }
                        if (config() != selected || setting(USERNAME) != usernameAtStart || setting(TOKEN) != tokenAtStart) {
                            refreshActiveScreen()
                            val changed = IllegalStateException("配置已变化，请重新登录")
                            complete(ConnectionDiagnostics.failure(LoginStage.CONFIGURATION, changed, chinese))
                            return@withContext
                        }
                        if (signIn != null) session?.let { saveLogin(signIn.username, it) }
                        val failed = error?.let { ConnectionDiagnostics.failure(stage, it, chinese) }
                        val hasUsername = (signIn?.username ?: usernameAtStart).isNotBlank()
                        val report = ConnectionDiagnostics.report(BuildConfig.VERSION_NAME, stage, selected.ca.isNotBlank(), selected.fingerprint.isNotBlank(), hasUsername, error, chinese)
                        preferences.edit().putString(DIAGNOSTIC, report).putString(DIAGNOSTIC_CODE, failed?.code ?: "OK").apply()
                        refreshActiveScreen()
                        complete(failed)
                    }
                }
            }
            return true
        }

        private fun form() = LinearLayout(dialogContext).apply {
            orientation = LinearLayout.VERTICAL
            isFocusableInTouchMode = true
            setPadding(dp(24), dp(8), dp(24), dp(12))
        }

        private fun trustOption(group: RadioGroup, label: String): Int {
            val button = RadioButton(dialogContext).apply {
                id = View.generateViewId()
                text = label
                minHeight = dp(48)
            }
            group.addView(button, RadioGroup.LayoutParams.MATCH_PARENT, RadioGroup.LayoutParams.WRAP_CONTENT)
            return button.id
        }

        private fun field(
            form: LinearLayout,
            label: String,
            type: Int,
            value: String = "",
            placeholder: String? = null,
            multiline: Boolean = false,
        ): EditText {
            val input = EditText(dialogContext).apply {
                id = View.generateViewId()
                if (!multiline) setSingleLine(true)
                inputType = type
                hint = placeholder
                setText(value)
                imeOptions = imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                if (multiline) {
                    minLines = 3
                    maxLines = 5
                    gravity = Gravity.TOP or Gravity.START
                }
            }
            val title = TextView(dialogContext).apply {
                text = label
                labelFor = input.id
            }
            form.addView(title, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            val layout = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(12)
            }
            form.addView(input, layout)
            return input
        }

        private fun showForm(title: String, form: LinearLayout, label: String, focus: EditText? = null, submit: (AlertDialog) -> Unit) {
            val dialog = AlertDialog.Builder(context)
                .setTitle(title)
                .setView(ScrollView(dialogContext).apply { addView(form) })
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(label, null)
                .create()
            dialog.setCanceledOnTouchOutside(false)
            val keyboard = if (focus == null) WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN else WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
            dialog.window?.setSoftInputMode(keyboard or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            dialog.setOnShowListener {
                (focus ?: form).requestFocus()
                dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener { submit(dialog) }
            }
            dialog.show()
        }

        private fun showDiagnostic() {
            val title = text("最近连接诊断", "Last connection report")
            val report = setting(DIAGNOSTIC).takeIf { setting(DIAGNOSTIC_CODE).isNotBlank() }
            val body = TextView(dialogContext).apply {
                text = report ?: text("尚未测试或登录", "Run a connection test or sign in first")
                setTextIsSelectable(true)
                setPadding(dp(24), dp(8), dp(24), dp(12))
            }
            val builder = AlertDialog.Builder(context)
                .setTitle(title)
                .setView(ScrollView(dialogContext).apply { addView(body) })
                .setNegativeButton(android.R.string.ok, null)
            if (report != null) {
                builder.setPositiveButton(text("复制", "Copy")) { _, _ ->
                    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(title, report))
                }
            }
            builder.show()
        }
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
