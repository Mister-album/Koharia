package koharia.epub.control

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * 首次启用朗读前的一次性数据披露对话框。
 *
 * review 修复：MiMo / Microsoft Edge 都会把**当前章节正文**上传到各自服务做语音合成，
 * 而设置页此前只说明是否需要 API Key。这里在用户第一次真正启用朗读前，明确告知
 * 数据接收方与用途。
 *
 * 与 [TtsControlPanel] 同为"只渲染不调度"：是否展示、确认后如何继续，都由调用方
 * （`EpubReaderActivity`）通过 [onAcknowledge] / [onDismissRequest] 决定。
 *
 * @param vendorDisplayName 当前所选 TTS 厂商名（即数据接收方），如 "MiMo TTS" / "Microsoft Edge TTS"
 * @param onAcknowledge 用户点「我知道了」：调用方应记录已确认并继续起播
 * @param onDismissRequest 用户取消（点外部 / 返回键）：不继续起播
 */
@Composable
fun TtsDisclosureDialog(
    vendorDisplayName: String,
    onAcknowledge: () -> Unit,
    onDismissRequest: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = {
            Text(text = stringResource(MR.strings.tts_data_disclosure_title))
        },
        text = {
            Text(
                text = stringResource(MR.strings.tts_data_disclosure_body, vendorDisplayName),
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = {
            TextButton(onClick = onAcknowledge) {
                Text(text = stringResource(MR.strings.tts_data_disclosure_ack))
            }
        },
    )
}
