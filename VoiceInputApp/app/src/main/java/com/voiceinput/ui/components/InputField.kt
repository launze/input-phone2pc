package com.voiceinput.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.KeyboardReturn
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

@Composable
fun InputField(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onSendWithEnter: () -> Unit,
    onPickImage: () -> Unit,
    onTakePhoto: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = onTextChange,
                modifier = Modifier.weight(1f),
                placeholder = {
                    Text(
                        if (enabled) "\u8f93\u5165\u6587\u5b57..." else "\u8bf7\u5148\u8fde\u63a5\u8bbe\u5907"
                    )
                },
                enabled = enabled,
                keyboardOptions = KeyboardOptions(
                    imeAction = ImeAction.Send
                ),
                keyboardActions = KeyboardActions(
                    onSend = { if (enabled) onSend() }
                ),
                maxLines = 3
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Button(
                onClick = onSend,
                enabled = enabled && text.isNotBlank()
            ) {
                Text("\u53d1\u9001")
            }

            IconButton(
                onClick = onSendWithEnter,
                enabled = enabled && text.isNotBlank()
            ) {
                Icon(
                    Icons.Default.KeyboardReturn,
                    contentDescription = "\u53d1\u9001\u5e76\u56de\u8f66"
                )
            }

            IconButton(
                onClick = {
                    val clipboard =
                        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("input_text", text))
                },
                enabled = text.isNotBlank()
            ) {
                Icon(
                    Icons.Default.ContentCopy,
                    contentDescription = "\u590d\u5236\u6587\u672c"
                )
            }

            IconButton(
                onClick = onPickImage,
                enabled = enabled
            ) {
                Icon(
                    Icons.Default.Image,
                    contentDescription = "\u9009\u62e9\u56fe\u7247"
                )
            }

            IconButton(
                onClick = onTakePhoto,
                enabled = enabled
            ) {
                Icon(
                    Icons.Default.PhotoCamera,
                    contentDescription = "\u62cd\u7167\u53d1\u9001"
                )
            }
        }
    }
}
