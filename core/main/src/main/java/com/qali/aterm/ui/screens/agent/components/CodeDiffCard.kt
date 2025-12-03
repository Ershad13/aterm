package com.qali.aterm.ui.screens.agent.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qali.aterm.ui.screens.agent.models.DiffLine
import com.qali.aterm.ui.screens.agent.models.DiffLineType
import com.qali.aterm.ui.screens.agent.models.FileDiff
import com.qali.aterm.ui.screens.agent.models.calculateLineDiff

/**
 * Beautiful code diff card component similar to Cursor AI
 */
@Composable
fun CodeDiffCard(
    fileDiff: FileDiff,
    modifier: Modifier = Modifier
) {
    val diffLines = remember(fileDiff.oldContent, fileDiff.newContent) {
        val allDiffLines = calculateLineDiff(fileDiff.oldContent, fileDiff.newContent)
        // Show context lines (unchanged) around changes, but limit total
        val changesOnly = allDiffLines.filter { it.type != DiffLineType.UNCHANGED }
        if (changesOnly.size > 200) {
            // If too many changes, show only first 200
            changesOnly.take(200)
        } else {
            // Show changes with some context
            val result = mutableListOf<DiffLine>()
            var lastWasChange = false
            for (i in allDiffLines.indices) {
                val line = allDiffLines[i]
                if (line.type != DiffLineType.UNCHANGED) {
                    // Add context before change (up to 2 lines)
                    if (!lastWasChange && i > 0) {
                        val contextStart = maxOf(0, i - 2)
                        for (j in contextStart until i) {
                            if (allDiffLines[j].type == DiffLineType.UNCHANGED && 
                                result.none { it.lineNumber == allDiffLines[j].lineNumber }) {
                                result.add(allDiffLines[j])
                            }
                        }
                    }
                    result.add(line)
                    lastWasChange = true
                } else if (lastWasChange && result.size < 300) {
                    // Add context after change (up to 2 lines)
                    result.add(line)
                    lastWasChange = false
                }
            }
            result.take(300) // Limit total
        }
    }
    
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        border = androidx.compose.foundation.BorderStroke(
            width = 1.dp,
            color = if (fileDiff.isNewFile) 
                Color(0xFF4CAF50).copy(alpha = 0.3f)
            else 
                MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(0.dp)
        ) {
            // File header - more prominent like Cursor CLI
            Surface(
                color = if (fileDiff.isNewFile) 
                    Color(0xFF1B5E20).copy(alpha = 0.1f)
                else 
                    MaterialTheme.colorScheme.surfaceContainerHighest,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (fileDiff.isNewFile) Icons.Outlined.Add else Icons.Outlined.InsertDriveFile,
                        contentDescription = null,
                        tint = if (fileDiff.isNewFile) 
                            Color(0xFF4CAF50) 
                        else 
                            MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp)
                    )
                    Text(
                        text = fileDiff.filePath,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    if (fileDiff.isNewFile) {
                        Surface(
                            color = Color(0xFF4CAF50).copy(alpha = 0.25f),
                            shape = MaterialTheme.shapes.small
                        ) {
                            Text(
                                text = "NEW",
                                style = MaterialTheme.typography.labelMedium,
                                color = Color(0xFF4CAF50),
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    } else {
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                            shape = MaterialTheme.shapes.small
                        ) {
                            Text(
                                text = "MODIFIED",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
            }
            
            // Diff content - scrollable and more prominent
            if (diffLines.isEmpty()) {
                Text(
                    text = "No changes",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp)
                )
            } else {
                // Scrollable diff content
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 100.dp, max = 500.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    diffLines.forEachIndexed { index, diffLine ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    when (diffLine.type) {
                                        DiffLineType.ADDED -> Color(0xFF1E4620).copy(alpha = 0.2f)
                                        DiffLineType.REMOVED -> Color(0xFF5C1F1F).copy(alpha = 0.2f)
                                        DiffLineType.UNCHANGED -> Color.Transparent
                                    }
                                ),
                            verticalAlignment = Alignment.Top
                        ) {
                            // Line number and indicator column - more prominent
                            Box(
                                modifier = Modifier
                                    .width(60.dp)
                                    .background(
                                        when (diffLine.type) {
                                            DiffLineType.ADDED -> Color(0xFF1E4620).copy(alpha = 0.4f)
                                            DiffLineType.REMOVED -> Color(0xFF5C1F1F).copy(alpha = 0.4f)
                                            DiffLineType.UNCHANGED -> MaterialTheme.colorScheme.surfaceContainerHighest
                                        }
                                    )
                                    .padding(horizontal = 10.dp, vertical = 4.dp),
                                contentAlignment = Alignment.CenterEnd
                            ) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = when (diffLine.type) {
                                            DiffLineType.ADDED -> "+"
                                            DiffLineType.REMOVED -> "-"
                                            DiffLineType.UNCHANGED -> " "
                                        },
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.Bold,
                                        color = when (diffLine.type) {
                                            DiffLineType.ADDED -> Color(0xFF4CAF50)
                                            DiffLineType.REMOVED -> Color(0xFFF44336)
                                            DiffLineType.UNCHANGED -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                                        },
                                        fontSize = 16.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                    if (diffLine.type != DiffLineType.UNCHANGED) {
                                        Text(
                                            text = diffLine.lineNumber.toString(),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                            fontSize = 11.sp,
                                            fontFamily = FontFamily.Monospace
                                        )
                                    }
                                }
                            }
                            
                            // Code content - more readable
                            SelectionContainer {
                                Text(
                                    text = diffLine.content,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontFamily = FontFamily.Monospace,
                                    color = when (diffLine.type) {
                                        DiffLineType.ADDED -> Color(0xFF81C784)
                                        DiffLineType.REMOVED -> Color(0xFFE57373)
                                        DiffLineType.UNCHANGED -> MaterialTheme.colorScheme.onSurface
                                    },
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(vertical = 4.dp, horizontal = 12.dp),
                                    fontSize = 13.sp,
                                    lineHeight = 20.sp
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
