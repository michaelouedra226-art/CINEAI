package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.api.TechLog
import com.example.ui.svg.AgnesIcon
import com.example.ui.svg.AgnesSvgIcon

@Composable
fun LogViewerModal(
    logs: List<TechLog>,
    onClose: () -> Unit,
    onClear: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onClose,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AgnesSvgIcon(icon = AgnesIcon.LOGS, tint = Color(0xFFA78BFA), size = 20.dp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = "Logs Techniques en Direct", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }

                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .clickable(onClick = onClose),
                    contentAlignment = Alignment.Center
                ) {
                    AgnesSvgIcon(icon = AgnesIcon.CLOSE, tint = Color(0xFF888899), size = 18.dp)
                }
            }
        },
        text = {
            Column(modifier = Modifier.height(380.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = "${logs.size} événements capturés", color = Color(0xFFA1A1AA), fontSize = 12.sp)
                    TextButton(onClick = onClear) {
                        Text(text = "Effacer", color = Color(0xFFEF4444), fontSize = 12.sp)
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                if (logs.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(text = "Aucun log technique pour le moment", color = Color(0xFF71717A), fontSize = 13.sp)
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF0D0D12))
                            .padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(logs) { log ->
                            val levelColor = when (log.level) {
                                "ERROR" -> Color(0xFFEF4444)
                                "WARN" -> Color(0xFFF59E0B)
                                "RATE_LIMIT" -> Color(0xFF06B6D4)
                                else -> Color(0xFF10B981)
                            }

                            Column(modifier = Modifier.fillMaxWidth()) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(4.dp))
                                                .background(levelColor.copy(alpha = 0.2f))
                                                .padding(horizontal = 4.dp, vertical = 2.dp)
                                        ) {
                                            Text(
                                                text = log.tag,
                                                color = levelColor,
                                                fontSize = 9.sp,
                                                fontFamily = FontFamily.Monospace,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = log.level,
                                            color = levelColor,
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                    Text(
                                        text = log.formattedTime,
                                        color = Color(0xFF71717A),
                                        fontSize = 10.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = log.message,
                                    color = Color(0xFFE4E4E7),
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    lineHeight = 15.sp
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onClose) {
                Text("Fermer", color = Color(0xFFA78BFA))
            }
        },
        containerColor = Color(0xFF1E1E28),
        shape = RoundedCornerShape(16.dp)
    )
}
