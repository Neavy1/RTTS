package com.rtts.app.audio

import kotlinx.coroutines.flow.Flow

/**
 * TODO(iOS): implement using AVAudioEngine (AVFoundation) -- install a tap on the input
 * node, convert its buffers to 16kHz mono Float PCM (AVAudioConverter), and emit as
 * [AudioChunk]. Mirrors [AnalogLineInAudioSource]/androidMain's use of AudioRecord.
 * Needs a macOS host with Xcode to write and test against AVFoundation.
 */
class IosMicrophoneAudioSource : AudioSource {
    override fun start(): Flow<AudioChunk> =
        throw NotImplementedError("IosMicrophoneAudioSource: AVAudioEngine capture not written yet, see class doc")

    override fun stop() = Unit
}
