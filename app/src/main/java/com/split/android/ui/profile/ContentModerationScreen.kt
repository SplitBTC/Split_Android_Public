package com.split.android.ui.profile

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Block
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.split.android.ui.theme.SplitBlack
import com.split.android.ui.theme.SplitBrandBlue
import com.split.android.ui.theme.SplitBrandPink

@Composable
fun ContentModerationScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(SplitBlack)
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ContentModerationHeaderButton(
                icon = Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = "Back",
                onClick = onBack
            )

            Column(
                modifier = Modifier.padding(start = 10.dp)
            ) {
                Text(
                    text = "Content Moderation",
                    style = MaterialTheme.typography.headlineSmall,
                    color = Color.White,
                    fontWeight = FontWeight.Black
                )
                Text(
                    text = "Blocking and user safety.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.70f)
                )
            }
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(26.dp),
            color = Color.Transparent
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        brush = Brush.linearGradient(
                            colors = listOf(SplitBrandBlue, SplitBrandPink)
                        ),
                        shape = RoundedCornerShape(26.dp)
                    )
                    .padding(22.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "Blocking Users",
                        style = MaterialTheme.typography.headlineMedium,
                        color = Color.White,
                        fontWeight = FontWeight.Black
                    )
                    Text(
                        text = "Use blocking for your own comfort and safety when you do not want another user to contact you.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color.White.copy(alpha = 0.92f)
                    )
                }
            }
        }

        ContentModerationInfoCard(
            title = "Blocking Users",
            icon = Icons.Rounded.Block,
            iconColor = SplitBrandPink,
            lines = listOf(
                "To block someone, open that person's detail view and tap the block icon.",
                "Once blocked, you will not receive messages from that user.",
                "Blocking helps you control who can contact you in Split.",
                "Blocking another user is at the complete discretion of each user."
            )
        )
    }
}

@Composable
private fun ContentModerationInfoCard(
    title: String,
    icon: ImageVector,
    iconColor: Color,
    lines: List<String>
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = Color(0xFF101013),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.06f))
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color.White),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = iconColor
                    )
                }

                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                lines.forEach { line ->
                    Row(
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .padding(top = 7.dp)
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.78f))
                        )

                        Text(
                            text = line,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.76f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ContentModerationHeaderButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(42.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.06f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            shape = CircleShape,
            color = Color.Transparent,
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.14f))
        ) {}

        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = Color.White,
            modifier = Modifier.size(18.dp)
        )
    }
}
