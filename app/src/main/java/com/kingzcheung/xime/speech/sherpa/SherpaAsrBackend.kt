package com.kingzcheung.xime.speech.sherpa

import android.content.Context
import android.util.Log
import com.kingzcheung.xime.speech.AsrBackend
import com.kingzcheung.xime.speech.RecognitionState

class SherpaAsrBackend(private val context: Context) : AsrBackend {

    override val name: String = "本地模型"

    private var engine: SherpaAsrEngine? = null
    private var resultCallback: ((String) -> Unit)? = null
    private var stateCallback: ((RecognitionState) -> Unit)? = null
    private var errorCallback: ((String) -> Unit)? = null

    override fun setCallbacks(
        onResult: (String) -> Unit,
        onStateChange: (RecognitionState) -> Unit,
        onError: (String) -> Unit
    ) {
        resultCallback = onResult
        stateCallback = onStateChange
        errorCallback = onError
    }

    override fun initialize(): Boolean {
        engine = SherpaAsrEngine(context)
        engine?.setCallbacks(
            onResult = { text -> resultCallback?.invoke(text) },
            onStateChange = { state -> stateCallback?.invoke(state) },
            onError = { error -> errorCallback?.invoke(error) }
        )
        if (!engine!!.isAvailable()) {
            Log.e("SherpaBackend", "sherpa-onnx JNI not loaded")
            return false
        }
        if (!engine!!.isModelReady()) {
            Log.e("SherpaBackend", "No model downloaded")
            return false
        }
        return true
    }

    override fun start(): Boolean {
        val eng = engine ?: return false
        return eng.initialize() && eng.startRecognition()
    }

    override fun processAudioChunk(buffer: ByteArray) {
        engine?.processAudioBytes(buffer)
    }

    override fun stop() {
        engine?.stopRecognition()
    }

    override fun cancel() {
        engine?.cancelRecognition()
    }

    override fun release() {
        engine?.release()
        engine = null
    }

    override fun getState(): RecognitionState {
        return engine?.getState() ?: RecognitionState.IDLE
    }

    override fun isAvailable(): Boolean {
        val eng = SherpaAsrEngine(context)
        return eng.isAvailable() && eng.isModelReady()
    }
}
