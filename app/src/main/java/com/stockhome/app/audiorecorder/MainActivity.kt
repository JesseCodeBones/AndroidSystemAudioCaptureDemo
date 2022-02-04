package com.stockhome.app.audiorecorder

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.media.*
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.stockhome.app.audiorecorder.services.MediaRecorderService
import com.tbruyelle.rxpermissions2.RxPermissions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.io.*
import kotlin.coroutines.coroutineContext

class MainActivity : AppCompatActivity() {
    var mediaProjectionManager: MediaProjectionManager? = null
    private var audioRecorder: AudioRecord? = null
    private  val FILE_NAME = "record.pcm"
    private val TAG = "MainActivity"
    private val rxPermission by lazy {
        RxPermissions(this)
    }

    private val minBufferSize by lazy {
        AudioRecord.getMinBufferSize(
            44100,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
    }


    @RequiresApi(Build.VERSION_CODES.O)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        Intent(this, MediaRecorderService::class.java).also {
            startForegroundService(it)
        }
        mediaProjectionManager =
            getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mediaProjectionManager!!.createScreenCaptureIntent(), 4578)
        findViewById<Button>(R.id.startRecord).setOnClickListener(::startRecord)
        findViewById<Button>(R.id.stopRecord).setOnClickListener(::stopRecord)
        rxPermission.request(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
            Manifest.permission.FOREGROUND_SERVICE
        ).subscribe {
        }
    }

    @SuppressLint("MissingPermission")
    @RequiresApi(Build.VERSION_CODES.Q)
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            4578 -> {
                if (resultCode == RESULT_OK) {
                    Toast.makeText(this, "Good to record", Toast.LENGTH_LONG).show()
                    if (data != null) {
                        val mediaProtection =
                            mediaProjectionManager?.getMediaProjection(resultCode, data)
                        if (mediaProtection != null) {
                            val configuration =
                                AudioPlaybackCaptureConfiguration.Builder(mediaProtection)
                                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                                    .build()
                            audioRecorder = AudioRecord.Builder()
                                .setAudioPlaybackCaptureConfig(configuration)
                                .setBufferSizeInBytes(minBufferSize)
                                .setAudioFormat(
                                    AudioFormat.Builder()
                                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                        .setSampleRate(44100)
                                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                                        .build()
                                ).build()
                        }
                    }

                } else {
                    Toast.makeText(this, "Fail to record", Toast.LENGTH_LONG).show()
                }
            }
        }

    }


    private fun startRecord(view: View) {
        Toast.makeText(this, "start to record", Toast.LENGTH_LONG).show()
        audioRecorder?.also {
            it.startRecording()
        }
        GlobalScope.launch(Dispatchers.IO) {
            Log.d(TAG, "startRecord: ${getExternalFilesDir(Environment.DIRECTORY_MUSIC)}")
            val file = File(getExternalFilesDir(Environment.DIRECTORY_MUSIC), FILE_NAME)
            if (!file.exists()) file.createNewFile()
            val out = DataOutputStream(file.outputStream())
            audioRecorder?.apply {
                while (recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    val buffer = ByteArray(minBufferSize)
                    val result = read(buffer, 0, minBufferSize)
                    when (result) {
                        AudioRecord.ERROR -> showToast("ERROR")
                        AudioRecord.ERROR_INVALID_OPERATION -> showToast("ERROR_INVALID_OPERATION")
                        AudioRecord.ERROR_DEAD_OBJECT -> showToast("ERROR_DEAD_OBJECT")
                        AudioRecord.ERROR_BAD_VALUE -> showToast("ERROR_BAD_VALUE")
                        else -> {
                            out.write(buffer)
                        }
                    }
                }
                out.flush()
                out.close()
            }
        }
    }

    fun reverseBytes(i: Short): Short {
        return (i.toInt() and 0xFF00 shr 8 or (i.toInt() shl 8)).toShort()
    }

    fun gain(x: Int, scale: Float): Short {
        val x0 = x * scale
        val GAIN_MARGIN = 4096
        return when {
            x0 <= -32768 - GAIN_MARGIN -> -32768
            x0 < -32768 + GAIN_MARGIN -> {
                val x1 = x0 + 32768 + GAIN_MARGIN
                ((0.25F / GAIN_MARGIN) * (x1 * x1 + GAIN_MARGIN * 2) - 32768).toInt().toShort()
            }
            x0 <= 32767 - GAIN_MARGIN -> x0.toInt().toShort()
            x0 < 32767 + GAIN_MARGIN -> {
                val x1 = x0 - 32767 - GAIN_MARGIN
                (-(0.25F / GAIN_MARGIN) * (x1 * x1 - GAIN_MARGIN * 2) + 32767).toInt().toShort()
            }
            else -> 32767
        }
    }

    private fun showToast(msg: String) {
        runOnUiThread {
            Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
        }
    }

    fun stopRecord(view: View) {
        audioRecorder?.apply {
            stop()
        }
        val wavFile = File(getExternalFilesDir(Environment.DIRECTORY_MUSIC), "output.wav")
        val file = File(getExternalFilesDir(Environment.DIRECTORY_MUSIC), FILE_NAME)
        GlobalScope.launch {
            convertToWave(file.absolutePath, wavFile.absolutePath) {
                if(it>0){
                    runOnUiThread {
                        showToast("success convert")
                    }
                } else {
                    runOnUiThread {
                        showToast("fail convert")
                    }
                }
            }
        }
    }



    fun convertToWave(
        inPath: String?,
        outPath: String?,
        callback: (status: Int) -> Unit
    ) {
        if (inPath.isNullOrBlank() || outPath.isNullOrBlank()) {
            callback(-1)
            return
        }
        val inFile = File(inPath)
        if (!inFile.exists()) {
            callback(-1)
            return
        }
        val outFile = File(outPath)
        if (outFile.exists()) outFile.delete()
        try {
            outFile.createNewFile()
        } catch (e: IOException) {
        }

        val fileLength = inFile.length()

        var fis: FileInputStream? = null
        var fos: FileOutputStream? = null
        try {
            fis = inFile.inputStream()
            fos = outFile.outputStream()
            val header = getWaveHeader(fileLength)
            fos.write(header)
//            fos.write(fis.readBytes())
            val data = ByteArray(minBufferSize)
            while (fis.read(data) != -1) {
                fos.write(data)
            }
            fis.close()
            fos.close()
            callback(1)
        } catch (e: Exception) {
            callback(-1)
        } finally {
            try {
                fis?.close()
                fos?.close()
            } catch (e: IOException) {
            }
        }
    }


    private fun getWaveHeader(fileLength: Long): ByteArray {
        val sampleRate = 44100
        val numChannels = 1.toLong()
        val bitsPerSample = 16
        val chunkSize = fileLength + 36
        val subChunk2Size = fileLength// - 44

        val byteRate = sampleRate * numChannels * bitsPerSample / 8
        val blockAlign = numChannels * bitsPerSample / 8
        val subChunk1Size = 16
        val audioFormat = 1

        val header = ByteArray(44)
        // The canonical WAVE format starts with the RIFF header:
        // ChunkID：RIFF
        header[0] = 'R'.toByte() // RIFF
        header[1] = 'I'.toByte()
        header[2] = 'F'.toByte()
        header[3] = 'F'.toByte()
        // ChunkSize：6 + SubChunk2Size, or 4 + (8 + SubChunk1Size) + (8 + SubChunk2Size)
        header[4] = (chunkSize and 0xff).toByte() // 数据大小
        header[5] = (chunkSize shr 8 and 0xff).toByte()
        header[6] = (chunkSize shr 16 and 0xff).toByte()
        header[7] = (chunkSize shr 24 and 0xff).toByte()
        // Format：WAVE
        header[8] = 'W'.toByte()
        header[9] = 'A'.toByte()
        header[10] = 'V'.toByte()
        header[11] = 'E'.toByte()

        // The "fmt " subChunk describes the sound data's format:
        // SubChunk1ID：'fmt '
        header[12] = 'f'.toByte()
        header[13] = 'm'.toByte()
        header[14] = 't'.toByte()
        header[15] = ' '.toByte()
        // SubChunk1Size：16 for PCM
        header[16] = subChunk1Size.toByte()
        header[17] = 0
        header[18] = 0
        header[19] = 0
        // AudioFormat：PCM = 1
        header[20] = audioFormat.toByte()
        header[21] = 0
        // NumChannels：Mono = 1，Stereo = 2
        header[22] = numChannels.toByte()
        header[23] = 0
        // SampleRate：采样率
        header[24] = (sampleRate and 0xff).toByte()
        header[25] = (sampleRate shr 8 and 0xff).toByte()
        header[26] = (sampleRate shr 16 and 0xff).toByte()
        header[27] = (sampleRate shr 24 and 0xff).toByte()
        // ByteRate：SampleRate * NumChannels * BitsPerSample / 8
        header[28] = (byteRate and 0xff).toByte()
        header[29] = (byteRate shr 8 and 0xff).toByte()
        header[30] = (byteRate shr 16 and 0xff).toByte()
        header[31] = (byteRate shr 24 and 0xff).toByte()
        // 确定系统一次要处理多少个这样字节的数据，确定缓冲区，通道数*采样位数
        // BlockAlign：NumChannels * BitsPerSample / 8
        header[32] = blockAlign.toByte()
        header[33] = 0
        // BitsPerSample：8 bits = 8, 16 bits = 16
        header[34] = bitsPerSample.toByte()
        header[35] = 0

        // The "data" subChunk contains the size of the data and the actual sound:
        // SubChunk2ID：data
        header[36] = 'd'.toByte() // data
        header[37] = 'a'.toByte()
        header[38] = 't'.toByte()
        header[39] = 'a'.toByte()
        // SubChunk2Size：NumSamples * NumChannels * BitsPerSample / 8
        header[40] = (subChunk2Size and 0xff).toByte()
        header[41] = (subChunk2Size shr 8 and 0xff).toByte()
        header[42] = (subChunk2Size shr 16 and 0xff).toByte()
        header[43] = (subChunk2Size shr 24 and 0xff).toByte()
        return header
    }
}