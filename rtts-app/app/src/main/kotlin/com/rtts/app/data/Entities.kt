package com.rtts.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "users")
data class UserEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val username: String,
    val pinHash: String,
    val salt: String,
)

@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val userId: Long,
    val startedAtEpochMs: Long,
    val endedAtEpochMs: Long? = null,
)

@Entity(tableName = "transcript_segments")
data class TranscriptSegmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val startMs: Long,
    val endMs: Long,
    val speakerLabel: String,
    val text: String,
    val lang: String,
    val createdAtEpochMs: Long,
)
