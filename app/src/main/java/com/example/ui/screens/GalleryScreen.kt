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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.example.ui.components.triggerHapticFeedback
import com.example.ui.svg.AgnesIcon
import com.example.ui.svg.AgnesSvgIcon

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

    // Modal détail création
    if (selectedItemForDetail != null) {
        val item = selectedItemForDetail!!
        AlertDialog(
            onDismissRequest = { selectedItemForDetail = null },
            title = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (item.type == "image") "Détail Image" else "Détail Vidéo",
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                    Row {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .clickable {
                                    triggerHapticFeedback(context)
                                    onToggleFavorite(item.id, !item.favorite)
                                    selectedItemForDetail = item.copy(favorite = !item.favorite)
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            AgnesSvgIcon(
                                icon = if (item.favorite) AgnesIcon.FAVORITE_FILLED else AgnesIcon.FAVORITE,
                                tint = if (item.favorite) Color(0xFFEF4444) else Color(0xFFA1A1AA),
                                size = 20.dp
                            )
                        }
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .clickable {
                                    triggerHapticFeedback(context)
                                    onDeleteCreation(item.id)
                                    selectedItemForDetail = null
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            AgnesSvgIcon(
                                icon = AgnesIcon.TRASH,
                                tint = Color(0xFFEF4444),
                                size = 20.dp
                            )
                        }
                    }
                }
            },
            text = {
                Column {
                    if (!item.thumbnail.isNullOrBlank()) {
                        AsyncImage(
                            model = item.thumbnail,
                            contentDescription = item.prompt,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(180.dp)
                                .clip(RoundedCornerShape(12.dp)),
                            contentScale = ContentScale.Crop
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                    }

                    Text(text = "Prompt :", color = Color(0xFFA1A1AA), fontSize = 12.sp)
                    Text(text = item.prompt, color = Color.White, fontSize = 13.sp)

                    Spacer(modifier = Modifier.height(8.dp))
                    Text(text = "Modèle : ${item.model}", color = Color(0xFF9CA3AF), fontSize = 12.sp)
                    Text(text = "Statut : ${item.status}", color = Color(0xFF34D399), fontSize = 12.sp)
                    if (item.error != null) {
                        Text(text = "Erreur : ${item.error}", color = Color(0xFFEF4444), fontSize = 12.sp)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { selectedItemForDetail = null }) {
                    Text("Fermer", color = Color(0xFFA78BFA))
                }
            },
            containerColor = Color(0xFF1E1E28),
            shape = RoundedCornerShape(16.dp)
        )
    }

    // Modal détail film
    if (selectedFilmForDetail != null) {
        val film = selectedFilmForDetail!!
        AlertDialog(
            onDismissRequest = { selectedFilmForDetail = null },
            title = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = film.title, color = Color.White, fontWeight = FontWeight.Bold)
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .clickable {
                                triggerHapticFeedback(context)
                                onDeleteFilm(film.id)
                                selectedFilmForDetail = null
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        AgnesSvgIcon(icon = AgnesIcon.TRASH, tint = Color(0xFFEF4444), size = 20.dp)
                    }
                }
            },
            text = {
                Column {
                    Text(text = "Synopsis :", color = Color(0xFFA1A1AA), fontSize = 12.sp)
                    Text(text = film.logline, color = Color.White, fontSize = 13.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(text = "Style : ${film.filmStyle}", color = Color(0xFF9CA3AF), fontSize = 12.sp)
                    Text(text = "Scènes : ${film.numScenes} plans", color = Color(0xFF9CA3AF), fontSize = 12.sp)
                    Text(text = "Statut : ${film.status}", color = Color(0xFF34D399), fontSize = 12.sp)
                }
            },
            confirmButton = {
                TextButton(onClick = { selectedFilmForDetail = null }) {
                    Text("Fermer", color = Color(0xFFA78BFA))
                }
            },
            containerColor = Color(0xFF1E1E28),
            shape = RoundedCornerShape(16.dp)
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
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            placeholder = { Text("Rechercher dans la galerie...", color = Color(0xFF555566), fontSize = 13.sp) },
            leadingIcon = {
                AgnesSvgIcon(icon = AgnesIcon.SEARCH, tint = Color(0xFF71717A), size = 18.dp)
            },
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

        Spacer(modifier = Modifier.height(16.dp))

        // Grille de résultats
        if (filteredCreations.isEmpty() && filteredFilms.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    AgnesSvgIcon(
                        icon = AgnesIcon.GALLERY,
                        tint = Color(0xFF3F3F46),
                        size = 48.dp
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Aucun contenu dans cette section",
                        color = Color(0xFF71717A),
                        fontSize = 14.sp
                    )
                }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Films
                items(filteredFilms) { film ->
                    AgnesInteractiveCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f),
                        onClick = { selectedFilmForDetail = film }
                    ) {
                        Box(modifier = Modifier.fillMaxSize()) {
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
                            // Badge FILM
                            Box(
                                modifier = Modifier
                                    .padding(8.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(Color(0xFF7C3AED))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text("FILM", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                // Créations (images, vidéos)
                items(filteredCreations) { creation ->
                    AgnesInteractiveCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f),
                        onClick = { selectedItemForDetail = creation }
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
                                        size = 18.dp
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
