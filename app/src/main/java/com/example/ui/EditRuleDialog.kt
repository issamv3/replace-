package com.example.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.ReplacementRule

@Composable
fun EditRuleDialog(
    rule: ReplacementRule?,
    onDismiss: () -> Unit,
    onSave: (ReplacementRule) -> Unit
) {
    var originalText by remember { mutableStateOf(rule?.originalText ?: "") }
    var replacementText by remember { mutableStateOf(rule?.replacementText ?: "") }
    var matchMode by remember { mutableStateOf(rule?.matchMode ?: ReplacementRule.MATCH_EXACT) }
    var textColor by remember { mutableIntStateOf(rule?.textColor ?: Color.White.toArgb()) }
    var backgroundColor by remember { mutableIntStateOf(rule?.backgroundColor ?: 0xFF0F172A.toInt()) }

    val presetColors = listOf(
        0xFF0F172A.toInt(), // Slate 900
        0xFF1E293B.toInt(), // Slate 800
        0xFFFFFFFF.toInt(), // White
        0xFF000000.toInt(), // Black
        0xFF0284C7.toInt(), // Sky 600
        0xFF10B981.toInt(), // Emerald
        0xFFF59E0B.toInt(), // Amber
        0xFFE11D48.toInt(), // Rose
        0xFF8B5CF6.toInt()  // Violet
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = if (rule == null) "New Replacement Rule" else "Edit Replacement Rule",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                OutlinedTextField(
                    value = originalText,
                    onValueChange = { originalText = it },
                    label = { Text("Original text (to replace)") },
                    placeholder = { Text("e.g. $1,250.00") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("original_text_input")
                )

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = replacementText,
                    onValueChange = { replacementText = it },
                    label = { Text("Replacement text") },
                    placeholder = { Text("e.g. $1,000,000.00") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("replacement_text_input")
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "MATCH MODE",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = matchMode == ReplacementRule.MATCH_EXACT,
                        onClick = { matchMode = ReplacementRule.MATCH_EXACT },
                        label = { Text("Exact") },
                        modifier = Modifier.testTag("chip_match_exact")
                    )
                    FilterChip(
                        selected = matchMode == ReplacementRule.MATCH_CONTAINS,
                        onClick = { matchMode = ReplacementRule.MATCH_CONTAINS },
                        label = { Text("Contains") },
                        modifier = Modifier.testTag("chip_match_contains")
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "VISUAL PREVIEW",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(6.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(64.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(backgroundColor))
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = replacementText.ifBlank { originalText.ifBlank { "Preview Text" } },
                        color = Color(textColor),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Background Color",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    IconButton(
                        onClick = {
                            val temp = backgroundColor
                            backgroundColor = textColor
                            textColor = temp
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.SwapHoriz,
                            contentDescription = "Swap colors"
                        )
                    }
                }

                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(bottom = 8.dp)
                ) {
                    items(presetColors) { c ->
                        ColorCircle(
                            color = Color(c),
                            isSelected = c == backgroundColor,
                            onClick = { backgroundColor = c }
                        )
                    }
                }

                Text(
                    text = "Text Color",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, bottom = 6.dp)
                )

                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(presetColors) { c ->
                        ColorCircle(
                            color = Color(c),
                            isSelected = c == textColor,
                            onClick = { textColor = c }
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (originalText.isNotBlank() || replacementText.isNotBlank()) {
                        val baseRule = rule ?: ReplacementRule(
                            originalText = originalText.trim(),
                            replacementText = replacementText.trim()
                        )
                        val updated = baseRule.copy(
                            originalText = originalText.trim().ifBlank { replacementText.trim() },
                            replacementText = replacementText.trim().ifBlank { originalText.trim() },
                            matchMode = matchMode,
                            textColor = textColor,
                            backgroundColor = backgroundColor
                        )
                        onSave(updated)
                        onDismiss()
                    }
                },
                modifier = Modifier.testTag("save_rule_button")
            ) {
                Text("Save Rule")
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("cancel_rule_button")
            ) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun ColorCircle(
    color: Color,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(color)
            .border(
                width = if (isSelected) 3.dp else 1.dp,
                color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Gray.copy(alpha = 0.5f),
                shape = CircleShape
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (isSelected) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = "Selected",
                tint = if (color == Color.White) Color.Black else Color.White,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}
