package com.example.ui.sheets

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.local.FavoriteEntity
import com.example.data.model.Destination
import com.example.ui.components.formatDistance
import com.example.ui.theme.*

@Composable
fun FavoritesSheet(
    favorites: List<FavoriteEntity>,
    onSelectFavorite: (Destination, Int) -> Unit,
    onDeleteFavorite: (FavoriteEntity) -> Unit,
    onAddCurrentAsFavorite: () -> Unit,
    hasActiveDestination: Boolean
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(bottom = 6.dp)
    ) {
        if (hasActiveDestination) {
            item {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = DarkSurfaceVariant,
                    border = androidx.compose.foundation.BorderStroke(1.dp, ElectricBlue.copy(alpha = 0.5f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onAddCurrentAsFavorite() }
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(ElectricBlue.copy(alpha = 0.2f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.AddLocation, contentDescription = null, tint = ElectricBlue, modifier = Modifier.size(20.dp))
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Save Selected Destination", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                            Text("Quickly launch trips to this destination later", fontSize = 11.sp, color = TextSecondary)
                        }
                        Icon(Icons.Default.Add, contentDescription = null, tint = ElectricBlue, modifier = Modifier.size(20.dp))
                    }
                }
            }
        }

        if (favorites.isEmpty()) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 48.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(Icons.Default.BookmarkBorder, contentDescription = null, tint = TextMuted, modifier = Modifier.size(48.dp))
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("No Saved Favorites Yet", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("Search and select a destination in Cockpit to save it here.", fontSize = 12.sp, color = TextSecondary)
                }
            }
        } else {
            item {
                Text(
                    text = "SAVED PLACES (${favorites.size})",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextMuted,
                    letterSpacing = 1.sp
                )
            }

            items(favorites) { favorite ->
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = DarkSurfaceVariant,
                    border = androidx.compose.foundation.BorderStroke(1.dp, BorderSubtle),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(AmberAction.copy(alpha = 0.2f)),
                            contentAlignment = Alignment.Center
                        ) {
                            val icon = when (favorite.category.lowercase()) {
                                "home" -> Icons.Default.Home
                                "work" -> Icons.Default.Work
                                "metro", "train" -> Icons.Default.Train
                                "bus" -> Icons.Default.DirectionsBus
                                "airport" -> Icons.Default.FlightTakeoff
                                else -> Icons.Default.Bookmark
                            }
                            Icon(icon, contentDescription = null, tint = AmberAction, modifier = Modifier.size(22.dp))
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(favorite.name, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                            if (favorite.address.isNotEmpty()) {
                                Text(favorite.address, fontSize = 11.sp, color = TextSecondary, maxLines = 1)
                            }
                            Text(
                                "Radius: ${formatDistance(favorite.preferredRadiusMeters.toDouble())} • ${favorite.preferredTransport}",
                                fontSize = 11.sp,
                                color = ElectricBlue
                            )
                        }

                        IconButton(
                            onClick = {
                                android.util.Log.d("RouteWakeTiming", "DESTINATION_TAP_RECEIVED: Favorite clicked: ${favorite.name}")
                                onSelectFavorite(favorite.toDestination(), favorite.preferredRadiusMeters)
                            },
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(AmberAction)
                                .testTag("favorite_start_${favorite.id}")
                        ) {
                            Icon(Icons.Default.Navigation, contentDescription = "Start Trip", tint = DarkBackground, modifier = Modifier.size(18.dp))
                        }

                        Spacer(modifier = Modifier.width(6.dp))

                        IconButton(
                            onClick = { onDeleteFavorite(favorite) },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(Icons.Default.DeleteOutline, contentDescription = "Delete", tint = TextMuted, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }
    }
}
