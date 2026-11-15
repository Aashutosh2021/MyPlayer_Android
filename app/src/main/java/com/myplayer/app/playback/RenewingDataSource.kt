package com.myplayer.app.playback

import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import java.io.InterruptedIOException

/**
 * OpenTune RenewingDataSource adapted for MyPlayer's NineSecondChunkDataSource pipeline:
 * 1. Handles HTTP 429 (Too Many Requests) by honoring Retry-After or exponential backoff.
 * 2. Handles HTTP 403 / 410 expired stream URLs by invoking [renewStreamUrl]
 *    synchronously to swap in a fresh signed URL at the exact byte offset.
 */
@OptIn(UnstableApi::class)
class RenewingDataSource(
    private val upstream: DataSource,
    private val renewStreamUrl: (failedUrl: String) -> String?,
    private val isPatientHost: (url: String) -> Boolean = { true }
) : DataSource {

    companion object {
        private const val TAG = "RenewingDataSource"
        private const val BUSY_TRIES = 2
        private const val BUSY_WAIT_MS = 1_500L
        private const val BUSY_WAIT_CAP_MS = 8_000L
    }

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        var waits = 0
        while (true) {
            try {
                return upstream.open(dataSpec)
            } catch (e: HttpDataSource.HttpDataSourceException) {
                if (e is HttpDataSource.CleartextNotPermittedException || e.cause is InterruptedIOException) {
                    throw e
                }
                val url = dataSpec.uri.toString()
                val invalidCode = (e as? HttpDataSource.InvalidResponseCodeException)?.responseCode

                if (invalidCode == 429) {
                    val retryAfterHeader = (e as HttpDataSource.InvalidResponseCodeException)
                        .headerFields.entries
                        .firstOrNull { it.key.equals("Retry-After", ignoreCase = true) }
                        ?.value?.firstOrNull()?.trim()?.toLongOrNull()?.times(1_000L)
                    val waitMs = retryAfterHeader ?: (BUSY_WAIT_MS shl waits)
                    if (waits < BUSY_TRIES && waitMs <= BUSY_WAIT_CAP_MS && isPatientHost(url)) {
                        runCatching { upstream.close() }
                        try {
                            Thread.sleep(waitMs)
                        } catch (_: InterruptedException) {
                            Thread.currentThread().interrupt()
                            throw e
                        }
                        waits++
                        continue
                    }
                }

                if (invalidCode != 403 && invalidCode != 410 && invalidCode != 429) {
                    throw e
                }

                Log.w(TAG, "Stream HTTP $invalidCode on ${url.take(60)}... Attempting instant URL renewal")
                val freshUrl = renewStreamUrl(url) ?: throw e
                runCatching { upstream.close() }
                val renewedSpec = dataSpec.buildUpon().setUri(Uri.parse(freshUrl)).build()
                return upstream.open(renewedSpec)
            }
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        upstream.read(buffer, offset, length)

    override fun getUri(): Uri? = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() = upstream.close()

    class Factory(
        private val upstreamFactory: DataSource.Factory,
        private val renewStreamUrl: (failedUrl: String) -> String?
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource {
            return RenewingDataSource(upstreamFactory.createDataSource(), renewStreamUrl)
        }
    }
}
