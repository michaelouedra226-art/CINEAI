package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.components.triggerHapticFeedback
import com.example.ui.svg.AgnesIcon
import com.example.ui.svg.AgnesSvgIcon

data class ChatMessage(
    val id: String = java.util.UUID.randomUUID().toString(),
    val sender: String, // "user" | "agnes"
    val text: String,
    val timestamp: Long = System.currentTimeMillis()
)

@Composable
fun ChatScreen(
    messages: List<ChatMessage>,
    isSending: Boolean,
    onSendMessage: (String) -> Unit,
    onSendToImage: (String) -> Unit,
    onSendToVideo: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var inputText by remember { mutableStateOf("") }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0A0A0F))
            .padding(16.dp)
    ) {
        Text(
            text = "Assistant Créatif Agnes",
            color = Color.White,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "Conception de scénarios, prompts cinématiques et éclairages sur mesure.",
            color = Color(0xFFA1A1AA),
            fontSize = 12.sp
        )

        Spacer(modifier = Modifier.height(14.dp))

        // Liste des messages
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(messages) { msg ->
                val isUser = msg.sender == "user"
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.85f)
                            .clip(
                                RoundedCornerShape(
                                    topStart = 14.dp,
                                    topEnd = 14.dp,
                                    bottomStart = if (isUser) 14.dp else 2.dp,
                                    bottomEnd = if (isUser) 2.dp else 14.dp
                                )
                            )
                            .background(if (isUser) Color(0xFF7C3AED) else Color(0xFF181824))
                            .border(
                                1.dp,
                                if (isUser) Color(0xFFA78BFA).copy(alpha = 0.4f) else Color(0xFF28283A),
                                RoundedCornerShape(14.dp)
                            )
                            .padding(14.dp)
                    ) {
                        Column {
                            Text(
                                text = if (isUser) "Vous" else "Agnes AI Studio",
                                color = if (isUser) Color(0xFFE9D5FF) else Color(0xFFA78BFA),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = msg.text,
                                color = Color.White,
                                fontSize = 13.sp,
                                lineHeight = 18.sp
                            )

                            if (!isUser) {
                                Spacer(modifier = Modifier.height(10.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(Color(0xFF2E2648))
                                            .clickable {
                                                triggerHapticFeedback(context)
                                                onSendToImage(msg.text)
                                            }
                                            .padding(horizontal = 8.dp, vertical = 4.dp)
                                    ) {
                                        Text(text = "Vers Image", color = Color(0xFFD8B4FE), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                    }
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(Color(0xFF2E2648))
                                            .clickable {
                                                triggerHapticFeedback(context)
                                                onSendToVideo(msg.text)
                                            }
                                            .padding(horizontal = 8.dp, vertical = 4.dp)
                                    ) {
                                        Text(text = "Vers Vidéo", color = Color(0xFFD8B4FE), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Champ d'envoi
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = inputText,
                onValueChange = { inputText = it },
                modifier = Modifier
                    .weight(1f)
                    .height(52.dp),
                placeholder = { Text("Posez une question ou demandez un prompt...", color = Color(0xFF555566), fontSize = 13.sp) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = Color(0xFF14141C),
                    unfocusedContainerColor = Color(0xFF14141C),
                    focusedBorderColor = Color(0xFF8B5CF6),
                    unfocusedBorderColor = Color(0xFF282836),
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White
                ),
                shape = RoundedCornerShape(12.dp),
                singleLine = true
            )
            Spacer(modifier = Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (inputText.isNotBlank() && !isSending) Color(0xFF7C3AED) else Color(0xFF262634))
                    .clickable(enabled = inputText.isNotBlank() && !isSending) {
                        triggerHapticFeedback(context)
                        val text = inputText
                        inputText = ""
                        onSendMessage(text)
                    },
                contentAlignment = Alignment.Center
            ) {
                if (isSending) {
                    AgnesSvgIcon(icon = AgnesIcon.SPINNER, tint = Color.White, size = 20.dp)
                } else {
                    AgnesSvgIcon(icon = AgnesIcon.GENERATE, tint = Color.White, size = 20.dp)
                }
            }
        }
    }
}
