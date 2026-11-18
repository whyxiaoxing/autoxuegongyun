package com.xiaoxing.network

import com.xiaoxing.concurrent.AppExecutors
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/** 业务异常：message 面向日志/页面展示 */
class ApiException(message: String) : Exception(message)

/** rustmatch 后端（FastAPI 包装 Rust 匹配核心）契约中的 JSON 模型 */

@Serializable
private data class SeeRequest(
    @SerialName("图片base64") val imageBase64: String,
    @SerialName("识别对象") val target: String,
    @SerialName("Cookie密钥") val cookieKey: String,
    @SerialName("置信度") val confidence: Float,
)

@Serializable
private data class SeeResponse(@SerialName("result") val result: List<Int>? = null)

/**
 * /AiText 请求体：对应服务端 `AI文本` 模型，三个字段都必填，随 JSON body 发送。
 *
 * `提示词` 是 user 消息（今日事件/报告正文/补充要求），
 * `assistant提示词` 是 assistant 消息的前缀预填（实习单位/身份描述 + 「这是一份周报」）。
 */
@Serializable
private data class AiTextRequest(
    @SerialName("提示词") val prompt: String,
    @SerialName("assistant提示词") val assistantPrompt: String = "",
    @SerialName("Cookie密钥") val cookieKey: String,
)

@Serializable
private data class AiTextResponse(@SerialName("result") val result: String? = null)

/** /See_Submit_Photo：模板图片与识别图片都由本机提供（Base64） */
@Serializable
private data class SubmitPhotoRequest(
    @SerialName("模板图片") val templateBase64: String,
    @SerialName("识别图片") val imageBase64: String,
    @SerialName("Cookie密钥") val cookieKey: String,
    @SerialName("置信度") val confidence: Float,
)

/**
 * /OCRPhoto：服务端按 `目标文字` 过滤 OCR 结果，请求体自带 Cookie 密钥（与 /See 一致）。
 * `识别图片` 与 SeeRequest 一样是 PNG 的 Base64 文本；`PSM` 是 Tesseract 页面分割模式。
 */
@Serializable
private data class OcrRequest(
    @SerialName("识别图片") val imageBase64: String,
    @SerialName("目标文字") val targetText: String,
    @SerialName("置信度") val confidence: Float,
    @SerialName("PSM") val psm: Int,
    @SerialName("Cookie密钥") val cookieKey: String,
)

/** /OCRPhoto：result 为 `{识别文字: [centerX, centerY]}`（只包含命中目标文字的条目） */
@Serializable
private data class OcrResponse(@SerialName("result") val result: Map<String, List<Int>>? = null)

@Serializable
private data class ApiMessage(@SerialName("message") val message: String? = null)

/** /GetMatchName 与 /UpdataPhotoName 共用：result 为服务端 Photo/ 目录下的模板名 */
@Serializable
private data class TemplateNamesResponse(@SerialName("result") val result: List<String>? = null)

/**
 * 匹配结果：NotFound 覆盖服务端的「未识别到目标」——
 * HTTP 510 + `{"message": ...}`（main.py 的 except 分支），以及旧版 200 + result:null。
 * 510 表示服务器可达、只是没匹配到；不能当成请求异常，否则匹配失败会被误判成网络错误。
 */
sealed interface MatchOutcome {
    data class Found(val x: Int, val y: Int) : MatchOutcome

    /**
     * 未匹配。
     * [detail] 保留服务端返回的原始原因（如 "HTTP 510: 没有找到目标位置"），
     * 只用于日志/页面展示，不参与控制流 —— 否则出问题时日志里只剩"超时未匹配"，无法定位。
     */
    data class NotFound(val detail: String? = null) : MatchOutcome
}

object ApiClient {
    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * AI 文案专用客户端：服务端转发大模型请求，最长可等 10 分钟。
     * 普通 /See 的 30s 读超时对 AI 生成显然不够，又不能整体放宽（避免普通匹配卡死 10 分钟）。
     */
    val longHttp: OkHttpClient = http.newBuilder()
        .readTimeout(600, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .callTimeout(620, TimeUnit.SECONDS)
        .build()

    /**
     * OCR 专用客户端：/OCRPhoto 内部给 Tesseract 的超时是 120 秒（main.py），
     * 普通 /See 的 30s 不够，又不该像 AI 文案那样等 10 分钟，单独给 130 秒读超时。
     */
    val ocrHttp: OkHttpClient = http.newBuilder()
        .readTimeout(130, TimeUnit.SECONDS)
        .callTimeout(140, TimeUnit.SECONDS)
        .build()

    /**
     * 服务端契约：成功响应只带 `result`，失败响应只带 `message`。
     * 错误文案优先取 `message`，解析不了时退回原文片段（如 FastAPI 的 422 `detail`）。
     */
    fun errorMessage(raw: String): String =
        runCatching { json.decodeFromString<ApiMessage>(raw).message }.getOrNull()
            ?: raw.take(200).ifBlank { "无响应体" }
}

/**
 * 令牌管理：POST /Register 换取 CookieAll（AES-128-GCM，明文 XIAOXING:<时间戳>）。
 * 服务端密钥 1800s 轮换、令牌上限 1300s → 提前 300s 主动刷新；任何 401 立即失效重注册。
 * 注意：/See 只认请求体字段 Cookie密钥，不读 Cookie 头，因此手工解析 Set-Cookie。
 */
class TokenManager(private val baseUrlProvider: () -> String) {

    companion object {
        /** 服务端注册路由：@_Fastapidata.fastapiObj.post("/Register")，与 API.md 一致 */
        const val REGISTER_PATH = "/Register"
        const val AUTH_HEADER = "Headers"
        const val AUTH_HEADER_VALUE = "XIAOXING"
        const val TOKEN_TTL_MS = 1300_000L
        const val REFRESH_AHEAD_MS = 300_000L
        private val COOKIE_ALL = Regex("""CookieAll=([^;]+)""")
    }

    @Volatile
    private var token: String? = null

    @Volatile
    private var issuedAtMs: Long = 0L

    /** 令牌刷新串行化：初始化预检、AI 预取、匹配步骤可能同时发现令牌过期，只应注册一次。 */
    private val refreshMutex = Mutex()

    val remainingMs: Long
        get() = if (token == null) 0 else (TOKEN_TTL_MS - (System.currentTimeMillis() - issuedAtMs)).coerceAtLeast(0)

    fun parseSetCookie(header: String): String? = COOKIE_ALL.find(header)?.groupValues?.get(1)

    fun invalidate() {
        token = null
        issuedAtMs = 0L
    }

    private fun isFresh(t: String?): Boolean =
        t != null && System.currentTimeMillis() - issuedAtMs < TOKEN_TTL_MS - REFRESH_AHEAD_MS

    suspend fun validToken(): String {
        token?.let { if (isFresh(it)) return it }
        // 多个协程同时发现过期时，先拿到锁的注册，其余复用新令牌。
        return refreshMutex.withLock {
            token?.takeIf(::isFresh) ?: register()
        }
    }

    /** POST /Register：强制重新注册（连接测试需要真实请求，不做新鲜度复用）。 */
    suspend fun refresh(): String = refreshMutex.withLock { register() }

    /** POST /Register，固定头 Headers: XIAOXING，从 Set-Cookie 解析 CookieAll */
    private suspend fun register(): String = withContext(AppExecutors.dispatcher) {
        val request = Request.Builder()
            .url(baseUrlProvider() + REGISTER_PATH)
            .header(AUTH_HEADER, AUTH_HEADER_VALUE)
            .post(ByteArray(0).toRequestBody(null))
            .build()
        ApiClient.http.newCall(request).execute().use { response ->
            if (response.code != 200) {
                val raw = response.body?.string().orEmpty()
                throw ApiException("注册失败 HTTP ${response.code}: ${ApiClient.errorMessage(raw)}")
            }
            val setCookie = response.header("Set-Cookie")
                ?: throw ApiException("注册响应缺少 Set-Cookie 头")
            parseSetCookie(setCookie) ?: throw ApiException("Set-Cookie 中未找到 CookieAll")
        }.also { token = it; issuedAtMs = System.currentTimeMillis() }
    }
}

/**
 * POST /See：PNG 截图 Base64 + 模板名 + 令牌 + 置信度（请求体） → 匹配坐标。
 *
 * @param confidenceProvider 识别置信度（设置页可调）；每次请求实时读取，无需重建客户端。
 *   放在 [baseUrlProvider] 之前，保证测试里 `SeeClient(tokenManager) { server }` 的尾随 lambda 仍绑定 URL。
 */
class SeeClient(
    private val tokenManager: TokenManager,
    private val confidenceProvider: () -> Float = { 0.7f },
    private val baseUrlProvider: () -> String,
) {
    private val jsonMedia = "application/json".toMediaType()

    private fun confidence(): Float = confidenceProvider().coerceIn(0.01f, 1f)

    /** 初始化预热：提前获取/刷新令牌，首个 match 步骤不再承担注册延迟 */
    suspend fun prepare() {
        tokenManager.validToken()
    }

    /**
     * 单次匹配。
     *  - 401 → 令牌失效，重注册并重放一次；
     *  - 510 → NotFound（服务端语义：`510` 表示模板匹配失败/未识别到目标，服务器可达）。
     *    不能把 510 当请求失败，否则匹配失败会被误判成网络/服务异常；
     *    服务端的 `message` 放进 [MatchOutcome.NotFound.detail] 供排查；
     *  - 其余状态码（含真实服务器错误 500）→ [ApiException]，并带上服务端 `message`。
     */
    suspend fun match(imageBase64: String, target: String): MatchOutcome {
        repeat(2) { attempt ->
            val body = ApiClient.json.encodeToString(
                SeeRequest.serializer(),
                SeeRequest(imageBase64, target, tokenManager.validToken(), confidence()),
            )
            val request = Request.Builder()
                .url(baseUrlProvider() + "/See")
                .post(body.toRequestBody(jsonMedia))
                .build()
            ApiClient.http.newCall(request).execute().use { response ->
                // 响应体只能读一次：先取出原文，各分支再按需解析/截断
                val raw = response.body?.string().orEmpty()
                when (response.code) {
                    200 -> {
                        val parsed = runCatching {
                            ApiClient.json.decodeFromString(SeeResponse.serializer(), raw)
                        }.getOrElse {
                            throw ApiException("响应解析失败（HTTP 200）: ${raw.take(200)}")
                        }
                        val r = parsed.result
                        return if (r != null && r.size >= 2) {
                            MatchOutcome.Found(r[0], r[1])
                        } else {
                            MatchOutcome.NotFound("HTTP 200 result:null（相似度未超过阈值）")
                        }
                    }

                    401 -> {
                        tokenManager.invalidate()
                        if (attempt == 1) throw ApiException("令牌重注册后仍 401（服务端可能已重启）")
                    }

                    510 -> {
                        // 服务端约定：模板匹配不到目标时 main.py 走 except 分支，
                        // 返回 510 + {"message": 原因}。这是正常的「未匹配」，
                        // 不能当请求失败处理，否则会被误判为网络/服务异常。
                        val msg = runCatching {
                            ApiClient.json.decodeFromString<ApiMessage>(raw).message
                        }.getOrNull()
                        return MatchOutcome.NotFound(
                            "HTTP 510: " + (msg ?: raw.take(200).ifBlank { "无响应体（服务端以 510 表示未匹配）" }),
                        )
                    }

                    else -> throw ApiException(
                        "HTTP ${response.code}: " + ApiClient.errorMessage(raw),
                    )
                }
            }
        }
        throw IllegalStateException("unreachable")
    }

    /**
     * `POST /See_Submit_Photo`：模板图片 + 识别图片都由本机提供，服务端不查模板表。
     * 401 重注册重放一次；510 → NotFound（与 /See 语义一致：510 = 未识别到目标，服务器可达）。
     */
    suspend fun matchPhoto(templatePngBase64: String, imagePngBase64: String): MatchOutcome {
        repeat(2) { attempt ->
            val body = ApiClient.json.encodeToString(
                SubmitPhotoRequest.serializer(),
                SubmitPhotoRequest(
                    templatePngBase64,
                    imagePngBase64,
                    tokenManager.validToken(),
                    confidence(),
                ),
            )
            val request = Request.Builder()
                .url(baseUrlProvider() + "/See_Submit_Photo")
                .post(body.toRequestBody(jsonMedia))
                .build()
            ApiClient.http.newCall(request).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                when (response.code) {
                    200 -> {
                        val parsed = runCatching {
                            ApiClient.json.decodeFromString(SeeResponse.serializer(), raw)
                        }.getOrElse {
                            throw ApiException("提交图片响应解析失败（HTTP 200）: ${raw.take(200)}")
                        }
                        val r = parsed.result
                        return if (r != null && r.size >= 2) {
                            MatchOutcome.Found(r[0], r[1])
                        } else {
                            MatchOutcome.NotFound("HTTP 200 result:null（相似度未超过阈值）")
                        }
                    }

                    401 -> {
                        tokenManager.invalidate()
                        if (attempt == 1) throw ApiException("令牌重注册后仍 401（服务端可能已重启）")
                    }

                    510 -> {
                        // 与服务端 /See 一致：未识别到目标时返回 510 + message，属于正常未匹配
                        val msg = runCatching {
                            ApiClient.json.decodeFromString<ApiMessage>(raw).message
                        }.getOrNull()
                        return MatchOutcome.NotFound(
                            "HTTP 510: " + (msg ?: raw.take(200).ifBlank { "无响应体（服务端以 510 表示未匹配）" }),
                        )
                    }

                    else -> throw ApiException(
                        "提交图片 HTTP ${response.code}: " + ApiClient.errorMessage(raw),
                    )
                }
            }
        }
        throw IllegalStateException("unreachable")
    }
}

/**
 * 服务端模板名列表：
 *  - `POST /GetMatchName?Cookie=…`  返回服务端缓存的模板名；
 *  - `POST /UpdataPhotoName?Cookie=…` 让服务端重新扫描 Photo/ 目录后返回最新列表。
 *
 * 两个路由的 Cookie 都是 FastAPI 的 query 参数（非请求体），401 时重注册并重放一次。
 */
class TemplateNameClient(
    private val tokenManager: TokenManager,
    private val baseUrlProvider: () -> String,
) {
    /** 读取服务端当前缓存的模板名（不触发重新扫描） */
    suspend fun list(): List<String> = request("/GetMatchName")

    /** 触发服务端重新扫描 Photo/ 目录并返回最新模板名 */
    suspend fun refresh(): List<String> = request("/UpdataPhotoName")

    private suspend fun request(path: String): List<String> = withContext(AppExecutors.dispatcher) {
        repeat(2) { attempt ->
            val base = (baseUrlProvider().trimEnd('/') + path).toHttpUrlOrNull()
                ?: throw ApiException("服务端地址无效")
            val requestUrl = base.newBuilder()
                .addQueryParameter("Cookie", tokenManager.validToken())
                .build()
            val request = Request.Builder()
                .url(requestUrl)
                .post(ByteArray(0).toRequestBody(null))
                .build()
            ApiClient.http.newCall(request).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                when (response.code) {
                    200 -> {
                        val parsed = runCatching {
                            ApiClient.json.decodeFromString(TemplateNamesResponse.serializer(), raw)
                        }.getOrElse {
                            throw ApiException("模板列表解析失败: ${raw.take(200)}")
                        }
                        return@withContext parsed.result.orEmpty()
                    }

                    401 -> {
                        tokenManager.invalidate()
                        if (attempt == 1) throw ApiException("令牌重注册后仍 401（服务端可能已重启）")
                    }

                    else -> throw ApiException(
                        "获取模板列表失败 HTTP ${response.code}: " + ApiClient.errorMessage(raw),
                    )
                }
            }
        }
        throw IllegalStateException("unreachable")
    }
}

/**
 * `POST /AiText`：JSON 请求体（服务端 `AI文本` 模型）`{提示词, assistant提示词, Cookie密钥}`。
 *
 * 2026-11 服务端更新：该路由改为 Pydantic 请求体传参（不再是 query），服务端会用
 * `data/SOUL.md` 作为系统提示词后转发大模型。生成耗时可能数分钟，使用 [ApiClient.longHttp]。
 *
 * @param assistantPrompt assistant 消息的前缀预填（身份信息 + 「这是一份周报」）；可为空串，
 *   但字段必须存在 —— 服务端 Pydantic 模型把它列为必填。
 */
class AiTextClient(
    private val tokenManager: TokenManager,
    private val baseUrlProvider: () -> String,
) {
    private val jsonMedia = "application/json".toMediaType()

    suspend fun generate(prompt: String, assistantPrompt: String = ""): String =
        withContext(AppExecutors.dispatcher) {
            require(prompt.isNotBlank()) { "提示词不能为空" }
            repeat(2) { attempt ->
                val body = ApiClient.json.encodeToString(
                    AiTextRequest.serializer(),
                    AiTextRequest(
                        prompt = prompt,
                        assistantPrompt = assistantPrompt,
                        cookieKey = tokenManager.validToken(),
                    ),
                )
                val request = Request.Builder()
                    .url(baseUrlProvider().trimEnd('/') + "/AiText")
                    .post(body.toRequestBody(jsonMedia))
                    .build()
                ApiClient.longHttp.newCall(request).execute().use { response ->
                    val raw = response.body?.string().orEmpty()
                    when (response.code) {
                        200 -> {
                            val parsed = runCatching {
                                ApiClient.json.decodeFromString(AiTextResponse.serializer(), raw)
                            }.getOrElse {
                                throw ApiException("AI 响应解析失败: ${raw.take(200)}")
                            }
                            val content = parsed.result?.trim().orEmpty()
                            if (content.isBlank()) throw ApiException("AI 未返回文案内容")
                            return@withContext content
                        }

                        401 -> {
                            tokenManager.invalidate()
                            if (attempt == 1) throw ApiException("令牌重注册后仍 401（服务端可能已重启）")
                        }

                        else -> throw ApiException(
                            "AI 文案失败 HTTP ${response.code}: " + ApiClient.errorMessage(raw),
                        )
                    }
                }
            }
            throw IllegalStateException("unreachable")
        }
}

/**
 * `POST /OCRPhoto`：请求体是 JSON（`OCR识别_提交数据`），字段与 `main.py` 一致：
 * `识别图片`（PNG 的 Base64）、`目标文字`、`置信度`、`PSM`、`Cookie密钥`。
 * 服务端用 Tesseract 只返回匹配目标文字的条目 `{识别文字: [centerX, centerY]}`。
 *
 * - `PSM` 固定 [DEFAULT_PSM]（稀疏文字，适合界面截图，与服务端 OCR_Core 默认值一致）；
 * - `置信度` 固定 [DEFAULT_CONFIDENCE]（服务端 OCR 默认阈值，与模板匹配的置信度设置互不影响）；
 * - 401 重注册并重放一次；使用 [ApiClient.ocrHttp]（服务端单次识别上限 120s）。
 */
class OcrClient(
    private val tokenManager: TokenManager,
    private val baseUrlProvider: () -> String,
) {
    private val jsonMedia = "application/json".toMediaType()

    companion object {
        /** Tesseract `--psm 11`：稀疏文字，适合界面截图 */
        const val DEFAULT_PSM = 11

        /** 服务端 OCR 默认相似度阈值（OCR_Core 默认 0.5） */
        const val DEFAULT_CONFIDENCE = 0.5f
    }

    /**
     * @param pngBase64 截图 PNG 的 Base64（无 `data:` 前缀），与 /See 的图片字段一致
     * @param targetText 要查找的文字；服务端按它过滤结果（支持配置的易混字符映射）
     * @param psm Tesseract 页面分割模式，默认 [DEFAULT_PSM]
     * @return 命中文字 → `[left, top]`；未命中返回空 Map
     */
    suspend fun recognize(
        pngBase64: String,
        targetText: String,
        psm: Int = DEFAULT_PSM,
    ): Map<String, List<Int>> =
        withContext(AppExecutors.dispatcher) {
            require(pngBase64.isNotEmpty()) { "截图数据为空" }
            require(targetText.isNotBlank()) { "OCR 识别文字为空" }
            repeat(2) { attempt ->
                val body = ApiClient.json.encodeToString(
                    OcrRequest.serializer(),
                    OcrRequest(
                        imageBase64 = pngBase64,
                        targetText = targetText,
                        confidence = DEFAULT_CONFIDENCE,
                        psm = psm,
                        cookieKey = tokenManager.validToken(),
                    ),
                )
                val request = Request.Builder()
                    .url(baseUrlProvider().trimEnd('/') + "/OCRPhoto")
                    .post(body.toRequestBody(jsonMedia))
                    .build()
                ApiClient.ocrHttp.newCall(request).execute().use { response ->
                    val raw = response.body?.string().orEmpty()
                    when (response.code) {
                        200 -> {
                            val parsed = runCatching {
                                ApiClient.json.decodeFromString(OcrResponse.serializer(), raw)
                            }.getOrElse {
                                throw ApiException("OCR 响应解析失败: ${raw.take(200)}")
                            }
                            return@withContext parsed.result.orEmpty()
                        }

                        401 -> {
                            tokenManager.invalidate()
                            if (attempt == 1) throw ApiException("令牌重注册后仍 401（服务端可能已重启）")
                        }

                        // 510 = 服务端 OCR 处理失败（message 给出原因）；未识别到文字是 200 + 空 result
                        else -> throw ApiException(
                            "OCR 失败 HTTP ${response.code}: " + ApiClient.errorMessage(raw),
                        )
                    }
                }
            }
            throw IllegalStateException("unreachable")
        }
}
