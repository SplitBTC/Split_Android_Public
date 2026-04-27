package com.split.android.data.wallet

import android.content.Context
import java.security.MessageDigest
import java.security.SecureRandom

class MnemonicGenerator(
    private val appContext: Context
) {
    fun generateWords(): List<String> {
        val wordlist = loadWordlist()
        require(wordlist.size == BIP39_WORD_COUNT) {
            "BIP39 wordlist must contain exactly $BIP39_WORD_COUNT words."
        }

        val entropy = ByteArray(16)
        SecureRandom().nextBytes(entropy)

        val checksumBits = checksumNibble(entropy)
        val allBits = buildString {
            entropy.forEach { byte ->
                append(
                    Integer.toBinaryString(byte.toInt() and 0xFF)
                        .padStart(8, '0')
                )
            }
            append(Integer.toBinaryString(checksumBits).padStart(4, '0'))
        }

        return List(12) { index ->
            val start = index * 11
            val end = start + 11
            val wordIndex = allBits.substring(start, end).toInt(radix = 2)
            wordlist[wordIndex]
        }
    }

    fun validWordSet(): Set<String> {
        return loadWordSet()
    }

    private fun loadWordlist(): List<String> {
        cachedWordlist?.let { return it }

        val wordlist = appContext.assets.open("bip39-english.txt").bufferedReader().use { reader ->
            reader.readLines().filter { it.isNotBlank() }
        }

        cachedWordlist = wordlist
        cachedWordSet = wordlist.toSet()
        return wordlist
    }

    private fun loadWordSet(): Set<String> {
        cachedWordSet?.let { return it }
        return loadWordlist().toSet().also { cachedWordSet = it }
    }

    private fun checksumNibble(entropy: ByteArray): Int {
        val hash = MessageDigest.getInstance("SHA-256").digest(entropy)
        return (hash[0].toInt() and 0xFF) ushr 4
    }

    private companion object {
        const val BIP39_WORD_COUNT = 2048
    }

    @Volatile
    private var cachedWordlist: List<String>? = null

    @Volatile
    private var cachedWordSet: Set<String>? = null
}
