package com.sih26168.idr.androidassets

import android.content.Context
import com.sih26168.idr.core.assets.AssetHandle
import com.sih26168.idr.core.assets.AssetManifest
import com.sih26168.idr.core.assets.AssetProvider
import com.sih26168.idr.core.assets.AssetSource
import java.io.File

class AndroidAssetProvider(private val context: Context) {

    data class PackagedEntry(
        val kind: String,
        val assetPathInApk: String,
        val manifestPathInApk: String,
    )

    fun build(entries: List<PackagedEntry>): AssetProvider {
        val manifestCache = HashMap<File, AssetManifest>()
        val candidatesByKind = HashMap<String, MutableList<Pair<AssetSource, File>>>()

        for (entry in entries) {
            val manifestJson = context.assets.open(entry.manifestPathInApk).bufferedReader().use { it.readText() }
            val manifest = AssetManifestJson.parse(manifestJson)

            val destDir = File(context.filesDir, "assets/${entry.kind}/${manifest.version}")
            destDir.mkdirs()
            val destFile = File(destDir, File(entry.assetPathInApk).name)
            copyFromApkIfMissingOrStale(entry.assetPathInApk, destFile)

            manifestCache[destFile] = manifest
            candidatesByKind.getOrPut(entry.kind) { mutableListOf() }.add(AssetSource.PACKAGED to destFile)
        }

        return AssetProvider(
            candidatesByKind = candidatesByKind,
            manifestReader = { file -> manifestCache.getValue(file) },
        )
    }

    private fun copyFromApkIfMissingOrStale(assetPathInApk: String, dest: File) {
        if (dest.exists() && dest.length() > 0) return
        context.assets.open(assetPathInApk).use { input ->
            dest.outputStream().use { output -> input.copyTo(output) }
        }
    }

    companion object {

        fun AssetProvider.requireHandle(kind: String): AssetHandle =
            resolve(kind) ?: error("no asset resolved for kind '$kind' — check it was registered with AndroidAssetProvider.build()")
    }
}
