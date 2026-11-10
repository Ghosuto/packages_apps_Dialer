/* Copyright (C) 2026 Lunaris AOSP
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND.
 */
package com.android.dialer.voip

import android.content.ContentValues
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiopolicy.AudioMix
import android.media.audiopolicy.AudioMixingRule
import android.media.audiopolicy.AudioPolicy
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

internal class VoipAudioRecorder(private val context: Context) {
    @Volatile private var stopped = false
    fun stop() { stopped = true }

    fun record(call: VoipCall, keepRecording: () -> Boolean, onStarted: () -> Unit,
        onSaving: () -> Unit, progress: (Boolean) -> Unit): Uri {
        val audio = context.getSystemService(AudioManager::class.java)
        val directory = File(context.cacheDir, "voip-recording").apply { mkdirs() }
        directory.listFiles()?.filter { it.name.endsWith(".pcm") }?.forEach { it.delete() }
        var remoteFile: File? = null
        var microphoneFile: File? = null
        var policy: AudioPolicy? = null
        var remote: AudioRecord? = null
        var microphone: AudioRecord? = null
        val threads = mutableListOf<Thread>()
        val captureFailure = AtomicReference<Exception?>(null)
        val remoteSignal = AtomicBoolean(false)
        val remoteLastRead = AtomicLong(SystemClock.elapsedRealtime())
        val microphoneLastRead = AtomicLong(SystemClock.elapsedRealtime())
        try {
            remoteFile = File.createTempFile("remote-", ".pcm", directory)
            microphoneFile = File.createTempFile("microphone-", ".pcm", directory)
            try {
                val rule = AudioMixingRule.Builder()
                    .addRule(AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).build(),
                        AudioMixingRule.RULE_MATCH_ATTRIBUTE_USAGE)
                    .addMixRule(AudioMixingRule.RULE_MATCH_UID, call.uid)
                    .allowPrivilegedPlaybackCapture(true)
                    .build()
                val mix = AudioMix.Builder(rule)
                    .setFormat(AudioFormat.Builder().setSampleRate(SAMPLE_RATE)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setRouteFlags(AudioMix.ROUTE_FLAG_LOOP_BACK or AudioMix.ROUTE_FLAG_RENDER)
                    .build()
                val candidate = AudioPolicy.Builder(context).addMix(mix).build()
                if (audio.registerAudioPolicy(candidate) != AudioManager.SUCCESS) {
                    throw IOException("ROM rejected voice communication capture policy")
                }
                policy = candidate
                remote = candidate.createAudioRecordSink(mix)
                    ?: throw IOException("ROM cannot create voice communication capture")
                val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                if (bufferSize <= 0) throw IOException("Microphone format unavailable")
                microphone = AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                    .setAudioFormat(AudioFormat.Builder().setSampleRate(SAMPLE_RATE)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO).build())
                    .setBufferSizeInBytes(maxOf(bufferSize, SAMPLE_RATE / 5 * 2)).build()
                if (stopped || !keepRecording()) throw IOException("Call ended before recording started")
                remote.startRecording()
                microphone.startRecording()
                if (remote.recordingState != AudioRecord.RECORDSTATE_RECORDING ||
                    microphone.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    throw IOException("Audio capture did not start")
                }
                threads += capture(remote, remoteFile, remoteSignal, remoteLastRead, captureFailure)
                threads += capture(microphone, microphoneFile, AtomicBoolean(),
                    microphoneLastRead, captureFailure)
                val started = SystemClock.elapsedRealtime()
                onStarted()
                while (!stopped && keepRecording()) {
                    captureFailure.get()?.let { throw it }
                    if (microphone.activeRecordingConfiguration?.isClientSilenced == true) {
                        throw IOException("Microphone capture was silenced")
                    }
                    val now = SystemClock.elapsedRealtime()
                    if (now - remoteLastRead.get() > 5000 || now - microphoneLastRead.get() > 5000) {
                        throw IOException("Audio capture stopped delivering samples")
                    }
                    if (directory.usableSpace < 16L * 1024 * 1024) {
                        throw IOException("Insufficient storage")
                    }
                    if (now - started >= 4 * 60 * 60 * 1000L) break
                    progress(remoteSignal.get())
                    Thread.sleep(200)
                }
            } finally {
                stopped = true
                stopRecord(remote)
                stopRecord(microphone)
                var interrupted = false
                threads.filter { it.isAlive }.forEach {
                    try { it.join(5000) }
                    catch (_: InterruptedException) { interrupted = true }
                }
                releaseRecord(remote)
                releaseRecord(microphone)
                try { policy?.let { audio.unregisterAudioPolicy(it) } }
                catch (e: RuntimeException) { Log.w("VoipAudioRecorder", "Cannot release audio policy", e) }
                if (interrupted) Thread.currentThread().interrupt()
            }
            if (threads.any { it.isAlive }) throw IOException("Audio capture did not stop")
            captureFailure.get()?.let { throw it }
            if (!remoteSignal.get()) throw NoRemoteAudioException()
            if (microphoneFile.length() == 0L) throw IOException("No microphone samples received")
            onSaving()
            return save(call, remoteFile, microphoneFile)
        } finally {
            remoteFile?.delete()
            microphoneFile?.delete()
        }
    }

    private fun capture(record: AudioRecord, file: File, signal: AtomicBoolean,
        lastRead: AtomicLong, failure: AtomicReference<Exception?>): Thread = Thread({
        try {
            file.outputStream().buffered().use { output ->
                val samples = ShortArray(SAMPLE_RATE / 50)
                val bytes = ByteArray(samples.size * 2)
                while (!stopped) {
                    val count = record.read(samples, 0, samples.size, AudioRecord.READ_BLOCKING)
                    if (stopped) break
                    if (count <= 0) throw IOException("Audio read failed: $count")
                    lastRead.set(SystemClock.elapsedRealtime())
                    for (i in 0 until count) {
                        val sample = samples[i].toInt()
                        if (sample > 32 || sample < -32) signal.set(true)
                        bytes[i * 2] = sample.toByte()
                        bytes[i * 2 + 1] = (sample shr 8).toByte()
                    }
                    output.write(bytes, 0, count * 2)
                }
            }
        } catch (e: Exception) { failure.compareAndSet(null, e) }
    }, "VoipAudioCapture").apply { start() }

    private fun save(call: VoipCall, remote: File, microphone: File): Uri {
        val resolver = context.contentResolver
        val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.ROOT).format(Date())
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, "${call.appName}_$timestamp.wav")
            put(MediaStore.Audio.Media.TITLE, "${call.appName} — ${call.caller}")
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav")
            put(MediaStore.Audio.Media.RELATIVE_PATH, RECORDINGS_PATH)
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Cannot create recording")
        try {
            val duration = resolver.openOutputStream(uri, "w")?.use {
                StereoWave.write(remote, microphone, it, SAMPLE_RATE)
            } ?: throw IOException("Cannot open recording")
            val completed = ContentValues().apply {
                put(MediaStore.Audio.Media.IS_PENDING, 0)
                put(MediaStore.Audio.Media.DURATION, duration)
            }
            if (resolver.update(uri, completed, null, null) != 1) {
                throw IOException("Cannot publish recording")
            }
            return uri
        } catch (e: Exception) {
            try { resolver.delete(uri, null, null) }
            catch (cleanup: RuntimeException) { e.addSuppressed(cleanup) }
            throw e
        }
    }

    private fun stopRecord(record: AudioRecord?) {
        try { record?.stop() } catch (e: RuntimeException) { Log.w("VoipAudioRecorder", "Cannot stop audio input", e) }
    }

    private fun releaseRecord(record: AudioRecord?) {
        try { record?.release() }
        catch (e: RuntimeException) { Log.w("VoipAudioRecorder", "Cannot release audio input", e) }
    }

    class NoRemoteAudioException : IOException("No audio received from the calling app")
    companion object {
        const val RECORDINGS_PATH = "Recordings/VoIP calls/"
        private const val SAMPLE_RATE = 16000
    }
}
