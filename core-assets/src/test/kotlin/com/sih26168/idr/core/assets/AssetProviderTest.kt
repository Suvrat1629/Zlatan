package com.sih26168.idr.core.assets

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class AssetProviderTest {
    private fun tempFile(dir: File, name: String, content: String): File =
        File(dir, name).apply { writeText(content) }

    @Test
    fun resolvesHighestPrecedenceSource() {
        val dir = createTempDirectory().toFile()
        val packaged = tempFile(dir, "packaged.tflite", "packaged-bytes")
        val downloaded = tempFile(dir, "downloaded.tflite", "downloaded-bytes")

        val manifests = mapOf(
            packaged to AssetManifest("speed", "1.0", Checksums.sha256(packaged), AssetType.SPEED_MODEL),
            downloaded to AssetManifest("speed", "1.1", Checksums.sha256(downloaded), AssetType.SPEED_MODEL),
        )
        val provider = AssetProvider(
            candidatesByKind = mapOf(
                "model/speed" to listOf(
                    AssetSource.PACKAGED to packaged,
                    AssetSource.DOWNLOADED to downloaded,
                )
            ),
            manifestReader = { manifests.getValue(it) },
        )

        val resolved = provider.resolve("model/speed")
        assertEquals("1.1", resolved?.manifest?.version, "downloaded should win over packaged")
    }

    @Test
    fun returnsNullForUnknownKind() {
        val provider = AssetProvider(emptyMap(), manifestReader = { error("should not be called") })
        assertNull(provider.resolve("model/context"))
    }

    @Test
    fun throwsLoudlyOnChecksumMismatch() {
        val dir = createTempDirectory().toFile()
        val packaged = tempFile(dir, "packaged.tflite", "packaged-bytes")
        val provider = AssetProvider(
            candidatesByKind = mapOf(
                "model/speed" to listOf(AssetSource.PACKAGED to packaged)
            ),
            manifestReader = {
                AssetManifest("speed", "1.0", sha256 = "deadbeef", assetType = AssetType.SPEED_MODEL)
            },
        )
        assertFailsWith<AssetManifestMismatchException> { provider.resolve("model/speed") }
    }
}
