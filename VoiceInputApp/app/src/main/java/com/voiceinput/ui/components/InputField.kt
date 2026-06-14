package com.voiceinput.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.KeyboardReturn
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.heightIn

@Composable
fun InputField(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onSendWithEnter: () -> Unit,
    onPickImage: () -> Unit,
    onPickFile: () -> Unit,
    onScanFile: () -> Unit,
    onTakePhoto: () -> Unit,
    onPasteEmpty: () -> Unit = {},
    enabled: Boolean = true,
    inputEnabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = onTextChange,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 116.dp, max = 260.dp),
            placeholder = {
                Text(
                    if (inputEnabled) "用手机输入法输入，发送到电脑..." else "请先选择或连接电脑"
                )
            },
            enabled = inputEnabled,
            keyboardOptions = KeyboardOptions(
                imeAction = ImeAction.Send
            ),
            keyboardActions = KeyboardActions(
                onSend = { if (enabled) onSend() }
            ),
            minLines = 4,
            maxLines = 9
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                InputActionIcon(
                    enabled = text.isNotBlank(),
                    onClick = {
                        clipboard.setPrimaryClip(ClipData.newPlainText("input_text", text))
                    }
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = "\u590d\u5236\u6587\u672c")
                }
                InputActionIcon(
                    enabled = inputEnabled,
                    onClick = {
                        val clipText = clipboard.primaryClip
                            ?.takeIf { it.itemCount > 0 }
                            ?.getItemAt(0)
                            ?.coerceToText(context)
                            ?.toString()
                            .orEmpty()
                        if (clipText.isNotBlank()) {
                            onTextChange(
                                if (text.isBlank()) clipText else "$text\n$clipText"
                            )
                        } else {
                            onPasteEmpty()
                        }
                    }
                ) {
                    Icon(Icons.Default.ContentPaste, contentDescription = "粘贴剪贴板")
                }
                InputActionIcon(enabled = enabled, onClick = onPickImage) {
                    Icon(Icons.Default.Image, contentDescription = "选择图片")
                }
                InputActionIcon(enabled = enabled, onClick = onPickFile) {
                    Icon(Icons.Default.AttachFile, contentDescription = "选择文件")
                }
                InputActionIcon(enabled = enabled, onClick = onScanFile) {
                    Icon(Icons.Default.QrCodeScanner, contentDescription = "扫码文件")
                }
                InputActionIcon(enabled = enabled, onClick = onTakePhoto) {
                    Icon(Icons.Default.PhotoCamera, contentDescription = "拍照")
                }
                InputActionIcon(enabled = enabled && text.isNotBlank(), onClick = onSendWithEnter) {
                    Icon(Icons.Default.KeyboardReturn, contentDescription = "发送并回车")
                }
            }
            Button(
                onClick = onSend,
                enabled = enabled && text.isNotBlank(),
                modifier = Modifier.width(112.dp)
            ) {
                Text("\u53d1\u9001")
            }
        }
    }
}

@Composable
private fun InputActionIcon(
    enabled: Boolean,
    onClick: () -> Unit,
    icon: @Composable () -> Unit
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(40.dp)
    ) {
        icon()
    }
}
