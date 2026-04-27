package com.split.android.data.messages

object MessageDirectoryVerifier {
    fun verifyDirectoryProof(
        binding: MessagingIdentityBindingPayload,
        directory: MessagingDirectoryProofPayload
    ) {
        val computedLeafHash = sha256(
            MessageBindingVerifier.buildDirectoryLeafMessage(binding).toByteArray(Charsets.UTF_8)
        ).toHex()

        require(computedLeafHash == directory.leafHash.trim().lowercase()) {
            "The messaging directory leaf hash is invalid."
        }

        var cursorHash = computedLeafHash
        directory.proof.forEach { node ->
            val siblingHash = node.hash.trim().lowercase()
            cursorHash = when (node.position.trim().lowercase()) {
                "left" -> combineHashes(siblingHash, cursorHash)
                "right" -> combineHashes(cursorHash, siblingHash)
                else -> throw IllegalArgumentException("The messaging directory proof position is invalid.")
            }
        }

        require(cursorHash == directory.checkpoint.rootHash.trim().lowercase()) {
            "The messaging directory root hash does not match the binding proof."
        }
    }

    private fun combineHashes(leftHex: String, rightHex: String): String {
        val leftBytes = leftHex.hexToByteArray(strictLength = 32)
        val rightBytes = rightHex.hexToByteArray(strictLength = 32)
        return sha256(leftBytes + rightBytes).toHex()
    }
}
