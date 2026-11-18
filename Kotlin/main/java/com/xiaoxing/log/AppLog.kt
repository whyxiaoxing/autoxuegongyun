package com.xiaoxing.log

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 运行日志级别（区别于顶层提示的 [com.xiaoxing.HintLevel]：这里多了 DEBUG） */
enum class LogLevel { DEBUG, INFO, WARN, ERROR }

/**
 * 一条运行日志。
 *
 * @property timeText 生成时就格式化好的时间（`MM-dd HH:mm:ss.SSS`），避免在 UI 层反复格式化
 */
data class LogRecord(
    val id: Long,
    val timeText: String,
    val level: LogLevel,
    val tag: String,
    val message: String,
)

/**
 * 进程内运行日志：**只保存在内存**，不写硬盘（不落文件、不进 DataStore），进程结束即清空。
 *
 * 用于「设置 → 运行日志」查看本次运行做了什么：工作流步骤、失败原因、投屏状态等。
 * 采用固定容量的环形缓冲，超出 [MAX_ENTRIES] 后丢弃最旧的记录，避免长时间运行占内存。
 *
 * 线程安全：服务、引擎、网络回调都可能从后台线程写入，统一在 [lock] 内追加；
 * 日志本身不允许影响业务，任何异常都不向外抛。
 */
object AppLog {

    /** 最多保留的日志条数（约几百 KB；超出后丢最旧） */
    const val MAX_ENTRIES = 500

    private val lock = Any()
    // DateTimeFormatter 不可变且线程安全（SimpleDateFormat 每次 format 都要分配 Date/Calendar）
    private val timeFormat = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss.SSS", Locale.US)
    private var nextId = 0L
    private val buffer = ArrayDeque<LogRecord>(MAX_ENTRIES)

    private val _records = MutableStateFlow<List<LogRecord>>(emptyList())

    /** 当前日志快照（旧 → 新）；UI 按需倒序展示 */
    val records: StateFlow<List<LogRecord>> = _records.asStateFlow()

    fun debug(tag: String, message: String) = append(LogLevel.DEBUG, tag, message)

    fun info(tag: String, message: String) = append(LogLevel.INFO, tag, message)

    fun warn(tag: String, message: String) = append(LogLevel.WARN, tag, message)

    fun error(tag: String, message: String) = append(LogLevel.ERROR, tag, message)

    fun append(level: LogLevel, tag: String, message: String) {
        val text = message.trim()
        if (text.isEmpty()) return
        synchronized(lock) {
            nextId += 1
            buffer.addLast(
                LogRecord(
                    id = nextId,
                    timeText = LocalDateTime.now().format(timeFormat),
                    level = level,
                    tag = tag,
                    message = text,
                ),
            )
            while (buffer.size > MAX_ENTRIES) buffer.removeFirst()
            _records.value = buffer.toList()
        }
    }

    /** 清空本次运行日志（只影响内存） */
    fun clear() {
        synchronized(lock) {
            buffer.clear()
            _records.value = emptyList()
        }
    }
}
