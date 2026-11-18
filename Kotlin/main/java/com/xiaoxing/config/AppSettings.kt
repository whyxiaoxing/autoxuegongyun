package com.xiaoxing.config

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

/** 识别置信度默认值与可调范围：默认与服务端 rustmatch 的 0.7 阈值一致 */
const val DEFAULT_MATCH_CONFIDENCE = 0.7f
val MATCH_CONFIDENCE_RANGE = 0.50f..0.99f

/** 界面主题：跟随系统 / 强制浅色 / 强制深色 */
enum class ThemeMode(val key: String, val label: String) {
    SYSTEM("system", "跟随系统"),
    LIGHT("light", "浅色"),
    DARK("dark", "深色");

    companion object {
        fun fromKey(value: String?): ThemeMode =
            entries.firstOrNull { it.key == value } ?: SYSTEM
    }
}

/**
 * 服务端连接配置。
 * IP 与端口均由开发者在页面修改并持久化（服务端实际 IP 不在客户端仓库内）；
 * IP 为空字符串时服务端按 socket 语义绑定所有接口，客户端此处不能为空。
 */
@Immutable
data class ServerConfig(
    val ip: String = "",
    val port: Int = DEFAULT_PORT,
) {
    val baseUrl: String get() = "http://$ip:$port"
    val isComplete: Boolean get() = ip.isNotBlank() && port in 1..65535

    companion object {
        const val DEFAULT_PORT = 8080
    }
}

/**
 * 失败提醒配置（一级导航「提醒」页可调）。
 *
 * @property vibrationEnabled 流程失败时是否震动
 * @property ringtoneEnabled 流程失败时是否响铃
 * @property ringtoneUri 自定义铃声 URI；空 = 系统默认闹铃
 */
@Immutable
data class FailureAlertSettings(
    val vibrationEnabled: Boolean = true,
    val ringtoneEnabled: Boolean = true,
    val ringtoneUri: String = "",
)

/** 阿里云 OSS 资源源配置（公共读 Bucket，走虚拟主机式 URL，无需签名） */
@Immutable
data class OssConfig(
    val endpoint: String = "oss-cn-hangzhou.aliyuncs.com",
    val bucket: String = "",
    val prefix: String = "gongxueyun",
) {
    val isComplete: Boolean get() = bucket.isNotBlank() && endpoint.isNotBlank()

    /** 对象 URL：https://{bucket}.{endpoint}/{prefix}/{key} */
    fun url(key: String): String {
        val p = prefix.trim('/')
        val k = key.trim('/')
        return if (p.isEmpty()) "https://$bucket.$endpoint/$k" else "https://$bucket.$endpoint/$p/$k"
    }
}

object SettingsStore {
    private val IP = stringPreferencesKey("server_ip")
    private val PORT = intPreferencesKey("server_port")
    private val OSS_ENDPOINT = stringPreferencesKey("oss_endpoint")
    private val OSS_BUCKET = stringPreferencesKey("oss_bucket")
    private val OSS_PREFIX = stringPreferencesKey("oss_prefix")
    private val TARGET_PACKAGE = stringPreferencesKey("target_package")
    private val HOLD_FRAME = booleanPreferencesKey("capture_hold_frame")
    private val SAVE_SCREENSHOTS = booleanPreferencesKey("capture_save_screenshots")
    private val CLICK_EFFECT = booleanPreferencesKey("click_effect")
    private val SKIP_PROJECTION_CONFIRM = booleanPreferencesKey("skip_projection_confirm")
    private val FAILURE_VIBRATION = booleanPreferencesKey("failure_vibration")
    private val FAILURE_RINGTONE = booleanPreferencesKey("failure_ringtone")
    private val FAILURE_RINGTONE_URI = stringPreferencesKey("failure_ringtone_uri")
    private val MATCH_CONFIDENCE_KEY = floatPreferencesKey("match_confidence")
    private val THEME_MODE = stringPreferencesKey("theme_mode")
    private val IDENTITY_UNIT = stringPreferencesKey("identity_unit")
    private val IDENTITY_ROLE = stringPreferencesKey("identity_role")
    private val IDENTITY_TODAY_EVENTS = stringPreferencesKey("identity_today_events")
    private val IDENTITY_DAILY_REPORT = stringPreferencesKey("identity_daily_report")
    private val IDENTITY_WEEKLY_REPORT = stringPreferencesKey("identity_weekly_report")
    private val IDENTITY_MONTHLY_REPORT = stringPreferencesKey("identity_monthly_report")
    private val IDENTITY_ASSISTANT = booleanPreferencesKey("identity_assistant")

    private lateinit var appContext: Context

    fun init(context: Context) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext
    }

    /** lazy：object 属性初始化发生在 <clinit>，早于 init(context)，此时 appContext 尚未赋值 */
    val serverConfig: Flow<ServerConfig> by lazy {
        appContext.dataStore.data.map {
            ServerConfig(ip = it[IP] ?: "", port = it[PORT] ?: ServerConfig.DEFAULT_PORT)
        }
    }

    val ossConfig: Flow<OssConfig> by lazy {
        appContext.dataStore.data.map {
            OssConfig(
                endpoint = it[OSS_ENDPOINT] ?: "oss-cn-hangzhou.aliyuncs.com",
                bucket = it[OSS_BUCKET] ?: "",
                prefix = it[OSS_PREFIX] ?: "gongxueyun",
            )
        }
    }

    val targetPackage: Flow<String> by lazy {
        appContext.dataStore.data.map { it[TARGET_PACKAGE] ?: "" }
    }

    /** 截图「保持帧」：屏幕静止不出新帧时复用上一帧（默认开） */
    val holdFrame: Flow<Boolean> by lazy {
        appContext.dataStore.data.map { it[HOLD_FRAME] ?: true }
    }

    /** 截图「保存内容」：每次捕获的 PNG 经 MediaStore 存入相册 DCIM/AutoGongxueyun，便于排查帧池/匹配问题（默认关） */
    val saveScreenshots: Flow<Boolean> by lazy {
        appContext.dataStore.data.map { it[SAVE_SCREENSHOTS] ?: false }
    }

    /** 点击位置效果：自动化点击/滑动时在屏幕上标出实际坐标（默认关） */
    val clickEffect: Flow<Boolean> by lazy {
        appContext.dataStore.data.map { it[CLICK_EFFECT] ?: false }
    }

    /** 跳过投屏二次确认（第二层闸门）：开启后不再等待通知栏「继续共享」确认（默认关） */
    val skipProjectionConfirm: Flow<Boolean> by lazy {
        appContext.dataStore.data.map { it[SKIP_PROJECTION_CONFIRM] ?: false }
    }

    /** 失败提醒：震动开关、铃声开关与自定义铃声 URI（默认全开、系统默认闹铃） */
    val failureAlertSettings: Flow<FailureAlertSettings> by lazy {
        appContext.dataStore.data.map {
            FailureAlertSettings(
                vibrationEnabled = it[FAILURE_VIBRATION] ?: true,
                ringtoneEnabled = it[FAILURE_RINGTONE] ?: true,
                ringtoneUri = it[FAILURE_RINGTONE_URI].orEmpty(),
            )
        }
    }

    /** 识别置信度：传给 /See 与 /See_Submit_Photo 的 threshold（默认 0.7） */
    val matchConfidence: Flow<Float> by lazy {
        appContext.dataStore.data.map { it[MATCH_CONFIDENCE_KEY] ?: DEFAULT_MATCH_CONFIDENCE }
    }

    /** 界面主题（默认跟随系统） */
    val themeMode: Flow<ThemeMode> by lazy {
        appContext.dataStore.data.map { ThemeMode.fromKey(it[THEME_MODE]) }
    }

    /** 身份信息：/AiText 提示词素材（默认全空） */
    val identityInfo: Flow<IdentityInfo> by lazy {
        appContext.dataStore.data.map {
            IdentityInfo(
                unit = it[IDENTITY_UNIT].orEmpty(),
                role = it[IDENTITY_ROLE].orEmpty(),
                todayEvents = it[IDENTITY_TODAY_EVENTS].orEmpty(),
                dailyReport = it[IDENTITY_DAILY_REPORT].orEmpty(),
                weeklyReport = it[IDENTITY_WEEKLY_REPORT].orEmpty(),
                monthlyReport = it[IDENTITY_MONTHLY_REPORT].orEmpty(),
                assistantIdentity = it[IDENTITY_ASSISTANT] ?: true,
            )
        }
    }

    suspend fun saveServerConfig(config: ServerConfig) {
        appContext.dataStore.edit {
            it[IP] = config.ip.trim()
            it[PORT] = config.port
        }
    }

    suspend fun saveOssConfig(config: OssConfig) {
        appContext.dataStore.edit {
            it[OSS_ENDPOINT] = config.endpoint.trim()
            it[OSS_BUCKET] = config.bucket.trim()
            it[OSS_PREFIX] = config.prefix.trim()
        }
    }

    suspend fun saveTargetPackage(pkg: String) {
        appContext.dataStore.edit { it[TARGET_PACKAGE] = pkg.trim() }
    }

    suspend fun saveHoldFrame(value: Boolean) {
        appContext.dataStore.edit { it[HOLD_FRAME] = value }
    }

    suspend fun saveSaveScreenshots(value: Boolean) {
        appContext.dataStore.edit { it[SAVE_SCREENSHOTS] = value }
    }

    suspend fun saveClickEffect(value: Boolean) {
        appContext.dataStore.edit { it[CLICK_EFFECT] = value }
    }

    suspend fun saveSkipProjectionConfirm(value: Boolean) {
        appContext.dataStore.edit { it[SKIP_PROJECTION_CONFIRM] = value }
    }

    suspend fun saveFailureVibration(value: Boolean) {
        appContext.dataStore.edit { it[FAILURE_VIBRATION] = value }
    }

    suspend fun saveFailureRingtone(value: Boolean) {
        appContext.dataStore.edit { it[FAILURE_RINGTONE] = value }
    }

    suspend fun saveFailureRingtoneUri(uri: String) {
        appContext.dataStore.edit { it[FAILURE_RINGTONE_URI] = uri.trim() }
    }

    suspend fun saveMatchConfidence(value: Float) {
        appContext.dataStore.edit { it[MATCH_CONFIDENCE_KEY] = value.coerceIn(MATCH_CONFIDENCE_RANGE) }
    }

    suspend fun saveThemeMode(mode: ThemeMode) {
        appContext.dataStore.edit { it[THEME_MODE] = mode.key }
    }

    suspend fun saveIdentity(info: IdentityInfo) {
        appContext.dataStore.edit {
            it[IDENTITY_UNIT] = info.unit.trim()
            it[IDENTITY_ROLE] = info.role.trim()
            it[IDENTITY_TODAY_EVENTS] = info.todayEvents.trim()
            it[IDENTITY_DAILY_REPORT] = info.dailyReport.trim()
            it[IDENTITY_WEEKLY_REPORT] = info.weeklyReport.trim()
            it[IDENTITY_MONTHLY_REPORT] = info.monthlyReport.trim()
            it[IDENTITY_ASSISTANT] = info.assistantIdentity
        }
    }
}
