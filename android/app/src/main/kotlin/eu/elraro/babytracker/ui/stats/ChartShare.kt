package eu.elraro.babytracker.ui.stats

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Hands a chart image to another app chosen by the user. The image is written to the app's
 * cache (never backed up, cleared by the system) and replaced by the next one, so no copy
 * piles up; other apps get read access to that one file only, through the FileProvider.
 */
object ChartShare {
    private const val DIRECTORY = "shared"
    private const val FILE = "sleep-chart.png"
    private const val QUALITY = 100

    /** Writes [bitmap] as a PNG and returns its shareable URI, or null if it could not be written. */
    suspend fun write(
        context: Context,
        bitmap: Bitmap,
    ): Uri? =
        withContext(Dispatchers.IO) {
            try {
                val directory = File(context.cacheDir, DIRECTORY).apply { mkdirs() }
                val file = File(directory, FILE)
                val written = file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, QUALITY, it) }
                if (written) FileProvider.getUriForFile(context, authority(context), file) else null
            } catch (_: IOException) {
                null
            } finally {
                bitmap.recycle()
            }
        }

    /** The system share sheet for the image at [uri]. */
    fun chooser(
        uri: Uri,
        title: String,
    ): Intent {
        val send =
            Intent(Intent.ACTION_SEND)
                .setType("image/png")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        // The chooser passes the read grant on only for URIs in ClipData.
        send.clipData = ClipData.newRawUri(null, uri)
        return Intent.createChooser(send, title)
    }

    private fun authority(context: Context) = "${context.packageName}.files"
}
