package com.rtts.app.audio

import kotlinx.coroutines.flow.Flow

data class AudioChunk(val samples: FloatArray, val sampleRate: Int)

interface AudioSource {
    fun start(): Flow<AudioChunk>
    fun stop()
}
