package com.galax71.steamhapticssinger

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.pow

data class MidiNoteEvent(val timeMs: Long, val note: Int, val velocity: Int, val on: Boolean, val channel: Int)
data class MidiSong(val events: List<MidiNoteEvent>, val durationMs: Long)

object MidiFileParser {
    private data class Raw(val tick: Long, val kind: Int, val channel: Int, val note: Int, val velocity: Int, val tempo: Int = 500000)
    private fun u16(d: DataInputStream): Int = d.readUnsignedShort()
    private fun u32(d: DataInputStream): Long = d.readInt().toLong() and 0xffffffffL
    private fun vlq(d: DataInputStream): Long {
        var result = 0L
        repeat(4) {
            val b = d.readUnsignedByte()
            result = (result shl 7) or (b and 0x7f).toLong()
            if ((b and 0x80) == 0) return result
        }
        error("Invalid MIDI variable length integer")
    }
    fun parse(data: ByteArray): MidiSong {
        require(data.size <= 8_000_000) { "MIDI file is too large (8MB limit)" }
        val file = DataInputStream(ByteArrayInputStream(data))
        require(file.readInt() == 0x4d546864) { "Not a Standard MIDI File" }
        val headerLength = u32(file).toInt()
        require(headerLength >= 6 && headerLength <= 64) { "Invalid MIDI header" }
        val format = u16(file)
        val tracks = u16(file)
        val division = u16(file)
        require(format == 0 || format == 1) { "Only MIDI format 0/1 supported" }
        require(tracks in 1..128 && division in 1..32767 && (division and 0x8000) == 0) { "Unsupported MIDI track/time division" }
        if (headerLength > 6) file.skipBytes(headerLength - 6)
        val events = mutableListOf<Raw>()
        repeat(tracks) {
            require(file.readInt() == 0x4d54726b) { "Missing MTrk" }
            val len = u32(file).toInt()
            require(len >= 0 && len <= data.size) { "Invalid track length" }
            val trackBytes = ByteArray(len)
            file.readFully(trackBytes)
            val d = DataInputStream(ByteArrayInputStream(trackBytes))
            var tick = 0L
            var status = -1
            while (d.available() > 0) {
                tick += vlq(d)
                var first = d.readUnsignedByte()
                if (first < 0x80) {
                    require(status in 0x80..0xef) { "Bad running status" }
                } else {
                    status = first
                    first = if (status in 0x80..0xef) d.readUnsignedByte() else -1
                }
                when {
                    status == 0xff -> {
                        val meta = d.readUnsignedByte()
                        val n = vlq(d).toInt()
                        require(n >= 0 && n <= d.available()) { "Bad meta event" }
                        if (meta == 0x51 && n == 3) {
                            val tempo = (d.readUnsignedByte() shl 16) or (d.readUnsignedByte() shl 8) or d.readUnsignedByte()
                            events.add(Raw(tick, 2, 0, 0, 0, tempo))
                        } else d.skipBytes(n)
                        status = -1
                    }
                    status == 0xf0 || status == 0xf7 -> {
                        val n = vlq(d).toInt()
                        require(n >= 0 && n <= d.available()) { "Bad SysEx" }
                        d.skipBytes(n)
                        status = -1
                    }
                    status in 0x80..0xef -> {
                        val type = status and 0xf0
                        val channel = status and 0x0f
                        val second = if (type == 0xc0 || type == 0xd0) 0 else d.readUnsignedByte()
                        if (type == 0x90 || type == 0x80)
                            events.add(Raw(tick, if (type == 0x90 && second > 0) 1 else 0, channel, first, second))
                        
                        if (type !in listOf(0x80, 0x90, 0xa0, 0xb0, 0xc0, 0xd0, 0xe0)) error("Unknown channel event")
                    }
                    else -> error("Unsupported MIDI status: ${status.toString(16)}")
                }
                require(events.size < 500_000) { "Too many MIDI events" }
            }
        }
        val sorted = events.sortedWith(compareBy<Raw> { it.tick }.thenBy { if (it.kind == 2) 0 else 1 })
        var tempo = 500000L
        var lastTick = 0L
        var micros = 0L
        val notes = mutableListOf<MidiNoteEvent>()
        for (e in sorted) {
            micros += (e.tick - lastTick) * tempo / division
            lastTick = e.tick
            if (e.kind == 2) tempo = e.tempo.coerceIn(1000, 10_000_000).toLong()
            else if (e.kind <= 1) notes.add(MidiNoteEvent(micros / 1000, e.note, e.velocity, e.kind == 1, e.channel))
        }
        return MidiSong(notes, notes.lastOrNull()?.timeMs ?: 0)
    }
}

/** Lightweight sine oscillator: preview only, not a General MIDI soundfont or Steam haptics. */
class MidiPlayer {
    private val running = AtomicBoolean(false)
    private var worker: Thread? = null
    fun stop() {
        running.set(false)
        worker?.interrupt()
    }
    fun play(song: MidiSong, onFinish: (String) -> Unit, onNotes: (Long, List<MidiNoteEvent>) -> Unit = { _, _ -> }, phoneAudio: Boolean = true, onMidiEvent: (MidiNoteEvent) -> Unit = {}) {
        stop()
        running.set(true)
        worker = thread(name = "midi-preview") {
            if (!phoneAudio) {
                // Controller-only playback must never open an Android audio output.
                val sounding = mutableMapOf<Pair<Int, Int>, MidiNoteEvent>()
                var index = 0
                val started = android.os.SystemClock.elapsedRealtime()
                try {
                    while (running.get()) {
                        val elapsed = android.os.SystemClock.elapsedRealtime() - started
                        var changed = false
                        while (index < song.events.size && song.events[index].timeMs <= elapsed) {
                            val event = song.events[index++]
                            val key = event.channel to event.note
                            if (event.on) sounding[key] = event else sounding.remove(key)
                            onMidiEvent(event)
                            changed = true
                            // Send every MIDI transition, including short notes, to haptics.
                            onNotes(event.timeMs, sounding.values.toList())
                        }
                        if (!changed) onNotes(elapsed.coerceAtMost(song.durationMs), sounding.values.toList())
                        if (elapsed >= song.durationMs + 250L) break
                        // Sleep until the next MIDI event rather than polling at 25 ms.
                        val nextAt = song.events.getOrNull(index)?.timeMs ?: (song.durationMs + 250L)
                        val remaining = nextAt - (android.os.SystemClock.elapsedRealtime() - started)
                        Thread.sleep(remaining.coerceIn(1L, 8L))
                    }
                    if (running.get()) {
                        onNotes(song.durationMs, emptyList())
                        onFinish("Controller MIDI playback complete.")
                    }
                } catch (_: InterruptedException) {
                    // Stopped by the user.
                } catch (e: Exception) {
                    if (running.get()) onFinish("Controller MIDI error: ${e.message}")
                } finally {
                    running.set(false)
                }
                return@thread
            }
            val rate = 22050
            val buffer = ShortArray(512)
            val active = mutableMapOf<Pair<Int, Int>, Double>()
            val phase = mutableMapOf<Pair<Int, Int>, Double>()
            var output: AudioTrack? = null
            try {
                output = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                    .setBufferSizeInBytes(buffer.size * 2 * 4)
                    .setTransferMode(AudioTrack.MODE_STREAM).build()
                require(output.state == AudioTrack.STATE_INITIALIZED) { "Audio output unavailable" }
                if (!phoneAudio) output.setVolume(0f)
                output.play()
                var idx = 0
                var sampleIndex = 0L
                val sounding = mutableMapOf<Pair<Int, Int>, MidiNoteEvent>()
                var lastDisplayMs = -100L
                val endSamples = (song.durationMs + 250L) * rate / 1000L
                while (running.get() && sampleIndex < endSamples) {
                    for (i in buffer.indices) {
                        val ms = sampleIndex * 1000L / rate
                        while (idx < song.events.size && song.events[idx].timeMs <= ms) {
                            val e = song.events[idx++]
                            val key = e.channel to e.note
                            if (e.on) {
                                active[key] = e.velocity.coerceIn(0, 127) / 127.0
                                sounding[key] = e
                            } else {
                                active.remove(key)
                                phase.remove(key)
                                sounding.remove(key)
                            }
                            onMidiEvent(e)
                            onNotes(e.timeMs, sounding.values.toList())
                        }
                        var mixed = 0.0
                        for ((key, velocity) in active) {
                            val freq = 440.0 * 2.0.pow((key.second - 69) / 12.0)
                            val angle = phase.getOrDefault(key, 0.0)
                            mixed += sin(angle) * velocity
                            phase[key] = (angle + 2.0 * PI * freq / rate) % (2.0 * PI)
                        }
                        if (ms - lastDisplayMs >= 75L) {
                            onNotes(ms, sounding.values.sortedWith(compareBy<MidiNoteEvent> { it.note }.thenBy { it.channel }))
                            lastDisplayMs = ms
                        }
                        buffer[i] = (mixed / maxOf(1, active.size) * 9000.0).toInt().coerceIn(-32767, 32767).toShort()
                        sampleIndex++
                    }
                    var pos = 0
                    while (pos < buffer.size && running.get()) {
                        val n = output.write(buffer, pos, buffer.size - pos)
                        if (n <= 0) error("Audio output failed")
                        pos += n
                    }
                }
                if (running.get()) {
                    onNotes(song.durationMs, emptyList())
                    onFinish("MIDI preview complete.")
                }
            } catch (e: Exception) {
                if (running.get()) onFinish("Playback error: ${e.message}")
            } finally {
                output?.stop()
                output?.release()
                running.set(false)
            }
        }
    }
}
