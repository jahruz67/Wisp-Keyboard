package org.futo.voiceinput.shared

import android.media.AudioRecord
import android.os.SystemClock
import kotlinx.coroutines.delay
import kotlinx.coroutines.yield
import kotlin.math.min

/** Reads PCM without waiting for UI work, preserving queued audio when finishing. */
suspend fun capturePcmAudio(
    recorder: AudioRecord,
    bufferSizeInBytes: Int,
    stopRequested: () -> Boolean,
    onSamples: (ShortArray, Int) -> Boolean
) {
    val samples = ShortArray(1600)
    var stopDeadline = Long.MAX_VALUE
    var drainSamplesRemaining = bufferSizeInBytes / 2
    var lastReadAt = SystemClock.elapsedRealtime()

    while (true) {
        yield()
        val now = SystemClock.elapsedRealtime()
        if (stopRequested() && stopDeadline == Long.MAX_VALUE) {
            // Allow the microphone pipeline to deliver the end of the last word.
            stopDeadline = now + 200L
        }
        val draining = now >= stopDeadline
        if (draining && drainSamplesRemaining <= 0) return
        check(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
            "The microphone stopped recording. Please try again."
        }

        val count = if (draining) min(samples.size, drainSamplesRemaining) else samples.size
        val nRead = recorder.read(samples, 0, count, AudioRecord.READ_NON_BLOCKING)
        check(nRead >= 0) { "Unable to read microphone audio (error $nRead). Please try again." }
        if (nRead == 0) {
            if (draining) return
            check(now - lastReadAt < 5000L) {
                "The microphone stopped delivering audio. Please try again."
            }
            delay(10)
            continue
        }

        lastReadAt = now
        if (draining) drainSamplesRemaining -= nRead
        if (!onSamples(samples, nRead)) return
    }
}
