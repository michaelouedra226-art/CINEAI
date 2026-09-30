package com.example.ui.components

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.example.data.model.CreationEntity
import com.example.ui.svg.AgnesIcon
import com.example.ui.svg.AgnesSvgIcon
import com.example.util.ImagePickerHelper
import kotlinx.coroutines.launch

/**
 * Modal complet et élégant pour choisir une image de départ :
 * 1. Galerie / Fichiers de l'appareil (via Photo Picker Android)
 * 2. Mes créations Agnes Studio (images déjà synthétisées dans l'application)
 * 3. URL Web directe (HTTPS)
 */
@Composable
fun AgnesImagePickerModal(
    availableCreations: List<CreationEntity> = emptyList(),
    onImageSelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var selectedTab by remember { mutableIntStateOf(0) } // 0: Galerie appareil, 1: Créations Agnes, 2: URL directe
    var customUrlInput by remember { mutableStateOf("") }
    var urlError by remember { mutableStateOf<String?>(null) }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            coroutineScope.launch {
                val savedPath = ImagePickerHelper.saveSelectedImage(context, uri)
                if (!savedPath.isNullOrBlank()) {
                    onImageSelected(savedPath)
                    onDismiss()
                }
            }
        }
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            coroutineScope.launch {
                val savedPath = ImagePickerHelper.saveSelectedImage(context, uri)
                if (!savedPath.isNullOrBlank()) {
                    onImageSelected(savedPath)
                    onDismiss()
                }
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.85f))
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 620.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF14141E)),
                shape = RoundedCornerShape(20.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF28283C))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp)
                ) {
                    // En-tête
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "Importer une image",
                                color = Color.White,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Choisissez la source pour votre image de départ",
                                color = Color(0xFFA1A1AA),
                                fontSize = 12.sp
                            )
                        }

                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF222230))
                        ) {
                            AgnesSvgIcon(icon = AgnesIcon.CLOSE, tint = Color(0xFFA1A1AA), size = 16.dp)
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Onglets de sélection
                    val tabs = listOf("Appareil", "Mes Créations", "URL Web")
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFF1A1A26))
                            .padding(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        tabs.forEachIndexed { index, title ->
                            val isSelected = selectedTab == index
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (isSelected) Color(0xFF7C3AED) else Color.Transparent)
                                    .clickable { selectedTab = index }
                                    .padding(vertical = 8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = title,
                                    color = if (isSelected) Color.White else Color(0xFFA1A1AA),
                                    fontSize = 12.sp,
                                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(18.dp))

                    // Contenu de l'onglet actif
                    when (selectedTab) {
                        0 -> {
                            // Onglet 1 : Appareil
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 12.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(64.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFF231E3D)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    AgnesSvgIcon(icon = AgnesIcon.UPLOAD, tint = Color(0xFFA78BFA), size = 32.dp)
                                }
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = "Sélectionner depuis votre appareil",
                                    color = Color.White,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "Ouvre la galerie multimédia de votre téléphone ou tablette.",
                                    color = Color(0xFFA1A1AA),
                                    fontSize = 12.sp,
                                    modifier = Modifier.padding(horizontal = 16.dp),
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                )

                                Spacer(modifier = Modifier.height(20.dp))

                                AgnesPrimaryButton(
                                    text = "Ouvrir la galerie photo",
                                    onClick = {
                                        photoPickerLauncher.launch(
                                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                        )
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    icon = AgnesIcon.HOME_IMAGES
                                )

                                Spacer(modifier = Modifier.height(10.dp))

                                Text(
                                    text = "Ou parcourir les fichiers de stockage",
                                    color = Color(0xFF8B5CF6),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier
                                        .clickable { filePickerLauncher.launch("image/*") }
                                        .padding(8.dp)
                                )
                            }
                        }
                        1 -> {
                            // Onglet 2 : Mes créations Agnes
                            val successfulImages = availableCreations.filter {
                                it.type == "image" && it.status == "done" && !it.thumbnail.isNullOrBlank()
                            }

                            if (successfulImages.isEmpty()) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(220.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        AgnesSvgIcon(icon = AgnesIcon.HOME_IMAGES, tint = Color(0xFF3E3E50), size = 40.dp)
                                        Spacer(modifier = Modifier.height(10.dp))
                                        Text(
                                            text = "Aucune image générée pour l'instant",
                                            color = Color(0xFFA1A1AA),
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Medium
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = "Générez d'abord des visuels dans l'onglet Images.",
                                            color = Color(0xFF71717A),
                                            fontSize = 11.sp
                                        )
                                    }
                                }
                            } else {
                                Text(
                                    text = "Sélectionnez une création existante (${successfulImages.size}) :",
                                    color = Color(0xFFA1A1AA),
                                    fontSize = 12.sp
                                )
                                Spacer(modifier = Modifier.height(10.dp))

                                LazyVerticalGrid(
                                    columns = GridCells.Fixed(3),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(max = 280.dp),
                                    contentPadding = PaddingValues(bottom = 8.dp)
                                ) {
                                    items(successfulImages) { item ->
                                        val imgUrl = item.resultUrl ?: item.thumbnail.orEmpty()
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .aspectRatio(1f)
                                                .clip(RoundedCornerShape(10.dp))
                                                .border(1.dp, Color(0xFF2E2E40), RoundedCornerShape(10.dp))
                                                .clickable {
                                                    onImageSelected(imgUrl)
                                                    onDismiss()
                                                }
                                        ) {
                                            AsyncImage(
                                                model = imgUrl,
                                                contentDescription = item.prompt,
                                                contentScale = ContentScale.Crop,
                                                modifier = Modifier.fillMaxSize()
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        2 -> {
                            // Onglet 3 : URL Web directe
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    text = "Coller une URL d'image directe (HTTPS) :",
                                    color = Color(0xFFA1A1AA),
                                    fontSize = 12.sp
                                )
                                Spacer(modifier = Modifier.height(8.dp))

                                OutlinedTextField(
                                    value = customUrlInput,
                                    onValueChange = {
                                        customUrlInput = it
                                        urlError = null
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    placeholder = { Text("https://exemple.com/image.jpg", color = Color(0xFF555566)) },
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedContainerColor = Color(0xFF14141C),
                                        unfocusedContainerColor = Color(0xFF14141C),
                                        focusedBorderColor = Color(0xFF8B5CF6),
                                        unfocusedBorderColor = Color(0xFF282836),
                                        focusedTextColor = Color.White,
                                        unfocusedTextColor = Color.White
                                    ),
                                    shape = RoundedCornerShape(10.dp),
                                    singleLine = true
                                )

                                if (urlError != null) {
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(text = urlError!!, color = Color(0xFFEF4444), fontSize = 11.sp)
                                }

                                if (customUrlInput.trim().startsWith("http")) {
                                    Spacer(modifier = Modifier.height(12.dp))
                                    Text(text = "Aperçu :", color = Color(0xFFA1A1AA), fontSize = 11.sp)
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(130.dp)
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(Color(0xFF1B1B26)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        AsyncImage(
                                            model = customUrlInput.trim(),
                                            contentDescription = "Aperçu URL",
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(16.dp))

                                AgnesPrimaryButton(
                                    text = "Valider cette image",
                                    onClick = {
                                        val trimmed = customUrlInput.trim()
                                        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
                                            onImageSelected(trimmed)
                                            onDismiss()
                                        } else {
                                            urlError = "L'adresse doit commencer par http:// ou https://"
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    enabled = customUrlInput.isNotBlank(),
                                    icon = AgnesIcon.CHECK
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
