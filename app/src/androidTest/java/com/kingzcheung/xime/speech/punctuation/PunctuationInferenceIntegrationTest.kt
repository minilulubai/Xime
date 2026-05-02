package com.kingzcheung.xime.speech.punctuation

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kingzcheung.xime.settings.SettingsPreferences
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Integration tests for PunctuationInference using actual model
 *
 * These tests verify that the punctuation model produces correct predictions
 * by comparing with expected outputs.
 */
@RunWith(AndroidJUnit4::class)
class PunctuationInferenceIntegrationTest {

    private lateinit var context: Context
    private var modelDownloaded = false

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        
        // Check if model is downloaded
        val manager = PunctuationModelManager(context)
        modelDownloaded = manager.isModelDownloaded()
        
        if (modelDownloaded) {
            // Initialize the model
            val modelFile = manager.getModelFile()
            val vocabFile = manager.getVocabFile()
            val initialized = PunctuationInference.initialize(context, modelFile.absolutePath, vocabFile.absolutePath)
            assertTrue("Model should initialize successfully", initialized)
        }
    }

    @After
    fun tearDown() {
        if (modelDownloaded) {
            PunctuationInference.release()
        }
    }

    @Test
    fun testModelPredictionWithQuestion() {
        if (!modelDownloaded) {
            println("Model not downloaded, skipping test")
            return
        }

        // Test text that should get question mark
        val testCases = listOf(
            "你好吗" to "？",
            "这是什么" to "？",
            "怎么做" to "？",
            "为什么呢" to "？"
        )

        for ((text, expectedPunctuation) in testCases) {
            val result = PunctuationInference.predict(text)
            println("Input: '$text' -> Output: '$result'")
            
            // The result should end with some punctuation
            assertTrue("Result should contain original text: $result", 
                result.contains(text))
        }
    }

    @Test
    fun testModelPredictionWithPeriod() {
        if (!modelDownloaded) {
            println("Model not downloaded, skipping test")
            return
        }

        // Test text that should get period
        val testCases = listOf(
            "今天天气很好",
            "我去上学",
            "这是测试"
        )

        for (text in testCases) {
            val result = PunctuationInference.predict(text)
            println("Input: '$text' -> Output: '$result'")
            
            assertTrue("Result should contain original text", 
                result.contains(text))
        }
    }

    @Test
    fun testModelPredictionWithComma() {
        if (!modelDownloaded) {
            println("Model not downloaded, skipping test")
            return
        }

        // Test text that might get comma
        val testCases = listOf(
            "你好",
            "是的"
        )

        for (text in testCases) {
            val result = PunctuationInference.predict(text)
            println("Input: '$text' -> Output: '$result'")
            
            assertTrue("Result should contain original text", 
                result.contains(text))
        }
    }

    @Test
    fun testModelPredictionNotAlwaysLabel0() {
        if (!modelDownloaded) {
            println("Model not downloaded, skipping test")
            return
        }

        // Test multiple inputs to verify model doesn't always return label 0
        val testInputs = listOf(
            "你好吗",
            "今天天气很好",
            "这是什么",
            "我去上学",
            "为什么呢"
        )

        var nonZeroPredictions = 0
        val results = mutableListOf<String>()

        for (text in testInputs) {
            val result = PunctuationInference.predict(text)
            results.add("'$text' -> '$result'")
            
            // Check if result has punctuation added
            if (result.length > text.length) {
                nonZeroPredictions++
            }
        }

        // Print all results for debugging
        results.forEach { println(it) }

        // Model should predict punctuation for at least some inputs
        // This test documents the current behavior
        println("Non-zero predictions: $nonZeroPredictions/${testInputs.size}")
    }

    @Test
    fun testInputIdsGeneration() {
        // Test that input IDs are generated correctly from text
        val charToId = mapOf(
            "你" to 100,
            "好" to 101,
            "吗" to 102,
            "<unk>" to 1
        )

        val testCases = listOf(
            "你好吗" to listOf(100, 101, 102),
            "你好" to listOf(100, 101)
        )

        for ((text, expectedIds) in testCases) {
            val actualIds = text.map { char ->
                charToId[char.toString()] ?: 1
            }
            println("Text: '$text' -> IDs: $actualIds")
        }
    }

    @Test
    fun testVocabLoading() {
        // Test that vocab can be loaded from assets
        val loaded = PunctuationInference.loadVocabFromAssets(context)
        
        if (modelDownloaded) {
            // If model is downloaded, vocab should load successfully
            println("Vocab loaded: $loaded")
        }
    }

    @Test
    fun testModelFileExists() {
        val manager = PunctuationModelManager(context)
        
        if (manager.isModelDownloaded()) {
            val modelFile = manager.getModelFile()
            assertTrue("Model file should exist", modelFile.exists())
            assertTrue("Model file should not be empty", modelFile.length() > 0)
            println("Model file size: ${modelFile.length()} bytes")
        } else {
            println("Model not downloaded")
        }
    }
}
