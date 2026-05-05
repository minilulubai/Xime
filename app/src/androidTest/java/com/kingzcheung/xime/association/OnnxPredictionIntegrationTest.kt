package com.kingzcheung.xime.association

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class OnnxPredictionIntegrationTest {

    private lateinit var context: Context
    private var hasModel = false
    private var vocab: Map<String, Int> = emptyMap()
    private var id2word: Map<Int, String> = emptyMap()

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        val vocabFile = File(context.filesDir, "vocab.json")
        val modelFile = File(context.filesDir, "model_int8_dynamic.onnx")
        hasModel = vocabFile.exists() && modelFile.exists()

        if (hasModel) {
            val vocabJson = JSONObject(vocabFile.readText())
            val vocabMap = when {
                vocabJson.has("model") -> vocabJson.getJSONObject("model").getJSONObject("vocab")
                vocabJson.has("vocab") -> vocabJson.getJSONObject("vocab")
                else -> vocabJson
            }
            vocab = vocabMap.keys().asSequence().associateWith { vocabMap.getInt(it) }
            id2word = vocab.entries.associate { it.value to it.key }
            println("Vocabulary loaded: ${vocab.size} words")
            println("filesDir: ${context.filesDir.absolutePath}")
            println("model size: ${modelFile.length()} bytes")

            NativeOnnxEngine.initialize(context, modelFile.absolutePath)
        } else {
            println("Model files not found in ${context.filesDir.absolutePath}")
        }
    }

    @After
    fun tearDown() {
        if (hasModel) {
            NativeOnnxEngine.release()
        }
    }

    @Test
    fun testPredictJinTianTianQi() = runBlocking {
        if (!hasModel) return@runBlocking

        val inputText = "今天天气"
        val inputIds = inputText.map { vocab[it.toString()] ?: 3 }
        println("Input: '$inputText'")
        println("Input tokens: $inputIds")
        println("Input token lookup: ${inputIds.map { id -> id2word[id] ?: "UNK" }}")

        val inputIdsLong = inputIds.map { it.toLong() }.toLongArray()
        val scores = NativeOnnxEngine.predict(inputIdsLong, 20)

        println("Native results (id -> score):")
        scores.forEachIndexed { i, (id, score) ->
            val word = id2word[id] ?: "UNK(id=$id)"
            println("  Top ${i+1}: id=$id word='$word' score=$score")
        }

        println("\n--- Verification ---")
        println("vocab['晴']=${vocab["晴"]}, vocab['预']=${vocab["预"]}, vocab['好']=${vocab["好"]}")
        println("id 308 -> '${id2word[308]}'")
        println("id 81 -> '${id2word[81]}'")
        println("id 9 -> '${id2word[9]}'")

        org.junit.Assert.assertTrue("Should have prediction results", scores.isNotEmpty())
    }

    @Test
    fun testPredictJinTian() = runBlocking {
        if (!hasModel) return@runBlocking

        val inputText = "今天"
        val inputIds = inputText.map { vocab[it.toString()] ?: 3 }
        val inputIdsLong = inputIds.map { it.toLong() }.toLongArray()
        val scores = NativeOnnxEngine.predict(inputIdsLong, 20)

        println("Input: '$inputText'")
        scores.forEachIndexed { i, (id, score) ->
            val word = id2word[id] ?: "UNK(id=$id)"
            println("  Top ${i+1}: id=$id word='$word' score=$score")
        }

        org.junit.Assert.assertTrue("Should have prediction results", scores.isNotEmpty())
    }
}
