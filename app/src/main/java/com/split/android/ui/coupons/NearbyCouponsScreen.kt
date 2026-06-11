package com.split.android.ui.coupons

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.split.android.ui.SplitFeatureIcons
import com.split.android.ui.SplitRootViewModel

private val PromoBackground = Color(0xFF050508)
private val PromoCardSurface = Color(0xFF141830)
private val PromoBrandBlue = Color(0xFF132B62)
private val PromoBrandPink = Color(0xFFBE3287)

@Composable
fun NearbyCouponsScreen(
    rootViewModel: SplitRootViewModel,
    onDismiss: () -> Unit,
    showBackButton: Boolean = true,
    modifier: Modifier = Modifier
) {
    BackHandler(onBack = onDismiss)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(PromoBackground)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) {
            PromoOverlayHeader(
                title = "Promos",
                onBack = onDismiss,
                showBackButton = showBackButton
            )

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                PromoComingSoonCard()
            }
        }
    }
}

@Composable
private fun PromoOverlayHeader(
    title: String,
    onBack: () -> Unit,
    showBackButton: Boolean = true
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (showBackButton) {
            Surface(
                modifier = Modifier
                    .size(46.dp)
                    .clip(CircleShape),
                color = Color.Black.copy(alpha = 0.9f),
                onClick = onBack
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = "Back",
                        tint = Color.White,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        } else {
            Spacer(modifier = Modifier.size(46.dp))
        }

        Box(
            modifier = Modifier.weight(1f),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Black,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(modifier = Modifier.width(46.dp))
    }
}

@Composable
private fun PromoComingSoonCard() {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(28.dp)
    ) {
        Surface(
            modifier = Modifier.size(82.dp),
            shape = CircleShape,
            color = Color.White.copy(alpha = 0.08f),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.10f))
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = SplitFeatureIcons.Tag,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier
                        .size(44.dp)
                        .background(
                            brush = Brush.linearGradient(
                                colors = listOf(PromoBrandBlue, PromoBrandPink)
                            ),
                            shape = CircleShape
                        )
                        .padding(10.dp)
                )
            }
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            color = PromoCardSurface,
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f))
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    text = "Promo feature coming soon.",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Black,
                    color = Color.White,
                    textAlign = TextAlign.Center
                )

                Text(
                    text = "Here we will bring you exclusive special offers from top quality brands.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White.copy(alpha = 0.72f),
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}
