package com.split.android.ui.rewards

import androidx.activity.compose.BackHandler
import com.split.android.core.AppConfig
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Percent
import androidx.compose.material.icons.rounded.Person
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
import com.split.android.data.rewards.RewardStatsResponse
import com.split.android.ui.MainTabHeader
import com.split.android.ui.SplitFeatureIcons
import com.split.android.ui.SplitRootViewModel
import com.split.android.ui.theme.SplitBlack
import com.split.android.ui.theme.SplitBrandBlue
import com.split.android.ui.theme.SplitBrandPink
import java.text.NumberFormat
import java.util.Locale

internal val RewardsHowItWorksParagraphs = listOf(
    "Each month, Split sets aside Bitcoin in a rewards pool that's paid out at the end of the month.",
    "You earn a share of the Bitcoin pool based on the Bitcoin you spend with verified merchants.",
    "You get credited for all of the Bitcoin you spend with verified merchants.",
    "Your Bitcoin reward is determined by your percentage of spend relative to the platform. If you account for 5% of the platform's reward eligible spending, you receive 5% of the Bitcoin rewards pot.",
    "As reward eligible spend grows, we will grow the size of the Bitcoin rewards pool. Our goal is simple: Drive real world Bitcoin transactions.",
    "If you have any questions, comments, suggestions, or concerns please reach out to ${AppConfig.supportLightningAddress}"
)

@Composable
fun WalletRewardsScreen(
    rootViewModel: SplitRootViewModel,
    onOpenBitcoinEvents: () -> Unit,
    onOpenContacts: () -> Unit,
    onOpenProfile: () -> Unit,
    onOpenMerchantMap: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isLoading by remember { mutableStateOf(false) }
    var hasAttemptedLoad by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var stats by remember { mutableStateOf<RewardStatsResponse?>(null) }
    var btcUsdRate by remember { mutableStateOf<Double?>(null) }
    var activeOverlay by remember { mutableStateOf<RewardsOverlay?>(null) }

    LaunchedEffect(Unit) {
        suspend fun refresh() {
            if (isLoading) return
            hasAttemptedLoad = true
            isLoading = true
            errorMessage = null

            runCatching {
                val rewardStats = rootViewModel.fetchRewardsStats()
                val price = runCatching { rootViewModel.fetchBtcUsdPrice() }.getOrNull()
                rewardStats to price
            }.onSuccess { (response, price) ->
                stats = response
                btcUsdRate = price
            }.onFailure {
                errorMessage = "Failed to load rewards stats."
            }

            isLoading = false
        }

        refresh()
        if (stats == null && errorMessage == null) {
            repeat(2) {
                kotlinx.coroutines.delay(400)
                refresh()
                if (stats != null || errorMessage != null) return@LaunchedEffect
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(SplitBlack)
    ) {
        RewardsBackgroundAtmosphere()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = 18.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            MainTabHeader(
                onOpenBitcoinEvents = onOpenBitcoinEvents,
                onOpenContacts = onOpenContacts,
                onOpenProfile = onOpenProfile,
                onOpenMerchantMap = onOpenMerchantMap
            )

            RewardsHeader(
                onShowMerchantHelp = { activeOverlay = RewardsOverlay.MerchantHelp },
                onShowHowItWorks = { activeOverlay = RewardsOverlay.HowItWorks }
            )

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                when {
                    !hasAttemptedLoad || isLoading -> RewardsMessageCard(
                        title = "Loading rewards stats...",
                        message = null,
                        accent = SplitBrandBlue,
                        showProgress = true
                    )

                    !errorMessage.isNullOrBlank() -> RewardsMessageCard(
                        title = "Rewards unavailable",
                        message = errorMessage,
                        accent = SplitBrandPink
                    )

                    stats == null -> RewardsMessageCard(
                        title = "No rewards data yet",
                        message = "Spend through Split and this screen will start to fill in.",
                        accent = SplitBrandBlue
                    )

                    else -> RewardsHeroCard(
                        stats = stats!!,
                        btcUsdRate = btcUsdRate
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))
            }
        }

        when (activeOverlay) {
            RewardsOverlay.HowItWorks -> RewardsInfoOverlay(
                title = "How Rewards Work",
                paragraphs = RewardsHowItWorksParagraphs,
                icon = SplitFeatureIcons.Rewards,
                accent = Color.White,
                isFullScreen = true,
                onDismiss = { activeOverlay = null }
            )

            RewardsOverlay.MerchantHelp -> RewardsInfoOverlay(
                title = "Add Merchants",
                paragraphs = listOf(
                    "Paid a bitcoin-accepting merchant and didn't get rewarded? Submit the business from your transaction details. We'll add them to our rewards program ASAP.",
                    "Tap the storefront icon in a sent transaction to open the merchant submission flow.",
                    "The more verified merchants we add, the more accurately Split can reward real Bitcoin spending."
                ),
                icon = SplitFeatureIcons.Store,
                accent = SplitBrandBlue,
                onDismiss = { activeOverlay = null }
            )

            null -> Unit
        }
    }
}

private enum class RewardsOverlay {
    HowItWorks,
    MerchantHelp
}

@Composable
private fun RewardsBackgroundAtmosphere() {
    Column {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(170.dp)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.045f),
                            Color.Transparent
                        )
                    )
                )
        )
        Spacer(modifier = Modifier.weight(1f))
    }
}

@Composable
private fun RewardsHeader(
    onShowMerchantHelp: () -> Unit,
    onShowHowItWorks: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "Rewards",
            style = MaterialTheme.typography.headlineLarge,
            color = Color.White,
            fontWeight = FontWeight.Black
        )

        Spacer(modifier = Modifier.weight(1f))

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            RewardsHeaderIconButton(
                icon = SplitFeatureIcons.Store,
                border = SplitBrandBlue.copy(alpha = 0.35f),
                onClick = onShowMerchantHelp
            )
            RewardsHeaderIconButton(
                icon = SplitFeatureIcons.Rewards,
                border = Color.White.copy(alpha = 0.12f),
                onClick = onShowHowItWorks
            )
        }
    }
}

@Composable
private fun RewardsHeaderIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    border: Color,
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
            border = BorderStroke(1.dp, border)
        ) {}

        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(18.dp)
        )
    }
}

@Composable
private fun RewardsHeroCard(
    stats: RewardStatsResponse,
    btcUsdRate: Double?
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        ProjectedRewardSummary(
            stats = stats,
            btcUsdRate = btcUsdRate
        )

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            RewardsSectionLabel("Your Stats")

            RewardsDetailRow(
                title = "Your Share",
                subtitle = "Of this month's rewards pot",
                value = stats.stats.shareBps.toShareLabel(),
                icon = Icons.Rounded.Percent,
                accent = SplitBrandPink
            )

            RewardsDetailRow(
                title = "Your Reward Spend",
                subtitle = stats.user.transactions.toTransactionsLabel(),
                value = stats.user.rewardSpendCents.toUsdFromCents(),
                icon = Icons.Rounded.Person,
                accent = SplitBrandBlue
            )

            RewardsDetailRow(
                title = "Your Lifetime BTC",
                subtitle = "Paid rewards",
                value = stats.stats.lifetimeEarningsSats.toBtcLabel(),
                icon = SplitFeatureIcons.Rewards,
                accent = SplitBrandPink
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            RewardsSectionLabel("Platform")

            RewardsPlatformCard(
                rewardSpend = stats.platform.rewardSpendCents.toUsdFromCents(),
                transactions = stats.platform.transactions.toTransactionsLabel()
            )
        }
    }
}

@Composable
private fun ProjectedRewardSummary(
    stats: RewardStatsResponse,
    btcUsdRate: Double?
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(26.dp),
        color = SplitBlack,
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.16f))
    ) {
        Column(
            modifier = Modifier.padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Text(
                        text = "Monthly Rewards",
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color.White.copy(alpha = 0.66f),
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Current projection",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.46f),
                        fontWeight = FontWeight.Medium
                    )
                }

                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(Color.White.copy(alpha = 0.08f))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = stats.monthKey,
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White.copy(alpha = 0.76f),
                        fontWeight = FontWeight.Bold
                    )
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(SplitBrandPink)
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                HeroRewardMetric(
                    modifier = Modifier.weight(1f),
                    title = "Your Rewards",
                    primaryValue = stats.stats.projectedEarningsSats.toUsdFromSats(btcUsdRate),
                    secondaryValue = stats.stats.projectedEarningsSats.toBtcLabel(),
                    accent = SplitBrandPink
                )

                HeroRewardMetric(
                    modifier = Modifier.weight(1f),
                    title = "Rewards Pot",
                    primaryValue = stats.monthlyPot.sats.toUsdFromSats(btcUsdRate),
                    secondaryValue = stats.monthlyPot.sats.toBtcLabel(),
                    accent = SplitBrandBlue
                )
            }
        }
    }
}

@Composable
private fun HeroRewardMetric(
    modifier: Modifier = Modifier,
    title: String,
    primaryValue: String,
    secondaryValue: String,
    accent: Color
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        color = Color.White.copy(alpha = 0.055f),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f))
    ) {
        Column(
            modifier = Modifier.padding(15.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .width(30.dp)
                    .height(3.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(accent)
            )

            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = 0.58f),
                fontWeight = FontWeight.SemiBold
            )

            Text(
                text = primaryValue,
                style = MaterialTheme.typography.titleLarge,
                color = Color.White,
                fontWeight = FontWeight.Black,
                maxLines = 1
            )

            Text(
                text = secondaryValue,
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = 0.58f),
                fontWeight = FontWeight.SemiBold,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun RewardsSectionLabel(text: String) {
    Text(
        text = text.uppercase(Locale.US),
        modifier = Modifier.padding(horizontal = 2.dp),
        style = MaterialTheme.typography.labelMedium,
        color = Color.White.copy(alpha = 0.44f),
        fontWeight = FontWeight.Bold
    )
}

@Composable
private fun RewardsDetailRow(
    title: String,
    subtitle: String,
    value: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    accent: Color
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = Color(0xFF141830),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f))
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(accent.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.92f),
                    modifier = Modifier.size(20.dp)
                )
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.86f),
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.48f),
                    fontWeight = FontWeight.Medium
                )
            }

            Text(
                text = value,
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.End
            )
        }
    }
}

@Composable
private fun RewardsPlatformCard(
    rewardSpend: String,
    transactions: String
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = Color.White.copy(alpha = 0.045f),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f))
    ) {
        PlatformStatRow(
            title = "Platform Reward Spend",
            value = rewardSpend,
            detail = transactions,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 17.dp)
        )
    }
}

@Composable
private fun PlatformStatRow(
    title: String,
    value: String,
    detail: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.68f),
                fontWeight = FontWeight.Medium
            )
            Text(
                text = detail,
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = 0.44f),
                fontWeight = FontWeight.Medium
            )
        }

        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            color = Color.White,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.End
        )
    }
}

@Composable
private fun RewardsMessageCard(
    title: String,
    message: String?,
    accent: Color,
    showProgress: Boolean = false
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = Color.White.copy(alpha = 0.04f),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.10f))
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (showProgress) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = Color.White,
                    strokeWidth = 2.dp
                )
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = Color.White,
                fontWeight = FontWeight.Bold
            )
            if (!message.isNullOrBlank()) {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyLarge,
                    color = accent
                )
            }
        }
    }
}

@Composable
private fun RewardsInfoOverlay(
    title: String,
    paragraphs: List<String>,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    accent: Color,
    isFullScreen: Boolean = false,
    onDismiss: () -> Unit
) {
    BackHandler(onBack = onDismiss)

    if (isFullScreen) {
        RewardsFullScreenInfoOverlay(
            title = title,
            paragraphs = paragraphs,
            icon = icon,
            accent = accent,
            onDismiss = onDismiss
        )
        return
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.72f))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .clickable(enabled = false) {},
            shape = RoundedCornerShape(26.dp),
            color = Color(0xFF101013),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.10f))
        ) {
            Column(
                modifier = Modifier.padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.06f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = accent
                        )
                    }

                    Text(
                        text = title,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Black,
                        color = Color.White
                    )
                }

                paragraphs.forEach { paragraph ->
                    Text(
                        text = paragraph,
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color.White.copy(alpha = 0.78f)
                    )
                }

                if (title == "How Rewards Work") {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(18.dp),
                        color = Color.White.copy(alpha = 0.06f),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.10f))
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "Reward Eligible Spend",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White
                            )
                            Text(
                                text = "Rewards are based on Bitcoin spent with verified merchants listed in the app.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White.copy(alpha = 0.72f)
                            )
                        }
                    }
                }

                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onDismiss),
                    shape = RoundedCornerShape(18.dp),
                    color = Color.White.copy(alpha = 0.08f),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.10f))
                ) {
                    Text(
                        text = "Close",
                        modifier = Modifier.padding(vertical = 14.dp),
                        color = Color.White,
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

@Composable
private fun RewardsFullScreenInfoOverlay(
    title: String,
    paragraphs: List<String>,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    accent: Color,
    onDismiss: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.97f))
            .statusBarsPadding()
            .padding(horizontal = 16.dp)
            .padding(top = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = title,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = Color.White
            )

            Surface(
                modifier = Modifier.size(40.dp),
                shape = CircleShape,
                color = Color.White.copy(alpha = 0.06f),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.10f)),
                onClick = onDismiss
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = "Close",
                        tint = Color.White.copy(alpha = 0.80f),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            paragraphs.forEach { paragraph ->
                Text(
                    text = paragraph,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.88f)
                )
            }

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                color = Color.White.copy(alpha = 0.06f),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f))
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = SplitBrandPink,
                        modifier = Modifier.size(18.dp)
                    )

                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "Reward Eligible Spend",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White.copy(alpha = 0.85f)
                        )
                        Text(
                            text = "Rewards are based on Bitcoin spent with verified merchants listed in the app.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.65f)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))
        }

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 6.dp),
            shape = RoundedCornerShape(12.dp),
            color = Color.Transparent,
            onClick = onDismiss
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        brush = Brush.horizontalGradient(
                            colors = listOf(
                                SplitBrandPink.copy(alpha = 0.85f),
                                SplitBrandBlue.copy(alpha = 0.70f)
                            )
                        ),
                        shape = RoundedCornerShape(12.dp)
                    )
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Got it",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )
            }
        }
    }
}

private fun Long.toBtcLabel(): String {
    return String.format(Locale.US, "₿ %.8f", toDouble() / 100_000_000.0)
}

private fun Long.toUsdFromSats(btcUsdRate: Double?): String {
    val rate = btcUsdRate ?: return "USD unavailable"
    val usd = (toDouble() / 100_000_000.0) * rate
    return NumberFormat.getCurrencyInstance(Locale.US).format(usd)
}

private fun Long.toUsdFromCents(): String {
    return NumberFormat.getCurrencyInstance(Locale.US).format(toDouble() / 100.0)
}

private fun Int.toShareLabel(): String {
    return String.format(Locale.US, "%.2f%%", toDouble() / 100.0)
}

private fun Int.toTransactionsLabel(): String {
    return if (this == 1) "1 transaction" else "$this transactions"
}
