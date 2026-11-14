package com.xiaoxing.service

import android.content.Context
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.VibrationEffect
import android.os.VibratorManager
import com.xiaoxing.config.FailureAlertSettings

/**
 * 流程失败提醒：震动 + 铃声。
 *
 * 自动化通常在后台运行、手机可能不在手里，仅靠顶部提示容易被忽略。
 * 每次流程最终失败（包含失败重复次数耗尽）时调用 [alert]；用户主动停止或取消投屏确认不算失败。
 *
 * 震动/铃声开关与铃声选择来自「提醒」一级导航页；实现上直接驱动震动器与系统铃声，
 * 不依赖通知通道的声音设置，因此即使通知权限被拒也仍能提醒（来电/静音模式由系统策略决定）。
 */
object FailureAlert {

    /** 保留最近一次播放的铃声，连续失败时先停掉上一段，避免叠音 */
    private var ringtone: Ringtone? = null

    fun alert(context: Context, settings: FailureAlertSettings) {
        if (settings.vibrationEnabled) vibrate(context)
        if (settings.ringtoneEnabled) playRingtone(context, settings.ringtoneUri)
    }

    private fun vibrate(context: Context) {
        runCatching {
            val vibrator = context.getSystemService(VibratorManager::class.java)
                ?.defaultVibrator
                ?: return
            if (!vibrator.hasVibrator()) return
            vibrator.cancel()
            // 三段长震，间隔 250ms，足以让用户在口袋里感知到
            vibrator.vibrate(
                VibrationEffect.createWaveform(
                    longArrayOf(0, 600, 250, 600, 250, 900),
                    -1,
                ),
            )
        }
    }

    private fun playRingtone(context: Context, customUri: String) {
        runCatching {
            ringtone?.stop()

            // 优先用户选择的铃声；不可用时回退系统默认闹铃/通知音/铃声
            if (customUri.isNotBlank()) {
                val custom = runCatching {
                    RingtoneManager.getRingtone(context, Uri.parse(customUri))
                }.getOrNull()
                if (custom != null) {
                    playTone(custom)
                    return
                }
            }

            for (type in intArrayOf(
                RingtoneManager.TYPE_ALARM,
                RingtoneManager.TYPE_NOTIFICATION,
                RingtoneManager.TYPE_RINGTONE,
            )) {
                val uri = RingtoneManager.getDefaultUri(type) ?: continue
                val tone = RingtoneManager.getRingtone(context, uri) ?: continue
                playTone(tone)
                return
            }
        }
    }

    private fun playTone(tone: Ringtone) {
        tone.audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        tone.play()
        ringtone = tone
    }
}
