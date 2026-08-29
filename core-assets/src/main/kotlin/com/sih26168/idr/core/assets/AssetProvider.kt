package com.sih26168.idr.core.assets

import java.io.File
import java.security.MessageDigest

data class AssetHandle(
    val file: File,
    val manifest: AssetManifest,
)

enum class AssetSource { IMPORTED, DOWNLOADED, PACKAGED }

class AssetProvider(
    private val candidatesByKind: Map<String, List<Pair<AssetSource, File>>>,
    private val manifestReader: (File) -> AssetManifest,
) {

    fun resolve(kind: String): AssetHandle? {
        val candidates = candidatesByKind[kind].orEmpty()
        val bySource = candidates.sortedBy {
            when (it.first) {
                AssetSource.IMPORTED -> 0
                AssetSource.DOWNLOADED -> 1
                AssetSource.PACKAGED -> 2
            }
        }
        val chosen = bySource.firstOrNull() ?: return null
        val file = chosen.second
        val manifest = manifestReader(file)
        Checksums.verifyOrThrow(file, manifest.sha256, manifest.assetId)
        return AssetHandle(file, manifest)
    }
}

object Checksums {
    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(8192)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun verifyOrThrow(file: File, expectedSha256: String, assetId: String) {
        if (expectedSha256.isBlank()) return
        val actual = sha256(file)
        if (!actual.equals(expectedSha256, ignoreCase = true)) {
            throw AssetManifestMismatchException(
                "checksum mismatch for asset '$assetId': manifest says $expectedSha256, file is $actual"
            )
        }
    }
}
