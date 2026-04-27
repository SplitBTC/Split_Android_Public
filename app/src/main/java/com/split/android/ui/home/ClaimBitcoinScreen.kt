package com.split.android.ui.home

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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.split.android.data.wallet.UnclaimedBitcoinDeposit
import com.split.android.ui.SplitRootViewModel
import com.split.android.ui.theme.SplitBlack
import com.split.android.ui.theme.SplitBrandBlue
import com.split.android.ui.theme.SplitBrandPink
import com.split.android.ui.theme.SplitSurface
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.util.Locale

@Composable
fun ClaimBitcoinScreen(
    rootViewModel: SplitRootViewModel,
    onDismiss: () -> Unit
) {
    var isLoading by remember { mutableStateOf(false) }
    var deposits by remember { mutableStateOf<List<UnclaimedBitcoinDeposit>>(emptyList()) }
    var selectedDeposit by remember { mutableStateOf<UnclaimedBitcoinDeposit?>(null) }
    var isClaiming by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var btcUsdRate by remember { mutableStateOf<Double?>(null) }

    suspend fun refresh() {
        if (isLoading) return
        isLoading = true
        errorMessage = null

        runCatching {
            val loadedDeposits = rootViewModel.fetchUnclaimedBitcoinDeposits()
            val price = runCatching { rootViewModel.fetchBtcUsdPrice() }.getOrNull()
            loadedDeposits to price
        }.onSuccess { (loadedDeposits, price) ->
            deposits = loadedDeposits
            btcUsdRate = price
        }.onFailure { error ->
            errorMessage = error.message ?: "Failed to load deposits."
        }

        isLoading = false
    }

    LaunchedEffect(Unit) {
        refresh()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(SplitBlack)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            ClaimBitcoinAtmosphere()

            ClaimBitcoinHeader(
                onDismiss = onDismiss
            )

            ClaimBitcoinHeaderCard()

            when {
                isLoading -> ClaimBitcoinMessageCard(
                    icon = null,
                    title = "Loading deposits…",
                    body = null,
                    accent = SplitBrandBlue,
                    showProgress = true
                )

                !errorMessage.isNullOrBlank() -> ClaimBitcoinMessageCard(
                    icon = null,
                    title = "Claim unavailable",
                    body = errorMessage,
                    accent = SplitBrandPink
                )

                deposits.isEmpty() -> ClaimBitcoinMessageCard(
                    icon = Icons.Rounded.CheckCircle,
                    title = "No deposits to claim.",
                    body = "Bitcoin deposits coming to your onchain wallet address will appear here.",
                    accent = SplitBrandBlue
                )

                else -> deposits.forEach { deposit ->
                    ClaimBitcoinDepositCard(
                        deposit = deposit,
                        btcUsdRate = btcUsdRate,
                        onClick = { selectedDeposit = deposit }
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }

        selectedDeposit?.let { deposit ->
            ClaimBitcoinDepositSheet(
                deposit = deposit,
                btcUsdRate = btcUsdRate,
                isClaiming = isClaiming,
                onDismiss = { if (!isClaiming) selectedDeposit = null },
                onClaim = {
                    if (isClaiming) return@ClaimBitcoinDepositSheet
                    isClaiming = true
                    runCatching {
                        rootViewModel.claimBitcoinDeposit(deposit)
                    }.onSuccess {
                        selectedDeposit = null
                        refresh()
                    }.onFailure { error ->
                        errorMessage = error.message ?: "Failed to claim deposit."
                    }
                    isClaiming = false
                }
            )
        }
    }
}

@Composable
private fun ClaimBitcoinHeader(
    onDismiss: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "Claim Bitcoin",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Black,
            color = Color.White
        )

        Spacer(modifier = Modifier.weight(1f))

        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.06f))
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Rounded.Close,
                contentDescription = "Close Claim Bitcoin",
                tint = Color.White,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
private fun ClaimBitcoinAtmosphere() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(88.dp)
            .background(
                brush = Brush.horizontalGradient(
                    colors = listOf(
                        SplitBrandBlue.copy(alpha = 0.18f),
                        SplitBrandPink.copy(alpha = 0.16f)
                    )
                ),
                shape = RoundedCornerShape(30.dp)
            )
    )
}

@Composable
private fun ClaimBitcoinHeaderCard() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = SplitSurface,
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.10f))
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Bitcoin Deposits",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            Text(
                text = "There is a fee to move your Bitcoin from your on-chain wallet into Split so you can spend it over the Lightning Network. Split does not receive any portion of this fee.",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.78f)
            )
            Text(
                text = "The fee rises and falls based on Bitcoin network congestion. We let you choose when you want to claim the deposit to give you maximum control over costs.",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.78f)
            )
        }
    }
}

@Composable
private fun ClaimBitcoinMessageCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector?,
    title: String,
    body: String?,
    accent: Color,
    showProgress: Boolean = false
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = SplitSurface,
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.10f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            when {
                showProgress -> CircularProgressIndicator(color = accent)
                icon != null -> Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(34.dp)
                )
            }

            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                textAlign = TextAlign.Center
            )

            if (!body.isNullOrBlank()) {
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.72f),
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
private fun ClaimBitcoinDepositCard(
    deposit: UnclaimedBitcoinDeposit,
    btcUsdRate: Double?,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(24.dp),
        color = SplitSurface,
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.10f))
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = formatSats(deposit.amountSats),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )

                Spacer(modifier = Modifier.weight(1f))

                Text(
                    text = "Claim",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(SplitBrandPink)
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                )
            }

            usdString(deposit.amountSats, btcUsdRate)?.let { usd ->
                Text(
                    text = usd,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.62f)
                )
            }

            when {
                deposit.requiredFeeSats != null -> {
                    Text(
                        text = "Network Fee",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.55f)
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = formatSats(deposit.requiredFeeSats),
                            style = MaterialTheme.typography.bodyLarge,
                            color = Color.White
                        )
                        usdString(deposit.requiredFeeSats, btcUsdRate)?.let { feeUsd ->
                            Text(
                                text = " • $feeUsd",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White.copy(alpha = 0.55f)
                            )
                        }
                    }
                }

                !deposit.failureReason.isNullOrBlank() -> {
                    Text(
                        text = deposit.failureReason,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.62f)
                    )
                }

                else -> {
                    Text(
                        text = "Fee details unavailable.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.62f)
                    )
                }
            }
        }
    }
}

@Composable
private fun ClaimBitcoinDepositSheet(
    deposit: UnclaimedBitcoinDeposit,
    btcUsdRate: Double?,
    isClaiming: Boolean,
    onDismiss: () -> Unit,
    onClaim: suspend () -> Unit
) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.82f))
            .clickable(enabled = !isClaiming, onClick = onDismiss),
        contentAlignment = Alignment.BottomCenter
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .clickable(enabled = false) {},
            shape = RoundedCornerShape(28.dp),
            color = SplitSurface,
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.10f))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Deposit",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.06f))
                            .clickable(enabled = !isClaiming, onClick = onDismiss),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "Close deposit sheet",
                            tint = Color.White.copy(alpha = 0.70f),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color.White.copy(alpha = 0.05f))
                        .padding(vertical = 22.dp, horizontal = 18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = formatSats(deposit.amountSats),
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    usdString(deposit.amountSats, btcUsdRate)?.let { usd ->
                        Text(
                            text = usd,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.60f)
                        )
                    }
                }

                deposit.requiredFeeSats?.let { feeSats ->
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        color = Color.White.copy(alpha = 0.04f),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.10f))
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = "Network fee",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.55f)
                            )
                            Text(
                                text = buildString {
                                    append(formatSats(feeSats))
                                    usdString(feeSats, btcUsdRate)?.let { append(" • ").append(it) }
                                },
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White
                            )
                            deposit.currentMaxFeeDescription?.let { maxFee ->
                                Text(
                                    text = "Current max fee: $maxFee",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.White.copy(alpha = 0.45f)
                                )
                            }
                        }
                    }
                }

                if (!deposit.failureReason.isNullOrBlank()) {
                    Text(
                        text = deposit.failureReason,
                        style = MaterialTheme.typography.bodyMedium,
                        color = SplitBrandPink
                    )
                }

                Button(
                    onClick = {
                        scope.launch {
                            onClaim()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isClaiming && deposit.requiredFeeRateSatPerVbyte != null
                ) {
                    if (isClaiming) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = Color.White
                        )
                        Spacer(modifier = Modifier.size(10.dp))
                        Text("Claiming…")
                    } else {
                        Text("Claim Now")
                    }
                }
            }
        }
    }
}

private fun formatSats(value: Long): String {
    val formatter = NumberFormat.getIntegerInstance(Locale.US)
    return "${formatter.format(value)} sats"
}

private fun usdString(
    sats: Long,
    btcUsdRate: Double?
): String? {
    val price = btcUsdRate ?: return null
    val btc = sats.toDouble() / 100_000_000.0
    val usd = btc * price
    val formatter = NumberFormat.getCurrencyInstance(Locale.US)
    return formatter.format(usd)
}
