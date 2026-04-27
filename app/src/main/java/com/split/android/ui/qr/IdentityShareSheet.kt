package com.split.android.ui.qr

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Icon
import androidx.compose.ui.platform.LocalContext
import com.split.android.ui.theme.SplitBrandBlue
import com.split.android.ui.theme.SplitBrandPink

@Composable
fun IdentityShareSheet(
    lightningAddress: String,
    suggestedContactName: String = suggestedContactName(lightningAddress),
    paymentQrString: String = buildPaymentQrString(lightningAddress),
    profilePicUrl: String? = null,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val suggestedName = remember(lightningAddress, suggestedContactName) { suggestedContactName }
    val paymentPayload = remember(lightningAddress, paymentQrString) { paymentQrString }
    val contactQrString = remember(lightningAddress, suggestedName, profilePicUrl) {
        buildSplitContactQrString(
            lightningAddress = lightningAddress,
            suggestedName = suggestedName,
            profilePicUrl = profilePicUrl
        )
    }
    var mode by rememberSaveable { mutableStateOf(IdentityShareMode.Payment) }

    val activeQr = if (mode == IdentityShareMode.Contact) contactQrString else paymentPayload
    val activePrimary = if (mode == IdentityShareMode.Contact) suggestedName else lightningAddress
    val activeSecondary = if (mode == IdentityShareMode.Contact) lightningAddress else "LNURL Pay"

    BackHandler(onBack = onDismiss)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .statusBarsPadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Spacer(modifier = Modifier.weight(1f))

                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.10f))
                        .clickable(onClick = onDismiss),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = "Close",
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                IdentityMode.entries.forEach { entry ->
                    val selected = mode == entry.mode
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(14.dp))
                            .background(if (selected) SplitBrandPink else Color.White.copy(alpha = 0.08f))
                            .clickable { mode = entry.mode }
                            .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = entry.label,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = Color.Transparent
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(24.dp))
                            .background(Color.White)
                            .padding(18.dp)
                    ) {
                        QrCodeCard(
                            qrString = activeQr,
                            size = 220
                        )
                    }
                }

                Text(
                    text = activePrimary,
                    style = MaterialTheme.typography.headlineSmall,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )

                Text(
                    text = activeSecondary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.58f),
                    textAlign = TextAlign.Center
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = {
                        shareIdentityBitmap(
                            context = context,
                            bitmap = buildIdentityShareBitmap(
                                qrString = activeQr,
                                primaryText = activePrimary,
                                secondaryText = activeSecondary
                            )
                        )
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Share")
                }

                Button(
                    onClick = {
                        copyIdentityBitmap(
                            context = context,
                            bitmap = buildIdentityShareBitmap(
                                qrString = activeQr,
                                primaryText = activePrimary,
                                secondaryText = activeSecondary
                            )
                        )
                    },
                    modifier = Modifier.weight(1f),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 14.dp)
                ) {
                    Text("Copy")
                }
            }
        }
    }
}

private enum class IdentityShareMode {
    Payment,
    Contact
}

private enum class IdentityMode(
    val mode: IdentityShareMode,
    val label: String
) {
    PAYMENT(IdentityShareMode.Payment, "Payment"),
    ADD_CONTACT(IdentityShareMode.Contact, "Add Contact");

    companion object {
        val entries: List<IdentityMode> = listOf(PAYMENT, ADD_CONTACT)
    }
}
