package com.myplayer.app.di

import android.content.Context
import android.net.Uri
import android.os.Handler
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import com.myplayer.app.playback.AudioOutputRouterManager
import com.myplayer.app.playback.dsp.LastWaveDspAudioProcessor
import com.myplayer.app.playback.dsp.MasterAudioDspProcessor
import com.myplayer.app.playback.dsp.MyPlayerCrossmixAudioProcessor
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.util.Collections
import java.util.WeakHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min

/**
 * Spotify-style 9-Second Rolling Chunk DataSource:
 * 1. Requests audio in 160 KB (~9 seconds of Opus/AAC audio) HTTP Range chunks.
 * 2. Parses total stream length from HTTP `Content-Range: bytes start-end/total` so
 *    ExoPlayer knows the exact file boundary even when `dataSpec.length == LENGTH_UNSET`.
 * 3. Retries transient chunk failures up to 4 times with backoff on slow 2G/3G networks.
 * 4. NEVER returns false EOF (`RESULT_END_OF_INPUT`) on network drops — throws `IOException`
 *    so ExoPlayer cleanly pauses to rebuffer and resumes from the exact byte!
 */
@OptIn(UnstableApi::class)
class NineSecondChunkDataSource(
    private val upstream: DataSource,
    private val chunkSizeBytes: Long = 160 * 1024L // ~9 seconds of audio per chunk
) : DataSource {
    private var currentDataSpec: DataSpec? = null
    private var currentPosition: Long = 0L
    private var totalStreamEndPosition: Long = C.LENGTH_UNSET.toLong()
    private var bytesRemainingInChunk: Long = 0L
    private var isChunkedStream: Boolean = false
    private var isChunkOpen: Boolean = false

    companion object {
        private const val TAG = "NineSecChunkDS"
        private const val MAX_CHUNK_RETRIES = 4
        private val CONTENT_RANGE_REGEX = Regex("""bytes\s+\d+-\d+/(\d+)""", RegexOption.IGNORE_CASE)
    }

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        currentDataSpec = dataSpec
        currentPosition = dataSpec.position
        totalStreamEndPosition = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
            dataSpec.position + dataSpec.length
        } else {
            C.LENGTH_UNSET.toLong()
        }

        val scheme = dataSpec.uri.scheme ?: ""
        val host = dataSpec.uri.host ?: ""
        isChunkedStream = (scheme == "http" || scheme == "https") &&
            (host.contains("googlevideo.com") ||
                dataSpec.length == C.LENGTH_UNSET.toLong() ||
                dataSpec.length > chunkSizeBytes)

        if (!isChunkedStream) {
            isChunkOpen = true
            return upstream.open(dataSpec)
        }

        openNextNineSecondChunkWithRetry(isInitialOpen = true)

        return if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
            dataSpec.length
        } else if (totalStreamEndPosition != C.LENGTH_UNSET.toLong()) {
            (totalStreamEndPosition - dataSpec.position).coerceAtLeast(0L)
        } else {
            C.LENGTH_UNSET.toLong()
        }
    }

    private fun openNextNineSecondChunkWithRetry(isInitialOpen: Boolean): Boolean {
        val baseSpec = currentDataSpec ?: return false

        if (totalStreamEndPosition != C.LENGTH_UNSET.toLong() && currentPosition >= totalStreamEndPosition) {
            return false // True end of audio stream reached
        }

        val remainingInStream = if (totalStreamEndPosition != C.LENGTH_UNSET.toLong()) {
            totalStreamEndPosition - currentPosition
        } else {
            C.LENGTH_UNSET.toLong()
        }

        val targetChunkLength = if (remainingInStream != C.LENGTH_UNSET.toLong()) {
            min(chunkSizeBytes, remainingInStream)
        } else {
            chunkSizeBytes
        }

        if (targetChunkLength <= 0L) return false

        var lastException: Exception? = null

        for (attempt in 1..MAX_CHUNK_RETRIES) {
            try {
                if (isChunkOpen) {
                    try { upstream.close() } catch (_: Exception) {}
                    isChunkOpen = false
                }

                val chunkSpec = baseSpec.buildUpon()
                    .setPosition(currentPosition)
                    .setLength(targetChunkLength)
                    .build()

                val openedBytes = upstream.open(chunkSpec)
                isChunkOpen = true

                if (totalStreamEndPosition == C.LENGTH_UNSET.toLong()) {
                    val headers = upstream.responseHeaders
                    val contentRange = headers["Content-Range"]?.firstOrNull()
                        ?: headers["content-range"]?.firstOrNull()
                    if (!contentRange.isNullOrBlank()) {
                        val totalSize = CONTENT_RANGE_REGEX.find(contentRange)
                            ?.groupValues?.getOrNull(1)?.toLongOrNull()
                        if (totalSize != null && totalSize > 0L) {
                            totalStreamEndPosition = if (baseSpec.length != C.LENGTH_UNSET.toLong()) {
                                min(totalSize, baseSpec.position + baseSpec.length)
                            } else {
                                totalSize
                            }
                        }
                    }
                }

                val actualRemaining = if (totalStreamEndPosition != C.LENGTH_UNSET.toLong()) {
                    (totalStreamEndPosition - currentPosition).coerceAtLeast(0L)
                } else {
                    targetChunkLength
                }

                bytesRemainingInChunk = when {
                    openedBytes != C.LENGTH_UNSET.toLong() -> min(openedBytes, actualRemaining)
                    else -> min(targetChunkLength, actualRemaining)
                }

                return bytesRemainingInChunk > 0L
            } catch (e: androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException) {
                if (e.responseCode == 416) {
                    totalStreamEndPosition = currentPosition
                    bytesRemainingInChunk = 0L
                    return false
                }
                lastException = e
                android.util.Log.w(TAG, "Chunk HTTP ${e.responseCode} at pos=$currentPosition (attempt $attempt/$MAX_CHUNK_RETRIES)")
            } catch (e: Exception) {
                lastException = e
                android.util.Log.w(TAG, "Slow network chunk retry $attempt/$MAX_CHUNK_RETRIES at pos=$currentPosition: ${e.message}")
            }

            if (attempt < MAX_CHUNK_RETRIES) {
                try {
                    Thread.sleep(350L * attempt)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
        }

        throw java.io.IOException(
            "Unable to load 9s audio chunk at byte $currentPosition (initial=$isInitialOpen)",
            lastException
        )
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (!isChunkedStream) {
            return upstream.read(buffer, offset, length)
        }
        if (length == 0) return 0

        if (bytesRemainingInChunk <= 0L) {
            if (isChunkOpen) {
                try { upstream.close() } catch (_: Exception) {}
                isChunkOpen = false
            }
            if (totalStreamEndPosition != C.LENGTH_UNSET.toLong() && currentPosition >= totalStreamEndPosition) {
                return C.RESULT_END_OF_INPUT
            }
            val opened = openNextNineSecondChunkWithRetry(isInitialOpen = false)
            if (!opened || bytesRemainingInChunk <= 0L) {
                return C.RESULT_END_OF_INPUT
            }
        }

        val bytesToRead = min(length.toLong(), bytesRemainingInChunk).toInt()
        val bytesRead = try {
            upstream.read(buffer, offset, bytesToRead)
        } catch (e: java.io.IOException) {
            android.util.Log.w(TAG, "Mid-chunk read interrupted at $currentPosition, re-opening chunk: ${e.message}")
            if (isChunkOpen) {
                try { upstream.close() } catch (_: Exception) {}
                isChunkOpen = false
            }
            val reopened = openNextNineSecondChunkWithRetry(isInitialOpen = false)
            if (!reopened || bytesRemainingInChunk <= 0L) return C.RESULT_END_OF_INPUT
            upstream.read(buffer, offset, min(length.toLong(), bytesRemainingInChunk).toInt())
        }

        if (bytesRead == C.RESULT_END_OF_INPUT) {
            if (isChunkOpen) {
                try { upstream.close() } catch (_: Exception) {}
                isChunkOpen = false
            }
            bytesRemainingInChunk = 0L
            if (totalStreamEndPosition != C.LENGTH_UNSET.toLong() && currentPosition < totalStreamEndPosition) {
                val opened = openNextNineSecondChunkWithRetry(isInitialOpen = false)
                if (opened && bytesRemainingInChunk > 0L) {
                    return read(buffer, offset, length)
                }
            }
            return C.RESULT_END_OF_INPUT
        }

        currentPosition += bytesRead
        bytesRemainingInChunk -= bytesRead
        return bytesRead
    }

    override fun getUri(): Uri? = upstream.uri ?: currentDataSpec?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() {
        currentDataSpec = null
        bytesRemainingInChunk = 0L
        isChunkedStream = false
        if (isChunkOpen) {
            isChunkOpen = false
            try { upstream.close() } catch (_: Exception) {}
        }
    }

    class Factory(private val upstreamFactory: DataSource.Factory) : DataSource.Factory {
        override fun createDataSource(): DataSource {
            return NineSecondChunkDataSource(upstreamFactory.createDataSource())
        }
    }
}

/**
 * MyPlayer Full-DSP Player Deck Factory:
 * Builds both the primary [ExoPlayer] and any secondary crossfade [ExoPlayer] deck with the
 * EXACT SAME audio pipeline:
 *   1. [MyPlayerCrossmixAudioProcessor] (sample-accurate MyPlayer DJ crossmix & equal-power fade)
 *   2. [MasterAudioDspProcessor] (isolated per-deck 64-bit 15-Band / 10-Band EQ + Studio Master Clarity + Preamp + LUFS)
 *   3. [LastWaveDspAudioProcessor] (isolated per-deck High-Shelf Clarity + True-Dolby Spatial Widening)
 *   4. [com.myplayer.app.myplayerfx.audio.SpatialAudioProcessor] (Headphone Crossfeed / Virtual Speakers / Surround)
 *   5. [com.myplayer.app.myplayerfx.audio.ReverbAudioProcessor] (Freeverb Room)
 *   6. [com.myplayer.app.myplayerfx.audio.LoudnessAudioProcessor] (Compressor + Boost + Soft-Knee Peak Limiter)
 *   7. [com.myplayer.app.myplayerfx.audio.MyPlayerVolumeSink] (Exponential VolumeCurve + ReplayGain + Multi-Output App Volume + Crossmix Clock)
 *
 * This guarantees 0% audio quality difference between the outgoing and incoming tracks during crossfade!
 */
@OptIn(UnstableApi::class)
@Singleton
class MyPlayerDeckFactory @Inject constructor(
    @ApplicationContext private val context: Context,
    private val audioAttributes: AudioAttributes,
    private val cacheDataSourceFactory: CacheDataSource.Factory,
    private val lastWaveDspProcessor: LastWaveDspAudioProcessor,
    private val masterAudioDspProcessor: MasterAudioDspProcessor,
    private val audioFx: com.myplayer.app.myplayerfx.AudioFxService,
    private val audioOutputRouterManager: AudioOutputRouterManager
) {
    private data class DeckResources(
        val crossmixProcessor: MyPlayerCrossmixAudioProcessor,
        val deckMasterDsp: MasterAudioDspProcessor?,
        val deckLastWaveDsp: LastWaveDspAudioProcessor?,
        val replayGain: com.myplayer.app.myplayerfx.audio.ReplayGainTags
    )

    private val deckResourcesMap = Collections.synchronizedMap(WeakHashMap<Player, DeckResources>())

    init {
        com.myplayer.app.ui.myplayer.MyPlayerAudioFxDspBridge.attach(audioFx, masterAudioDspProcessor)
    }

    fun crossmixProcessorFor(player: Player?): MyPlayerCrossmixAudioProcessor? {
        if (player == null) return null
        return deckResourcesMap[player]?.crossmixProcessor
    }

    /**
     * Creates a new [ExoPlayer] deck with the complete MyPlayer + MyPlayer DSP chain.
     * @param isPrimary If true, uses the primary [MasterAudioDspProcessor] & [LastWaveDspAudioProcessor]
     *                  and attaches as the primary player on [AudioOutputRouterManager].
     *                  If false (crossfade deck), creates isolated synced child DSP processors
     *                  and pins to the same output route via [AudioOutputRouterManager.applyCurrentRouteToAuxiliaryPlayer].
     */
    fun createExoPlayerDeck(isPrimary: Boolean = false): ExoPlayer {
        val replayGain = com.myplayer.app.myplayerfx.audio.ReplayGainTags()
        val crossmixProcessor = MyPlayerCrossmixAudioProcessor()
        val effectiveMasterDsp = if (isPrimary) masterAudioDspProcessor else masterAudioDspProcessor.createDeckProcessor()
        val effectiveLastWaveDsp = if (isPrimary) lastWaveDspProcessor else lastWaveDspProcessor.createDeckProcessor()

        val myplayerFx = arrayOf<androidx.media3.common.audio.AudioProcessor>(
            com.myplayer.app.myplayerfx.audio.SpatialAudioProcessor(audioFx),
            com.myplayer.app.myplayerfx.audio.ReverbAudioProcessor(audioFx),
            com.myplayer.app.myplayerfx.audio.LoudnessAudioProcessor(audioFx),
        )

        val prefs = context.getSharedPreferences("playback_preferences", Context.MODE_PRIVATE)
        val initialRepeat = prefs.getInt("playback_repeat_mode", Player.REPEAT_MODE_OFF)
        val initialShuffle = prefs.getBoolean("playback_shuffle_mode", false)

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 9_000,
                /* maxBufferMs = */ 27_000,
                /* bufferForPlaybackMs = */ if (isPrimary) 1_000 else 450,
                /* bufferForPlaybackAfterRebufferMs = */ 2_000
            )
            .setTargetBufferBytes(C.LENGTH_UNSET)
            .setPrioritizeTimeOverSizeThresholds(true)
            .setBackBuffer(18_000, false)
            .build()

        val mediaSourceFactory = DefaultMediaSourceFactory(cacheDataSourceFactory)
            .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(/* minLoadableRetryCount = */ 6))

        val accurateAudioMediaCodecSelector = MediaCodecSelector { mimeType, requiresSecureDecoder, requiresTunnelingDecoder ->
            val decoderInfos = runCatching {
                MediaCodecSelector.DEFAULT.getDecoderInfos(
                    mimeType,
                    requiresSecureDecoder,
                    requiresTunnelingDecoder,
                )
            }.getOrDefault(emptyList())
            if (decoderInfos.isEmpty()) {
                emptyList()
            } else {
                decoderInfos.sortedBy { info ->
                    val lower = info.name.lowercase()
                    if (lower.contains("sec.") || lower.contains("exynos")) 1 else 0
                }
            }
        }

        val renderersFactory = object : DefaultRenderersFactory(context) {
            override fun buildAudioRenderers(
                context: Context,
                extensionRendererMode: Int,
                mediaCodecSelector: MediaCodecSelector,
                enableDecoderFallback: Boolean,
                audioSink: AudioSink,
                eventHandler: Handler,
                eventListener: AudioRendererEventListener,
                out: ArrayList<Renderer>,
            ) {
                val dolbySpatialRenderer = object : MediaCodecAudioRenderer(
                    context,
                    mediaCodecSelector,
                    enableDecoderFallback,
                    eventHandler,
                    eventListener,
                    audioSink,
                ) {
                    override fun supportsFormat(
                        mediaCodecSelector: MediaCodecSelector,
                        format: Format,
                    ): Int {
                        val mime = format.sampleMimeType?.lowercase().orEmpty()
                        val isDolbyOrSpatial = mime.contains("eac3") || mime.contains("ec-3") ||
                            mime.contains("ac-3") || mime.contains("ac3") || mime.contains("ac4") ||
                            mime.contains("mha1") || mime.contains("mhm1")
                        if (!isDolbyOrSpatial) {
                            return RendererCapabilities.create(C.FORMAT_UNSUPPORTED_TYPE)
                        }
                        return super.supportsFormat(mediaCodecSelector, format)
                    }
                }
                out.add(dolbySpatialRenderer)

                super.buildAudioRenderers(
                    context,
                    extensionRendererMode,
                    mediaCodecSelector,
                    enableDecoderFallback,
                    audioSink,
                    eventHandler,
                    eventListener,
                    out,
                )
            }

            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): AudioSink {
                val sink = DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(false)
                    .setEnableAudioTrackPlaybackParams(false)
                    .setAudioCapabilities(AudioCapabilities.getCapabilities(context))
                    .setAudioProcessors(
                        arrayOf(
                            effectiveMasterDsp,
                            effectiveLastWaveDsp,
                            *myplayerFx,
                            crossmixProcessor
                        )
                    )
                    .build()
                return com.myplayer.app.myplayerfx.audio.MyPlayerVolumeSink(
                    delegate = sink,
                    audioFx = audioFx,
                    replayGain = replayGain,
                    crossmixProcessor = crossmixProcessor
                )
            }
        }.apply {
            setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
            setEnableDecoderFallback(true)
            setEnableAudioTrackPlaybackParams(false)
            setMediaCodecSelector(accurateAudioMediaCodecSelector)
        }

        val initialIgnoreFocus = audioOutputRouterManager.state.value.ignoreAudioFocus
        val handleFocus = if (isPrimary) !initialIgnoreFocus else false

        val exoPlayer = ExoPlayer.Builder(context, renderersFactory)
            .setAudioAttributes(audioAttributes, /* handleAudioFocus = */ handleFocus)
            .setHandleAudioBecomingNoisy(!initialIgnoreFocus)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(mediaSourceFactory)
            .build().apply {
                repeatMode = initialRepeat
                shuffleModeEnabled = initialShuffle
                if (isPrimary) {
                    audioOutputRouterManager.attachExoPlayer(this, audioAttributes)
                } else {
                    audioOutputRouterManager.applyCurrentRouteToAuxiliaryPlayer(this)
                }
                addListener(object : Player.Listener {
                    override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                        replayGain.update(tracks)
                    }
                })
                addAnalyticsListener(object : AnalyticsListener {
                    override fun onAudioInputFormatChanged(
                        eventTime: AnalyticsListener.EventTime,
                        format: Format,
                    ) {
                        effectiveMasterDsp.setTrackFormat(format)
                    }
                })
            }

        deckResourcesMap[exoPlayer] = DeckResources(
            crossmixProcessor = crossmixProcessor,
            deckMasterDsp = if (isPrimary) null else effectiveMasterDsp,
            deckLastWaveDsp = if (isPrimary) null else effectiveLastWaveDsp,
            replayGain = replayGain
        )
        return exoPlayer
    }

    /**
     * Releases a secondary crossfade deck and unregisters its child DSP processors.
     */
    fun releaseExoPlayerDeck(deck: ExoPlayer?) {
        if (deck == null) return
        val res = deckResourcesMap.remove(deck)
        if (res != null) {
            res.crossmixProcessor.clear()
            masterAudioDspProcessor.releaseDeckProcessor(res.deckMasterDsp)
            lastWaveDspProcessor.releaseDeckProcessor(res.deckLastWaveDsp)
        }
        audioOutputRouterManager.detachAuxiliaryPlayer(deck)
        runCatching {
            deck.stop()
            deck.clearMediaItems()
            deck.release()
        }
    }
}

@UnstableApi
@Module
@InstallIn(SingletonComponent::class)
object PlayerModule {

    @Provides
    @Singleton
    fun provideAudioAttributes(): AudioAttributes {
        return AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(C.USAGE_MEDIA)
            .build()
    }

    @OptIn(UnstableApi::class)
    @Provides
    @Singleton
    fun provideCacheDataSourceFactory(
        @ApplicationContext context: Context,
        simpleCache: SimpleCache,
        playbackSourceResolverProvider: javax.inject.Provider<com.myplayer.app.playback.PlaybackSourceResolver>
    ): CacheDataSource.Factory {
        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(30_000)
            .setReadTimeoutMs(30_000)
            .setKeepPostFor302Redirects(true)
            .setUserAgent("com.google.android.youtube/19.29.37 (Linux; U; Android 14) gzip")

        val renewingHttpFactory = com.myplayer.app.playback.RenewingDataSource.Factory(
            upstreamFactory = httpDataSourceFactory,
            renewStreamUrl = { failedUrl -> playbackSourceResolverProvider.get().renewExpiredUrlBlocking(failedUrl) }
        )
        val chunkedHttpFactory = NineSecondChunkDataSource.Factory(renewingHttpFactory)
        val upstreamFactory = DefaultDataSource.Factory(context, chunkedHttpFactory)

        return CacheDataSource.Factory()
            .setCache(simpleCache)
            .setUpstreamDataSourceFactory(upstreamFactory)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    @OptIn(UnstableApi::class)
    @Provides
    @Singleton
    fun provideExoPlayer(
        deckFactory: MyPlayerDeckFactory
    ): ExoPlayer {
        return deckFactory.createExoPlayerDeck(isPrimary = true)
    }
}