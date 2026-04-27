package com.split.android.ui.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.split.android.ui.theme.SplitBlack
import com.split.android.ui.theme.SplitBrandBlue
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CashAppAmountOverlay(
    btcUsdRate: Double?,
    isStarting: Boolean,
    onStart: (amountSats: Long) -> Unit,
    onCancel: () -> Unit
) {
    var amountUnit by remember { mutableStateOf(CashAppAmountUnit.USD) }
    var amountText by remember { mutableStateOf("") }

    val amountSats = remember(amountUnit, amountText, btcUsdRate) {
        CashAppAmountCalculator.parseAmountSats(
            unit = amountUnit,
            inputText = amountText,
            btcUsdRate = btcUsdRate
        )
    }
    val helperText = remember(amountUnit, amountText, amountSats, btcUsdRate) {
        CashAppAmountCalculator.helperText(
            unit = amountUnit,
            inputText = amountText,
            amountSats = amountSats,
            btcUsdRate = btcUsdRate
        )
    }
    val quickAmounts = remember(amountUnit) { CashAppAmountCalculator.quickAmounts(amountUnit) }
    val canContinue = !isStarting && amountSats != null

    fun switchUnit(nextUnit: CashAppAmountUnit) {
        if (nextUnit == amountUnit) return
        amountText = CashAppAmountCalculator.convertInputText(
            fromUnit = amountUnit,
            toUnit = nextUnit,
            inputText = amountText,
            btcUsdRate = btcUsdRate
        )
        amountUnit = nextUnit
    }

    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
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
                    .padding(horizontal = 20.dp, vertical = 24.dp)
                    .padding(bottom = 132.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Purchase Amount",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Black,
                        color = Color.White
                    )

                    Spacer(modifier = Modifier.weight(1f))

                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .border(
                                width = 1.dp,
                                color = Color.White.copy(alpha = 0.10f),
                                shape = RoundedCornerShape(18.dp)
                            )
                            .background(
                                color = Color.White.copy(alpha = 0.08f),
                                shape = RoundedCornerShape(18.dp)
                            )
                            .clickable(onClick = onCancel),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "Close",
                            tint = Color.White.copy(alpha = 0.82f)
                        )
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "Purchase amount",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.62f)
                    )

                    TextField(
                        value = amountText,
                        onValueChange = {
                            amountText = CashAppAmountCalculator.sanitizeInput(
                                unit = amountUnit,
                                value = it
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(
                                width = 1.dp,
                                color = Color.White.copy(alpha = 0.08f),
                                shape = RoundedCornerShape(22.dp)
                            ),
                        singleLine = true,
                        placeholder = {
                            Text(
                                text = if (amountUnit == CashAppAmountUnit.USD) "0.00" else "1000",
                                color = Color.White.copy(alpha = 0.20f)
                            )
                        },
                        prefix = if (amountUnit == CashAppAmountUnit.USD) {
                            {
                                Text(
                                    text = "$",
                                    color = Color.White.copy(alpha = 0.72f),
                                    style = MaterialTheme.typography.headlineSmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        } else {
                            null
                        },
                        suffix = if (amountUnit == CashAppAmountUnit.SATS) {
                            {
                                Text(
                                    text = "sats",
                                    color = Color.White.copy(alpha = 0.72f),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        } else {
                            null
                        },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = if (amountUnit == CashAppAmountUnit.USD) {
                                KeyboardType.Decimal
                            } else {
                                KeyboardType.Number
                            }
                        ),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.White.copy(alpha = 0.06f),
                            unfocusedContainerColor = Color.White.copy(alpha = 0.06f),
                            disabledContainerColor = Color.White.copy(alpha = 0.06f),
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            disabledTextColor = Color.White.copy(alpha = 0.60f),
                            cursorColor = Color.White,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            disabledIndicatorColor = Color.Transparent,
                            focusedPlaceholderColor = Color.White.copy(alpha = 0.20f),
                            unfocusedPlaceholderColor = Color.White.copy(alpha = 0.20f)
                        ),
                        shape = RoundedCornerShape(22.dp),
                        textStyle = MaterialTheme.typography.displaySmall.copy(
                            fontWeight = FontWeight.Bold
                        )
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    CashAppUnitButton(
                        title = "USD",
                        selected = amountUnit == CashAppAmountUnit.USD,
                        onClick = { switchUnit(CashAppAmountUnit.USD) },
                        modifier = Modifier.weight(1f)
                    )
                    CashAppUnitButton(
                        title = "Sats",
                        selected = amountUnit == CashAppAmountUnit.SATS,
                        onClick = { switchUnit(CashAppAmountUnit.SATS) },
                        modifier = Modifier.weight(1f)
                    )
                }

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    quickAmounts.forEach { option ->
                        CashAppQuickAmountChip(
                            label = option.label,
                            onClick = { amountText = option.rawValue }
                        )
                    }
                }

                helperText?.let { text ->
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.62f)
                    )
                }
            }

            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(SplitBlack)
                    .navigationBarsPadding()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(Color.White.copy(alpha = 0.06f))
                )

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        onClick = {
                            val sats = amountSats ?: return@Button
                            onStart(sats)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = canContinue,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = SplitBrandBlue,
                            contentColor = Color.White
                        )
                    ) {
                        if (isStarting) {
                            CircularProgressIndicator(
                                strokeWidth = 2.dp,
                                color = Color.White
                            )
                        } else {
                            Text("Continue to Cash App")
                        }
                    }

                    Text(
                        text = "Cancel",
                        modifier = Modifier
                            .align(Alignment.CenterHorizontally)
                            .clickable(enabled = !isStarting, onClick = onCancel),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White.copy(alpha = 0.72f)
                    )
                }
            }
        }
    }
}

internal enum class CashAppAmountUnit {
    USD,
    SATS
}

internal data class CashAppQuickAmount(
    val label: String,
    val rawValue: String
)

@Composable
private fun CashAppUnitButton(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .border(
                width = 1.dp,
                color = if (selected) Color.White else Color.White.copy(alpha = 0.14f),
                shape = RoundedCornerShape(16.dp)
            )
            .background(
                color = if (selected) Color.White else Color.White.copy(alpha = 0.04f),
                shape = RoundedCornerShape(16.dp)
            )
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            color = if (selected) Color.Black else Color.White
        )
    }
}

@Composable
private fun CashAppQuickAmountChip(
    label: String,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .border(
                width = 1.dp,
                color = Color.White.copy(alpha = 0.10f),
                shape = RoundedCornerShape(16.dp)
            )
            .background(
                color = Color.White.copy(alpha = 0.05f),
                shape = RoundedCornerShape(16.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 14.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = Color.White
        )
    }
}

internal object CashAppAmountCalculator {
    private val satsPerBitcoin = BigDecimal("100000000")

    fun quickAmounts(unit: CashAppAmountUnit): List<CashAppQuickAmount> {
        return when (unit) {
            CashAppAmountUnit.USD -> listOf(
                CashAppQuickAmount(label = "$25", rawValue = "25"),
                CashAppQuickAmount(label = "$50", rawValue = "50"),
                CashAppQuickAmount(label = "$100", rawValue = "100"),
                CashAppQuickAmount(label = "$250", rawValue = "250")
            )

            CashAppAmountUnit.SATS -> listOf(
                CashAppQuickAmount(label = "10k sats", rawValue = "10000"),
                CashAppQuickAmount(label = "25k sats", rawValue = "25000"),
                CashAppQuickAmount(label = "50k sats", rawValue = "50000"),
                CashAppQuickAmount(label = "100k sats", rawValue = "100000")
            )
        }
    }

    fun sanitizeInput(unit: CashAppAmountUnit, value: String): String {
        if (unit == CashAppAmountUnit.SATS) {
            return value.filter { it.isDigit() }.trimStart('0')
        }

        val builder = StringBuilder()
        var hasDecimal = false
        var fractionDigits = 0

        value.forEach { character ->
            when {
                character.isDigit() -> {
                    if (hasDecimal) {
                        if (fractionDigits >= 2) return@forEach
                        fractionDigits += 1
                    }
                    builder.append(character)
                }

                character == '.' && !hasDecimal -> {
                    hasDecimal = true
                    if (builder.isEmpty()) {
                        builder.append("0")
                    }
                    builder.append(character)
                }
            }
        }

        return builder.toString()
    }

    fun parseAmountSats(
        unit: CashAppAmountUnit,
        inputText: String,
        btcUsdRate: Double?
    ): Long? {
        val cleaned = inputText
            .trim()
            .replace("$", "")
            .replace(",", "")

        val decimalValue = cleaned.toBigDecimalOrNull()
            ?.takeIf { it > BigDecimal.ZERO }
            ?: return null

        val satsValue = when (unit) {
            CashAppAmountUnit.USD -> {
                val rate = btcUsdRate?.takeIf { it > 0.0 }?.toBigDecimal() ?: return null
                decimalValue
                    .divide(rate, 16, RoundingMode.HALF_UP)
                    .multiply(satsPerBitcoin)
            }

            CashAppAmountUnit.SATS -> decimalValue
        }

        val rounded = satsValue.setScale(0, RoundingMode.HALF_UP)
        return runCatching { rounded.longValueExact() }
            .getOrNull()
            ?.takeIf { it > 0L }
    }

    fun convertInputText(
        fromUnit: CashAppAmountUnit,
        toUnit: CashAppAmountUnit,
        inputText: String,
        btcUsdRate: Double?
    ): String {
        val amountSats = parseAmountSats(
            unit = fromUnit,
            inputText = inputText,
            btcUsdRate = btcUsdRate
        ) ?: return ""

        return when (toUnit) {
            CashAppAmountUnit.USD -> {
                val rate = btcUsdRate?.takeIf { it > 0.0 }?.toBigDecimal() ?: return ""
                val usdAmount = BigDecimal.valueOf(amountSats)
                    .divide(satsPerBitcoin, 16, RoundingMode.HALF_UP)
                    .multiply(rate)
                formatUsdInput(usdAmount)
            }

            CashAppAmountUnit.SATS -> amountSats.toString()
        }
    }

    fun helperText(
        unit: CashAppAmountUnit,
        inputText: String,
        amountSats: Long?,
        btcUsdRate: Double?
    ): String? {
        if (amountSats != null) {
            return when (unit) {
                CashAppAmountUnit.USD -> {
                    "Approx. ${formatSatsDisplay(amountSats)}"
                }

                CashAppAmountUnit.SATS -> {
                    val rate = btcUsdRate?.takeIf { it > 0.0 }?.toBigDecimal() ?: return null
                    val usdAmount = BigDecimal.valueOf(amountSats)
                        .divide(satsPerBitcoin, 16, RoundingMode.HALF_UP)
                        .multiply(rate)
                    "Approx. ${formatUsdDisplay(usdAmount)}"
                }
            }
        }

        if (inputText.isBlank()) {
            return null
        }

        return if (unit == CashAppAmountUnit.USD && (btcUsdRate == null || btcUsdRate <= 0.0)) {
            "Waiting for Bitcoin price data."
        } else {
            null
        }
    }

    fun formatUsdDisplay(amountUsd: BigDecimal): String {
        val formatter = NumberFormat.getCurrencyInstance(Locale.US)
        formatter.maximumFractionDigits = 2
        formatter.minimumFractionDigits = 2
        return formatter.format(amountUsd.setScale(2, RoundingMode.HALF_UP))
    }

    private fun formatUsdInput(amountUsd: BigDecimal): String {
        return amountUsd
            .setScale(2, RoundingMode.HALF_UP)
            .toPlainString()
    }

    private fun formatSatsDisplay(amountSats: Long): String {
        val formatter = NumberFormat.getIntegerInstance(Locale.US)
        return "${formatter.format(amountSats)} sats"
    }
}
