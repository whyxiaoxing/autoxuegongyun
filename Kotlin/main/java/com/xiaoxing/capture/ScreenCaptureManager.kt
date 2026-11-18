package com.xiaoxing.capture

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Base64
import com.xiaoxing.AppRepository
import com.xiaoxing.HintLevel
import com.xiaoxing.ProjectionGate
import com.xiaoxing.concurrent.AppExecutors
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * MediaProjection 截屏管理。
 *
 * 捕获对象（强制整个屏幕）：
 *   授权 intent 统一由 [createScreenCaptureIntent] 创建：API 34+ 使用
 *   MediaProjectionConfig.createConfigForDefaultDisplay()，弹窗中不再出现「单个应用」选项，
 *   始终镜像默认显示器；API < 34 本身也只支持整个屏幕捕获。
 *   捕获内容尺寸/可见性变化仍经 onCapturedContentResize / onCapturedContentVisibilityChanged
 *   （API 34 新回调）感知，但不再据此 resize VirtualDisplay，回调仅用于打日志。
 *
 * 授权与帧池创建时序（官方契约，minSdk=34 只跑 Android 14+）：
 *   1) 用户在投屏授权弹窗允许捕获整个屏幕；
 *   2) mediaProjection 类型前台服务已启动，且其通知真正出现在通知栏（前置：POST_NOTIFICATIONS 已授予）；
 *   3) 之后才允许 getMediaProjection + createVirtualDisplay 创建帧池。
 *   通知未上栏（拒绝通知权限）就建帧池 → 投影不出帧，帧池永远为空。
 *   调用方（MainActivity / AutomationForegroundService）负责完成 1)、2) 后才调用 [start]。
 *
 * PNG 数据链路（约定：截图统一走 PNG，不做 JPEG 有损压缩）：
 *   ImageReader(RGBA_8888) → Bitmap(ARGB_8888，处理 rowStride 行对齐) → PNG 压缩 → 纯 Base64（无 data: 前缀）
 *
 * 帧策略（500ms 定时采样帧池）：
 *   旧实现"初始化等 3 秒拿一帧 + 被动等合成器推帧"——画面静止时合成器不推帧，池里永远只有一帧。
 *   现改为主动采样：帧池每 500ms acquireLatestImage 采集一帧 → 复用池中的 Bitmap → 发布；
 *   采集时保持帧开启直接取最新缓存帧；保持帧关闭则等一个采样周期内的新采样帧。
 *   「保持帧」由页面开关控制（AppRepository.holdFrame，默认开）。
 *   「保存截图内容」开启时，每个新帧的 PNG 经 MediaStore 存入相册 DCIM/AutoGongxueyun，
 *   同一帧的重复轮询不会重复写盘（AppRepository.saveScreenshots）。
 *
 * 内存策略：
 *   - ImageReader 只保留 2 个全屏缓冲；
 *   - 采样位图从复用池取，消费者通过引用计数持有，退休且 refs == 0 才回池；
 *   - PNG / Base64 按帧号缓存：同一张静止画面在 match 轮询中只编码一次；
 *   - rowStride 打包 buffer 复用，不再每帧 allocateDirect。
 *
 * 官方契约（developer.android.com → MediaProjection，targetSdk U+ 严格校验）：
 *   - Callback 必须先于 createVirtualDisplay 注册，否则 IllegalStateException；
 *   - 同一 MediaProjection 实例只允许一次 createVirtualDisplay，结束时必须 stop()；
 *   - getMediaProjection 对同一授权数据只能调用一次。
 *
 * API 34+ 约束：调用 [start] 前必须已启动 foregroundServiceType 含 mediaProjection 的前台服务，
 * 否则 getMediaProjection 抛 SecurityException。
 */
class ScreenCaptureManager(private val context: Context) {

    /** 用户从状态栏停止投屏 / 系统撤销授权时回调（在截屏线程上触发） */
    var onProjectionStopped: (() -> Unit)? = null

    private var projection: MediaProjection? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null

    // 捕获对象尺寸参照（用于区分"单应用窗口"与"整个屏幕"）
    private var screenWidth = 0
    private var screenHeight = 0
    private var densityDpi = 0

    /**
     * 一帧 = 位图 + 帧号。帧号让「同一帧只编码一次」成为可能，也让位图复用不会与
     * 编码缓存错配。
     */
    private class CapturedFrame(val id: Long, val bitmap: Bitmap) {
        var refs = 0
        var retired = false
    }

    /** 发布帧 / 位图池 / 引用计数的所有权锁（采样线程与工作流协程共享）。 */
    private val frameLock = Any()

    /** 当前发布帧：消费者持有期间（refs > 0）绝不会被采样线程复用或回收。 */
    private var published: CapturedFrame? = null

    /** 空闲位图池：复用全屏 ARGB_8888，避免每帧新分配。 */
    private val spareBitmaps = ArrayDeque<Bitmap>()

    /** 已发布的帧计数（判定"是否有新帧到达"，同时用作帧 id） */
    private val frameCounter = AtomicLong(0L)

    /** PNG 字节缓存：同一帧只压缩一次（静止画面轮询不再重复编码）。 */
    private val pngLock = Any()
    private var cachedPngId = -1L
    private var cachedPng: ByteArray? = null

    /** Base64 缓存：保持 /See 原有 String 入参不变，但同一帧只编码一次。 */
    private val base64Lock = Any()
    private var cachedBase64Id = -1L
    private var cachedBase64: String? = null

    /** rowStride 有对齐填充时复用同一块 direct buffer（只在采样线程访问）。 */
    private var packedBuffer: ByteBuffer? = null

    /** 「保存截图内容」已落盘的张数（用于首张落盘时提示目录，避免日志刷屏） */
    private val savedCounter = AtomicLong(0L)

    /** 已自动落盘的帧号：同一帧（画面静止）只写一次相册，避免轮询时重复写盘。 */
    private val lastAutoSavedFrameId = AtomicLong(-1L)

    /** 采样开关（stop 置 false，采样任务自行退出） */
    private val sampling = AtomicBoolean(false)

    /** 预览缩略图上次发布时刻（限流，避免每次采样都分配一张位图） */
    private var previewAtMs = 0L

    /** 500ms 定时采样任务：取 ImageReader 最新帧转 Bitmap 发布（画面静止采不到新帧则沿用上一帧） */
    private val sampleTask = object : Runnable {
        override fun run() {
            if (!sampling.get()) return
            imageReader?.let { reader ->
                var image: Image? = null
                var bitmap: Bitmap? = null
                try {
                    image = reader.acquireLatestImage()
                    image?.let {
                        // 复用池中的全屏位图，避免每帧 allocate 一张新的 ARGB_8888
                        bitmap = obtainBitmap(it.width, it.height)
                        copyImageToBitmap(it, bitmap!!)
                    }
                } catch (_: Throwable) {
                    // 采样失败（如 "bad position"）时下一周期继续；出帧状态由 awaitFirstFrame/超时提示兜底
                    bitmap?.let { temp ->
                        synchronized(frameLock) { if (!temp.isRecycled) temp.recycle() }
                    }
                    bitmap = null
                } finally {
                    image?.close()
                }
                bitmap?.let { frame ->
                    // 先缩略预览（缩放副本），再发布；发布可能因 stop() 竞态而回收该位图
                    if (AppRepository.projectionGate.value == ProjectionGate.AWAITING) {
                        publishPreview(frame)
                    }
                    publishFrame(frame)
                }
            }
            handler?.postDelayed(this, SAMPLE_INTERVAL_MS)
        }
    }

    val isReady: Boolean get() = imageReader != null

    /**
     * 当前发布帧号（0 = 尚无帧）。同一帧号代表画面没有更新：
     * 工作流轮询识别时可直接复用上一轮结果，不必把同一张截图重复发给服务端。
     */
    val currentFrameId: Long get() = frameCounter.get()

    fun start(projectionResultData: Intent) {
        stop()
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
        val bounds = wm.currentWindowMetrics.bounds
        screenWidth = bounds.width()
        screenHeight = bounds.height()
        densityDpi = context.resources.displayMetrics.densityDpi

        val thread = HandlerThread("XiaoXing-Capture").also { it.start() }
        handlerThread = thread
        val handler = Handler(thread.looper)
        this.handler = handler

        val mm = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val mp = mm.getMediaProjection(Activity.RESULT_OK, projectionResultData)
            ?: throw IllegalStateException("投屏授权无效（getMediaProjection 返回空）")
        projection = mp

        // 官方契约：Callback 必须先于 createVirtualDisplay 注册；保留引用以便拆除时注销
        val callback = object : MediaProjection.Callback() {
            override fun onStop() {
                onProjectionStopped?.invoke()
            }

            override fun onCapturedContentResize(width: Int, height: Int) {
                // 已强制捕获整个默认显示器，VirtualDisplay 尺寸与显示器一致，不再 resize。
            }

            override fun onCapturedContentVisibilityChanged(isVisible: Boolean) {
                // 内容不可见（屏幕关闭/安全界面）时出帧暂停，保持帧继续使用最后一帧。
            }
        }
        projectionCallback = callback
        mp.registerCallback(callback, handler)

        // 每次采样 acquire + close，不跨 tick 持有 Image；2 张足够，且比 4 张少约 20MB 图形内存。
        val reader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
        imageReader = reader

        virtualDisplay = mp.createVirtualDisplay(
            "XiaoXing",
            screenWidth, screenHeight, densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            handler,
        )

        // 启动 500ms 定时采样（在截屏线程上跑，转换耗时不上主线程）
        active = this
        sampling.set(true)
        handler.post(sampleTask)
    }

    /** 一帧的编码结果：帧号用于缓存命中判断，PNG 字节供保存路径直接写盘。 */
    private class EncodedCapture(val id: Long, val png: ByteArray)

    /**
     * 截一帧并返回 PNG 字节（保存路径直接使用，避免 base64 往返）。
     * 压缩期间帧由引用计数保护，采样线程不会复用/回收该位图。
     *
     * 这里不再触发「保存截图内容」：调用方（saveScreenshot 步骤）会自己带标签落盘，
     * 否则一次截图会产生两份相册文件。
     */
    suspend fun capturePngBytes(): ByteArray = captureEncoded(autoSave = false).png

    /**
     * 截一帧并返回 PNG Base64（保持 /See 原有 String 入参不变）。
     * 同一帧的 PNG/Base64 都会缓存，静止画面轮询不会反复编码。
     *
     * 保持帧开启 → 直接取最新缓存帧；保持帧关闭 → 等一个采样周期内的新采样帧，没有则失败。
     * 「保存截图内容」开启时，每个新帧落盘一次（同一帧的重复轮询不会重复写相册）。
     */
    suspend fun capturePngBase64(): String {
        val capture = captureEncoded()
        synchronized(base64Lock) {
            if (cachedBase64Id == capture.id) {
                cachedBase64?.let { return it }
            }
            val encoded = Base64.encodeToString(capture.png, Base64.NO_WRAP)
            // 停止后不再回填缓存（避免已拆除的会话继续持有大对象）
            if (sampling.get()) {
                cachedBase64Id = capture.id
                cachedBase64 = encoded
            }
            return encoded
        }
    }

    private suspend fun captureEncoded(autoSave: Boolean = true): EncodedCapture {
        val frame = acquireFrame()
        val png = try {
            encodePng(frame)
        } finally {
            releaseFrame(frame)
        }
        if (autoSave) saveScreenshotIfEnabled(frame.id, png)
        return EncodedCapture(frame.id, png)
    }

    /** 按帧号缓存 PNG：同一张静止画面在 match 轮询中只压缩一次。 */
    private suspend fun encodePng(frame: CapturedFrame): ByteArray {
        synchronized(pngLock) {
            if (cachedPngId == frame.id) {
                cachedPng?.let { return it }
            }
        }
        val bytes = withContext(AppExecutors.dispatcher) {
            ByteArrayOutputStream(initialPngCapacity()).use { out ->
                frame.bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) // PNG 忽略质量参数
                out.toByteArray()
            }
        }
        synchronized(pngLock) {
            if (sampling.get()) {
                cachedPngId = frame.id
                cachedPng = bytes
            }
        }
        return bytes
    }

    private fun initialPngCapacity(): Int =
        (screenWidth * screenHeight / 2).coerceIn(1 shl 20, 8 shl 20)

    /**
     * 工作流初始化预热：等待帧池产出首帧（≤timeoutMs）。
     * 避免工作流抢在帧池前面 —— 后续 match/saveScreenshot 步骤直接取到真实帧。
     */
    suspend fun awaitFirstFrame(timeoutMs: Long = FIRST_FRAME_TIMEOUT_MS): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (!isReady) return false
            if (frameCounter.get() > 0L) return true
            delay(32)
        }
        return frameCounter.get() > 0L
    }

    fun stop() {
        // 先停采样任务，再拆投影链路，最后退出截屏线程
        sampling.set(false)
        handler?.removeCallbacks(sampleTask)
        // 官方建议的拆除顺序（修两类 teardown 报错）：
        //  1) 先注销投影回调 —— 之后 stop() 触发的 onStop 分发不会再投递到本类截屏线程，
        //     否则线程退出后仍收到投递 → "sending message to a Handler on a dead thread"；
        //  2) 先释放 VirtualDisplay（producer 脱离 surface）再关 ImageReader，
        //     顺序反了 → "BufferQueue has been abandoned"；
        //  3) 停投影，最后退出截屏线程。
        projectionCallback?.let { cb ->
            projection?.let { p -> runCatching { p.unregisterCallback(cb) } }
        }
        projectionCallback = null
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        projection?.stop()
        projection = null
        handlerThread?.quitSafely()
        handlerThread = null
        handler = null
        // 消费者仍持有的帧（refs > 0）不在这里回收；releaseFrame 会在最后一方释放时回收。
        synchronized(frameLock) {
            published?.let {
                it.retired = true
                if (it.refs == 0) it.bitmap.recycle()
            }
            published = null
            while (spareBitmaps.isNotEmpty()) spareBitmaps.removeFirst().recycle()
        }
        synchronized(pngLock) {
            cachedPngId = -1L
            cachedPng = null
        }
        synchronized(base64Lock) {
            cachedBase64Id = -1L
            cachedBase64 = null
        }
        frameCounter.set(0L)
        savedCounter.set(0L)
        lastAutoSavedFrameId.set(-1L)
        previewAtMs = 0L
        packedBuffer = null
        if (active === this) active = null
        AppRepository.projectionPreview.value = null
    }

    /** 内存紧张时释放可重建缓存；不触碰正在被消费者使用的帧。 */
    fun trimMemory() {
        synchronized(pngLock) {
            cachedPngId = -1L
            cachedPng = null
        }
        synchronized(base64Lock) {
            cachedBase64Id = -1L
            cachedBase64 = null
        }
        handler?.post {
            packedBuffer = null
            synchronized(frameLock) {
                while (spareBitmaps.isNotEmpty()) spareBitmaps.removeFirst().recycle()
            }
        }
    }

    private suspend fun acquireFrame(): CapturedFrame {
        if (!isReady) throw IllegalStateException("投屏未初始化")
        // 帧池已在后台按 500ms 节奏采样；保持帧开启直接取最新发布帧
        if (AppRepository.holdFrame.value) {
            return acquirePublished() ?: throw IllegalStateException(failureText())
        }
        // 保持帧关闭：等一个采样周期内"比本次调用更新"的采样帧
        val seen = frameCounter.get()
        val deadline = System.currentTimeMillis() + SAMPLE_INTERVAL_MS
        while (System.currentTimeMillis() < deadline) {
            if (frameCounter.get() != seen) {
                acquirePublished()?.let { return it }
            }
            delay(16)
        }
        throw IllegalStateException(failureText())
    }

    /** 取得当前发布帧的引用；调用方必须最终调用 [releaseFrame]。 */
    private fun acquirePublished(): CapturedFrame? = synchronized(frameLock) {
        published?.also { it.refs++ }
    }

    /** 释放引用；已退休且无人持有（refs == 0）的位图回到复用池或直接回收。 */
    private fun releaseFrame(frame: CapturedFrame) {
        synchronized(frameLock) {
            if (frame.refs > 0) frame.refs--
            if (frame.retired && frame.refs == 0 && !frame.bitmap.isRecycled) {
                if (sampling.get()) {
                    returnBitmapToPool(frame.bitmap)
                } else {
                    frame.bitmap.recycle()
                }
            }
        }
    }

    /** 采样线程发布新帧；旧帧退休，refs == 0 时立即回池（内部持 [frameLock]）。 */
    private fun publishFrame(bitmap: Bitmap) {
        synchronized(frameLock) {
            if (!sampling.get()) {
                if (!bitmap.isRecycled) bitmap.recycle()
                return
            }
            val frame = CapturedFrame(frameCounter.incrementAndGet(), bitmap)
            val old = published
            published = frame
            if (old != null) {
                old.retired = true
                if (old.refs == 0) returnBitmapToPool(old.bitmap)
            }
        }
    }

    /** 从复用池取一张全屏位图（尺寸不匹配/已回收则新建，内部持 [frameLock]）。 */
    private fun obtainBitmap(width: Int, height: Int): Bitmap {
        synchronized(frameLock) {
            val pooled = spareBitmaps.removeLastOrNull()
            if (pooled != null) {
                if (!pooled.isRecycled && pooled.width == width && pooled.height == height) {
                    return pooled
                }
                if (!pooled.isRecycled) pooled.recycle()
            }
            return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        }
    }

    /** 调用时必须持有 [frameLock]。 */
    private fun returnBitmapToPool(bitmap: Bitmap) {
        if (bitmap.isRecycled) return
        if (spareBitmaps.size < MAX_SPARE_BITMAPS) {
            spareBitmaps.addLast(bitmap)
        } else {
            bitmap.recycle()
        }
    }

    private fun failureText(): String =
        if (synchronized(frameLock) { published == null }) {
            "截屏失败：帧池未采到任何帧 —— 请确认已授予通知权限（通知上栏后才会创建帧池），并重新启动自动化"
        } else {
            "截屏失败：一个采样周期内无新帧，且保持帧开关已关闭"
        }

    /**
     * RGBA_8888 → ARGB_8888（rowStride 与像素宽不一致时逐行拷贝去对齐填充）。
     * 位图由调用方从复用池取得；打包 buffer 也复用，避免每帧 allocateDirect。
     */
    private fun copyImageToBitmap(image: Image, bitmap: Bitmap) {
        val plane = image.planes[0]
        val width = image.width
        val height = image.height
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val buffer = plane.buffer
        if (rowStride == pixelStride * width) {
            bitmap.copyPixelsFromBuffer(buffer)
        } else {
            // 逐行去掉 rowStride 对齐填充。顺序必须是先 limit 后 position：
            // Buffer.position() 按"当前 limit"校验，若先 position，第二行起
            // (r+1)·rowStride 必然越过上一行的 limit → IllegalArgumentException("bad position")
            val rowSize = pixelStride * width
            val need = rowSize * height
            val dense = packedBuffer?.takeIf { it.capacity() == need }
                ?: ByteBuffer.allocateDirect(need).also { packedBuffer = it }
            dense.clear()
            for (row in 0 until height) {
                buffer.limit(row * rowStride + rowSize)
                buffer.position(row * rowStride)
                dense.put(buffer)
            }
            dense.flip()
            buffer.clear()
            bitmap.copyPixelsFromBuffer(dense)
        }
    }

    /**
     * 二次确认弹窗的实时预览：把最新帧缩成 [PREVIEW_WIDTH] 宽的缩略图发布给页面（[PREVIEW_INTERVAL_MS] 限流）。
     *
     * 旧缩略图不做 recycle —— Compose 可能仍在绘制上一张，回收会抛
     * "Canvas: trying to use a recycled bitmap"；缩略图约 0.5MB/张且只在确认窗口内产生，交给 GC 即可。
     */
    private fun publishPreview(source: Bitmap) {
        val now = System.currentTimeMillis()
        if (now - previewAtMs < PREVIEW_INTERVAL_MS) return
        previewAtMs = now
        val height = (source.height.toLong() * PREVIEW_WIDTH / source.width).toInt().coerceAtLeast(1)
        val preview = Bitmap.createScaledBitmap(source, PREVIEW_WIDTH, height, true)
        AppRepository.projectionPreview.value = preview
    }

    /**
     * 「保存截图内容」：把每次捕获的 PNG 经 MediaStore 存入相册 DCIM/AutoGongxueyun
     * （用户在相册/文件管理直接可见，无需存储权限）。
     * 首张落盘时提示目录；失败也只提示一次，不打断工作流。
     *
     * 直接写 PNG 字节，不再 base64 → decode 往返。
     */
    private suspend fun saveScreenshotIfEnabled(frameId: Long, pngBytes: ByteArray) {
        if (!AppRepository.saveScreenshots.value) return
        // 画面静止时 match 轮询会反复取到同一帧：同一帧只落盘一次，
        // 否则开启「保存截图内容」后相册会被重复截图迅速塞满，轮询也被写盘拖慢。
        if (lastAutoSavedFrameId.getAndSet(frameId) == frameId) return
        withContext(AppExecutors.dispatcher) {
            val seq = savedCounter.incrementAndGet()
            runCatching {
                ScreenshotStore.savePng(
                    context,
                    "auto_${System.currentTimeMillis()}_$seq.png",
                    pngBytes,
                )
            }.onFailure { e ->
                if (seq == 1L) {
                    AppRepository.showHint("截图保存失败: ${e.message}", HintLevel.WARNING)
                }
            }
        }
    }

    companion object {
        /** 帧池采样周期：每 500ms 主动采集一帧 */
        private const val SAMPLE_INTERVAL_MS = 500L

        /** 复用位图池上限（不含当前发布帧）。 */
        private const val MAX_SPARE_BITMAPS = 2

        @Volatile
        private var active: ScreenCaptureManager? = null

        /** 供 Application.onTrimMemory 调用：释放当前存活会话的可重建缓存。 */
        fun trimActive() {
            active?.trimMemory()
        }

        /** 二次确认预览缩略图宽度（1080 宽的屏 → 270 宽，约 1/4） */
        private const val PREVIEW_WIDTH = 270

        /** 预览发布间隔（与采样周期同频，避免每次采样都分配位图） */
        private const val PREVIEW_INTERVAL_MS = 500L

        /** 初始化预热等待首帧上限 */
        const val FIRST_FRAME_TIMEOUT_MS = 3000L

        /**
         * 创建投屏授权 intent（调用方用 registerForActivityResult 启动，等待用户授权后再 [start]）。
         *
         * API 34+ 传入 MediaProjectionConfig.createConfigForDefaultDisplay()，强制捕获整个屏幕，
         * 授权弹窗中不再出现「单个应用」选项；API < 34 系统只支持整个屏幕，直接创建即可。
         */
        fun createScreenCaptureIntent(context: Context): Intent {
            val mpm = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
            } else {
                mpm.createScreenCaptureIntent()
            }
        }
    }
}
