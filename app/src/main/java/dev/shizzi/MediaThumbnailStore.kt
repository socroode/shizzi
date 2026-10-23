package dev.shizzi

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors

object MediaThumbnailStore {
    private val executor = Executors.newSingleThreadExecutor()

    fun thumbnailFile(context: Context, entry: MediaEntry): File? {
        val directory = directory(context)
        val destination = File(
            directory,
            MediaThumbnailPolicy.cacheFileName(entry.id, entry.size, entry.mimeType),
        )
        if (destination.isFile && destination.length() > 0L) return destination

        synchronized(("thumb:" + entry.id).intern()) {
            if (destination.isFile && destination.length() > 0L) return destination
            val bitmap = generate(context, entry) ?: return null
            val temporary = File(directory, destination.name + ".tmp")
            return try {
                FileOutputStream(temporary).use { output ->
                    if (!bitmap.compress(
                            Bitmap.CompressFormat.JPEG,
                            MediaThumbnailPolicy.JPEG_QUALITY,
                            output,
                        )
                    ) {
                        error("JPEG compression failed")
                    }
                    output.fd.sync()
                }
                if (!temporary.renameTo(destination)) {
                    temporary.copyTo(destination, overwrite = true)
                    temporary.delete()
                }
                destination
            } catch (_: Throwable) {
                temporary.delete()
                null
            } finally {
                bitmap.recycle()
            }
        }
    }

    fun warmAsync(context: Context, entries: List<MediaEntry>) {
        val appContext = context.applicationContext
        val snapshot = entries.toList()
        executor.execute {
            snapshot.forEach { entry ->
                runCatching { thumbnailFile(appContext, entry) }
            }
            prune(appContext, snapshot)
        }
    }

    private fun generate(context: Context, entry: MediaEntry): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, entry.uri)
            when (entry.kind) {
                MediaKind.MUSIC -> {
                    val cover = retriever.embeddedPicture ?: return null
                    decodeEmbeddedCover(cover)
                }

                MediaKind.FILMS, MediaKind.SERIES -> {
                    val durationMillis = retriever
                        .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                        ?.toLongOrNull()
                        ?: 0L
                    val timeUs = MediaThumbnailPolicy.frameTimeUs(durationMillis)
                    retriever.getScaledFrameAtTime(
                        timeUs,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                        MediaThumbnailPolicy.WIDTH,
                        MediaThumbnailPolicy.HEIGHT,
                    ) ?: retriever.getFrameAtTime(
                        timeUs,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                    )
                }
            }
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun decodeEmbeddedCover(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (
            bounds.outWidth / sample > MediaThumbnailPolicy.WIDTH * 2 ||
                bounds.outHeight / sample > MediaThumbnailPolicy.HEIGHT * 2
        ) {
            sample *= 2
        }

        return BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample },
        )
    }

    private fun prune(context: Context, entries: List<MediaEntry>) {
        val keep = entries
            .map {
                MediaThumbnailPolicy.cacheFileName(it.id, it.size, it.mimeType)
            }
            .toSet()
        directory(context).listFiles()
            ?.filter { it.isFile && it.extension.equals("jpg", ignoreCase = true) }
            ?.filterNot { it.name in keep }
            ?.forEach { runCatching { it.delete() } }
    }

    private fun directory(context: Context): File =
        File(context.filesDir, DIRECTORY_NAME).apply { mkdirs() }

    private const val DIRECTORY_NAME = "media-thumbnails-v1"
}
