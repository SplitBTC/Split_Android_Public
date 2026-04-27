package com.split.android.ui.messages

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.ChatBubble
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SmartDisplay
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.window.Dialog
import androidx.core.content.FileProvider
import com.split.android.core.AppConfig
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.split.android.data.messages.AttachmentMessagePayload
import com.split.android.data.messages.MessageAttachmentManager
import com.split.android.data.messages.MessageConversationPreview
import com.split.android.data.messages.MessageVideoProcessor
import com.split.android.data.messages.MessagePayloadCodec
import com.split.android.data.messages.MessageRecipientMetadata
import com.split.android.data.messages.MessageReactionKind
import com.split.android.data.messages.MessageThreadPresenceTracker
import com.split.android.data.messages.PaymentRequestMessagePayload
import com.split.android.data.messages.StoredMessage
import com.split.android.data.wallet.WalletContact
import com.split.android.ui.MainTabHeader
import com.split.android.ui.SendOverlayConfig
import com.split.android.ui.SplitRootViewModel
import com.split.android.ui.theme.SplitBrandBlue
import com.split.android.ui.theme.SplitBrandPink
import com.split.android.ui.theme.SplitSurfaceAlt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import java.net.URL
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.roundToInt

private data class ActiveThread(
    val conversationId: String,
    val title: String,
    val lightningAddress: String?
)

private data class ReactionStateKey(
    val targetMessageId: String,
    val senderWalletPubkey: String
)

private data class ReactionBadgeSummary(
    val kind: MessageReactionKind,
    val count: Int
)

private data class PreviewImageAttachment(
    val fileName: String,
    val data: ByteArray
)

private data class ActiveContact(
    val id: String,
    val name: String,
    val paymentIdentifier: String
)

private enum class ComposeRecipientSource {
    CONTACT,
    CONVERSATION,
}

private data class ComposeRecipientRecord(
    val lightningAddress: String,
    val displayName: String,
    val profilePicUrl: String?,
    val lastInteractedAtMillis: Long?,
    val source: ComposeRecipientSource
)

@Composable
fun MessagesScreen(
    rootViewModel: SplitRootViewModel,
    onOpenBitcoinEvents: () -> Unit,
    onOpenContacts: () -> Unit,
    onOpenProfile: () -> Unit,
    onOpenMerchantMap: () -> Unit,
    onOpenSendOverlay: (SendOverlayConfig) -> Unit,
    bottomInset: Dp = 0.dp,
    requestedComposeLightningAddress: String? = null,
    onConsumeRequestedCompose: () -> Unit = {},
    requestedConversationId: String? = null,
    requestedConversationTitle: String? = null,
    requestedConversationLightningAddress: String? = null,
    onConsumeRequestedConversation: () -> Unit = {},
    requestedOpenContacts: Boolean = false,
    onConsumeRequestedOpenContacts: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val storedMessages by rootViewModel.storedMessages.collectAsStateWithLifecycle()
    val messagingUiState by rootViewModel.messagingUiState.collectAsStateWithLifecycle()
    val recipientMetadataByConversationId by rootViewModel.recipientMetadataByConversationId.collectAsStateWithLifecycle()
    val contactsByPaymentIdentifier by rootViewModel.contactsByPaymentIdentifier.collectAsStateWithLifecycle()
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var activeThread by remember { mutableStateOf<ActiveThread?>(null) }
    var activeContact by remember { mutableStateOf<ActiveContact?>(null) }
    var showComposer by remember { mutableStateOf(false) }
    var showContacts by remember { mutableStateOf(false) }
    var showBlockedUsers by remember { mutableStateOf(false) }
    var composerPrefilledLightningAddress by rememberSaveable { mutableStateOf<String?>(null) }
    var composeReturnContact by remember { mutableStateOf<ActiveContact?>(null) }

    val allConversations = remember(storedMessages, recipientMetadataByConversationId) {
        rootViewModel.conversationPreviews()
    }
    val messagesByConversationId = remember(storedMessages) {
        storedMessages.groupBy { it.conversationId }
    }
    val conversations = remember(
        allConversations,
        searchQuery,
        contactsByPaymentIdentifier,
        messagesByConversationId
    ) {
        filterConversationPreviews(
            allConversations = allConversations,
            searchQuery = searchQuery,
            contactsByPaymentIdentifier = contactsByPaymentIdentifier,
            messagesByConversationId = messagesByConversationId
        )
    }

    LaunchedEffect(Unit) {
        if (!messagingUiState.hasLoadedOnce && !messagingUiState.isLoading) {
            rootViewModel.syncMessages(force = false)
        }
        rootViewModel.syncOutgoingStatuses(force = false)
        rootViewModel.loadContacts()
    }

    LaunchedEffect(requestedComposeLightningAddress) {
        val requested = requestedComposeLightningAddress?.trim().orEmpty()
        if (requested.isNotEmpty()) {
            activeThread = null
            activeContact = null
            showContacts = false
            showBlockedUsers = false
            composeReturnContact = null
            composerPrefilledLightningAddress = requested
            showComposer = true
            onConsumeRequestedCompose()
        }
    }

    LaunchedEffect(
        requestedConversationId,
        requestedConversationTitle,
        requestedConversationLightningAddress,
        allConversations,
        recipientMetadataByConversationId,
        contactsByPaymentIdentifier
    ) {
        val conversationId = requestedConversationId?.trim().orEmpty()
        if (conversationId.isNotEmpty()) {
            val preview = allConversations.firstOrNull { it.id == conversationId }
            val metadata = recipientMetadataByConversationId[conversationId]
            val resolvedLightningAddress = preview?.lightningAddress
                ?: metadata?.lightningAddress
                ?: requestedConversationLightningAddress?.trim()?.ifBlank { null }
            val providedTitle = requestedConversationTitle?.trim()?.ifBlank { null }
            val resolvedTitle = when {
                !providedTitle.isNullOrEmpty() -> providedTitle
                !resolvedLightningAddress.isNullOrEmpty() -> {
                    val normalizedAddress = resolvedLightningAddress.lowercase()
                    contactsByPaymentIdentifier[normalizedAddress]?.name
                        ?: preview?.title
                        ?: resolvedLightningAddress
                }
                preview != null -> preview.title
                else -> conversationId.take(12)
            }

            searchQuery = ""
            showComposer = false
            showContacts = false
            showBlockedUsers = false
            activeContact = null
            activeThread = ActiveThread(
                conversationId = conversationId,
                title = resolvedTitle,
                lightningAddress = resolvedLightningAddress
            )
            onConsumeRequestedConversation()
        }
    }

    LaunchedEffect(requestedOpenContacts) {
        if (requestedOpenContacts) {
            activeThread = null
            activeContact = null
            composerPrefilledLightningAddress = null
            composeReturnContact = null
            showComposer = false
            showContacts = true
            showBlockedUsers = false
            onConsumeRequestedOpenContacts()
        }
    }

    when {
        activeContact != null -> ContactDetailScreen(
            rootViewModel = rootViewModel,
            contact = activeContact!!,
            onBack = { activeContact = null },
            onUpdated = { activeContact = it },
            onDeleted = { activeContact = null },
            onOpenSendOverlay = onOpenSendOverlay,
            onOpenThread = { conversationId, title, lightningAddress ->
                activeContact = null
                activeThread = ActiveThread(
                    conversationId = conversationId,
                    title = title,
                    lightningAddress = lightningAddress
                )
            },
            onCompose = { lightningAddress ->
                composeReturnContact = activeContact
                activeContact = null
                showContacts = false
                composerPrefilledLightningAddress = lightningAddress
                showComposer = true
            }
        )

        activeThread != null -> MessageThreadScreen(
            rootViewModel = rootViewModel,
            thread = activeThread!!,
            onBack = { activeThread = null },
            onOpenSendOverlay = onOpenSendOverlay,
            bottomInset = bottomInset
        )

        showBlockedUsers -> BlockedUsersScreen(
            rootViewModel = rootViewModel,
            onBack = { showBlockedUsers = false }
        )

        showContacts -> ContactsScreen(
            rootViewModel = rootViewModel,
            contactsByPaymentIdentifier = contactsByPaymentIdentifier,
            recipientMetadataByConversationId = recipientMetadataByConversationId,
            onBack = { showContacts = false },
            onOpenBlockedUsers = { showBlockedUsers = true },
            onOpenContact = { contact ->
                activeContact = contact
            }
        )

        showComposer -> ComposeMessageScreen(
            rootViewModel = rootViewModel,
            prefilledLightningAddress = composerPrefilledLightningAddress,
            onDismiss = {
                val returnContact = composeReturnContact
                composerPrefilledLightningAddress = null
                showComposer = false
                composeReturnContact = null
                if (returnContact != null) {
                    showContacts = true
                    activeContact = returnContact
                }
            },
            onSent = { result ->
                showComposer = false
                composerPrefilledLightningAddress = null
                composeReturnContact = null
                val normalizedAddress = result.lightningAddress.trim().lowercase()
                activeThread = ActiveThread(
                    conversationId = result.conversationId,
                    title = contactsByPaymentIdentifier[normalizedAddress]?.name
                        ?: recipientMetadataByConversationId[result.conversationId]?.displayTitle
                        ?: result.conversationTitle,
                    lightningAddress = recipientMetadataByConversationId[result.conversationId]?.lightningAddress
                        ?: result.lightningAddress
                )
            }
        )

        else -> MessagesListScreen(
            conversations = conversations,
            contactsByPaymentIdentifier = contactsByPaymentIdentifier,
            messagesByConversationId = messagesByConversationId,
            searchQuery = searchQuery,
            onSearchQueryChange = { searchQuery = it },
            uiState = messagingUiState,
            onOpenContactsFromHeader = onOpenContacts,
            onOpenBitcoinEvents = onOpenBitcoinEvents,
            onOpenProfile = onOpenProfile,
            onOpenMerchantMap = onOpenMerchantMap,
            modifier = modifier,
            onCompose = {
                composerPrefilledLightningAddress = null
                composeReturnContact = null
                showComposer = true
            },
            onDeleteConversation = { conversation ->
                rootViewModel.deleteConversation(conversation.id)
            },
            onOpenConversation = { conversation ->
                val normalizedAddress = conversation.lightningAddress?.trim()?.lowercase()
                activeThread = ActiveThread(
                    conversationId = conversation.id,
                    title = normalizedAddress?.let { contactsByPaymentIdentifier[it]?.name } ?: conversation.title,
                    lightningAddress = conversation.lightningAddress
                )
            }
        )
    }
}

@Composable
private fun MessagesListScreen(
    conversations: List<MessageConversationPreview>,
    contactsByPaymentIdentifier: Map<String, WalletContact>,
    messagesByConversationId: Map<String, List<StoredMessage>>,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    uiState: com.split.android.data.messages.MessagingUiState,
    onOpenContactsFromHeader: () -> Unit,
    onOpenBitcoinEvents: () -> Unit,
    onOpenProfile: () -> Unit,
    onOpenMerchantMap: () -> Unit,
    modifier: Modifier = Modifier,
    onCompose: () -> Unit,
    onDeleteConversation: (MessageConversationPreview) -> Unit,
    onOpenConversation: (MessageConversationPreview) -> Unit
) {
    var revealedConversationId by remember { mutableStateOf<String?>(null) }
    var showMessagingInfo by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = 18.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            MainTabHeader(
                onOpenBitcoinEvents = onOpenBitcoinEvents,
                onOpenContacts = onOpenContactsFromHeader,
                onOpenProfile = onOpenProfile,
                onOpenMerchantMap = onOpenMerchantMap
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Messages",
                    style = MaterialTheme.typography.headlineMedium,
                    color = Color.White,
                    fontWeight = FontWeight.Black
                )

                Spacer(modifier = Modifier.weight(1f))

                MessagesComposeButton(onClick = onCompose)
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = "Encrypted conversations.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.58f)
                )

                MessagingPrivacyInfoButton(
                    onClick = { showMessagingInfo = true }
                )
            }

            SearchField(
                query = searchQuery,
                onQueryChange = onSearchQueryChange
            )

            if (!uiState.errorMessage.isNullOrBlank()) {
                Text(
                    text = uiState.errorMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = SplitBrandPink
                )
            }

            if (conversations.isEmpty()) {
                EmptyMessagesState(onCompose = onCompose)
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(conversations, key = { it.id }) { conversation ->
                        val displayTitle = conversationDisplayTitle(
                            conversation = conversation,
                            contactsByPaymentIdentifier = contactsByPaymentIdentifier
                        )
                        SwipeableConversationRow(
                            conversation = conversation,
                            displayTitle = displayTitle,
                            subtitle = conversationSubtitle(
                                conversation = conversation,
                                messages = messagesByConversationId[conversation.id].orEmpty(),
                                searchQuery = searchQuery
                            ),
                            isDeleteRevealed = revealedConversationId == conversation.id,
                            onRevealChange = { isRevealed ->
                                revealedConversationId = if (isRevealed) {
                                    conversation.id
                                } else if (revealedConversationId == conversation.id) {
                                    null
                                } else {
                                    revealedConversationId
                                }
                            },
                            onDelete = {
                                if (revealedConversationId == conversation.id) {
                                    revealedConversationId = null
                                }
                                onDeleteConversation(conversation)
                            },
                            onClick = { onOpenConversation(conversation) }
                        )
                    }
                }
            }
        }

        if (showMessagingInfo) {
            MessagingPrivacyInfoOverlay(
                onDismiss = { showMessagingInfo = false }
            )
        }
    }
}

@Composable
private fun MessagingPrivacyInfoButton(
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier.size(24.dp),
        shape = CircleShape,
        color = Color.White.copy(alpha = 0.08f)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Rounded.Info,
                contentDescription = "How Split messaging works",
                tint = Color.White.copy(alpha = 0.62f),
                modifier = Modifier.size(14.dp)
            )
        }
    }
}

private val MessagingPrivacyInfoParagraphs = listOf(
    "Split messages are designed so Split can deliver your messages without reading their contents. When you send a message, your device encrypts the message before it is sent to Split's relay. The relay receives encrypted data and routing information, but not the readable message body.",
    "Attachments work similarly. Photos, videos, and files are encrypted on your device before upload. Split stores and forwards encrypted attachment data, not the plaintext file.",
    "Split uses your wallet identity to make messaging harder to impersonate. Your wallet signs a binding between your wallet public key, your Lightning Address, and your messaging public key. That lets other Split clients verify that a messaging key really belongs to the wallet and Lightning Address it claims to represent.",
    "When you message someone by Lightning Address, Split returns their messaging identity from a server directory. Your app does not blindly trust that response. It verifies the recipient's wallet-signed identity and checks a cryptographic directory proof before using that recipient key.",
    "Each message also includes sender authentication. After decrypting a message, your app verifies that the sender's wallet-signed identity and message envelope are valid. This helps protect against forged messages or silent identity substitution by the relay.",
    "The benefit is that Split messaging provides authenticated end-to-end encryption: message contents and attachment bytes are encrypted for the recipient, while sender and recipient identities are tied back to wallet-controlled keys.",
    "There are still important privacy limits. Split is not anonymous messaging. The relay still sees metadata needed to operate the service, such as which accounts are messaging, when messages are sent, which Lightning Addresses are resolved, message type, ciphertext size, and attachment size. Push notifications may also reveal to Apple or Google that Split received a notification for your device, although notification text does not include message contents.",
    "Split messaging is also not the same as Signal's double-ratchet design. Messages use fresh encryption material when sent, but the recipient has a longer-lived messaging key. That means local device security still matters, and this should not be described as full Signal-style forward secrecy.",
    "In short: Split is built so the server can route encrypted messages without reading them, and so your app can verify wallet-backed sender and recipient identities. It protects message contents, but it does not hide all messaging metadata."
)

@Composable
private fun MessagingPrivacyInfoOverlay(
    onDismiss: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.74f))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.BottomCenter
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .heightIn(max = 680.dp),
            shape = RoundedCornerShape(28.dp),
            color = SplitSurfaceAlt,
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.10f))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Info,
                        contentDescription = null,
                        tint = SplitBrandPink,
                        modifier = Modifier.size(24.dp)
                    )

                    Text(
                        text = "How Split Messaging Works",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Black,
                        color = Color.White
                    )
                }

                MessagingPrivacyInfoParagraphs.forEach { paragraph ->
                    Text(
                        text = paragraph,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.88f)
                    )
                }

                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text("Done")
                }
            }
        }
    }
}

@Composable
private fun EmptyMessagesState(
    onCompose: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(86.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.08f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Rounded.ChatBubble,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.88f)
            )
        }

        Spacer(modifier = Modifier.height(18.dp))

        Text(
            text = "No Messages Yet",
            style = MaterialTheme.typography.titleLarge,
            color = Color.White,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Start a conversation with a friend using their Lightning Address.",
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.62f)
        )

        Spacer(modifier = Modifier.height(18.dp))

        Button(onClick = onCompose) {
            Text("New Message")
        }
    }
}

@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = Color(0xFF101013),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.06f))
    ) {
        TextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            leadingIcon = {
                Icon(
                    imageVector = Icons.Rounded.Search,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.68f)
                )
            },
            trailingIcon = if (query.isNotBlank()) {
                {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = "Clear search",
                        tint = Color.White.copy(alpha = 0.46f),
                        modifier = Modifier.clickable { onQueryChange("") }
                    )
                }
            } else {
                null
            },
            placeholder = { Text("Search") },
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                disabledContainerColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent,
                cursorColor = Color.White,
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                focusedPlaceholderColor = Color.White.copy(alpha = 0.46f),
                unfocusedPlaceholderColor = Color.White.copy(alpha = 0.46f)
            )
        )
    }
}

@Composable
private fun SwipeableConversationRow(
    conversation: MessageConversationPreview,
    displayTitle: String,
    subtitle: String,
    isDeleteRevealed: Boolean,
    onRevealChange: (Boolean) -> Unit,
    onDelete: () -> Unit,
    onClick: () -> Unit
) {
    val density = LocalDensity.current
    val deleteActionWidth = 82.dp
    val deleteActionWidthPx = remember(density) { with(density) { deleteActionWidth.toPx() } }
    var dragOffsetPx by remember(conversation.id) { mutableFloatStateOf(0f) }
    var isDragging by remember(conversation.id) { mutableStateOf(false) }
    val settledOffsetPx = if (isDeleteRevealed) -deleteActionWidthPx else 0f
    val animatedOffsetPx by animateFloatAsState(
        targetValue = if (isDragging) dragOffsetPx else settledOffsetPx,
        label = "conversationSwipeOffset"
    )
    val revealProgress = (-animatedOffsetPx / deleteActionWidthPx).coerceIn(0f, 1f)

    LaunchedEffect(conversation.id, isDeleteRevealed, deleteActionWidthPx) {
        if (!isDragging) {
            dragOffsetPx = settledOffsetPx
        }
    }

    Box(
        modifier = Modifier.fillMaxWidth()
    ) {
        Surface(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(vertical = 2.dp)
                .width(deleteActionWidth)
                .fillMaxHeight()
                .clickable(onClick = onDelete)
                .offset {
                    val hiddenOffsetPx = with(density) { 10.dp.toPx() }
                    IntOffset(
                        x = ((1f - (-animatedOffsetPx / deleteActionWidthPx).coerceIn(0f, 1f)) * hiddenOffsetPx).roundToInt(),
                        y = 0
                    )
                },
            shape = RoundedCornerShape(18.dp),
            color = SplitBrandPink.copy(alpha = revealProgress)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(vertical = 8.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = Icons.Rounded.Delete,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = revealProgress),
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = "Delete",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = revealProgress),
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        ConversationRow(
            conversation = conversation,
            displayTitle = displayTitle,
            subtitle = subtitle,
            modifier = Modifier
                .offset { IntOffset(animatedOffsetPx.roundToInt(), 0) }
                .draggable(
                    orientation = Orientation.Horizontal,
                    state = rememberDraggableState { delta ->
                        isDragging = true
                        dragOffsetPx = (dragOffsetPx + delta).coerceIn(-deleteActionWidthPx, 0f)
                    },
                    onDragStarted = {
                        isDragging = true
                    },
                    onDragStopped = {
                        isDragging = false
                        val shouldRevealDelete = dragOffsetPx <= (-deleteActionWidthPx * 0.52f)
                        dragOffsetPx = if (shouldRevealDelete) -deleteActionWidthPx else 0f
                        onRevealChange(shouldRevealDelete)
                    }
                ),
            onClick = {
                if (isDeleteRevealed) {
                    dragOffsetPx = 0f
                    onRevealChange(false)
                } else {
                    onClick()
                }
            }
        )
    }
}

@Composable
private fun ConversationRow(
    conversation: MessageConversationPreview,
    displayTitle: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        color = Color(0xFF101013)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            AvatarCircle(
                label = displayTitle,
                profilePicUrl = conversation.profilePicUrl
            )

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = displayTitle,
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.SemiBold
                )

                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (conversation.hasFailedOutgoingMessage) {
                        SplitBrandPink
                    } else {
                        Color.White.copy(alpha = 0.58f)
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = MessagePayloadCodec.formatConversationTime(conversation.latestAtMillis),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.5f)
                )

                if (conversation.hasFailedOutgoingMessage) {
                    Icon(
                        imageVector = Icons.Rounded.ErrorOutline,
                        contentDescription = "Delivery failed",
                        tint = SplitBrandPink,
                        modifier = Modifier.size(16.dp)
                    )
                } else if (conversation.hasUnreadMessages) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(SplitBrandPink)
                    )
                }
            }
        }
    }
}

@Composable
private fun FailedDeliveryCaption(
    isRetrying: Boolean,
    onRetry: () -> Unit
) {
    Row(
        modifier = Modifier
            .clickable(enabled = !isRetrying, onClick = onRetry),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (isRetrying) {
            CircularProgressIndicator(
                modifier = Modifier.size(12.dp),
                color = SplitBrandPink,
                strokeWidth = 2.dp
            )
        } else {
            Icon(
                imageVector = Icons.Rounded.ErrorOutline,
                contentDescription = null,
                tint = SplitBrandPink,
                modifier = Modifier.size(14.dp)
            )
        }

        Text(
            text = if (isRetrying) "Retrying..." else "Couldn't deliver. Tap to resend.",
            style = MaterialTheme.typography.bodySmall,
            color = SplitBrandPink,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun MessageThreadScreen(
    rootViewModel: SplitRootViewModel,
    thread: ActiveThread,
    onBack: () -> Unit,
    onOpenSendOverlay: (SendOverlayConfig) -> Unit,
    bottomInset: Dp = 0.dp,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val storedMessages by rootViewModel.storedMessages.collectAsStateWithLifecycle()
    val cachedAttachmentIds by rootViewModel.cachedAttachmentIds.collectAsStateWithLifecycle()
    val recipientMetadataByConversationId by rootViewModel.recipientMetadataByConversationId.collectAsStateWithLifecycle()
    val contactsByPaymentIdentifier by rootViewModel.contactsByPaymentIdentifier.collectAsStateWithLifecycle()
    val threadMessages = remember(storedMessages, thread.conversationId) {
        rootViewModel.messagesForConversation(thread.conversationId)
    }
    val recipientMetadata = recipientMetadataByConversationId[thread.conversationId]
    val displayLightningAddress = recipientMetadata?.lightningAddress ?: thread.lightningAddress
    val savedContact = displayLightningAddress
        ?.trim()
        ?.lowercase()
        ?.let { contactsByPaymentIdentifier[it] }
    val displayTitle = savedContact?.name ?: recipientMetadata?.displayTitle ?: thread.title
    val visibleMessages = remember(threadMessages) {
        threadMessages.filterNot { it.messageType == "payment_request_paid" || it.messageType == "reaction" }
    }
    val paidRequestMessageIds = remember(threadMessages) {
        threadMessages
            .filter { it.messageType == "payment_request_paid" }
            .mapNotNull { MessagePayloadCodec.decodePaymentRequestPaid(it.body)?.requestMessageId }
            .toSet()
    }
    val latestReactionByTargetAndSender = remember(threadMessages) {
        threadMessages
            .filter { it.messageType == "reaction" }
            .sortedWith(compareBy<StoredMessage> { it.createdAtMillis }.thenBy { it.id })
            .fold(mutableMapOf<ReactionStateKey, MessageReactionKind>()) { acc, reactionMessage ->
                val payload = MessagePayloadCodec.decodeReaction(reactionMessage.body)
                val kind = MessagePayloadCodec.decodeReactionKind(reactionMessage.body)
                if (payload != null && kind != null) {
                    val key = ReactionStateKey(
                        targetMessageId = payload.targetMessageId,
                        senderWalletPubkey = reactionMessage.senderWalletPubkey
                    )
                    if (kind == MessageReactionKind.REMOVE) {
                        acc.remove(key)
                    } else {
                        acc[key] = kind
                    }
                }
                acc
            }
    }
    val reactionsByMessageId = remember(latestReactionByTargetAndSender) {
        latestReactionByTargetAndSender.entries
            .groupBy({ it.key.targetMessageId }, { it.value })
            .mapValues { (_, kinds) ->
                MessageReactionKind.selectableCases.mapNotNull { reactionKind ->
                    val count = kinds.count { it == reactionKind }
                    if (count > 0) ReactionBadgeSummary(reactionKind, count) else null
                }
            }
    }
    val coroutineScope = rememberCoroutineScope()
    var draftMessage by rememberSaveable(thread.conversationId) { mutableStateOf("") }
    var isSending by remember { mutableStateOf(false) }
    var sendError by remember { mutableStateOf<String?>(null) }
    var showRequestSheet by remember { mutableStateOf(false) }
    var showPaymentOptions by remember { mutableStateOf(false) }
    var showAttachmentOptions by remember { mutableStateOf(false) }
    var showAddContactDialog by remember { mutableStateOf(false) }
    var showContactDetail by remember { mutableStateOf(false) }
    var activeBlock by remember(thread.conversationId, displayLightningAddress) {
        mutableStateOf<com.split.android.data.messages.MessagingBlockedUser?>(null)
    }
    var pendingPayRequest by remember { mutableStateOf<Pair<StoredMessage, PaymentRequestMessagePayload>?>(null) }
    var pendingReactionMessage by remember { mutableStateOf<StoredMessage?>(null) }
    var openingAttachmentId by remember { mutableStateOf<String?>(null) }
    var previewImageAttachment by remember { mutableStateOf<PreviewImageAttachment?>(null) }
    var contactNameDraft by rememberSaveable(displayLightningAddress) { mutableStateOf("") }
    var retryingFailedMessageId by remember(thread.conversationId) { mutableStateOf<String?>(null) }
    val threadProfilePicUrl = recipientMetadata?.profilePicUrl?.trim()?.ifBlank { null }
    val hasThreadProfileImage = !threadProfilePicUrl.isNullOrBlank()
    val messageListState = rememberLazyListState()
    val density = LocalDensity.current
    val isKeyboardVisible = WindowInsets.ime.getBottom(density) > 0
    val timestampRevealWidth = 74.dp
    val timestampRevealWidthPx = with(density) { timestampRevealWidth.toPx() }
    var timestampRevealOffsetPx by remember(thread.conversationId) { mutableFloatStateOf(0f) }
    val animatedTimestampRevealOffsetPx by animateFloatAsState(
        targetValue = timestampRevealOffsetPx,
        label = "Message timestamp reveal"
    )
    val timestampRevealOffset = with(density) { animatedTimestampRevealOffsetPx.toDp() }
    val timestampRevealProgress = if (timestampRevealWidthPx > 0f) {
        (animatedTimestampRevealOffsetPx / timestampRevealWidthPx).coerceIn(0f, 1f)
    } else {
        0f
    }
    val timestampRevealDraggableState = rememberDraggableState { delta ->
        val isHorizontalRevealActive = delta < 0f || timestampRevealOffsetPx > 0f
        if (isHorizontalRevealActive) {
            timestampRevealOffsetPx = (timestampRevealOffsetPx - delta)
                .coerceIn(0f, timestampRevealWidthPx)
        }
    }
    val threadListItemCount = remember(visibleMessages) {
        messageThreadListItemCount(visibleMessages)
    }
    var previousVisibleMessageCount by remember(thread.conversationId) { mutableStateOf(0) }
    val isMessagingBlocked = activeBlock != null

    suspend fun refreshBlockState() {
        val normalizedLightningAddress = displayLightningAddress?.trim()?.lowercase().orEmpty()
        if (normalizedLightningAddress.isEmpty()) {
            activeBlock = null
            return
        }

        runCatching {
            rootViewModel.fetchMessagingBlocks()
        }.onSuccess { blocks ->
            activeBlock = blocks.firstOrNull { block ->
                block.blockedWalletPubkey.trim().equals(thread.conversationId.trim(), ignoreCase = true) ||
                    block.normalizedLightningAddress == normalizedLightningAddress
            }
        }.onFailure { error ->
            sendError = error.message ?: "Failed to load blocked user state."
        }
    }

    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri == null || thread.lightningAddress.isNullOrBlank() || isMessagingBlocked) {
            return@rememberLauncherForActivityResult
        }
        coroutineScope.launch {
            sendSelectedAttachment(
                context = context,
                uri = uri,
                lightningAddress = thread.lightningAddress,
                rootViewModel = rootViewModel,
                onStart = {
                    isSending = true
                    sendError = null
                },
                onFinish = { isSending = false },
                onError = { sendError = it }
            )
        }
    }

    val videoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri == null || thread.lightningAddress.isNullOrBlank() || isMessagingBlocked) {
            return@rememberLauncherForActivityResult
        }
        coroutineScope.launch {
            sendSelectedAttachment(
                context = context,
                uri = uri,
                lightningAddress = thread.lightningAddress,
                rootViewModel = rootViewModel,
                onStart = {
                    isSending = true
                    sendError = null
                },
                onFinish = { isSending = false },
                onError = { sendError = it }
            )
        }
    }

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null || thread.lightningAddress.isNullOrBlank() || isMessagingBlocked) {
            return@rememberLauncherForActivityResult
        }
        coroutineScope.launch {
            sendSelectedAttachment(
                context = context,
                uri = uri,
                lightningAddress = thread.lightningAddress,
                rootViewModel = rootViewModel,
                onStart = {
                    isSending = true
                    sendError = null
                },
                onFinish = { isSending = false },
                onError = { sendError = it }
            )
        }
    }

    BackHandler(onBack = onBack)

    DisposableEffect(thread.conversationId) {
        MessageThreadPresenceTracker.enterConversation(thread.conversationId)
        onDispose {
            MessageThreadPresenceTracker.leaveConversation(thread.conversationId)
        }
    }

    LaunchedEffect(thread.conversationId) {
        rootViewModel.markConversationAsRead(thread.conversationId)
        rootViewModel.syncMessages(force = true)
        rootViewModel.syncOutgoingStatuses(force = true)
        refreshBlockState()
    }

    LaunchedEffect(displayLightningAddress, thread.conversationId) {
        refreshBlockState()
    }

    LaunchedEffect(thread.conversationId, visibleMessages.size) {
        rootViewModel.markConversationAsRead(thread.conversationId)

        if (visibleMessages.isEmpty()) {
            previousVisibleMessageCount = 0
            return@LaunchedEffect
        }

        val lastVisibleMessageIndex = (threadListItemCount - 1).coerceAtLeast(0)
        if (previousVisibleMessageCount == 0) {
            messageListState.scrollToItem(lastVisibleMessageIndex)
        } else if (visibleMessages.size != previousVisibleMessageCount) {
            messageListState.animateScrollToItem(lastVisibleMessageIndex)
        }

        previousVisibleMessageCount = visibleMessages.size
    }

    LaunchedEffect(thread.conversationId, isKeyboardVisible) {
        if (isKeyboardVisible && visibleMessages.isNotEmpty()) {
            messageListState.animateScrollToItem((threadListItemCount - 1).coerceAtLeast(0))
        }
    }

    pendingPayRequest?.let { (originalMessage, payload) ->
        AlertDialog(
            onDismissRequest = { pendingPayRequest = null },
            confirmButton = {
                Button(
                    onClick = {
                        pendingPayRequest = null
                        coroutineScope.launch {
                            isSending = true
                            sendError = null
                            runCatching {
                                rootViewModel.payMessageRequest(
                                    originalRequest = originalMessage,
                                    invoice = payload.invoice
                                )
                            }.onFailure { error ->
                                sendError = error.message ?: "Failed to pay request."
                            }
                            isSending = false
                        }
                    }
                ) {
                    Text("Pay")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { pendingPayRequest = null }) {
                    Text("Cancel")
                }
            },
            title = { Text("Pay Request") },
            text = {
                Text("Pay ${formatSats(payload.amountSats)} sats in the existing wallet flow?")
            }
        )
    }

    if (showContactDetail && !displayLightningAddress.isNullOrBlank()) {
        if (savedContact != null) {
            ContactDetailScreen(
                rootViewModel = rootViewModel,
                contact = ActiveContact(
                    id = savedContact.id,
                    name = savedContact.name,
                    paymentIdentifier = savedContact.paymentIdentifier
                ),
                onBack = {
                    showContactDetail = false
                    coroutineScope.launch {
                        refreshBlockState()
                    }
                },
                onUpdated = { },
                onDeleted = { showContactDetail = false },
                onOpenSendOverlay = onOpenSendOverlay,
                onOpenThread = { _, _, _ -> showContactDetail = false },
                onCompose = { _ -> showContactDetail = false }
            )
        } else {
            MessageParticipantDetailScreen(
                rootViewModel = rootViewModel,
                participant = MessageParticipantDescriptor(
                    title = displayTitle,
                    lightningAddress = displayLightningAddress,
                    walletPubkey = thread.conversationId,
                    profilePicUrl = threadProfilePicUrl
                ),
                onBack = {
                    showContactDetail = false
                    coroutineScope.launch {
                        refreshBlockState()
                    }
                },
                onOpenSendOverlay = onOpenSendOverlay,
                onOpenThread = { _, _, _ -> showContactDetail = false },
                onCompose = { _ -> showContactDetail = false }
            )
        }
        return
    }

    pendingReactionMessage?.let { reactionMessage ->
        ReactionPickerDialog(
            onDismiss = { pendingReactionMessage = null },
            onSelect = { reactionKind ->
                pendingReactionMessage = null
                val destination = thread.lightningAddress ?: return@ReactionPickerDialog
                coroutineScope.launch {
                    runCatching {
                        rootViewModel.sendReactionMessage(
                            lightningAddress = destination,
                            targetMessageId = reactionMessage.id,
                            reactionKind = reactionKind
                        )
                    }.onFailure { error ->
                        sendError = error.message ?: "Failed to send reaction."
                    }
                }
            }
        )
    }

    previewImageAttachment?.let { attachment ->
        ImageAttachmentPreviewDialog(
            attachment = attachment,
            onDismiss = { previewImageAttachment = null }
        )
    }

    if (showPaymentOptions && !thread.lightningAddress.isNullOrBlank()) {
        AlertDialog(
            onDismissRequest = { showPaymentOptions = false },
            confirmButton = {
                Button(
                    onClick = {
                        showPaymentOptions = false
                        displayLightningAddress?.let { lightningAddress ->
                            onOpenSendOverlay(
                                SendOverlayConfig(initialDestination = lightningAddress)
                            )
                        }
                    }
                ) {
                    Text("Send")
                }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            showPaymentOptions = false
                            showRequestSheet = true
                        }
                    ) {
                        Text("Request")
                    }

                    OutlinedButton(onClick = { showPaymentOptions = false }) {
                        Text("Cancel")
                    }
                }
            },
            title = { Text("Bitcoin") },
            text = {
                Text("Send or request Bitcoin directly in this encrypted conversation.")
            }
        )
    }

    if (showAttachmentOptions && !thread.lightningAddress.isNullOrBlank()) {
        AlertDialog(
            onDismissRequest = { showAttachmentOptions = false },
            confirmButton = {},
            dismissButton = {
                OutlinedButton(onClick = { showAttachmentOptions = false }) {
                    Text("Cancel")
                }
            },
            title = { Text("Send Attachment") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Choose a photo, video, or file to send as an encrypted attachment.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.74f)
                    )

                    OutlinedButton(
                        onClick = {
                            showAttachmentOptions = false
                            imagePicker.launch("image/*")
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Choose Photo")
                    }

                    OutlinedButton(
                        onClick = {
                            showAttachmentOptions = false
                            videoPicker.launch("video/*")
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Choose Video")
                    }

                    OutlinedButton(
                        onClick = {
                            showAttachmentOptions = false
                            filePicker.launch(arrayOf("*/*"))
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Choose File")
                    }
                }
            }
        )
    }

    if (showRequestSheet && !thread.lightningAddress.isNullOrBlank()) {
        Dialog(onDismissRequest = { showRequestSheet = false }) {
            RequestPaymentComposer(
                lightningAddress = thread.lightningAddress,
                onCancel = { showRequestSheet = false },
                onSend = { amountSats, note ->
                    coroutineScope.launch {
                        isSending = true
                        sendError = null
                        runCatching {
                            rootViewModel.sendPaymentRequestMessage(
                                lightningAddress = thread.lightningAddress,
                                amountSats = amountSats,
                                note = note
                            )
                        }.onSuccess {
                            showRequestSheet = false
                        }.onFailure { error ->
                            sendError = error.message ?: "Failed to send payment request."
                        }
                        isSending = false
                    }
                }
            )
        }
    }

    if (showAddContactDialog && !displayLightningAddress.isNullOrBlank()) {
        AlertDialog(
            onDismissRequest = { showAddContactDialog = false },
            confirmButton = {
                Button(
                    onClick = {
                        val paymentIdentifier = displayLightningAddress
                        coroutineScope.launch {
                            runCatching {
                                rootViewModel.addWalletContact(
                                    name = contactNameDraft,
                                    paymentIdentifier = paymentIdentifier
                                )
                            }.onSuccess {
                                showAddContactDialog = false
                                contactNameDraft = ""
                            }.onFailure { error ->
                                sendError = error.message ?: "Failed to add contact."
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
                        text = displayLightningAddress.orEmpty(),
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

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .statusBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.clickable(onClick = onBack),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = "Back",
                    tint = Color.White,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = "Back",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White,
                    fontWeight = FontWeight.Medium
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            Row(
                modifier = if (!displayLightningAddress.isNullOrBlank()) {
                    Modifier.clickable { showContactDetail = true }
                } else {
                    Modifier
                },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (hasThreadProfileImage) {
                    AvatarCircle(
                        label = displayTitle,
                        profilePicUrl = threadProfilePicUrl,
                        size = 38.dp
                    )
                }

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = displayTitle,
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "Encrypted conversation",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.52f)
                    )
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            if (!displayLightningAddress.isNullOrBlank()) {
                Row(
                    modifier = Modifier.widthIn(min = 52.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ThreadHeaderIconButton(
                        icon = Icons.Rounded.Bolt,
                        contentDescription = "Bitcoin actions",
                        enabled = !isMessagingBlocked,
                        onClick = { showPaymentOptions = true }
                    )

                    if (savedContact == null) {
                        ThreadHeaderIconButton(
                            icon = Icons.Rounded.Add,
                            contentDescription = "Add contact",
                            enabled = !isMessagingBlocked,
                            onClick = {
                                contactNameDraft = displayTitle.takeIf { it != displayLightningAddress }.orEmpty()
                                showAddContactDialog = true
                            }
                        )
                    }
                }
            } else {
                Spacer(modifier = Modifier.width(52.dp))
            }
        }

        if (isMessagingBlocked) {
            Text(
                text = "You blocked this user. Unblock them from their detail view to message again.",
                modifier = Modifier.padding(horizontal = 18.dp),
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.68f)
            )
        }

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .draggable(
                    state = timestampRevealDraggableState,
                    orientation = Orientation.Horizontal,
                    enabled = visibleMessages.isNotEmpty(),
                    onDragStopped = { timestampRevealOffsetPx = 0f }
                ),
            state = messageListState,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 14.dp,
                top = 12.dp,
                end = 14.dp,
                bottom = 12.dp
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            visibleMessages.forEachIndexed { index, message ->
                if (shouldShowMessageDaySeparator(
                        message = message,
                        previousMessage = visibleMessages.getOrNull(index - 1)
                    )
                ) {
                    item(key = "day-separator-${message.id}") {
                        MessageDaySeparator(timestampMillis = message.createdAtMillis)
                    }
                }

                item(key = message.id) {
                    MessageBubble(
                        message = message,
                        isPaidRequest = message.id in paidRequestMessageIds,
                        reactions = reactionsByMessageId[message.id].orEmpty(),
                        isAttachmentBusy = openingAttachmentId == message.id,
                        isRetryingFailedMessage = retryingFailedMessageId == message.id,
                        timestampRevealOffset = timestampRevealOffset,
                        timestampRevealWidth = timestampRevealWidth,
                        timestampRevealProgress = timestampRevealProgress,
                        hasCachedAttachment = { payload -> payload.attachmentId in cachedAttachmentIds },
                        cachedAttachmentData = { payload -> rootViewModel.cachedAttachmentData(payload) },
                        onLongPress = {
                            if (!thread.lightningAddress.isNullOrBlank() && !isMessagingBlocked) {
                                pendingReactionMessage = message
                            }
                        },
                        onOpenAttachment = { payload ->
                            if (openingAttachmentId == message.id) return@MessageBubble
                            coroutineScope.launch {
                                openingAttachmentId = message.id
                                sendError = null
                                runCatching {
                                    val data = rootViewModel.prepareAttachmentData(
                                        payload = payload,
                                        shouldMarkReceived = message.isIncoming
                                    )
                                    if (payload.mimeType.lowercase().startsWith("image/")) {
                                        previewImageAttachment = PreviewImageAttachment(
                                            fileName = payload.fileName,
                                            data = data
                                        )
                                    } else {
                                        openAttachmentInExternalApp(context, payload, data)
                                    }
                                }.onFailure { error ->
                                    sendError = error.message ?: "Failed to prepare attachment."
                                }
                                openingAttachmentId = null
                            }
                        },
                        onPayRequest = { payload ->
                            pendingPayRequest = message to payload
                        },
                        onRetryFailedMessage = {
                            if (retryingFailedMessageId == message.id) {
                                return@MessageBubble
                            }

                            coroutineScope.launch {
                                retryingFailedMessageId = message.id
                                sendError = null
                                runCatching {
                                    rootViewModel.resendStoredMessage(message)
                                }.onSuccess { resentResult ->
                                    if (resentResult == null) {
                                        sendError = if (message.messageType == "attachment") {
                                            "Couldn't resend this attachment right now."
                                        } else {
                                            "Couldn't resend this message right now."
                                        }
                                    }
                                }.onFailure { error ->
                                    sendError = error.message ?: "Failed to resend message."
                                }
                                retryingFailedMessageId = null
                            }
                        }
                    )
                }
            }
        }

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .padding(bottom = if (isKeyboardVisible) 0.dp else bottomInset),
            color = Color(0xFF101013)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 1.dp, start = 18.dp, end = 18.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(Color.White.copy(alpha = 0.08f))
                )

                if (!sendError.isNullOrBlank()) {
                    Text(
                        text = sendError!!,
                        style = MaterialTheme.typography.bodyMedium,
                        color = SplitBrandPink
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    ThreadComposerUtilityButton(
                        icon = Icons.Rounded.Add,
                        contentDescription = "Send attachment",
                        enabled = !thread.lightningAddress.isNullOrBlank() && !isSending && !isMessagingBlocked,
                        onClick = { showAttachmentOptions = true }
                    )

                    Surface(
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(20.dp),
                        color = Color.White.copy(alpha = 0.08f)
                    ) {
                        TextField(
                            value = draftMessage,
                            onValueChange = { draftMessage = it },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("iMessage") },
                            maxLines = 5,
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                disabledContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                                disabledIndicatorColor = Color.Transparent,
                                cursorColor = Color.White,
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedPlaceholderColor = Color.White.copy(alpha = 0.46f),
                                unfocusedPlaceholderColor = Color.White.copy(alpha = 0.46f)
                            )
                        )
                    }

                    Surface(
                        modifier = Modifier.size(42.dp),
                        shape = CircleShape,
                        color = if (!isSending &&
                            !thread.lightningAddress.isNullOrBlank() &&
                            !isMessagingBlocked &&
                            draftMessage.trim().isNotEmpty()
                        ) {
                            SplitBrandPink
                        } else {
                            Color.White.copy(alpha = 0.12f)
                        }
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .clickable(
                                    enabled = !isSending &&
                                        !thread.lightningAddress.isNullOrBlank() &&
                                        !isMessagingBlocked &&
                                        draftMessage.trim().isNotEmpty()
                                ) {
                                    val destination = thread.lightningAddress ?: return@clickable
                                    coroutineScope.launch {
                                        isSending = true
                                        sendError = null
                                        runCatching {
                                            rootViewModel.sendTextMessage(
                                                lightningAddress = destination,
                                                plaintext = draftMessage
                                            )
                                        }.onSuccess {
                                            draftMessage = ""
                                        }.onFailure { error ->
                                            sendError = error.message ?: "Failed to send message."
                                        }
                                        isSending = false
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            if (isSending) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    color = Color.White,
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Rounded.Send,
                                    contentDescription = "Send message",
                                    tint = if (!thread.lightningAddress.isNullOrBlank() &&
                                        !isMessagingBlocked &&
                                        draftMessage.trim().isNotEmpty()
                                    ) {
                                        Color.White
                                    } else {
                                        Color.White.copy(alpha = 0.30f)
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MessageDaySeparator(timestampMillis: Long) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color.Transparent
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp, bottom = 2.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                shape = RoundedCornerShape(999.dp),
                color = Color.White.copy(alpha = 0.055f)
            ) {
                Text(
                    text = formatMessageDaySeparator(timestampMillis),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.48f),
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun MessageBubble(
    message: StoredMessage,
    isPaidRequest: Boolean,
    reactions: List<ReactionBadgeSummary>,
    isAttachmentBusy: Boolean,
    isRetryingFailedMessage: Boolean,
    timestampRevealOffset: Dp,
    timestampRevealWidth: Dp,
    timestampRevealProgress: Float,
    hasCachedAttachment: (AttachmentMessagePayload) -> Boolean,
    cachedAttachmentData: (AttachmentMessagePayload) -> ByteArray?,
    onLongPress: () -> Unit,
    onOpenAttachment: (AttachmentMessagePayload) -> Unit,
    onPayRequest: (PaymentRequestMessagePayload) -> Unit,
    onRetryFailedMessage: () -> Unit
) {
    val isIncoming = message.isIncoming
    val bubbleColor = if (isIncoming) {
        SplitBrandBlue
    } else {
        SplitBrandPink
    }
    val paymentRequestPayload = if (message.messageType == "payment_request") {
        MessagePayloadCodec.decodePaymentRequest(message.body)
    } else {
        null
    }
    val attachmentPayload = if (message.messageType == "attachment") {
        MessagePayloadCodec.decodeAttachmentPayload(message.body)
    } else {
        null
    }
    val bubbleClickAction: (() -> Unit)? = when {
        paymentRequestPayload != null && message.isIncoming && !isPaidRequest -> {
            { onPayRequest(paymentRequestPayload) }
        }

        attachmentPayload != null -> {
            { onOpenAttachment(attachmentPayload) }
        }

        else -> null
    }

    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.CenterEnd
    ) {
        Text(
            text = formatMessageRevealTime(message.createdAtMillis),
            modifier = Modifier
                .width(timestampRevealWidth)
                .padding(end = 2.dp)
                .offset(x = 10.dp * (1f - timestampRevealProgress))
                .alpha(timestampRevealProgress),
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.48f),
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            textAlign = TextAlign.End
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .offset(x = -timestampRevealOffset),
            horizontalArrangement = if (isIncoming) Arrangement.Start else Arrangement.End
        ) {
            Box(
                modifier = Modifier.padding(bottom = if (reactions.isEmpty()) 0.dp else 12.dp)
            ) {
                Column(
                    horizontalAlignment = if (isIncoming) Alignment.Start else Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Surface(
                        modifier = Modifier
                            .widthIn(max = 280.dp)
                            .combinedClickable(
                                onClick = { bubbleClickAction?.invoke() },
                                onLongClick = onLongPress
                            ),
                        shape = RoundedCornerShape(22.dp),
                        color = bubbleColor,
                    ) {
                        when {
                            paymentRequestPayload != null -> {
                                PaymentRequestBubble(
                                    message = message,
                                    payload = paymentRequestPayload,
                                    isPaid = isPaidRequest
                                )
                            }

                            attachmentPayload != null -> {
                                AttachmentMessageBubble(
                                    payload = attachmentPayload,
                                    hasCachedAttachment = hasCachedAttachment(attachmentPayload),
                                    cachedAttachmentData = cachedAttachmentData(attachmentPayload),
                                    isBusy = isAttachmentBusy
                                )
                            }

                            else -> {
                                StandardMessageBubble(message = message)
                            }
                        }
                    }

                    if (message.hasFailedDelivery) {
                        FailedDeliveryCaption(
                            isRetrying = isRetryingFailedMessage,
                            onRetry = onRetryFailedMessage
                        )
                    }
                }

                if (reactions.isNotEmpty()) {
                    ReactionBadge(
                        reactions = reactions,
                        modifier = Modifier
                            .align(if (isIncoming) Alignment.BottomStart else Alignment.BottomEnd)
                            .offset(x = if (isIncoming) (-6).dp else 6.dp, y = 6.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun MessagesBackgroundAtmosphere() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(88.dp)
            .background(
                brush = Brush.horizontalGradient(
                    colors = listOf(
                        SplitBrandBlue.copy(alpha = 0.20f),
                        SplitBrandPink.copy(alpha = 0.16f)
                    )
                ),
                shape = RoundedCornerShape(32.dp)
            )
    )
}

@Composable
private fun MessagesActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    iconTint: Color = Color.White,
    border: Color = Color.White.copy(alpha = 0.14f)
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
            border = BorderStroke(1.dp, border)
        ) {}

        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = iconTint,
            modifier = Modifier.size(18.dp)
        )
    }
}

@Composable
private fun ContactDetailActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    isActive: Boolean = false,
    isBusy: Boolean = false
) {
    val backgroundColor = when {
        !enabled -> Color.White.copy(alpha = 0.04f)
        isActive -> SplitBrandPink.copy(alpha = 0.18f)
        else -> Color.White.copy(alpha = 0.08f)
    }
    val borderColor = when {
        !enabled -> Color.White.copy(alpha = 0.08f)
        isActive -> SplitBrandPink.copy(alpha = 0.34f)
        else -> Color.White.copy(alpha = 0.14f)
    }
    val iconTint = when {
        !enabled -> Color.White.copy(alpha = 0.28f)
        isActive -> SplitBrandPink
        else -> Color.White
    }
    val labelColor = if (enabled) {
        Color.White.copy(alpha = 0.82f)
    } else {
        Color.White.copy(alpha = 0.34f)
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            modifier = Modifier
                .size(66.dp)
                .clip(CircleShape)
                .background(backgroundColor)
                .clickable(enabled = enabled && !isBusy, onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                shape = CircleShape,
                color = Color.Transparent,
                border = BorderStroke(1.dp, borderColor)
            ) {}

            if (isBusy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = Color.White,
                    strokeWidth = 2.dp
                )
            } else {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    tint = iconTint,
                    modifier = Modifier.size(24.dp)
                )
            }
        }

        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = labelColor,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun MessagesComposeButton(
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier.size(42.dp),
        shape = CircleShape,
        color = SplitBrandPink,
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.20f))
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Rounded.Edit,
                contentDescription = "Compose message",
                tint = Color.White
            )
        }
    }
}

@Composable
private fun ThreadHeaderIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier.size(34.dp),
        shape = CircleShape,
        color = Color.White.copy(alpha = 0.08f)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = if (enabled) Color.White else Color.White.copy(alpha = 0.18f),
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
private fun ThreadComposerUtilityButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(34.dp)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (enabled) Color.White.copy(alpha = 0.86f) else Color.White.copy(alpha = 0.18f),
            modifier = Modifier.size(26.dp)
        )
    }
}

@Composable
private fun StandardMessageBubble(message: StoredMessage) {
    Text(
        text = MessagePayloadCodec.bubbleText(message),
        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
        style = MaterialTheme.typography.bodyLarge,
        color = Color.White
    )
}

@Composable
private fun PaymentRequestBubble(
    message: StoredMessage,
    payload: PaymentRequestMessagePayload,
    isPaid: Boolean
) {
    Column(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Rounded.Bolt,
                contentDescription = null,
                tint = Color.White
            )
            Spacer(modifier = Modifier.size(8.dp))
            Text(
                text = "Payment Request",
                style = MaterialTheme.typography.titleSmall,
                color = Color.White,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.weight(1f))

            if (isPaid) {
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = Color.White.copy(alpha = 0.16f)
                ) {
                    Text(
                        text = "Paid",
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        Text(
            text = "${formatSats(payload.amountSats)} sats",
            style = MaterialTheme.typography.headlineSmall,
            color = Color.White,
            fontWeight = FontWeight.Black
        )

        payload.requesterLightningAddress?.takeIf { it.isNotBlank() }?.let { address ->
            Text(
                text = address,
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.72f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        if (isPaid) {
            Text(
                text = "Invoice paid",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.78f),
                fontWeight = FontWeight.SemiBold
            )
        } else if (message.isIncoming) {
            Text(
                text = "Tap to pay in the existing send flow",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.78f),
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun AttachmentMessageBubble(
    payload: AttachmentMessagePayload,
    hasCachedAttachment: Boolean,
    cachedAttachmentData: ByteArray?,
    isBusy: Boolean
) {
    val context = LocalContext.current
    val isImage = payload.mimeType.lowercase().startsWith("image/")
    val isVideo = payload.mimeType.lowercase().startsWith("video/")
    val imageBitmap = remember(cachedAttachmentData) {
        cachedAttachmentData?.takeIf { isImage }?.let { data ->
            decodeAttachmentImageBitmap(data)
        }
    }

    Column(
        modifier = Modifier
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (imageBitmap != null) {
            Image(
                bitmap = imageBitmap,
                contentDescription = payload.fileName,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(170.dp)
                    .clip(RoundedCornerShape(16.dp)),
                contentScale = ContentScale.Crop
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = when {
                    isImage -> Icons.Rounded.Image
                    isVideo -> Icons.Rounded.SmartDisplay
                    else -> Icons.Rounded.AttachFile
                },
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(16.dp)
            )

            Text(
                text = when {
                    isImage -> "Photo"
                    isVideo -> "Video"
                    else -> "Attachment"
                },
                style = MaterialTheme.typography.titleSmall,
                color = Color.White,
                fontWeight = FontWeight.Bold
            )
        }

        Text(
            text = payload.fileName,
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White,
            fontWeight = FontWeight.SemiBold
        )

        Text(
            text = android.text.format.Formatter.formatShortFileSize(context, payload.sizeBytes.toLong()),
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.75f)
        )

        Text(
            text = when {
                isBusy -> "Preparing attachment..."
                hasCachedAttachment && isImage -> "Tap to view photo"
                hasCachedAttachment && isVideo -> "Tap to play video"
                hasCachedAttachment -> "Tap to open attachment"
                isImage -> "Tap to download photo"
                isVideo -> "Tap to download video"
                else -> "Tap to download attachment"
            },
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.72f)
        )
    }
}

@Composable
private fun RequestPaymentComposer(
    lightningAddress: String,
    onCancel: () -> Unit,
    onSend: (Long, String?) -> Unit
) {
    var amountText by rememberSaveable(lightningAddress) { mutableStateOf("") }
    var noteText by rememberSaveable(lightningAddress) { mutableStateOf("") }
    val parsedAmount = amountText.trim().toLongOrNull()?.takeIf { it > 0 }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = Color(0xFF16161A),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.10f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "Request Payment",
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                fontWeight = FontWeight.Bold
            )

            TextField(
                value = amountText,
                onValueChange = { amountText = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Amount (sats)") }
            )

            TextField(
                value = noteText,
                onValueChange = { noteText = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Note (optional)") }
            )

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Cancel")
                }

                Button(
                    onClick = { onSend(parsedAmount ?: return@Button, noteText.trim().ifBlank { null }) },
                    modifier = Modifier.weight(1f),
                    enabled = parsedAmount != null
                ) {
                    Text("Send Request")
                }
            }
        }
    }
}

@Composable
private fun ReactionPickerDialog(
    onDismiss: () -> Unit,
    onSelect: (MessageReactionKind) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
        title = {
            Text("React")
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                MessageReactionKind.selectableCases.chunked(3).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { reactionKind ->
                            OutlinedButton(
                                onClick = { onSelect(reactionKind) },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(reactionKind.badgeText)
                            }
                        }
                    }
                }

                OutlinedButton(
                    onClick = { onSelect(MessageReactionKind.REMOVE) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Remove Reaction")
                }
            }
        }
    )
}

@Composable
private fun ImageAttachmentPreviewDialog(
    attachment: PreviewImageAttachment,
    onDismiss: () -> Unit
) {
    val imageBitmap = remember(attachment.data) {
        decodeAttachmentImageBitmap(attachment.data)
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = Color.Black.copy(alpha = 0.96f),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.10f))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = attachment.fileName,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    OutlinedButton(onClick = onDismiss) {
                        Text("Close")
                    }
                }

                if (imageBitmap != null) {
                    Image(
                        bitmap = imageBitmap,
                        contentDescription = attachment.fileName,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 240.dp, max = 560.dp)
                            .clip(RoundedCornerShape(18.dp)),
                        contentScale = ContentScale.Fit
                    )
                } else {
                    Text(
                        text = "Unable to preview image.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.72f)
                    )
                }
            }
        }
    }
}

@Composable
private fun ReactionBadge(
    reactions: List<ReactionBadgeSummary>,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(999.dp),
        color = Color.Black.copy(alpha = 0.82f),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.16f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            reactions.forEach { reaction ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = reaction.kind.badgeText,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                    if (reaction.count > 1) {
                        Text(
                            text = "${reaction.count}",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.82f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ContactsScreen(
    rootViewModel: SplitRootViewModel,
    contactsByPaymentIdentifier: Map<String, com.split.android.data.wallet.WalletContact>,
    recipientMetadataByConversationId: Map<String, MessageRecipientMetadata>,
    onBack: () -> Unit,
    onOpenBlockedUsers: () -> Unit,
    onOpenContact: (ActiveContact) -> Unit
) {
    BackHandler(onBack = onBack)

    val contacts = remember(contactsByPaymentIdentifier) {
        contactsByPaymentIdentifier.values.sortedBy { it.name.lowercase() }
    }
    var showAddDialog by remember { mutableStateOf(false) }
    var addName by rememberSaveable { mutableStateOf("") }
    var addPaymentIdentifier by rememberSaveable { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val coroutineScope = rememberCoroutineScope()

    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            confirmButton = {
                Button(
                    onClick = {
                        coroutineScope.launch {
                            runCatching {
                                rootViewModel.addWalletContact(
                                    name = addName,
                                    paymentIdentifier = addPaymentIdentifier
                                )
                            }.onSuccess {
                                showAddDialog = false
                                addName = ""
                                addPaymentIdentifier = ""
                                errorMessage = null
                            }.onFailure { error ->
                                errorMessage = error.message ?: "Failed to add contact."
                            }
                        }
                    },
                    enabled = addName.trim().isNotEmpty() && addPaymentIdentifier.trim().isNotEmpty()
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showAddDialog = false }) {
                    Text("Cancel")
                }
            },
            title = { Text("Add Contact") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    TextField(
                        value = addName,
                        onValueChange = { addName = it },
                        singleLine = true,
                        label = { Text("Name") }
                    )
                    TextField(
                        value = addPaymentIdentifier,
                        onValueChange = { addPaymentIdentifier = it },
                        singleLine = true,
                        label = { Text("Lightning Address") }
                    )
                    if (!errorMessage.isNullOrBlank()) {
                        Text(
                            text = errorMessage!!,
                            style = MaterialTheme.typography.bodySmall,
                            color = SplitBrandPink
                        )
                    }
                }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .statusBarsPadding()
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            MessagesActionButton(
                icon = Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = "Back",
                onClick = onBack
            )

            Text(
                text = "Contacts",
                modifier = Modifier.padding(start = 10.dp),
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White,
                fontWeight = FontWeight.Black
            )

            Spacer(modifier = Modifier.weight(1f))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MessagesActionButton(
                    icon = Icons.Rounded.Block,
                    contentDescription = "Blocked users",
                    onClick = onOpenBlockedUsers
                )

                MessagesActionButton(
                    icon = Icons.Rounded.Add,
                    contentDescription = "Add contact",
                    onClick = { showAddDialog = true }
                )
            }
        }

        Text(
            text = "Saved Lightning identities.",
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.68f)
        )

        if (contacts.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
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
                        Text(
                            text = "No Contacts Yet",
                            style = MaterialTheme.typography.titleLarge,
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Saved wallet contacts will appear here.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.62f)
                        )
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(contacts, key = { it.id }) { contact ->
                    val profilePicUrl = recipientMetadataByConversationId.values.firstOrNull {
                        it.lightningAddress?.trim()?.lowercase() ==
                            contact.paymentIdentifier.trim().lowercase()
                    }?.profilePicUrl

                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onOpenContact(
                                    ActiveContact(
                                        id = contact.id,
                                        name = contact.name,
                                        paymentIdentifier = contact.paymentIdentifier
                                    )
                                )
                            },
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
                            AvatarCircle(
                                label = contact.name,
                                profilePicUrl = profilePicUrl
                            )

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = contact.name,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = Color.White,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = contact.paymentIdentifier,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.White.copy(alpha = 0.58f)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ContactDetailScreen(
    rootViewModel: SplitRootViewModel,
    contact: ActiveContact,
    onBack: () -> Unit,
    onUpdated: (ActiveContact) -> Unit,
    onDeleted: () -> Unit,
    onOpenSendOverlay: (SendOverlayConfig) -> Unit,
    onOpenThread: (String, String, String) -> Unit,
    onCompose: (String) -> Unit
) {
    BackHandler(onBack = onBack)

    var currentContact by remember(contact.id, contact.name, contact.paymentIdentifier) {
        mutableStateOf(contact)
    }
    val storedMessages by rootViewModel.storedMessages.collectAsStateWithLifecycle()
    val recipientMetadataByConversationId by rootViewModel.recipientMetadataByConversationId.collectAsStateWithLifecycle()
    val conversations = remember(storedMessages) {
        rootViewModel.conversationPreviews()
    }
    val existingConversation = conversations.firstOrNull {
        it.lightningAddress?.trim()?.lowercase() == currentContact.paymentIdentifier.trim().lowercase()
    }
    val profilePicUrl = recipientMetadataByConversationId.values.firstOrNull {
        it.lightningAddress?.trim()?.lowercase() == currentContact.paymentIdentifier.trim().lowercase()
    }?.profilePicUrl
    var showEditDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var editName by rememberSaveable(currentContact.id) { mutableStateOf(currentContact.name) }
    var editPaymentIdentifier by rememberSaveable(currentContact.id) { mutableStateOf(currentContact.paymentIdentifier) }
    var actionError by remember { mutableStateOf<String?>(null) }
    var activeBlock by remember(currentContact.paymentIdentifier) {
        mutableStateOf<com.split.android.data.messages.MessagingBlockedUser?>(null)
    }
    var isUpdatingBlockState by remember { mutableStateOf(false) }
    var showBlockConfirmation by remember { mutableStateOf(false) }
    var showUnblockConfirmation by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    val isMessagingBlocked = activeBlock != null

    suspend fun refreshBlockState() {
        val normalizedLightningAddress = currentContact.paymentIdentifier.trim().lowercase()
        runCatching {
            rootViewModel.fetchMessagingBlocks()
        }.onSuccess { blocks ->
            activeBlock = blocks.firstOrNull {
                it.normalizedLightningAddress == normalizedLightningAddress
            }
        }.onFailure { error ->
            actionError = error.message ?: "Failed to load blocked user state."
        }
    }

    LaunchedEffect(currentContact.paymentIdentifier) {
        refreshBlockState()
    }

    if (showEditDialog) {
        AlertDialog(
            onDismissRequest = { showEditDialog = false },
            confirmButton = {
                Button(
                    onClick = {
                        coroutineScope.launch {
                            runCatching {
                                rootViewModel.updateWalletContact(
                                    contactId = currentContact.id,
                                    name = editName,
                                    paymentIdentifier = editPaymentIdentifier
                                )
                            }.onSuccess { updated ->
                                currentContact = ActiveContact(
                                    id = updated.id,
                                    name = updated.name,
                                    paymentIdentifier = updated.paymentIdentifier
                                )
                                actionError = null
                                showEditDialog = false
                                onUpdated(currentContact)
                            }.onFailure { error ->
                                actionError = error.message ?: "Failed to update contact."
                            }
                        }
                    },
                    enabled = editName.trim().isNotEmpty() && editPaymentIdentifier.trim().isNotEmpty()
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showEditDialog = false }) {
                    Text("Cancel")
                }
            },
            title = { Text("Edit Contact") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    TextField(
                        value = editName,
                        onValueChange = { editName = it },
                        singleLine = true,
                        label = { Text("Name") }
                    )
                    TextField(
                        value = editPaymentIdentifier,
                        onValueChange = { editPaymentIdentifier = it },
                        singleLine = true,
                        label = { Text("Lightning Address") }
                    )
                }
            }
        )
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            confirmButton = {
                Button(
                    onClick = {
                        coroutineScope.launch {
                            runCatching {
                                rootViewModel.deleteWalletContact(currentContact.id)
                            }.onSuccess {
                                showDeleteDialog = false
                                onDeleted()
                            }.onFailure { error ->
                                actionError = error.message ?: "Failed to delete contact."
                            }
                        }
                    }
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showDeleteDialog = false }) {
                    Text("Cancel")
                }
            },
            title = { Text("Delete Contact") },
            text = {
                Text("Remove ${currentContact.name} from your saved contacts?")
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
                                    lightningAddress = currentContact.paymentIdentifier
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
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .statusBarsPadding()
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.clickable(onClick = onBack),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = "Back",
                    tint = Color.White,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Back",
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            Text(
                text = "Edit",
                modifier = Modifier.clickable {
                    editName = currentContact.name
                    editPaymentIdentifier = currentContact.paymentIdentifier
                    showEditDialog = true
                },
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White,
                fontWeight = FontWeight.SemiBold
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            ParticipantAvatar(
                label = currentContact.name,
                profilePicUrl = profilePicUrl,
                size = 104.dp
            )

            Text(
                text = currentContact.name,
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.headlineMedium,
                color = Color.White,
                fontWeight = FontWeight.Black,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            Text(
                text = currentContact.paymentIdentifier,
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.62f),
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                ContactDetailActionButton(
                    icon = Icons.Rounded.ChatBubble,
                    label = "Message",
                    enabled = !isMessagingBlocked,
                    onClick = {
                        val conversation = existingConversation
                        if (conversation != null) {
                            onOpenThread(
                                conversation.id,
                                conversation.title,
                                conversation.lightningAddress ?: currentContact.paymentIdentifier
                            )
                        } else {
                            onCompose(currentContact.paymentIdentifier)
                        }
                    }
                )

                ContactDetailActionButton(
                    icon = Icons.Rounded.Bolt,
                    label = "Pay",
                    onClick = {
                        onOpenSendOverlay(
                            SendOverlayConfig(initialDestination = currentContact.paymentIdentifier)
                        )
                    }
                )

                ContactDetailActionButton(
                    icon = Icons.Rounded.Block,
                    label = if (isMessagingBlocked) "Blocked" else "Block",
                    isActive = isMessagingBlocked,
                    isBusy = isUpdatingBlockState,
                    onClick = {
                        actionError = null
                        if (isMessagingBlocked) {
                            showUnblockConfirmation = true
                        } else {
                            showBlockConfirmation = true
                        }
                    }
                )
            }
        }

        if (isMessagingBlocked) {
            Text(
                text = "You blocked this user. Unblock them to message again.",
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.72f),
                textAlign = TextAlign.Center
            )
        }

        if (!actionError.isNullOrBlank()) {
            Text(
                text = actionError!!,
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.bodySmall,
                color = SplitBrandPink,
                textAlign = TextAlign.Center
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        Text(
            text = "Delete Contact",
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .clickable(onClick = { showDeleteDialog = true }),
            style = MaterialTheme.typography.bodyMedium,
            color = SplitBrandPink,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun ComposeMessageScreen(
    rootViewModel: SplitRootViewModel,
    prefilledLightningAddress: String? = null,
    onDismiss: () -> Unit,
    onSent: (com.split.android.data.messages.MessageSendResult) -> Unit
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val coroutineScope = rememberCoroutineScope()
    val recipientFocusRequester = remember { FocusRequester() }
    val messageFocusRequester = remember { FocusRequester() }
    val storedMessages by rootViewModel.storedMessages.collectAsStateWithLifecycle()
    val contactsByPaymentIdentifier by rootViewModel.contactsByPaymentIdentifier.collectAsStateWithLifecycle()
    val recipientMetadataByConversationId by rootViewModel.recipientMetadataByConversationId.collectAsStateWithLifecycle()
    val normalizedPrefilledRecipient = remember(prefilledLightningAddress) {
        normalizeComposeRecipientInput(prefilledLightningAddress)
    }
    var recipientQuery by rememberSaveable(prefilledLightningAddress) {
        mutableStateOf(
            if (normalizedPrefilledRecipient == null) {
                prefilledLightningAddress.orEmpty()
            } else {
                ""
            }
        )
    }
    var selectedRecipientAddress by rememberSaveable(prefilledLightningAddress) {
        mutableStateOf(normalizedPrefilledRecipient)
    }
    var draftMessage by rememberSaveable { mutableStateOf("") }
    var sendError by remember { mutableStateOf<String?>(null) }
    var isSending by remember { mutableStateOf(false) }
    var showAttachmentOptions by remember { mutableStateOf(false) }

    val allRecipientRecords = remember(
        storedMessages,
        recipientMetadataByConversationId,
        contactsByPaymentIdentifier
    ) {
        buildComposeRecipientRecords(
            contactsByPaymentIdentifier = contactsByPaymentIdentifier,
            conversations = rootViewModel.conversationPreviews()
        )
    }
    val selectedRecipient = remember(selectedRecipientAddress, allRecipientRecords) {
        selectedRecipientAddress?.let { address ->
            allRecipientRecords.firstOrNull { it.lightningAddress == address }
                ?: ComposeRecipientRecord(
                    lightningAddress = address,
                    displayName = address,
                    profilePicUrl = null,
                    lastInteractedAtMillis = null,
                    source = ComposeRecipientSource.CONVERSATION
                )
        }
    }
    val resolvedRecipientAddress = selectedRecipientAddress ?: normalizeComposeRecipientInput(recipientQuery)
    val canSend = !isSending &&
        !draftMessage.trim().isEmpty() &&
        !resolvedRecipientAddress.isNullOrBlank()
    val normalizedRecipientQuery = remember(recipientQuery) {
        normalizeComposeSearchText(recipientQuery)
    }
    val recipientSuggestions = remember(
        allRecipientRecords,
        normalizedRecipientQuery,
        selectedRecipientAddress
    ) {
        if (selectedRecipientAddress != null || normalizedRecipientQuery.isEmpty()) {
            emptyList()
        } else {
            allRecipientRecords
                .mapNotNull { record ->
                    composeRecipientMatchScore(
                        record = record,
                        query = normalizedRecipientQuery
                    )?.let { score ->
                        record to score
                    }
                }
                .sortedWith(
                    compareByDescending<Pair<ComposeRecipientRecord, Int>> { it.second }
                        .thenByDescending { it.first.lastInteractedAtMillis ?: Long.MIN_VALUE }
                        .thenBy { it.first.displayName.lowercase() }
                )
                .take(8)
                .map { it.first }
        }
    }
    val typedRecipientCandidate = remember(
        allRecipientRecords,
        recipientQuery,
        normalizedRecipientQuery,
        selectedRecipientAddress,
        recipientSuggestions
    ) {
        if (selectedRecipientAddress != null || normalizedRecipientQuery.isEmpty()) {
            null
        } else {
            val normalizedCandidate = normalizeComposeRecipientInput(recipientQuery)
            val alreadyKnown = normalizedCandidate != null && allRecipientRecords.any {
                it.lightningAddress == normalizedCandidate
            }
            if (normalizedCandidate != null &&
                !alreadyKnown &&
                (normalizedRecipientQuery.contains("@") || recipientSuggestions.isEmpty())
            ) {
                normalizedCandidate
            } else {
                null
            }
        }
    }
    val showsRecipientSuggestions = selectedRecipientAddress == null &&
        normalizedRecipientQuery.isNotEmpty() &&
        (recipientSuggestions.isNotEmpty() || typedRecipientCandidate != null)
    val showsUsernameHint = selectedRecipientAddress == null &&
        recipientQuery.trim().isNotEmpty() &&
        !normalizedRecipientQuery.contains("@") &&
        normalizeComposeRecipientInput(recipientQuery) != null
    val isKeyboardVisible = WindowInsets.ime.getBottom(density) > 0
    val bottomInset = with(density) {
        WindowInsets.navigationBars.getBottom(this).toDp()
    }

    fun selectRecipient(recipient: ComposeRecipientRecord) {
        selectedRecipientAddress = recipient.lightningAddress
        recipientQuery = ""
        sendError = null
    }

    fun selectTypedRecipientCandidate() {
        val candidate = typedRecipientCandidate ?: return
        selectRecipient(
            ComposeRecipientRecord(
                lightningAddress = candidate,
                displayName = candidate,
                profilePicUrl = null,
                lastInteractedAtMillis = null,
                source = ComposeRecipientSource.CONVERSATION
            )
        )
    }

    fun clearSelectedRecipient() {
        selectedRecipientAddress = null
        sendError = null
    }

    fun handleRecipientSubmit() {
        val exactMatch = exactComposeRecipientMatch(
            rawQuery = recipientQuery,
            allRecipientRecords = allRecipientRecords
        )
        when {
            exactMatch != null -> selectRecipient(exactMatch)
            recipientSuggestions.size == 1 -> selectRecipient(recipientSuggestions.first())
            typedRecipientCandidate != null -> selectTypedRecipientCandidate()
            resolvedRecipientAddress != null -> {
                selectRecipient(
                    ComposeRecipientRecord(
                        lightningAddress = resolvedRecipientAddress,
                        displayName = resolvedRecipientAddress,
                        profilePicUrl = null,
                        lastInteractedAtMillis = null,
                        source = ComposeRecipientSource.CONVERSATION
                    )
                )
            }
        }
    }

    suspend fun sendText() {
        val destination = resolvedRecipientAddress
            ?: throw IllegalStateException("Enter a valid Lightning Address.")

        isSending = true
        sendError = null
        runCatching {
            rootViewModel.sendTextMessage(
                lightningAddress = destination,
                plaintext = draftMessage
            )
        }.onSuccess { result ->
            draftMessage = ""
            recipientQuery = ""
            selectedRecipientAddress = null
            onSent(result)
        }.onFailure { error ->
            sendError = error.message ?: "Failed to send message."
        }
        isSending = false
    }

    suspend fun sendSelectedAttachmentUri(uri: Uri) {
        val destination = resolvedRecipientAddress
            ?: throw IllegalStateException("Enter a valid Lightning Address before sending an attachment.")
        val selectedAttachment = readSelectedAttachment(context, uri)
        rootViewModel.sendAttachmentMessage(
            lightningAddress = destination,
            fileData = selectedAttachment.data,
            fileName = selectedAttachment.fileName,
            mimeType = selectedAttachment.mimeType,
            imageWidth = selectedAttachment.imageWidth,
            imageHeight = selectedAttachment.imageHeight
        ).also { result ->
            draftMessage = ""
            recipientQuery = ""
            selectedRecipientAddress = null
            onSent(result)
        }
    }

    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri == null || resolvedRecipientAddress.isNullOrBlank()) {
            return@rememberLauncherForActivityResult
        }
        coroutineScope.launch {
            isSending = true
            sendError = null
            runCatching {
                sendSelectedAttachmentUri(uri)
            }.onFailure { error ->
                sendError = error.message ?: "Failed to send attachment."
            }
            isSending = false
        }
    }

    val videoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri == null || resolvedRecipientAddress.isNullOrBlank()) {
            return@rememberLauncherForActivityResult
        }
        coroutineScope.launch {
            isSending = true
            sendError = null
            runCatching {
                sendSelectedAttachmentUri(uri)
            }.onFailure { error ->
                sendError = error.message ?: "Failed to send attachment."
            }
            isSending = false
        }
    }

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null || resolvedRecipientAddress.isNullOrBlank()) {
            return@rememberLauncherForActivityResult
        }
        coroutineScope.launch {
            isSending = true
            sendError = null
            runCatching {
                sendSelectedAttachmentUri(uri)
            }.onFailure { error ->
                sendError = error.message ?: "Failed to send attachment."
            }
            isSending = false
        }
    }

    LaunchedEffect(selectedRecipientAddress) {
        delay(150)
        if (selectedRecipientAddress.isNullOrBlank()) {
            recipientFocusRequester.requestFocus()
        } else {
            messageFocusRequester.requestFocus()
        }
    }

    BackHandler {
        focusManager.clearFocus(force = true)
        keyboardController?.hide()
        onDismiss()
    }

    if (showAttachmentOptions && !resolvedRecipientAddress.isNullOrBlank()) {
        AlertDialog(
            onDismissRequest = { showAttachmentOptions = false },
            confirmButton = {},
            dismissButton = {
                OutlinedButton(onClick = { showAttachmentOptions = false }) {
                    Text("Cancel")
                }
            },
            title = { Text("Send Attachment") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Choose a photo, video, or file to send as an encrypted attachment.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.74f)
                    )

                    OutlinedButton(
                        onClick = {
                            showAttachmentOptions = false
                            imagePicker.launch("image/*")
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Choose Photo")
                    }

                    OutlinedButton(
                        onClick = {
                            showAttachmentOptions = false
                            videoPicker.launch("video/*")
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Choose Video")
                    }

                    OutlinedButton(
                        onClick = {
                            showAttachmentOptions = false
                            filePicker.launch(arrayOf("*/*"))
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Choose File")
                    }
                }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .statusBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.clickable(onClick = onDismiss),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = "Back",
                    tint = Color.White,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = "Back",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White,
                    fontWeight = FontWeight.Medium
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            Text(
                text = "New Message",
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                fontWeight = FontWeight.SemiBold
            )

            Spacer(modifier = Modifier.weight(1f))

            Spacer(modifier = Modifier.width(52.dp))
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "To",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.72f),
                        fontWeight = FontWeight.SemiBold
                    )

                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        color = Color(0xFF101013),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.04f))
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .animateContentSize()
                                .padding(horizontal = 14.dp, vertical = 14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            if (selectedRecipient != null) {
                                ComposeRecipientChip(
                                    recipient = selectedRecipient,
                                    onClear = ::clearSelectedRecipient
                                )
                            } else {
                                TextField(
                                    value = recipientQuery,
                                    onValueChange = {
                                        recipientQuery = it
                                        sendError = null
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .focusRequester(recipientFocusRequester),
                                    singleLine = true,
                                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                                        color = Color.White
                                    ),
                                    placeholder = {
                                        Text(
                                            text = "Name or Lightning Address",
                                            style = MaterialTheme.typography.bodyLarge
                                        )
                                    },
                                    keyboardOptions = KeyboardOptions(
                                        capitalization = KeyboardCapitalization.None,
                                        autoCorrectEnabled = false,
                                        keyboardType = KeyboardType.Ascii,
                                        imeAction = ImeAction.Next
                                    ),
                                    keyboardActions = KeyboardActions(
                                        onNext = { handleRecipientSubmit() }
                                    ),
                                    colors = TextFieldDefaults.colors(
                                        focusedContainerColor = Color.Transparent,
                                        unfocusedContainerColor = Color.Transparent,
                                        disabledContainerColor = Color.Transparent,
                                        focusedIndicatorColor = Color.Transparent,
                                        unfocusedIndicatorColor = Color.Transparent,
                                        disabledIndicatorColor = Color.Transparent,
                                        cursorColor = Color.White,
                                        focusedTextColor = Color.White,
                                        unfocusedTextColor = Color.White,
                                        focusedPlaceholderColor = Color.White.copy(alpha = 0.46f),
                                        unfocusedPlaceholderColor = Color.White.copy(alpha = 0.46f)
                                    )
                                )
                            }

                            if (showsUsernameHint) {
                                Text(
                                    text = "Typing only a Split username will send to @${AppConfig.lightningAddressDomain}.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.White.copy(alpha = 0.52f)
                                )
                            }
                        }
                    }
                }
            }

            AnimatedVisibility(
                visible = showsRecipientSuggestions,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                ComposeRecipientSuggestionsDropdown(
                    suggestions = recipientSuggestions,
                    typedRecipientCandidate = typedRecipientCandidate,
                    onSelectRecipient = ::selectRecipient,
                    onSelectTypedCandidate = ::selectTypedRecipientCandidate
                )
            }

            Spacer(modifier = Modifier.weight(1f))
        }

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .padding(bottom = if (isKeyboardVisible) 0.dp else bottomInset),
            color = Color(0xFF101013)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 1.dp, start = 18.dp, end = 18.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(Color.White.copy(alpha = 0.08f))
                )

                if (isSending) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            color = Color.White,
                            strokeWidth = 2.dp
                        )
                        Text(
                            text = "Sending encrypted message...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.75f)
                        )
                    }
                }

                if (!sendError.isNullOrBlank()) {
                    Text(
                        text = sendError!!,
                        style = MaterialTheme.typography.bodyMedium,
                        color = SplitBrandPink
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Surface(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .clickable(
                                enabled = !resolvedRecipientAddress.isNullOrBlank() && !isSending
                            ) {
                                showAttachmentOptions = true
                            },
                        shape = CircleShape,
                        color = Color.White.copy(alpha = 0.08f),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.06f))
                    ) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Add,
                                contentDescription = "Send attachment",
                                tint = if (!resolvedRecipientAddress.isNullOrBlank() && !isSending) {
                                    Color.White.copy(alpha = 0.86f)
                                } else {
                                    Color.White.copy(alpha = 0.18f)
                                },
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    Surface(
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(20.dp),
                        color = Color.White.copy(alpha = 0.08f),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.04f))
                    ) {
                        TextField(
                            value = draftMessage,
                            onValueChange = { draftMessage = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(messageFocusRequester),
                            minLines = 1,
                            maxLines = 5,
                            textStyle = MaterialTheme.typography.bodyLarge.copy(
                                color = Color.White
                            ),
                            placeholder = {
                                Text(
                                    text = "Type an encrypted message...",
                                    style = MaterialTheme.typography.bodyLarge
                                )
                            },
                            keyboardOptions = KeyboardOptions(
                                capitalization = KeyboardCapitalization.Sentences,
                                imeAction = ImeAction.Send
                            ),
                            keyboardActions = KeyboardActions(
                                onSend = {
                                    if (canSend) {
                                        coroutineScope.launch { sendText() }
                                    }
                                }
                            ),
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                disabledContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                                disabledIndicatorColor = Color.Transparent,
                                cursorColor = Color.White,
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedPlaceholderColor = Color.White.copy(alpha = 0.46f),
                                unfocusedPlaceholderColor = Color.White.copy(alpha = 0.46f)
                            )
                        )
                    }

                    Surface(
                        modifier = Modifier.size(44.dp),
                        shape = CircleShape,
                        color = if (canSend) {
                            SplitBrandPink
                        } else {
                            Color.White.copy(alpha = 0.12f)
                        }
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .clickable(enabled = canSend) {
                                    coroutineScope.launch {
                                        sendText()
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            if (isSending) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    color = Color.White,
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Rounded.Send,
                                    contentDescription = "Send message",
                                    tint = if (canSend) Color.White else Color.White.copy(alpha = 0.42f),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ComposeRecipientChip(
    recipient: ComposeRecipientRecord,
    onClear: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(999.dp),
        color = Color.White.copy(alpha = 0.08f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            AvatarCircle(
                label = recipient.displayName,
                profilePicUrl = recipient.profilePicUrl,
                size = 30.dp
            )

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(1.dp)
            ) {
                Text(
                    text = recipient.displayName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Text(
                    text = recipient.lightningAddress,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.62f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.10f))
                    .clickable(onClick = onClear),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = "Clear recipient",
                    tint = Color.White.copy(alpha = 0.58f),
                    modifier = Modifier.size(14.dp)
                )
            }
        }
    }
}

@Composable
private fun ComposeRecipientSuggestionsDropdown(
    suggestions: List<ComposeRecipientRecord>,
    typedRecipientCandidate: String?,
    onSelectRecipient: (ComposeRecipientRecord) -> Unit,
    onSelectTypedCandidate: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = Color(0xFF101013),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.06f))
    ) {
        Column {
            suggestions.forEachIndexed { index, recipient ->
                ComposeRecipientSuggestionRow(
                    recipient = recipient,
                    onClick = { onSelectRecipient(recipient) }
                )

                if (index < suggestions.lastIndex || typedRecipientCandidate != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 62.dp)
                            .height(1.dp)
                            .background(Color.White.copy(alpha = 0.08f))
                    )
                }
            }

            if (typedRecipientCandidate != null) {
                ComposeTypedRecipientSuggestionRow(
                    lightningAddress = typedRecipientCandidate,
                    onClick = onSelectTypedCandidate
                )
            }
        }
    }
}

@Composable
private fun ComposeRecipientSuggestionRow(
    recipient: ComposeRecipientRecord,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        AvatarCircle(
            label = recipient.displayName,
            profilePicUrl = recipient.profilePicUrl,
            size = 36.dp
        )

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Text(
                text = recipient.displayName,
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Text(
                text = recipient.lightningAddress,
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.62f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun ComposeTypedRecipientSuggestionRow(
    lightningAddress: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(SplitBrandPink.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.Send,
                contentDescription = null,
                tint = SplitBrandPink,
                modifier = Modifier.size(18.dp)
            )
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Text(
                text = "Use Lightning Address",
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White,
                fontWeight = FontWeight.Medium
            )

            Text(
                text = lightningAddress,
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.62f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private fun buildComposeRecipientRecords(
    contactsByPaymentIdentifier: Map<String, WalletContact>,
    conversations: List<MessageConversationPreview>
): List<ComposeRecipientRecord> {
    val mergedByAddress = linkedMapOf<String, ComposeRecipientRecord>()

    contactsByPaymentIdentifier.values.forEach { contact ->
        val normalizedAddress = normalizeComposeLightningAddress(contact.paymentIdentifier)
        if (normalizedAddress.isEmpty()) {
            return@forEach
        }

        val displayName = contact.name.trim().ifEmpty { normalizedAddress }
        val candidate = ComposeRecipientRecord(
            lightningAddress = normalizedAddress,
            displayName = displayName,
            profilePicUrl = mergedByAddress[normalizedAddress]?.profilePicUrl,
            lastInteractedAtMillis = mergedByAddress[normalizedAddress]?.lastInteractedAtMillis,
            source = ComposeRecipientSource.CONTACT
        )
        mergedByAddress[normalizedAddress] = mergeComposeRecipientRecords(
            existing = mergedByAddress[normalizedAddress],
            incoming = candidate
        )
    }

    conversations.forEach { conversation ->
        val normalizedAddress = normalizeComposeLightningAddress(conversation.lightningAddress)
        if (normalizedAddress.isEmpty()) {
            return@forEach
        }

        val contactName = contactsByPaymentIdentifier[normalizedAddress]?.name?.trim().orEmpty()
        val existing = mergedByAddress[normalizedAddress]
        val displayName = when {
            contactName.isNotEmpty() -> contactName
            !existing?.displayName.isNullOrBlank() -> existing.displayName
            else -> conversation.title
        }

        val candidate = ComposeRecipientRecord(
            lightningAddress = normalizedAddress,
            displayName = displayName.ifBlank { normalizedAddress },
            profilePicUrl = normalizeComposeProfileUrl(conversation.profilePicUrl) ?: existing?.profilePicUrl,
            lastInteractedAtMillis = conversation.latestAtMillis,
            source = ComposeRecipientSource.CONVERSATION
        )
        mergedByAddress[normalizedAddress] = mergeComposeRecipientRecords(
            existing = existing,
            incoming = candidate
        )
    }

    return mergedByAddress.values.sortedWith(
        compareByDescending<ComposeRecipientRecord> { it.lastInteractedAtMillis ?: Long.MIN_VALUE }
            .thenBy { it.displayName.lowercase() }
    )
}

private fun mergeComposeRecipientRecords(
    existing: ComposeRecipientRecord?,
    incoming: ComposeRecipientRecord
): ComposeRecipientRecord {
    if (existing == null) {
        return incoming
    }

    val preferredDisplayName = when {
        existing.source == ComposeRecipientSource.CONTACT -> existing.displayName
        incoming.source == ComposeRecipientSource.CONTACT -> incoming.displayName
        existing.displayName.length >= incoming.displayName.length -> existing.displayName
        else -> incoming.displayName
    }

    val preferredProfilePicUrl = normalizeComposeProfileUrl(incoming.profilePicUrl)
        ?: normalizeComposeProfileUrl(existing.profilePicUrl)
    val preferredLastInteractedAtMillis = maxOf(
        existing.lastInteractedAtMillis ?: Long.MIN_VALUE,
        incoming.lastInteractedAtMillis ?: Long.MIN_VALUE
    ).takeIf { it != Long.MIN_VALUE }
    val preferredSource = if (
        existing.source == ComposeRecipientSource.CONTACT ||
        incoming.source == ComposeRecipientSource.CONTACT
    ) {
        ComposeRecipientSource.CONTACT
    } else {
        ComposeRecipientSource.CONVERSATION
    }

    return ComposeRecipientRecord(
        lightningAddress = existing.lightningAddress,
        displayName = preferredDisplayName,
        profilePicUrl = preferredProfilePicUrl,
        lastInteractedAtMillis = preferredLastInteractedAtMillis,
        source = preferredSource
    )
}

private fun exactComposeRecipientMatch(
    rawQuery: String,
    allRecipientRecords: List<ComposeRecipientRecord>
): ComposeRecipientRecord? {
    val normalizedQuery = normalizeComposeSearchText(rawQuery)
    val normalizedAddress = normalizeComposeRecipientInput(rawQuery)
    if (normalizedAddress != null) {
        val exactAddressMatch = allRecipientRecords.firstOrNull {
            it.lightningAddress == normalizedAddress
        }
        if (exactAddressMatch != null) {
            return exactAddressMatch
        }
    }

    return allRecipientRecords.firstOrNull { record ->
        normalizeComposeSearchText(record.displayName) == normalizedQuery
    }
}

private fun composeRecipientMatchScore(
    record: ComposeRecipientRecord,
    query: String
): Int? {
    val displayName = normalizeComposeSearchText(record.displayName)
    val displayNameKey = compactComposeSearchKey(record.displayName)
    val lightningAddress = normalizeComposeSearchText(record.lightningAddress)
    val username = lightningAddress.substringBefore('@', "")
    val displayNameTokens = displayName.split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }
    val queryKey = compactComposeSearchKey(query)

    var bestScore = 0

    when {
        displayName == query -> bestScore = maxOf(bestScore, 140)
        queryKey.isNotEmpty() && displayNameKey == queryKey -> bestScore = maxOf(bestScore, 138)
        displayName.startsWith(query) -> bestScore = maxOf(bestScore, 125)
        queryKey.isNotEmpty() && displayNameKey.startsWith(queryKey) -> bestScore = maxOf(bestScore, 123)
        displayNameTokens.any { it.startsWith(query) } -> bestScore = maxOf(bestScore, 115)
        queryKey.isNotEmpty() && displayNameTokens.any { compactComposeSearchKey(it).startsWith(queryKey) } -> {
            bestScore = maxOf(bestScore, 113)
        }
        displayName.contains(query) -> bestScore = maxOf(bestScore, 100)
        queryKey.isNotEmpty() && displayNameKey.contains(queryKey) -> bestScore = maxOf(bestScore, 98)
    }

    when {
        lightningAddress == query -> bestScore = maxOf(bestScore, 96)
        username == query -> bestScore = maxOf(bestScore, 94)
        lightningAddress.startsWith(query) -> bestScore = maxOf(bestScore, 90)
        username.startsWith(query) -> bestScore = maxOf(bestScore, 88)
        lightningAddress.contains(query) || username.contains(query) -> bestScore = maxOf(bestScore, 80)
    }

    return bestScore.takeIf { it > 0 }
}

private fun normalizeComposeSearchText(rawValue: String): String {
    return rawValue
        .trim()
        .lowercase()
        .split(Regex("\\s+"))
        .filter { it.isNotEmpty() }
        .joinToString(" ")
}

private fun compactComposeSearchKey(rawValue: String): String {
    return normalizeComposeSearchText(rawValue)
        .filter { it.isLetterOrDigit() }
}

private fun normalizeComposeLightningAddress(lightningAddress: String?): String {
    return lightningAddress
        ?.trim()
        ?.lowercase()
        .orEmpty()
}

private fun normalizeComposeRecipientInput(rawValue: String?): String? {
    val trimmed = rawValue
        ?.trim()
        ?.lowercase()
        .orEmpty()
    if (trimmed.isEmpty()) {
        return null
    }

    val normalized = if (trimmed.contains('@')) {
        trimmed
    } else {
        val allowedCharacters = "abcdefghijklmnopqrstuvwxyz0123456789._-"
        if (!trimmed.all { it in allowedCharacters }) {
            return null
        }
        "$trimmed@${AppConfig.lightningAddressDomain}"
    }

    val pieces = normalized.split('@')
    if (pieces.size != 2 || pieces[0].isBlank() || pieces[1].isBlank()) {
        return null
    }

    return normalized
}

private fun normalizeComposeProfileUrl(rawValue: String?): String? {
    return rawValue
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
}

@Composable
private fun AvatarCircle(
    label: String,
    profilePicUrl: String? = null,
    size: androidx.compose.ui.unit.Dp = 48.dp
) {
    val remoteBitmap by produceState<Bitmap?>(initialValue = null, profilePicUrl) {
        value = profilePicUrl
            ?.takeIf { it.isNotBlank() }
            ?.let { remoteUrl ->
                withContext(Dispatchers.IO) {
                    runCatching {
                        URL(remoteUrl).openStream().use { stream ->
                            BitmapFactory.decodeStream(stream)
                        }
                    }.getOrNull()
                }
            }
    }

    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.08f)),
        contentAlignment = Alignment.Center
    ) {
        if (remoteBitmap != null) {
            Image(
                bitmap = remoteBitmap!!.asImageBitmap(),
                contentDescription = label,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else {
            Icon(
                imageVector = Icons.Rounded.Person,
                contentDescription = label,
                tint = Color.White.copy(alpha = 0.88f),
                modifier = Modifier.size(size * 0.46f)
            )
        }
    }
}

private fun filterConversationPreviews(
    allConversations: List<MessageConversationPreview>,
    searchQuery: String,
    contactsByPaymentIdentifier: Map<String, WalletContact>,
    messagesByConversationId: Map<String, List<StoredMessage>>
): List<MessageConversationPreview> {
    val normalizedQuery = searchQuery.trim().lowercase()
    if (normalizedQuery.isEmpty()) {
        return allConversations
    }

    return allConversations.filter { conversation ->
        val titleMatches = conversationDisplayTitle(
            conversation = conversation,
            contactsByPaymentIdentifier = contactsByPaymentIdentifier
        ).lowercase().contains(normalizedQuery)

        val messageMatches = messagesByConversationId[conversation.id]
            .orEmpty()
            .any { searchableMessageText(it).lowercase().contains(normalizedQuery) }

        titleMatches || messageMatches
    }
}

private fun conversationDisplayTitle(
    conversation: MessageConversationPreview,
    contactsByPaymentIdentifier: Map<String, WalletContact>
): String {
    val normalizedAddress = conversation.lightningAddress
        ?.trim()
        ?.lowercase()
    return normalizedAddress?.let { contactsByPaymentIdentifier[it]?.name } ?: conversation.title
}

private fun conversationSubtitle(
    conversation: MessageConversationPreview,
    messages: List<StoredMessage>,
    searchQuery: String
): String {
    if (conversation.hasFailedOutgoingMessage) {
        return "Couldn't deliver"
    }

    val trimmedSearchQuery = searchQuery.trim()
    if (trimmedSearchQuery.isEmpty()) {
        return conversation.latestBody
    }

    messages.asReversed().forEach { message ->
        val candidate = normalizedSnippetText(searchableMessageText(message))
        if (candidate.contains(trimmedSearchQuery, ignoreCase = true)) {
            return excerptAround(trimmedSearchQuery, candidate)
        }
    }

    return if (conversation.latestBody.contains(trimmedSearchQuery, ignoreCase = true)) {
        excerptAround(trimmedSearchQuery, conversation.latestBody)
    } else {
        conversation.latestBody
    }
}

private fun searchableMessageText(message: StoredMessage): String {
    return when (message.messageType) {
        "payment_request", "payment_request_paid", "attachment" -> {
            normalizedSnippetText(MessagePayloadCodec.previewText(message))
        }

        else -> {
            val body = normalizedSnippetText(message.body)
            if (body.isEmpty()) {
                normalizedSnippetText(MessagePayloadCodec.previewText(message))
            } else {
                body
            }
        }
    }
}

private fun normalizedSnippetText(text: String): String {
    return text
        .split(Regex("\\s+"))
        .filter { it.isNotEmpty() }
        .joinToString(" ")
}

private fun excerptAround(
    query: String,
    text: String,
    contextCharacters: Int = 24
): String {
    val normalizedText = normalizedSnippetText(text)
    val matchStart = normalizedText.indexOf(query, ignoreCase = true)
    if (normalizedText.isEmpty() || matchStart < 0) {
        return normalizedText
    }

    val matchEnd = matchStart + query.length
    val startIndex = maxOf(0, matchStart - contextCharacters)
    val endIndex = minOf(normalizedText.length, matchEnd + contextCharacters)

    return buildString {
        if (startIndex > 0) {
            append("...")
        }
        append(normalizedText.substring(startIndex, endIndex))
        if (endIndex < normalizedText.length) {
            append("...")
        }
    }
}

private suspend fun sendSelectedAttachment(
    context: Context,
    uri: Uri,
    lightningAddress: String,
    rootViewModel: SplitRootViewModel,
    onStart: () -> Unit,
    onFinish: () -> Unit,
    onError: (String) -> Unit
) {
    onStart()

    runCatching {
        val selectedAttachment = readSelectedAttachment(context, uri)
        rootViewModel.sendAttachmentMessage(
            lightningAddress = lightningAddress,
            fileData = selectedAttachment.data,
            fileName = selectedAttachment.fileName,
            mimeType = selectedAttachment.mimeType,
            imageWidth = selectedAttachment.imageWidth,
            imageHeight = selectedAttachment.imageHeight
        )
    }.onFailure { error ->
        onError(error.message ?: "Failed to send attachment.")
    }

    onFinish()
}

private data class SelectedAttachment(
    val data: ByteArray,
    val fileName: String,
    val mimeType: String,
    val imageWidth: Int?,
    val imageHeight: Int?
)

private suspend fun readSelectedAttachment(
    context: Context,
    uri: Uri
): SelectedAttachment {
    val contentResolver = context.contentResolver
    val fileName = contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (index >= 0 && cursor.moveToFirst()) {
            cursor.getString(index)
        } else {
            null
        }
    } ?: "attachment"

    val mimeType = contentResolver.getType(uri).orEmpty().ifBlank { "application/octet-stream" }
    if (mimeType.lowercase().startsWith("video/")) {
        val processedVideo = MessageVideoProcessor(context).prepareAttachment(
            uri = uri,
            originalFileName = fileName,
            maxBytes = MessageAttachmentManager.MAXIMUM_ATTACHMENT_BYTES
        )

        return SelectedAttachment(
            data = processedVideo.data,
            fileName = processedVideo.fileName,
            mimeType = processedVideo.mimeType,
            imageWidth = null,
            imageHeight = null
        )
    }

    val data = withContext(Dispatchers.IO) {
        contentResolver.openInputStream(uri)?.use { it.readBytes() }
    } ?: throw IllegalStateException("Failed to read selected file.")

    require(data.size <= MessageAttachmentManager.MAXIMUM_ATTACHMENT_BYTES) {
        "Attachments must be smaller than ${MessageAttachmentManager.MAXIMUM_ATTACHMENT_BYTES / (1024 * 1024)} MB."
    }

    val (imageWidth, imageHeight) = if (mimeType.lowercase().startsWith("image/")) {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, options)
        options.outWidth.takeIf { it > 0 } to options.outHeight.takeIf { it > 0 }
    } else {
        null to null
    }

    return SelectedAttachment(
        data = data,
        fileName = fileName,
        mimeType = mimeType,
        imageWidth = imageWidth,
        imageHeight = imageHeight
    )
}

private fun openAttachmentInExternalApp(
    context: Context,
    payload: AttachmentMessagePayload,
    data: ByteArray
) {
    require(data.isNotEmpty()) { "Attachment data is empty." }

    val previewDirectory = File(context.cacheDir, "message_attachment_previews").apply {
        if (!exists()) {
            mkdirs()
        }
    }
    val safeName = payload.fileName.replace(Regex("[^A-Za-z0-9._-]"), "_")
    val previewFile = File(previewDirectory, "${payload.attachmentId}__${safeName}").apply {
        writeBytes(data)
    }

    val uri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        previewFile
    )

    val viewIntent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, payload.mimeType.ifBlank { "*/*" })
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    val chooser = Intent.createChooser(viewIntent, "Open attachment").apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    context.startActivity(chooser)
}

private fun decodeAttachmentImageBitmap(data: ByteArray): androidx.compose.ui.graphics.ImageBitmap? {
    val bitmap = BitmapFactory.decodeByteArray(data, 0, data.size) ?: return null
    val orientedBitmap = applyExifOrientation(bitmap, data)
    return orientedBitmap.asImageBitmap()
}

private fun applyExifOrientation(
    bitmap: Bitmap,
    data: ByteArray
): Bitmap {
    val orientation = runCatching {
        ByteArrayInputStream(data).use { inputStream ->
            ExifInterface(inputStream).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
        }
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

    val matrix = Matrix()
    when (orientation) {
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> {
            matrix.setScale(-1f, 1f)
        }

        ExifInterface.ORIENTATION_ROTATE_180 -> {
            matrix.setRotate(180f)
        }

        ExifInterface.ORIENTATION_FLIP_VERTICAL -> {
            matrix.setRotate(180f)
            matrix.postScale(-1f, 1f)
        }

        ExifInterface.ORIENTATION_TRANSPOSE -> {
            matrix.setRotate(90f)
            matrix.postScale(-1f, 1f)
        }

        ExifInterface.ORIENTATION_ROTATE_90 -> {
            matrix.setRotate(90f)
        }

        ExifInterface.ORIENTATION_TRANSVERSE -> {
            matrix.setRotate(-90f)
            matrix.postScale(-1f, 1f)
        }

        ExifInterface.ORIENTATION_ROTATE_270 -> {
            matrix.setRotate(-90f)
        }

        else -> return bitmap
    }

    return runCatching {
        Bitmap.createBitmap(
            bitmap,
            0,
            0,
            bitmap.width,
            bitmap.height,
            matrix,
            true
        )
    }.getOrElse {
        bitmap
    }
}

private fun formatSats(amountSats: Long): String {
    return java.text.NumberFormat.getIntegerInstance().format(amountSats)
}

private fun messageThreadListItemCount(messages: List<StoredMessage>): Int {
    var itemCount = 0
    messages.forEachIndexed { index, message ->
        if (shouldShowMessageDaySeparator(message, messages.getOrNull(index - 1))) {
            itemCount += 1
        }
        itemCount += 1
    }
    return itemCount
}

private fun shouldShowMessageDaySeparator(
    message: StoredMessage,
    previousMessage: StoredMessage?
): Boolean {
    return previousMessage == null ||
        localMessageDate(message.createdAtMillis) != localMessageDate(previousMessage.createdAtMillis)
}

private fun localMessageDate(timestampMillis: Long): LocalDate {
    return Instant.ofEpochMilli(timestampMillis)
        .atZone(ZoneId.systemDefault())
        .toLocalDate()
}

private fun formatMessageDaySeparator(timestampMillis: Long): String {
    return DateTimeFormatter
        .ofPattern("EEEE M/d/yy", Locale.getDefault())
        .withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochMilli(timestampMillis))
}

private fun formatMessageRevealTime(timestampMillis: Long): String {
    return DateTimeFormatter
        .ofLocalizedTime(FormatStyle.SHORT)
        .withLocale(Locale.getDefault())
        .withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochMilli(timestampMillis))
}
