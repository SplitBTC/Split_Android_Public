package com.split.android.data.wallet

internal enum class WalletConnectionSource {
    CONFIGURE,
    CREATE,
    RESTORE
}

internal fun normalizeRecoveryPhraseWords(raw: String): List<String> {
    return raw
        .lowercase()
        .trim()
        .split(Regex("\\s+"))
        .map(::normalizeRecoveryPhraseWord)
        .filter { it.isNotEmpty() }
}

internal fun normalizeRecoveryPhraseWord(word: String): String {
    return word.trim().trim(',', '.', ';', ':', '"', '\'', '(', ')', '[', ']', '{', '}')
}

internal fun validateRecoveryPhraseWords(
    words: List<String>
): String? {
    if (words.isEmpty()) {
        return "Enter a recovery phrase to continue."
    }

    if (words.size < 12) {
        return "Seed phrase too short. Expected at least 12 words."
    }

    if (words.size != 12 && words.size != 24) {
        return "Recovery phrase must use 12 or 24 words."
    }

    if (words.any { !isReasonableRecoveryPhraseWord(it) }) {
        return invalidRecoveryPhraseMessage()
    }

    return null
}

internal fun walletConnectionFailureMessage(
    error: Throwable,
    source: WalletConnectionSource
): String {
    val rootCause = unwrapWalletFailure(error)
    val rawMessage = listOfNotNull(
        rootCause.message?.trim()?.takeIf { it.isNotEmpty() },
        rootCause.localizedMessage?.trim()?.takeIf { it.isNotEmpty() },
        rootCause.toString().trim().takeIf { it.isNotEmpty() },
        error.message?.trim()?.takeIf { it.isNotEmpty() },
        error.localizedMessage?.trim()?.takeIf { it.isNotEmpty() },
        error.toString().trim().takeIf { it.isNotEmpty() }
    ).firstOrNull().orEmpty()

    val normalizedMessage = rawMessage.lowercase()

    if (source == WalletConnectionSource.RESTORE &&
        looksLikeInvalidRecoveryPhraseError(error)
    ) {
        return invalidRecoveryPhraseMessage()
    }

    if (looksLikeSdkChecksumFailure(normalizedMessage)) {
        return when (source) {
            WalletConnectionSource.CREATE ->
                "Wallet SDK integrity check failed in this Android build. Please reinstall the latest app build and try again."
            WalletConnectionSource.RESTORE ->
                "Wallet SDK integrity check failed in this Android build. Please reinstall the latest app build and try again."
            WalletConnectionSource.CONFIGURE ->
                "Wallet SDK integrity check failed in this Android build. Please reinstall the latest app build and try again."
        }
    }

    if (looksLikeNativeLibraryFailure(normalizedMessage)) {
        return when (source) {
            WalletConnectionSource.CREATE ->
                "Wallet SDK failed to initialize on this device. Please reinstall the app or try a newer 64-bit Android device."
            WalletConnectionSource.RESTORE ->
                "Wallet SDK failed to initialize on this device. Please reinstall the app or try a newer 64-bit Android device."
            WalletConnectionSource.CONFIGURE ->
                "Wallet SDK failed to initialize on this device. Please reinstall the app or try a newer 64-bit Android device."
        }
    }

    if (normalizedMessage.contains("breez api key")) {
        return when (source) {
            WalletConnectionSource.CREATE ->
                "Wallet services are temporarily unavailable. Please try creating the wallet again in a minute."
            WalletConnectionSource.RESTORE ->
                "Wallet services are temporarily unavailable. Please try restoring again in a minute."
            WalletConnectionSource.CONFIGURE ->
                "Wallet services are temporarily unavailable. Please try opening the wallet again in a minute."
        }
    }

    if (looksLikeNetworkFailure(normalizedMessage)) {
        return when (source) {
            WalletConnectionSource.CREATE ->
                "Unable to finish wallet setup right now. Split or Breez may be temporarily unreachable. Please try again in a minute."
            WalletConnectionSource.RESTORE ->
                "Unable to restore this wallet right now. Split or Breez may be temporarily unreachable. Please try again in a minute."
            WalletConnectionSource.CONFIGURE ->
                "Unable to reconnect your wallet right now. Split or Breez may be temporarily unreachable. Please try again in a minute."
        }
    }

    if (looksLikeSdkInternalFailure(normalizedMessage) || rawMessage.isBlank()) {
        return when (source) {
            WalletConnectionSource.CREATE ->
                "Unable to finish wallet setup right now. Please try again."
            WalletConnectionSource.RESTORE ->
                "Unable to restore this wallet right now. Double-check the phrase and try again."
            WalletConnectionSource.CONFIGURE ->
                "Unable to reconnect your wallet right now. Please try again."
        }
    }

    return rawMessage
}

internal fun looksLikeInvalidRecoveryPhraseError(error: Throwable): Boolean {
    val normalizedDescription = listOfNotNull(
        unwrapWalletFailure(error).message,
        unwrapWalletFailure(error).localizedMessage,
        unwrapWalletFailure(error).toString(),
        error.message,
        error.localizedMessage,
        error.toString()
    ).joinToString(" ").lowercase()

    if (!normalizedDescription.contains("mnemonic")) {
        return false
    }

    return normalizedDescription.contains("unknown word") ||
        normalizedDescription.contains("unknow word") ||
        normalizedDescription.contains("invalid mnemonic") ||
        normalizedDescription.contains("invalid word") ||
        normalizedDescription.contains("mnemonic contains")
}

internal fun walletRecoveryFailureMessage(error: Throwable): String {
    val rootCause = unwrapWalletFailure(error)
    val rawMessage = listOfNotNull(
        rootCause.message?.trim()?.takeIf { it.isNotEmpty() },
        rootCause.localizedMessage?.trim()?.takeIf { it.isNotEmpty() },
        rootCause.toString().trim().takeIf { it.isNotEmpty() },
        error.message?.trim()?.takeIf { it.isNotEmpty() },
        error.localizedMessage?.trim()?.takeIf { it.isNotEmpty() },
        error.toString().trim().takeIf { it.isNotEmpty() }
    ).firstOrNull().orEmpty()

    val normalizedMessage = rawMessage.lowercase()

    if (looksLikeSdkChecksumFailure(normalizedMessage)) {
        return "Wallet SDK integrity check failed in this Android build. Please reinstall the latest app build and try again."
    }

    if (looksLikeNativeLibraryFailure(normalizedMessage)) {
        return "Wallet SDK failed to initialize on this device. Please reinstall the app or try a newer 64-bit Android device."
    }

    if (normalizedMessage.contains("breez api key")) {
        return "Wallet services are temporarily unavailable. Please try opening the wallet again in a minute."
    }

    if (looksLikeNetworkFailure(normalizedMessage)) {
        return "Unable to reconnect your wallet right now. Split or Breez may be temporarily unreachable. Please try again in a minute."
    }

    if (looksLikeSdkInternalFailure(normalizedMessage) || rawMessage.isBlank()) {
        return "We found a wallet on this device, but Split couldn't reopen it automatically. Try again, restore the wallet, or create a new one."
    }

    return rawMessage
}

internal fun walletSecureStorageFailureMessage(error: Throwable): String {
    val rawMessage = listOfNotNull(
        unwrapWalletFailure(error).message?.trim()?.takeIf { it.isNotEmpty() },
        unwrapWalletFailure(error).localizedMessage?.trim()?.takeIf { it.isNotEmpty() },
        error.message?.trim()?.takeIf { it.isNotEmpty() },
        error.localizedMessage?.trim()?.takeIf { it.isNotEmpty() }
    ).firstOrNull().orEmpty()

    if (rawMessage.isBlank()) {
        return "Split couldn't access secure wallet storage on this device. Try again, restore the wallet, or create a new wallet."
    }

    return "Split couldn't access secure wallet storage on this device. Try again, restore the wallet, or create a new wallet.\n\n$rawMessage"
}

internal fun isReasonableRecoveryPhraseWord(word: String): Boolean {
    return word.isNotEmpty() && word.all { it in 'a'..'z' }
}

internal fun unwrapWalletFailure(error: Throwable): Throwable {
    val seen = mutableSetOf<Throwable>()
    var current: Throwable = error
    seen += current

    while (true) {
        val next = when (current) {
            is ExceptionInInitializerError -> current.exception ?: current.cause
            else -> current.cause
        } ?: break

        if (!seen.add(next)) {
            break
        }

        current = next
    }

    return current
}

internal fun walletFailureDebugSummary(error: Throwable): String {
    val chain = mutableListOf<String>()
    val seen = mutableSetOf<Throwable>()
    var current: Throwable? = error

    while (current != null && seen.add(current)) {
        val label = current::class.java.name
        val message = current.message?.trim().orEmpty()
        chain += if (message.isEmpty()) {
            label
        } else {
            "$label: $message"
        }

        current = when (current) {
            is ExceptionInInitializerError -> current.exception ?: current.cause
            else -> current.cause
        }
    }

    return chain.joinToString(" <- ")
}

private fun invalidRecoveryPhraseMessage(): String {
    return "That recovery phrase looks invalid. Double-check the words and their order."
}

private fun looksLikeSdkChecksumFailure(message: String): Boolean {
    return message.contains("uniffi api checksum mismatch") ||
        message.contains("uniffi contract version mismatch")
}

private fun looksLikeNativeLibraryFailure(message: String): Boolean {
    return message.contains("no implementation found") ||
        message.contains("unsatisfiedlinkerror") ||
        message.contains("dlopen failed") ||
        message.contains("native register") ||
        message.contains("couldn't find")
}

private fun looksLikeSdkInternalFailure(message: String): Boolean {
    if (message.isBlank()) {
        return true
    }

    return message.contains("ffi") ||
        message.contains("breez_sdk") ||
        message.contains("breez sdk") ||
        message.contains("breezsdk") ||
        message.contains("uniffi")
}

private fun looksLikeNetworkFailure(message: String): Boolean {
    return message.contains("timeout") ||
        message.contains("timed out") ||
        message.contains("network") ||
        message.contains("socket") ||
        message.contains("unable to resolve host") ||
        message.contains("no route to host") ||
        message.contains("connection reset") ||
        message.contains("failed to connect") ||
        message.contains("host unreachable") ||
        message.contains("dns")
}
