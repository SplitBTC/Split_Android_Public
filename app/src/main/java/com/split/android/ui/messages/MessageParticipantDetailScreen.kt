package com.split.android.ui.messages

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.split.android.data.messages.MessageConversationPreview
import com.split.android.data.messages.MessagingBlockedUser
import com.split.android.data.wallet.WalletContact
import com.split.android.ui.SendOverlayConfig
import com.split.android.ui.SplitRootViewModel
import com.split.android.ui.theme.SplitBlack
import com.split.android.ui.theme.SplitBrandBlue
import com.split.android.ui.theme.SplitBrandPink
import kotlinx.coroutines.launch

data class MessageParticipantDescriptor(
    val title: String,
    val lightningAddress: String,
    val walletPubkey: String? = null,
    val profilePicUrl: String? = null
)

@Composable
fun MessageParticipantDetailScreen(
    rootViewModel: SplitRootViewModel,
    participant: MessageParticipantDescriptor,
    onBack: () -> Unit,
    onOpenSendOverlay: (SendOverlayConfig) -> Unit,
    onOpenThread: ((String, String, String) -> Unit)? = null,
    onCompose: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    BackHandler(onBack = onBack)

    val contactsByPaymentIdentifier by rootViewModel.contactsByPaymentIdentifier.collectAsStateWithLifecycle()
    val storedMessages by rootViewModel.storedMessages.collectAsStateWithLifecycle()
    val coroutineScope = rememberCoroutineScope()

    var displayLightningAddress by rememberSaveable(participant.lightningAddress) {
        mutableStateOf(participant.lightningAddress.trim().lowercase())
    }
    var showAddContactDialog by remember { mutableStateOf(false) }
    var contactNameDraft by rememberSaveable(displayLightningAddress) { mutableStateOf("") }
    var actionError by remember { mutableStateOf<String?>(null) }
    var activeBlock by remember(displayLightningAddress, participant.walletPubkey) {
        mutableStateOf<MessagingBlockedUser?>(null)
    }
    var isUpdatingBlockState by remember { mutableStateOf(false) }
    var showBlockConfirmation by remember { mutableStateOf(false) }
    var showUnblockConfirmation by remember { mutableStateOf(false) }

    val savedContact = remember(contactsByPaymentIdentifier, displayLightningAddress) {
        contactsByPaymentIdentifier[displayLightningAddress]
    }
    val conversations = remember(storedMessages) {
        rootViewModel.conversationPreviews()
    }
    val existingConversation = remember(conversations, displayLightningAddress) {
        conversations.firstOrNull {
            it.lightningAddress?.trim()?.lowercase() == displayLightningAddress
        }
    }
    val displayTitle = savedContact?.name ?: participant.title
    val resolvedProfilePicUrl = activeBlock?.blockedProfilePicUrl ?: participant.profilePicUrl
    val isMessagingBlocked = activeBlock != null

    suspend fun refreshBlockState() {
        runCatching {
            rootViewModel.fetchMessagingBlocks()
        }.onSuccess { blocks ->
            activeBlock = blocks.firstOrNull { block ->
                block.matchesParticipant(
                    walletPubkey = participant.walletPubkey,
                    lightningAddress = displayLightningAddress
                )
            }
        }.onFailure { error ->
            actionError = error.message ?: "Failed to load blocked user state."
        }
    }

    LaunchedEffect(displayLightningAddress, participant.walletPubkey) {
        refreshBlockState()
    }

    if (showAddContactDialog) {
        AlertDialog(
            onDismissRequest = { showAddContactDialog = false },
            confirmButton = {
                Button(
                    onClick = {
                        coroutineScope.launch {
                            runCatching {
                                rootViewModel.addWalletContact(
                                    name = contactNameDraft,
                                    paymentIdentifier = displayLightningAddress
                                )
                            }.onSuccess {
                                actionError = null
                                showAddContactDialog = false
                            }.onFailure { error ->
                                actionError = error.message ?: "Failed to add contact."
                            }
                        }
                    },
                    enabled = contactNameDraft.trim().isNotEmpty()
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showAddContactDialog = false }) {
                    Text("Cancel")
                }
            },
            title = { Text("Add Contact") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = displayLightningAddress,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.62f)
                    )

                    TextField(
                        value = contactNameDraft,
                        onValueChange = { contactNameDraft = it },
                        singleLine = true,
                        label = { Text("Name") }
                    )
                }
            }
        )
    }

    if (showBlockConfirmation) {
        AlertDialog(
            onDismissRequest = { showBlockConfirmation = false },
            confirmButton = {
                Button(
                    onClick = {
                        showBlockConfirmation = false
                        coroutineScope.launch {
                            isUpdatingBlockState = true
                            actionError = null
                            runCatching {
                                rootViewModel.blockMessagingUser(
                                    walletPubkey = participant.walletPubkey
                                        ?.trim()
                                        ?.takeIf {
                                            displayLightningAddress.equals(
                                                participant.lightningAddress.trim().lowercase(),
                                                ignoreCase = true
                                            )
                                        },
                                    lightningAddress = displayLightningAddress
                                )
                            }.onSuccess { block ->
                                activeBlock = block
                            }.onFailure { error ->
                                actionError = error.message ?: "Failed to block user."
                            }
                            isUpdatingBlockState = false
                        }
                    }
                ) {
                    Text("Block")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showBlockConfirmation = false }) {
                    Text("Cancel")
                }
            },
            title = { Text("Block User") },
            text = {
                Text("They won't be able to message you.")
            }
        )
    }

    if (showUnblockConfirmation && activeBlock != null) {
        AlertDialog(
            onDismissRequest = { showUnblockConfirmation = false },
            confirmButton = {
                Button(
                    onClick = {
                        val blockedWalletPubkey = activeBlock?.blockedWalletPubkey ?: return@Button
                        showUnblockConfirmation = false
                        coroutineScope.launch {
                            isUpdatingBlockState = true
                            actionError = null
                            runCatching {
                                rootViewModel.unblockMessagingUser(blockedWalletPubkey)
                            }.onSuccess {
                                activeBlock = null
                            }.onFailure { error ->
                                actionError = error.message ?: "Failed to unblock user."
                            }
                            isUpdatingBlockState = false
                        }
                    }
                ) {
                    Text("Unblock")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showUnblockConfirmation = false }) {
                    Text("Cancel")
                }
            },
            title = { Text("Unblock User") },
            text = {
                Text("This will let you message each other again.")
            }
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(SplitBlack)
            .statusBarsPadding()
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
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

            Spacer(modifier = Modifier.weight(1f))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (savedContact == null) {
                    ParticipantHeaderIconButton(
                        icon = Icons.Rounded.Add,
                        contentDescription = "Add contact",
                        onClick = {
                            contactNameDraft = displayTitle.takeIf {
                                !it.equals(displayLightningAddress, ignoreCase = true)
                            }.orEmpty()
                            showAddContactDialog = true
                        }
                    )
                }

                ParticipantHeaderIconButton(
                    icon = Icons.Rounded.Block,
                    contentDescription = if (isMessagingBlocked) "Unblock user" else "Block user",
                    onClick = {
                        actionError = null
                        if (isMessagingBlocked) {
                            showUnblockConfirmation = true
                        } else {
                            showBlockConfirmation = true
                        }
                    },
                    iconTint = if (isMessagingBlocked) SplitBrandPink else Color.White,
                    border = if (isMessagingBlocked) {
                        SplitBrandPink.copy(alpha = 0.24f)
                    } else {
                        Color.White.copy(alpha = 0.14f)
                    },
                    isBusy = isUpdatingBlockState
                )
            }
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(28.dp),
            color = Color(0xFF101013),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.10f))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                ParticipantAvatar(
                    label = displayTitle,
                    profilePicUrl = resolvedProfilePicUrl,
                    size = 88.dp
                )

                Text(
                    text = displayTitle,
                    style = MaterialTheme.typography.headlineMedium,
                    color = Color.White,
                    fontWeight = FontWeight.Black,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                Text(
                    text = displayLightningAddress,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.62f)
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (onOpenThread != null && onCompose != null) {
                Button(
                    onClick = {
                        val existing = existingConversation
                        if (existing != null) {
                            onOpenThread(
                                existing.id,
                                existing.displayTitle(savedContact),
                                existing.lightningAddress ?: displayLightningAddress
                            )
                        } else {
                            onCompose(displayLightningAddress)
                        }
                    },
                    modifier = Modifier.weight(1f),
                    enabled = !isMessagingBlocked
                ) {
                    Text("Message")
                }
            }

            OutlinedButton(
                onClick = {
                    onOpenSendOverlay(
                        SendOverlayConfig(initialDestination = displayLightningAddress)
                    )
                },
                modifier = Modifier.weight(1f)
            ) {
                Text("Pay")
            }
        }

        if (isMessagingBlocked) {
            Text(
                text = "You blocked this user. Unblock them to message again.",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.72f)
            )
        }

        if (!actionError.isNullOrBlank()) {
            Text(
                text = actionError!!,
                style = MaterialTheme.typography.bodySmall,
                color = SplitBrandPink
            )
        }
    }
}

@Composable
internal fun ParticipantHeaderIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    iconTint: Color = Color.White,
    border: Color = Color.White.copy(alpha = 0.14f),
    isBusy: Boolean = false
) {
    Box(
        modifier = Modifier
            .size(42.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.06f))
            .clickable(enabled = !isBusy, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            shape = CircleShape,
            color = Color.Transparent,
            border = BorderStroke(1.dp, border)
        ) {}

        if (isBusy) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                color = Color.White,
                strokeWidth = 2.dp
            )
        } else {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = iconTint,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
internal fun ParticipantAvatar(
    label: String,
    profilePicUrl: String?,
    size: androidx.compose.ui.unit.Dp
) {
    val imageModel = profilePicUrl?.trim()?.takeIf { it.isNotEmpty() }
    val initial = label.firstOrNull()?.uppercase() ?: "?"

    if (imageModel != null) {
        AsyncImage(
            model = imageModel,
            contentDescription = label,
            modifier = Modifier
                .size(size)
                .clip(CircleShape),
            contentScale = ContentScale.Crop
        )
    } else {
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(
                    Brush.linearGradient(
                        colors = listOf(SplitBrandBlue, SplitBrandPink)
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = initial,
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White,
                fontWeight = FontWeight.Black
            )
        }
    }
}

private fun MessagingBlockedUser.matchesParticipant(
    walletPubkey: String?,
    lightningAddress: String
): Boolean {
    val normalizedWalletPubkey = walletPubkey?.trim()
    if (!normalizedWalletPubkey.isNullOrEmpty() &&
        blockedWalletPubkey.trim().equals(normalizedWalletPubkey, ignoreCase = true)
    ) {
        return true
    }

    return normalizedLightningAddress == lightningAddress.trim().lowercase()
}

private fun MessageConversationPreview.displayTitle(
    savedContact: WalletContact?
): String {
    return savedContact?.name ?: title
}
