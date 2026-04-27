package com.split.android.ui.messages

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.split.android.data.messages.MessagingBlockedUser
import com.split.android.ui.SplitRootViewModel
import com.split.android.ui.theme.SplitBlack
import com.split.android.ui.theme.SplitBrandPink
import kotlinx.coroutines.launch

@Composable
fun BlockedUsersScreen(
    rootViewModel: SplitRootViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler(onBack = onBack)

    val contactsByPaymentIdentifier by rootViewModel.contactsByPaymentIdentifier.collectAsStateWithLifecycle()
    val coroutineScope = rememberCoroutineScope()

    var blockedUsers by remember { mutableStateOf<List<MessagingBlockedUser>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var pendingUnblockWalletPubkey by remember { mutableStateOf<String?>(null) }

    suspend fun refresh() {
        isLoading = true
        errorMessage = null

        runCatching {
            rootViewModel.fetchMessagingBlocks()
        }.onSuccess { blocks ->
            blockedUsers = blocks
        }.onFailure { error ->
            blockedUsers = emptyList()
            errorMessage = error.message ?: "Failed to load blocked users."
        }

        isLoading = false
    }

    LaunchedEffect(Unit) {
        refresh()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(SplitBlack)
            .statusBarsPadding()
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ParticipantHeaderIconButton(
                icon = Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = "Back",
                onClick = onBack
            )

            Text(
                text = "Blocked Users",
                modifier = Modifier.padding(start = 10.dp),
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White,
                fontWeight = FontWeight.Black
            )
        }

        Text(
            text = "Anyone you block in messaging will appear here.",
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.68f)
        )

        when {
            isLoading -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp)
                }
            }

            !errorMessage.isNullOrBlank() && blockedUsers.isEmpty() -> {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    color = Color(0xFF101013),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.10f))
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "Unable to load blocked users",
                            style = MaterialTheme.typography.titleLarge,
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = errorMessage!!,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.72f)
                        )
                        Button(
                            onClick = {
                                coroutineScope.launch {
                                    refresh()
                                }
                            }
                        ) {
                            Text("Try Again")
                        }
                    }
                }
            }

            blockedUsers.isEmpty() -> {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    color = Color(0xFF101013),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.10f))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 22.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(54.dp)
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.08f)),
                            contentAlignment = Alignment.Center
                        ) {
                            androidx.compose.material3.Icon(
                                imageVector = Icons.Rounded.Block,
                                contentDescription = null,
                                tint = Color.White
                            )
                        }

                        Text(
                            text = "No Blocked Users",
                            style = MaterialTheme.typography.titleLarge,
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Users you block from messaging will show up here.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.62f)
                        )
                    }
                }
            }

            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(blockedUsers, key = { it.blockId }) { block ->
                        val contactName = block.normalizedLightningAddress
                            ?.let { contactsByPaymentIdentifier[it]?.name }
                        val displayName = contactName
                            ?: block.normalizedLightningAddress
                            ?: abbreviateWalletPubkey(block.blockedWalletPubkey)
                        val secondaryText = block.normalizedLightningAddress
                            ?: abbreviateWalletPubkey(block.blockedWalletPubkey)

                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(18.dp),
                            color = Color(0xFF101013),
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f))
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                ParticipantAvatar(
                                    label = displayName,
                                    profilePicUrl = block.blockedProfilePicUrl,
                                    size = 48.dp
                                )

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = displayName,
                                        style = MaterialTheme.typography.titleMedium,
                                        color = Color.White,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = secondaryText,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color.White.copy(alpha = 0.58f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }

                                if (pendingUnblockWalletPubkey == block.blockedWalletPubkey) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        color = Color.White,
                                        strokeWidth = 2.dp
                                    )
                                } else {
                                    OutlinedButton(
                                        onClick = {
                                            coroutineScope.launch {
                                                pendingUnblockWalletPubkey = block.blockedWalletPubkey
                                                runCatching {
                                                    rootViewModel.unblockMessagingUser(block.blockedWalletPubkey)
                                                }.onSuccess {
                                                    blockedUsers = blockedUsers.filterNot {
                                                        it.blockedWalletPubkey == block.blockedWalletPubkey
                                                    }
                                                    errorMessage = null
                                                }.onFailure { error ->
                                                    errorMessage = error.message ?: "Failed to unblock user."
                                                }
                                                pendingUnblockWalletPubkey = null
                                            }
                                        }
                                    ) {
                                        Text(
                                            text = "Unblock",
                                            color = SplitBrandPink
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        if (!errorMessage.isNullOrBlank() && blockedUsers.isNotEmpty()) {
            Text(
                text = errorMessage!!,
                style = MaterialTheme.typography.bodySmall,
                color = SplitBrandPink
            )
        }
    }
}

private fun abbreviateWalletPubkey(value: String): String {
    val trimmed = value.trim()
    if (trimmed.length <= 16) {
        return trimmed
    }

    return "${trimmed.take(8)}...${trimmed.takeLast(8)}"
}
