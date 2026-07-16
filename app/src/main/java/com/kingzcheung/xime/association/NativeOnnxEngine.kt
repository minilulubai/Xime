package com.kingzcheung.xime.association

import android.content.Context
import android.util.Log
import com.kingzcheung.xime.util.FileLogger
import java.io.File

object NativeOnnxEngine {
    private const val TAG = "NativeOnnxEngine"
    private var nativeLoaded = false

    fun loadNativeLibrary(context: Context): Boolean {
        val libsToLoad = listOf("libonnxruntime.so", "libonnx_env.so", "libonnx_jni.so")

        for (libName in libsToLoad) {
            if (!loadSingleLibrary(context, libName)) {
                FileLogger.e(TAG, "Failed to load $libName")
                return false
            }
        }

        nativeLoaded = true
        FileLogger.i(TAG, "All native libraries loaded successfully")
        return true
    }

    private fun loadSingleLibrary(context: Context, libName: String): Boolean {
        val simpleName = libName.removePrefix("lib").removeSuffix(".so")

        try {
            System.loadLibrary(simpleName)
            FileLogger.d(TAG, "Loaded $libName via System.loadLibrary")
            return true
        } catch (e: UnsatisfiedLinkError) {
            if (e.message?.contains("already opened") == true || e.message?.contains("already loaded") == true) {
                FileLogger.d(TAG, "$libName already loaded, skipping")
                return true
            }
            FileLogger.d(TAG, "System.loadLibrary failed for $libName, trying alternative methods...")
        }

        val nativeLibDir = context.applicationInfo?.nativeLibraryDir
        if (nativeLibDir != null) {
            val libFile = File(nativeLibDir, libName)
            if (libFile.exists()) {
                try {
                    System.load(libFile.absolutePath)
                    FileLogger.d(TAG, "Loaded $libName from nativeLibraryDir: ${libFile.absolutePath}")
                    return true
                } catch (e: UnsatisfiedLinkError) {
                    if (e.message?.contains("already opened") == true || e.message?.contains("already loaded") == true) {
                        FileLogger.d(TAG, "$libName already loaded, skipping")
                        return true
                    }
                    FileLogger.e(TAG, "Failed to load from nativeLibraryDir: ${e.message}")
                } catch (e: Exception) {
                    FileLogger.e(TAG, "Failed to load from nativeLibraryDir", e)
                }
            }
        }

        return false
    }

    fun initVocab(vocab: Map<String, Int>) {
        val keys = vocab.keys.toTypedArray()
        val values = vocab.values.toIntArray()
        nativeInitVocab(keys, values)
    }

    fun initialize(context: Context, modelPath: String): Boolean {
        try {
            nativeInitialize(modelPath)
            FileLogger.d(TAG, "Native method already available")
            return true
        } catch (e: UnsatisfiedLinkError) {
            FileLogger.d(TAG, "Native method not available, loading libraries...")
        }

        if (!loadNativeLibrary(context)) {
            FileLogger.e(TAG, "Native libraries not loaded")
            return false
        }

        return try {
            nativeInitialize(modelPath)
        } catch (e: UnsatisfiedLinkError) {
            FileLogger.e(TAG, "Native method still unavailable after loading: ${e.message}")
            nativeLoaded = false
            false
        } catch (e: Exception) {
            FileLogger.e(TAG, "Native method failed: ${e.message}", e)
            false
        }
    }

    fun predict(inputText: String, topK: Int = 20): List<AssociationCandidate> {
        val inputIds = nativeEncode(inputText) ?: return emptyList()
        val result = nativePredict(inputIds, topK) ?: return emptyList()

        val candidates = mutableListOf<AssociationCandidate>()
        for (i in result.indices step 2) {
            val word = result[i]
            val score = result[i + 1].toFloatOrNull() ?: continue
            candidates.add(AssociationCandidate(word, score))
        }
        return candidates
    }

    fun release() {
        nativeRelease()
    }

    fun isInitialized(): Boolean {
        return nativeIsInitialized()
    }

    private external fun nativeInitVocab(keys: Array<String>, values: IntArray)
    private external fun nativeEncode(text: String): LongArray?
    private external fun nativeInitialize(modelPath: String): Boolean
    private external fun nativePredict(inputIds: LongArray, topK: Int): Array<String>?
    private external fun nativeRelease()
    private external fun nativeIsInitialized(): Boolean
}
