package com.split.android.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.example.splitandroid.R

private val MainTabHeaderPink = Color(0xFFBE3287)

@Composable
fun MainTabHeader(
    onOpenBitcoinEvents: () -> Unit,
    onOpenContacts: () -> Unit,
    onOpenProfile: () -> Unit,
    onOpenMerchantMap: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(
            painter = painterResource(id = R.drawable.token_logo),
            contentDescription = "Split",
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .border(
                    width = 1.5.dp,
                    color = MainTabHeaderPink.copy(alpha = 0.90f),
                    shape = CircleShape
                )
        )

        Spacer(modifier = Modifier.weight(1f))

        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            MainTabHeaderActionButton(
                icon = SplitFeatureIcons.Contacts,
                onClick = onOpenContacts
            )
            MainTabHeaderActionButton(
                icon = SplitFeatureIcons.Store,
                onClick = onOpenMerchantMap
            )
            MainTabHeaderActionButton(
                icon = Icons.Rounded.Public,
                onClick = onOpenBitcoinEvents
            )
            MainTabHeaderActionButton(
                icon = Icons.Rounded.Menu,
                onClick = onOpenProfile
            )
        }
    }
}

@Composable
private fun MainTabHeaderActionButton(
    icon: ImageVector,
    onClick: () -> Unit
) {
    androidx.compose.foundation.layout.Box(
        modifier = Modifier
            .size(46.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.90f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(26.dp)
        )
    }
}
