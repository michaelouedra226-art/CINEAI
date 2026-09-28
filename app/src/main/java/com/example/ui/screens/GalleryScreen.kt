package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import coil.compose.AsyncImage
import com.example.data.model.CreationEntity
import com.example.data.model.FilmEntity
import com.example.ui.components.AgnesInteractiveCard
import com.example.ui.components.MediaViewerModal
import com.example.ui.components.triggerHapticFeedback
import com.example.ui.svg.AgnesIcon
import com.example.ui.svg.AgnesSvgIcon
import com.example.util.DownloadHelper

@Composable
fun GalleryScreen(
    creations: List<CreationEntity>,
    films: List<FilmEntity>,
    onToggleFavorite: (id: String, isFav: Boolean) -> Unit,
    onDeleteCreation: (id: String) -> Unit,
    onDeleteFilm: (id: String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var selectedFilter by remember { mutableStateOf("Tous") } // "Tous" | "Images" | "Vidéos" | "Films"
    var searchQuery by remember { mutableStateOf("") }
    var selectedItemForDetail by remember { mutableStateOf<CreationEntity?>(null) }
    var selectedFilmForDetail by remember { mutableStateOf<FilmEntity?>(null) }

    val filters = listOf("Tous", "Images", "Vidéos", "Films")

    // Filtrage
    val filteredCreations = creations.filter { c ->
        val matchesFilter = when (selectedFilter) {
            "Images" -> c.type == "image"
            "Vidéos" -> c.type == "video"
            "Films" -> false
            else -> true
        }
        val matchesSearch = searchQuery.isBlank() || c.prompt.contains(searchQuery, ignoreCase = true)
        matchesFilter && matchesSearch
    }

    val filteredFilms = if (selectedFilter == "Tous" || selectedFilter == "Films") {
        films.filter { f ->
            searchQuery.isBlank() || f.title.contains(searchQuery, ignoreCase = true) || f.prompt.contains(searchQuery, ignoreCase = true)
        }
    } else {
        emptyList()
    }

    // Modal Visualiseur Médias (Photos HD avec zoom, Vidéos avec lecteur et téléchargements)
    if (selectedItemForDetail != null) {
        MediaViewerModal(
            creation = selectedItemForDetail,
            film = null,
            onDismiss = { selectedItemForDetail = null },
            onToggleFavorite = { id, isFav ->
                onToggleFavorite(id, isFav)
                selectedItemForDetail = selectedItemForDetail?.copy(favorite = isFav)
            },
            onDeleteCreation = { id ->
                onDeleteCreation(id)
                selectedItemForDetail = null
            }
        )
    }

    // Modal Visualiseur Film (Lecteur de film et de scènes avec téléchargements)
    if (selectedFilmForDetail != null) {
        MediaViewerModal(
            creation = null,
            film = selectedFilmForDetail,
            onDismiss = { selectedFilmForDetail = null },
            onDeleteFilm = { id ->
                onDeleteFilm(id)
                selectedFilmForDetail = null
            }
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0A0A0F))
            .padding(16.dp)
    ) {
        Text(
            text = "Galerie",
            color = Color.White,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Onglets filtres : [Tous] [Images] [Vidéos] [Films]
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            filters.forEach { flt ->
                val isSelected = selectedFilter == flt
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isSelected) Color(0xFF7C3AED) else Color(0xFF1A1A24))
                        .clickable { selectedFilter = flt }
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = flt,
                        color = if (isSelected) Color.White else Color(0xFFBBBBD0),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Barre de recherche
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text("Rechercher par prompt ou titre...", color = Color(0xFF6B7280), fontSize = 13.sp) },
            leadingIcon = { AgnesSvgIcon(icon = AgnesIcon.SEARCH, tint = Color(0xFFA1A1AA), size = 18.dp) },
            trailingIcon = {
                if (searchQuery.isNotBlank()) {
                    Box(
                        modifier = Modifier
                            .clickable { searchQuery = "" }
                            .padding(8.dp)
                    ) {
                        AgnesSvgIcon(icon = AgnesIcon.CLOSE, tint = Color(0xFFA1A1AA), size = 16.dp)
                    }
                }
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = Color(0xFF161622),
                unfocusedContainerColor = Color(0xFF13131A),
                focusedBorderColor = Color(0xFF7C3AED),
                unfocusedBorderColor = Color(0xFF282836),
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White
            ),
            shape = RoundedCornerShape(10.dp)
        )

        Spacer(modifier = Modifier.height(16.dp))

        val totalItems = filteredCreations.size + filteredFilms.size

        if (totalItems == 0) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    AgnesSvgIcon(icon = AgnesIcon.GALLERY, tint = Color(0xFF3F3F46), size = 48.dp)
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = if (searchQuery.isNotBlank()) "Aucun résultat pour \"$searchQuery\"" else "Aucune création pour le moment",
                        color = Color(0xFFA1A1AA),
                        fontSize = 14.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Vos créations d'images, vidéos et films apparaîtront ici.",
                        color = Color(0xFF71717A),
                        fontSize = 12.sp
                    )
                }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                // Films d'abord
                items(filteredFilms) { film ->
                    AgnesInteractiveCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f),
                        onClick = {
                            triggerHapticFeedback(context)
                            selectedFilmForDetail = film
                        }
                    ) {
                        Box(modifier = Modifier.fillMaxSize()) {
                            if (film.startImage.isNotBlank()) {
                                AsyncImage(
                                    model = film.startImage,
                                    contentDescription = film.title,
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop
                                )
                            } else {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(Color(0xFF231B36)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        AgnesSvgIcon(
                                            icon = AgnesIcon.FILM,
                                            tint = Color(0xFFA78BFA),
                                            size = 32.dp
                                        )
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Text(
                                            text = film.title,
                                            color = Color.White,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1,
                                            modifier = Modifier.padding(horizontal = 8.dp)
                                        )
                                    }
                                }
                            }
                            // Badge FILM en haut à gauche
                            Box(
                                modifier = Modifier
                                    .padding(8.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(Color(0xFF7C3AED))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                                    .align(Alignment.TopStart)
                            ) {
                                Text("FILM", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                            }

                            // Bouton Lecture rapide en bas à droite
                            Box(
                                modifier = Modifier
                                    .padding(6.dp)
                                    .size(28.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xCC7C3AED))
                                    .align(Alignment.BottomEnd),
                                contentAlignment = Alignment.Center
                            ) {
                                AgnesSvgIcon(icon = AgnesIcon.PLAY, tint = Color.White, size = 12.dp)
                            }
                        }
                    }
                }

                // Créations (images, vidéos)
                items(filteredCreations) { creation ->
                    val mediaUrl = creation.resultUrl ?: creation.thumbnail.orEmpty()
                    AgnesInteractiveCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f),
                        onClick = {
                            triggerHapticFeedback(context)
                            selectedItemForDetail = creation
                        }
                    ) {
                        Box(modifier = Modifier.fillMaxSize()) {
                            if (!creation.thumbnail.isNullOrBlank()) {
                                AsyncImage(
                                    model = creation.thumbnail,
                                    contentDescription = creation.prompt,
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop
                                )
                            } else {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(Color(0xFF1E1E2A)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    AgnesSvgIcon(
                                        icon = if (creation.type == "video") AgnesIcon.VIDEO else AgnesIcon.HOME_IMAGES,
                                        tint = Color(0xFF71717A),
                                        size = 30.dp
                                    )
                                }
                            }

                            // Badge type en haut à gauche
                            Box(
                                modifier = Modifier
                                    .padding(8.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(Color.Black.copy(alpha = 0.6f))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                                    .align(Alignment.TopStart)
                            ) {
                                Text(
                                    text = creation.type.uppercase(),
                                    color = Color.White,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            // Icône favori en haut à droite
                            if (creation.favorite) {
                                Box(
                                    modifier = Modifier
                                        .padding(8.dp)
                                        .align(Alignment.TopEnd)
                                ) {
                                    AgnesSvgIcon(
                                        icon = AgnesIcon.FAVORITE_FILLED,
                                        tint = Color(0xFFEF4444),
                                        size = 16.dp
                                    )
                                }
                            }

                            // Bouton téléchargement direct en bas à droite
                            if (mediaUrl.isNotBlank()) {
                                Box(
                                    modifier = Modifier
                                        .padding(6.dp)
                                        .size(28.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xCC000000))
                                        .align(Alignment.BottomEnd)
                                        .clickable {
                                            triggerHapticFeedback(context)
                                            if (creation.type == "video") {
                                                DownloadHelper.downloadVideo(context, mediaUrl, creation.prompt)
                                            } else {
                                                DownloadHelper.downloadImage(context, mediaUrl, creation.prompt)
                                            }
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    AgnesSvgIcon(icon = AgnesIcon.DOWNLOAD, tint = Color.White, size = 14.dp)
                                }
                            }

                            // Icône Play si vidéo
                            if (creation.type == "video" && mediaUrl.isNotBlank()) {
                                Box(
                                    modifier = Modifier
                                        .size(32.dp)
                                        .clip(CircleShape)
                                        .background(Color(0x88000000))
                                        .align(Alignment.Center),
                                    contentAlignment = Alignment.Center
                                ) {
                                    AgnesSvgIcon(icon = AgnesIcon.PLAY, tint = Color.White, size = 14.dp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
