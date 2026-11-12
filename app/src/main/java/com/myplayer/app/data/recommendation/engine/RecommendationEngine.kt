package com.myplayer.app.data.recommendation.engine

import com.myplayer.app.data.recommendation.api.RecommendationSource
import com.myplayer.app.data.recommendation.logging.RecommendationLogger
import com.myplayer.app.data.recommendation.logging.RecommendationMetrics
import com.myplayer.app.data.recommendation.mapper.RecommendationMapper
import com.myplayer.app.data.recommendation.model.RecommendationResult
import com.myplayer.app.data.recommendation.model.RecommendationSeed
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.system.measureTimeMillis

@Singleton
class RecommendationEngine @Inject constructor(
    private val source: RecommendationSource,
    private val mapper: RecommendationMapper,
    private val logger: RecommendationLogger,
    private val metrics: RecommendationMetrics
) {
    suspend fun generateRecommendations(seed: RecommendationSeed): RecommendationResult {
        logger.log("Starting recommendation generation for seed: '${seed.songId}' via ${source.sourceName}")
        var result: RecommendationResult
        
        val timeTaken = measureTimeMillis {
            try {
                // 1. Fetch raw candidates
                logger.log("Executing fetch from source: ${source.sourceName}")
                val rawCandidates = source.fetchRawRecommendations(seed)
                
                // 2. Map to Domain Models
                val mappedCandidates = rawCandidates.map { 
                    mapper.toRecommendationSong(it, source = source.sourceName)
                }

                // Return raw unranked/unfiltered mapped candidates
                result = RecommendationResult.Success(mappedCandidates)
            } catch (e: Exception) {
                logger.error("Exception during recommendation generation", e)
                metrics.recordFetchFailure()
                result = RecommendationResult.Error(e.message ?: "Unknown error", e)
            }
        }
        
        if (result is RecommendationResult.Success) {
            val successResult = result as RecommendationResult.Success
            if (successResult.songs.isNotEmpty()) {
                metrics.recordFetchSuccess(timeTaken) // Accurately record time
                logger.log("Completed generation in ${timeTaken}ms. Fetched: ${successResult.songs.size}")
            } else {
                metrics.recordFetchFailure()
                logger.log("Completed generation in ${timeTaken}ms. Fetched: 0 (empty)")
            }
        }
        
        return result
    }
}
