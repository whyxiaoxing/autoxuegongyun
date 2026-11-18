package com.xiaoxing.ui.pages

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.IntentCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xiaoxing.AppRepository
import com.xiaoxing.HintLevel
import com.xiaoxing.service.FailureAlert
import com.xiaoxing.ui.components.AppDivider
import com.xiaoxing.ui.components.AppPanel
import com.xiaoxing.ui.components.IconPlate
import com.xiaoxing.ui.components.MetaChip
import com.xiaoxing.ui.components.SectionEyebrow
import kotlinx.coroutines.launch

/**
 * 设置 → 失败提醒：调整流程失败后的震动与铃声。
 *
 * 设置即时落盘并作用于前台服务（[FailureAlert] 每次失败时读取
 * [AppRepository.failureAlertSettings]）。铃声通过系统铃声选择器挑选，
 * 选定后不能读取时自动回退系统默认闹铃。
 */
@Composable
internal fun AlertSettingsDetail(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by AppRepository.failureAlertSettings.collectAsStateWithLifecycle()

    val ringtoneName = remember(settings.ringtoneUri) { ringtoneLabel(context, settings.ringtoneUri) }

    val ringtonePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val data = result.data ?: return@rememberLauncherForActivityResult
        val uri = IntentCompat.getParcelableExtra(
            data,
            RingtoneManager.EXTRA_RINGTONE_PICKED_URI,
            Uri::class.java,
        )
        // 选择「默认」时系统返回 null → 保存空串 = 系统默认闹铃
        scope.launch { AppRepository.setFailureRingtoneUri(uri?.toString().orEmpty()) }
    }

    DetailScaffold(
        title = "失败提醒",
        subtitle = "流程失败后的震动与提示铃声",
        onBack = onBack,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AppPanel {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconPlate(
                        icon = Icons.Filled.Notifications,
                        size = 42,
                        tint = MaterialTheme.colorScheme.tertiary,
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("失败提醒", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "流程最终失败时提醒（含每个步骤的失败重试耗尽）。主动停止或取消投屏确认不会提醒。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            SectionEyebrow("提醒方式")
            AppPanel {
                Column {
                    OptionSwitchRow(
                        title = "震动",
                        description = "失败后按三段长震节奏提醒，适合手机放在口袋里。",
                        checked = settings.vibrationEnabled,
                        onCheckedChange = { scope.launch { AppRepository.setFailureVibration(it) } },
                    )
                    AppDivider(Modifier.padding(start = 16.dp))
                    OptionSwitchRow(
                        title = "铃声",
                        description = "失败后播放下方选定的铃声；关闭后只震动。",
                        checked = settings.ringtoneEnabled,
                        onCheckedChange = { scope.launch { AppRepository.setFailureRingtone(it) } },
                    )
                    AppDivider(Modifier.padding(start = 16.dp))
                    SettingsEntry(
                        icon = Icons.Filled.Notifications,
                        title = "提示铃声",
                        subtitle = ringtoneName,
                        accent = MaterialTheme.colorScheme.primary,
                        trailingText = if (settings.ringtoneUri.isBlank()) "系统默认" else "自定义",
                        onClick = {
                            runCatching {
                                ringtonePicker.launch(ringtonePickerIntent(settings.ringtoneUri))
                            }.onFailure {
                                AppRepository.showHint("无法打开铃声选择器: ${it.message}", HintLevel.ERROR)
                            }
                        },
                    )
                }
            }

            OutlinedButton(
                onClick = { FailureAlert.alert(context, settings) },
                enabled = settings.vibrationEnabled || settings.ringtoneEnabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("试听提醒")
            }

            AppPanel {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("当前状态", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.weight(1f))
                        MetaChip(if (settings.vibrationEnabled) "震动开" else "震动关")
                        Spacer(Modifier.width(6.dp))
                        MetaChip(if (settings.ringtoneEnabled) "铃声开" else "铃声关")
                    }
                    Text(
                        "系统静音、勿扰模式或媒体音量为 0 时可能听不到铃声；震动也受系统设置影响。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "若所选铃声无法读取，会自动回退到系统默认闹铃。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}

/** 系统铃声选择器：默认 / 闹铃列表，选「默认」时返回 null = 系统默认闹铃 */
private fun ringtonePickerIntent(currentUri: String): Intent =
    Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
        putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
        putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
        putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
        putExtra(
            RingtoneManager.EXTRA_RINGTONE_DEFAULT_URI,
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
        )
        if (currentUri.isNotBlank()) {
            putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, Uri.parse(currentUri))
        }
    }

/** 展示用铃声名：空 = 系统默认闹铃；读取失败时退回 URI 末段 */
private fun ringtoneLabel(context: Context, uriString: String): String {
    if (uriString.isBlank()) return "系统默认闹铃"
    val uri = Uri.parse(uriString)
    return runCatching { RingtoneManager.getRingtone(context, uri)?.getTitle(context) }
        .getOrNull()
        ?.takeIf { it.isNotBlank() }
        ?: uri.lastPathSegment?.takeIf { it.isNotBlank() }
        ?: "自定义铃声"
}
