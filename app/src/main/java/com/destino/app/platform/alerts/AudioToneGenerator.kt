package com.destino.app.platform.alerts

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sin

object AudioToneGenerator {

    /**
     * Gera um arquivo WAV PCM 16-bit com tom de alarme perceptível e harmônico.
     */
    fun getOrCreateAlarmToneFile(context: Context): File {
        val file = File(context.filesDir, "destino_alarm_tone.wav")
        if (file.exists() && file.length() > 0) {
            return file
        }

        val sampleRate = 22050
        val durationSeconds = 3.0
        val numSamples = (durationSeconds * sampleRate).toInt()
        val pcmData = ByteArray(numSamples * 2)

        // Gerar padrão de alarme alternado (880 Hz e 1174 Hz)
        val buffer = ByteBuffer.wrap(pcmData).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until numSamples) {
            val time = i.toDouble() / sampleRate
            // Alternar a frequência a cada 0.25 segundos
            val freq = if ((time % 0.5) < 0.25) 880.0 else 1174.66
            // Envelope simples para suavizar o início e o fim
            val envelope = sin(Math.PI * (i.toDouble() / numSamples))
            val sampleValue = (sin(2.0 * Math.PI * freq * time) * 32767 * 0.8 * envelope).toInt().toShort()
            buffer.putShort(sampleValue)
        }

        FileOutputStream(file).use { fos ->
            writeWavHeader(fos, pcmData.size, sampleRate, 1, 16)
            fos.write(pcmData)
        }

        return file
    }

    private fun writeWavHeader(
        out: FileOutputStream,
        pcmDataSize: Int,
        sampleRate: Int,
        channels: Int,
        bitsPerSample: Int
    ) {
        val totalDataLen = pcmDataSize + 36
        val byteRate = sampleRate * channels * (bitsPerSample / 8)

        val header = ByteArray(44)
        val bb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)

        // RIFF chunk
        bb.put('R'.code.toByte()).put('I'.code.toByte()).put('F'.code.toByte()).put('F'.code.toByte())
        bb.putInt(totalDataLen)
        bb.put('W'.code.toByte()).put('A'.code.toByte()).put('V'.code.toByte()).put('E'.code.toByte())

        // fmt chunk
        bb.put('f'.code.toByte()).put('m'.code.toByte()).put('t'.code.toByte()).put(' '.code.toByte())
        bb.putInt(16) // Subchunk1Size for PCM
        bb.putShort(1.toShort()) // AudioFormat 1 = PCM
        bb.putShort(channels.toShort())
        bb.putInt(sampleRate)
        bb.putInt(byteRate)
        bb.putShort((channels * bitsPerSample / 8).toShort()) // BlockAlign
        bb.putShort(bitsPerSample.toShort())

        // data chunk
        bb.put('d'.code.toByte()).put('a'.code.toByte()).put('t'.code.toByte()).put('a'.code.toByte())
        bb.putInt(pcmDataSize)

        out.write(header)
    }
}
