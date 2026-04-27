package com.split.android.ui.home

import android.app.DatePickerDialog
import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.split.android.data.wallet.PaymentUsdSnapshot
import com.split.android.data.wallet.SpendWalletSource
import com.split.android.data.wallet.WalletTransactionRow
import com.split.android.ui.SplitRootViewModel
import com.split.android.ui.theme.SplitBrandPink
import kotlinx.coroutines.launch
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Calendar
import java.util.Locale

@Composable
fun TransactionExportButton(
    enabled: Boolean,
    onClick: () -> Unit
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.35f))
    ) {
        Text(
            text = "Export",
            color = Color.White
        )
    }
}

@Composable
fun TransactionExportScreen(
    rootViewModel: SplitRootViewModel,
    transactions: List<WalletTransactionRow>,
    transactionSource: SpendWalletSource,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val zoneId = remember { ZoneId.systemDefault() }
    val todayStartMillis = remember(zoneId) {
        LocalDate.now(zoneId)
            .atStartOfDay(zoneId)
            .toInstant()
            .toEpochMilli()
    }

    var selectedPreset by remember { mutableStateOf(TransactionExportPreset.THIS_MONTH) }
    var selectedTransactionType by remember { mutableStateOf(TransactionExportType.ALL) }
    var customStartDateMillis by rememberSaveable { mutableStateOf(todayStartMillis) }
    var customEndDateMillis by rememberSaveable { mutableStateOf(todayStartMillis) }
    var isGenerating by remember { mutableStateOf(false) }
    var exportErrorMessage by remember { mutableStateOf<String?>(null) }
    var currentExportFile by remember { mutableStateOf<File?>(null) }
    val latestExportFile by rememberUpdatedState(currentExportFile)

    val shareLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        cleanupExportFile(currentExportFile)
        currentExportFile = null
    }

    DisposableEffect(Unit) {
        onDispose {
            cleanupExportFile(latestExportFile)
        }
    }

    val activeRange = remember(
        selectedPreset,
        customStartDateMillis,
        customEndDateMillis,
        zoneId
    ) {
        TransactionExportRange.make(
            preset = selectedPreset,
            customStartDateMillis = customStartDateMillis,
            customEndDateMillis = customEndDateMillis,
            zoneId = zoneId
        )
    }

    val filteredTransactions = remember(
        transactions,
        activeRange,
        selectedTransactionType
    ) {
        transactions
            .filter { transaction ->
                transaction.transactionTimestampMillis >= activeRange.startMillis &&
                    transaction.transactionTimestampMillis < activeRange.endExclusiveMillis
            }
            .filter { transaction ->
                selectedTransactionType.matches(transaction.direction)
            }
            .sortedBy { it.transactionTimestampMillis }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        TransactionScreenHeader(
            title = "Export Transactions",
            subtitle = "Choose a date range for your CSV export.",
            onBack = onDismiss
        )

        TransactionSectionCard("Date Range") {
            TransactionExportPreset.entries.forEach { preset ->
                ExportSelectionButton(
                    label = preset.label,
                    isSelected = selectedPreset == preset,
                    onClick = {
                        selectedPreset = preset
                        if (preset != TransactionExportPreset.CUSTOM) {
                            customStartDateMillis = todayStartMillis
                            customEndDateMillis = todayStartMillis
                        }
                    }
                )
            }
        }

        if (selectedPreset == TransactionExportPreset.CUSTOM) {
            TransactionSectionCard("Custom Range") {
                ExportDateButton(
                    label = "Start Date",
                    value = activeRange.displayDate(customStartDateMillis, zoneId)
                ) {
                    showDatePicker(
                        context = context,
                        initialMillis = customStartDateMillis,
                        minMillis = null
                    ) { selectedMillis ->
                        customStartDateMillis = selectedMillis
                        if (customEndDateMillis < selectedMillis) {
                            customEndDateMillis = selectedMillis
                        }
                    }
                }

                ExportDateButton(
                    label = "End Date",
                    value = activeRange.displayDate(customEndDateMillis, zoneId)
                ) {
                    showDatePicker(
                        context = context,
                        initialMillis = customEndDateMillis,
                        minMillis = customStartDateMillis
                    ) { selectedMillis ->
                        customEndDateMillis = selectedMillis
                    }
                }
            }
        }

        TransactionSectionCard("Transaction Type") {
            Text(
                text = "Transaction Type:",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                TransactionExportType.entries.forEach { type ->
                    ExportTypeButton(
                        label = type.label,
                        isSelected = selectedTransactionType == type,
                        onClick = { selectedTransactionType = type }
                    )
                }
            }
        }

        TransactionSectionCard("Summary") {
            Text(
                text = "Exported files may contain sensitive financial information.",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.70f)
            )
            Text(
                text = activeRange.summaryText(
                    transactionType = selectedTransactionType,
                    zoneId = zoneId
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.86f)
            )

            if (filteredTransactions.isEmpty()) {
                Text(
                    text = "No transactions match this range yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = SplitBrandPink
                )
            }
        }

        if (!exportErrorMessage.isNullOrBlank()) {
            WalletTransactionsMessageCard(
                title = "Export failed",
                body = exportErrorMessage!!
            )
        }

        OutlinedButton(
            onClick = onDismiss,
            modifier = Modifier.fillMaxWidth(),
            enabled = !isGenerating
        ) {
            Text("Close")
        }

        Button(
            onClick = {
                scope.launch {
                    isGenerating = true
                    exportErrorMessage = null

                    runCatching {
                        rootViewModel.ensureTransactionUsdSnapshots(
                            transactions = filteredTransactions,
                            source = transactionSource
                        )
                        val snapshots = rootViewModel.transactionUsdSnapshots(
                            paymentIds = filteredTransactions.map { it.id },
                            source = transactionSource
                        )

                        val exportFile = TransactionCsvExporter.makeFile(
                            context = context,
                            transactions = filteredTransactions,
                            range = activeRange,
                            transactionType = selectedTransactionType,
                            snapshots = snapshots
                        )

                        cleanupExportFile(currentExportFile)
                        currentExportFile = exportFile
                        shareLauncher.launch(buildShareIntent(context, exportFile))
                    }.onFailure { error ->
                        cleanupExportFile(currentExportFile)
                        currentExportFile = null
                        exportErrorMessage = error.message ?: "Unable to create CSV."
                    }

                    isGenerating = false
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = !isGenerating && filteredTransactions.isNotEmpty()
        ) {
            if (isGenerating) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    color = Color.Black,
                    strokeWidth = 2.dp
                )
            } else {
                Text("Generate CSV")
            }
        }
    }
}

@Composable
private fun ExportSelectionButton(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = Color.White.copy(alpha = if (isSelected) 0.11f else 0.06f),
        border = BorderStroke(
            1.dp,
            if (isSelected) SplitBrandPink.copy(alpha = 0.85f)
            else Color.White.copy(alpha = 0.10f)
        ),
        onClick = onClick
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = Color.White
            )
            Text(
                text = if (isSelected) "Selected" else "",
                style = MaterialTheme.typography.bodySmall,
                color = SplitBrandPink
            )
        }
    }
}

@Composable
private fun ExportDateButton(
    label: String,
    value: String,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = Color.White.copy(alpha = 0.06f),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.10f)),
        onClick = onClick
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.62f)
                )
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White
                )
            }

            Text(
                text = "Change",
                style = MaterialTheme.typography.bodyMedium,
                color = SplitBrandPink,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun RowScope.ExportTypeButton(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier.weight(1f),
        shape = RoundedCornerShape(12.dp),
        color = if (isSelected) SplitBrandPink else Color.White.copy(alpha = 0.06f),
        border = BorderStroke(
            1.dp,
            if (isSelected) SplitBrandPink
            else Color.White.copy(alpha = 0.12f)
        ),
        onClick = onClick
    ) {
        Box(
            modifier = Modifier.padding(vertical = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = Color.White
            )
        }
    }
}

private enum class TransactionExportPreset(
    val label: String
) {
    THIS_MONTH("This Month"),
    LAST_MONTH("Last Month"),
    YEAR_TO_DATE("Year to Date"),
    CUSTOM("Custom Range")
}

private enum class TransactionExportType(
    val label: String
) {
    ALL("All"),
    SENT("Sent"),
    RECEIVED("Received");

    fun matches(direction: String): Boolean {
        return when (this) {
            ALL -> true
            SENT -> direction.equals("sent", ignoreCase = true)
            RECEIVED -> direction.equals("received", ignoreCase = true)
        }
    }
}

private data class TransactionExportRange(
    val label: String,
    val startMillis: Long,
    val endInclusiveMillis: Long,
    val endExclusiveMillis: Long
) {
    fun summaryText(
        transactionType: TransactionExportType,
        zoneId: ZoneId
    ): String {
        return "$label • ${transactionType.label}: ${displayDateTime(startMillis, zoneId)} – ${displayDateTime(endInclusiveMillis, zoneId)}"
    }

    fun displayDate(timestampMillis: Long, zoneId: ZoneId): String {
        return Instant.ofEpochMilli(timestampMillis)
            .atZone(zoneId)
            .format(displayDateFormatter)
    }

    companion object {
        private val displayDateFormatter = DateTimeFormatter.ofPattern(
            "MMM d, uuuu",
            Locale.US
        )
        private val summaryDateTimeFormatter = DateTimeFormatter.ofPattern(
            "MMM d, uuuu, h:mm:ss a",
            Locale.US
        )

        fun make(
            preset: TransactionExportPreset,
            customStartDateMillis: Long,
            customEndDateMillis: Long,
            zoneId: ZoneId
        ): TransactionExportRange {
            val now = ZonedDateTime.now(zoneId)
            val nowMillis = now.toInstant().toEpochMilli()

            return when (preset) {
                TransactionExportPreset.THIS_MONTH -> {
                    val start = now.withDayOfMonth(1)
                        .toLocalDate()
                        .atStartOfDay(zoneId)
                        .toInstant()
                        .toEpochMilli()

                    TransactionExportRange(
                        label = preset.label,
                        startMillis = start,
                        endInclusiveMillis = nowMillis,
                        endExclusiveMillis = nowMillis + 1L
                    )
                }

                TransactionExportPreset.LAST_MONTH -> {
                    val thisMonthStart = now.withDayOfMonth(1)
                        .toLocalDate()
                        .atStartOfDay(zoneId)
                    val lastMonthStart = thisMonthStart.minusMonths(1)
                    val endExclusive = thisMonthStart.toInstant().toEpochMilli()

                    TransactionExportRange(
                        label = preset.label,
                        startMillis = lastMonthStart.toInstant().toEpochMilli(),
                        endInclusiveMillis = endExclusive - 1L,
                        endExclusiveMillis = endExclusive
                    )
                }

                TransactionExportPreset.YEAR_TO_DATE -> {
                    val start = now.withDayOfYear(1)
                        .toLocalDate()
                        .atStartOfDay(zoneId)
                        .toInstant()
                        .toEpochMilli()

                    TransactionExportRange(
                        label = preset.label,
                        startMillis = start,
                        endInclusiveMillis = nowMillis,
                        endExclusiveMillis = nowMillis + 1L
                    )
                }

                TransactionExportPreset.CUSTOM -> {
                    val startLocalDate = minOf(
                        Instant.ofEpochMilli(customStartDateMillis).atZone(zoneId).toLocalDate(),
                        Instant.ofEpochMilli(customEndDateMillis).atZone(zoneId).toLocalDate()
                    )
                    val endLocalDate = maxOf(
                        Instant.ofEpochMilli(customStartDateMillis).atZone(zoneId).toLocalDate(),
                        Instant.ofEpochMilli(customEndDateMillis).atZone(zoneId).toLocalDate()
                    )

                    val start = startLocalDate.atStartOfDay(zoneId).toInstant().toEpochMilli()
                    val endExclusive = endLocalDate.plusDays(1)
                        .atStartOfDay(zoneId)
                        .toInstant()
                        .toEpochMilli()

                    TransactionExportRange(
                        label = preset.label,
                        startMillis = start,
                        endInclusiveMillis = endExclusive - 1L,
                        endExclusiveMillis = endExclusive
                    )
                }
            }
        }

        private fun displayDateTime(timestampMillis: Long, zoneId: ZoneId): String {
            return Instant.ofEpochMilli(timestampMillis)
                .atZone(zoneId)
                .format(summaryDateTimeFormatter)
        }
    }
}

private object TransactionCsvExporter {
    private val metadataDateFormatter = DateTimeFormatter.ofPattern(
        "MMM d, uuuu, h:mm:ss a",
        Locale.US
    )

    private val rowDateFormatter = DateTimeFormatter.ofPattern(
        "yyyy-MM-dd HH:mm:ss Z",
        Locale.US
    )

    fun makeFile(
        context: Context,
        transactions: List<WalletTransactionRow>,
        range: TransactionExportRange,
        transactionType: TransactionExportType,
        snapshots: Map<String, PaymentUsdSnapshot>
    ): File {
        val csv = makeCsv(
            transactions = transactions,
            range = range,
            transactionType = transactionType,
            snapshots = snapshots
        )

        val directory = File(context.cacheDir, "shared/transaction_exports").apply {
            if (!exists()) {
                mkdirs()
            }
        }

        val file = nextAvailableFile(directory)
        file.writeText(csv, Charsets.UTF_8)
        return file
    }

    private fun makeCsv(
        transactions: List<WalletTransactionRow>,
        range: TransactionExportRange,
        transactionType: TransactionExportType,
        snapshots: Map<String, PaymentUsdSnapshot>
    ): String {
        val rows = mutableListOf(
            csvRow(listOf("Export Name", "split-transactions")),
            csvRow(listOf("Date Range", range.label)),
            csvRow(listOf("Transaction Type", transactionType.label)),
            csvRow(listOf("Start Date", metadataDateFormatter.format(Instant.ofEpochMilli(range.startMillis).atZone(ZoneId.systemDefault())))),
            csvRow(listOf("End Date", metadataDateFormatter.format(Instant.ofEpochMilli(range.endInclusiveMillis).atZone(ZoneId.systemDefault())))),
            csvRow(listOf("Generated At", metadataDateFormatter.format(ZonedDateTime.now(ZoneId.systemDefault())))),
            "",
            csvRow(
                listOf(
                    "Date",
                    "Type",
                    "Network",
                    "Amount Sats",
                    "Amount BTC",
                    "Fee Sats",
                    "Fee BTC",
                    "BTC/USD Rate At Transaction",
                    "USD Value At Transaction",
                    "Reportable Status",
                    "Status",
                    "Note"
                )
            )
        )

        transactions.forEach { transaction ->
            val snapshot = snapshots[transaction.id]
            rows += csvRow(
                listOf(
                    rowDateFormatter.format(
                        Instant.ofEpochMilli(transaction.transactionTimestampMillis)
                            .atZone(ZoneId.systemDefault())
                    ),
                    transaction.direction.replaceFirstChar { it.uppercase() },
                    transaction.network.replaceFirstChar { it.uppercase() },
                    transaction.amountSats.toString(),
                    transaction.btcAmount,
                    transaction.feeSats.toString(),
                    transaction.feeBtcAmount,
                    snapshot?.btcUsdRateAtTransaction?.let {
                        String.format(Locale.US, "%.8f", it)
                    }.orEmpty(),
                    snapshot?.usdValueAtTransaction?.let {
                        String.format(Locale.US, "%.2f", it)
                    }.orEmpty(),
                    formatReportableStatus(snapshot),
                    transaction.status,
                    transaction.note
                )
            )
        }

        return rows.joinToString(separator = "\n")
    }

    private fun csvRow(values: List<String>): String {
        return values.joinToString(separator = ",") { csvEscape(it) }
    }

    private fun csvEscape(value: String): String {
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"${value.replace("\"", "\"\"")}\""
        }
        return value
    }

    private fun formatReportableStatus(snapshot: PaymentUsdSnapshot?): String {
        return if (snapshot?.isReportable == true) {
            "Reportable"
        } else {
            "Non-reportable"
        }
    }

    private fun nextAvailableFile(directory: File): File {
        var suffix = 1
        while (true) {
            val name = if (suffix == 1) {
                "split-transactions.csv"
            } else {
                "split-transactions$suffix.csv"
            }

            val candidate = File(directory, name)
            if (!candidate.exists()) {
                return candidate
            }
            suffix += 1
        }
    }
}

private fun buildShareIntent(
    context: Context,
    file: File
): Intent {
    val uri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        file
    )

    val shareIntent = Intent(Intent.ACTION_SEND).apply {
        type = "text/csv"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, file.name)
        clipData = ClipData.newUri(context.contentResolver, file.name, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    return Intent.createChooser(shareIntent, "Share transactions CSV")
}

private fun cleanupExportFile(file: File?) {
    if (file == null || !file.exists()) return
    runCatching {
        file.delete()
    }
}

private fun showDatePicker(
    context: Context,
    initialMillis: Long,
    minMillis: Long?,
    onDateSelected: (Long) -> Unit
) {
    val calendar = Calendar.getInstance().apply {
        timeInMillis = initialMillis
    }

    DatePickerDialog(
        context,
        { _, year, month, dayOfMonth ->
            val selectedCalendar = Calendar.getInstance().apply {
                set(Calendar.YEAR, year)
                set(Calendar.MONTH, month)
                set(Calendar.DAY_OF_MONTH, dayOfMonth)
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            onDateSelected(selectedCalendar.timeInMillis)
        },
        calendar.get(Calendar.YEAR),
        calendar.get(Calendar.MONTH),
        calendar.get(Calendar.DAY_OF_MONTH)
    ).apply {
        datePicker.maxDate = System.currentTimeMillis()
        if (minMillis != null) {
            datePicker.minDate = minMillis
        }
    }.show()
}
