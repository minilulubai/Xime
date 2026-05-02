#include <jni.h>
#include <android/log.h>
#include <onnxruntime_c_api.h>
#include <string>
#include <vector>
#include <cstring>
#include <algorithm>
#include <mutex>
#include <cmath>

#define LOG_TAG "PunctuationJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

static OrtEnv* g_punc_env = nullptr;
static OrtSession* g_punc_session = nullptr;
static OrtAllocator* g_punc_allocator = nullptr;
static std::mutex g_punc_mutex;

static const OrtApi* GetPuncApi() {
    static const OrtApi* api = nullptr;
    if (!api) {
        api = OrtGetApiBase()->GetApi(ORT_API_VERSION);
        if (!api) {
            LOGE("Failed to get ONNX Runtime API");
        }
    }
    return api;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_kingzcheung_xime_speech_punctuation_PunctuationInference_nativeInitialize(
    JNIEnv* env, jobject thiz, jstring model_path) {

    std::lock_guard<std::mutex> lock(g_punc_mutex);

    const OrtApi* api = GetPuncApi();
    if (!api) {
        LOGE("ONNX Runtime API not available");
        return JNI_FALSE;
    }

    if (g_punc_session) {
        LOGD("Punctuation session already initialized");
        return JNI_TRUE;
    }

    const char* modelPathStr = env->GetStringUTFChars(model_path, nullptr);
    LOGI("Initializing Punctuation ONNX Runtime with model: %s", modelPathStr);

    OrtStatus* status = nullptr;

    if (!g_punc_env) {
        status = api->CreateEnv(ORT_LOGGING_LEVEL_WARNING, "punctuation", &g_punc_env);
        if (status) {
            LOGE("Failed to create OrtEnv: %s", api->GetErrorMessage(status));
            api->ReleaseStatus(status);
            env->ReleaseStringUTFChars(model_path, modelPathStr);
            return JNI_FALSE;
        }
    }

    status = api->GetAllocatorWithDefaultOptions(&g_punc_allocator);
    if (status) {
        LOGE("Failed to get allocator: %s", api->GetErrorMessage(status));
        api->ReleaseStatus(status);
        env->ReleaseStringUTFChars(model_path, modelPathStr);
        return JNI_FALSE;
    }

    OrtSessionOptions* session_options = nullptr;
    status = api->CreateSessionOptions(&session_options);
    if (status) {
        LOGE("Failed to create session options: %s", api->GetErrorMessage(status));
        api->ReleaseStatus(status);
        env->ReleaseStringUTFChars(model_path, modelPathStr);
        return JNI_FALSE;
    }

    status = api->SetIntraOpNumThreads(session_options, 2);
    if (status) {
        LOGE("Failed to set intra op num threads: %s", api->GetErrorMessage(status));
        api->ReleaseStatus(status);
        api->ReleaseSessionOptions(session_options);
        env->ReleaseStringUTFChars(model_path, modelPathStr);
        return JNI_FALSE;
    }

    status = api->CreateSession(g_punc_env, modelPathStr, session_options, &g_punc_session);
    if (status) {
        LOGE("Failed to create session: %s", api->GetErrorMessage(status));
        api->ReleaseStatus(status);
        api->ReleaseSessionOptions(session_options);
        env->ReleaseStringUTFChars(model_path, modelPathStr);
        return JNI_FALSE;
    }

    api->ReleaseSessionOptions(session_options);
    env->ReleaseStringUTFChars(model_path, modelPathStr);

    LOGI("Punctuation ONNX Runtime initialized successfully");
    return JNI_TRUE;
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_kingzcheung_xime_speech_punctuation_PunctuationInference_nativePredict(
    JNIEnv* env, jobject thiz, jintArray input_ids) {

    std::lock_guard<std::mutex> lock(g_punc_mutex);

    const OrtApi* api = GetPuncApi();
    if (!api || !g_punc_session) {
        LOGE("Punctuation ONNX Runtime not initialized");
        return env->NewStringUTF("");
    }

    jsize input_len = env->GetArrayLength(input_ids);
    if (input_len == 0) {
        return env->NewStringUTF("");
    }

    jint* input_data = env->GetIntArrayElements(input_ids, nullptr);

    // Debug: print input IDs
    LOGD("Punctuation input IDs (%d tokens):", input_len);
    for (jsize i = 0; i < input_len && i < 20; i++) {
        LOGD("  [%d]: %d", i, input_data[i]);
    }

    std::vector<int64_t> input_shape = {1, static_cast<int64_t>(input_len)};
    size_t input_shape_len = input_shape.size();

    OrtMemoryInfo* memory_info = nullptr;
    OrtStatus* status = api->CreateMemoryInfo("Cpu", OrtArenaAllocator, 0, OrtMemTypeDefault, &memory_info);
    if (status) {
        LOGE("Failed to create memory info: %s", api->GetErrorMessage(status));
        api->ReleaseStatus(status);
        env->ReleaseIntArrayElements(input_ids, input_data, JNI_ABORT);
        return env->NewStringUTF("");
    }

    std::vector<int64_t> input_ids_64(input_len);
    for (jsize i = 0; i < input_len; i++) {
        input_ids_64[i] = static_cast<int64_t>(input_data[i]);
    }

    OrtValue* input_tensor = nullptr;
    status = api->CreateTensorWithDataAsOrtValue(
        memory_info,
        input_ids_64.data(),
        input_len * sizeof(int64_t),
        input_shape.data(),
        input_shape_len,
        ONNX_TENSOR_ELEMENT_DATA_TYPE_INT64,
        &input_tensor
    );

    if (status) {
        LOGE("Failed to create input tensor: %s", api->GetErrorMessage(status));
        api->ReleaseStatus(status);
        api->ReleaseMemoryInfo(memory_info);
        env->ReleaseIntArrayElements(input_ids, input_data, JNI_ABORT);
        return env->NewStringUTF("");
    }

    const char* input_names[] = {"input_ids", "attention_mask"};
    const char* output_names[] = {"logits"};
    
    // Create attention mask (all ones)
    std::vector<int64_t> attention_mask(input_len, 1);
    OrtValue* attention_mask_tensor = nullptr;
    status = api->CreateTensorWithDataAsOrtValue(
        memory_info,
        attention_mask.data(),
        input_len * sizeof(int64_t),
        input_shape.data(),
        input_shape_len,
        ONNX_TENSOR_ELEMENT_DATA_TYPE_INT64,
        &attention_mask_tensor
    );
    
    api->ReleaseMemoryInfo(memory_info);
    
    if (status) {
        LOGE("Failed to create attention_mask tensor: %s", api->GetErrorMessage(status));
        api->ReleaseStatus(status);
        api->ReleaseValue(input_tensor);
        env->ReleaseIntArrayElements(input_ids, input_data, JNI_ABORT);
        return env->NewStringUTF("");
    }

    const OrtValue* input_tensors[] = {input_tensor, attention_mask_tensor};
    OrtValue* output_tensor = nullptr;
    status = api->Run(g_punc_session, nullptr, input_names, input_tensors, 2,
                      output_names, 1, &output_tensor);

    api->ReleaseValue(input_tensor);
    api->ReleaseValue(attention_mask_tensor);
    env->ReleaseIntArrayElements(input_ids, input_data, JNI_ABORT);

    if (status) {
        LOGE("Failed to run session: %s", api->GetErrorMessage(status));
        api->ReleaseStatus(status);
        return env->NewStringUTF("");
    }

    OrtTensorTypeAndShapeInfo* output_info = nullptr;
    status = api->GetTensorTypeAndShape(output_tensor, &output_info);
    if (status) {
        LOGE("Failed to get output info: %s", api->GetErrorMessage(status));
        api->ReleaseStatus(status);
        api->ReleaseValue(output_tensor);
        return env->NewStringUTF("");
    }

    size_t dims_count = 0;
    status = api->GetDimensionsCount(output_info, &dims_count);
    if (status) {
        LOGE("Failed to get dimensions count: %s", api->GetErrorMessage(status));
        api->ReleaseStatus(status);
        api->ReleaseTensorTypeAndShapeInfo(output_info);
        api->ReleaseValue(output_tensor);
        return env->NewStringUTF("");
    }

    std::vector<int64_t> output_dims(dims_count);
    status = api->GetDimensions(output_info, output_dims.data(), dims_count);
    api->ReleaseTensorTypeAndShapeInfo(output_info);

    if (status) {
        LOGE("Failed to get dimensions: %s", api->GetErrorMessage(status));
        api->ReleaseStatus(status);
        api->ReleaseValue(output_tensor);
        return env->NewStringUTF("");
    }

    LOGD("Punctuation output shape: [%ld, %ld, %ld]", 
         (long)output_dims[0], (long)output_dims[1], (long)output_dims[2]);

    float* output_data = nullptr;
    status = api->GetTensorMutableData(output_tensor, (void**)&output_data);
    if (status) {
        LOGE("Failed to get output data: %s", api->GetErrorMessage(status));
        api->ReleaseStatus(status);
        api->ReleaseValue(output_tensor);
        return env->NewStringUTF("");
    }

    int64_t seq_len = output_dims[1];
    int64_t num_labels = output_dims[2];

    // Debug: print all logits for all positions
    LOGD("Punctuation output shape: batch=%ld, seq_len=%ld, num_labels=%ld", 
         (long)output_dims[0], (long)seq_len, (long)num_labels);

    // Build result string with all predicted labels
    std::string result;
    for (int64_t pos = 0; pos < seq_len; pos++) {
        float* pos_logits = output_data + pos * num_labels;
        
        int best_label = 0;
        float best_score = pos_logits[0];
        for (int64_t i = 1; i < num_labels; i++) {
            if (pos_logits[i] > best_score) {
                best_score = pos_logits[i];
                best_label = static_cast<int>(i);
            }
        }
        
        // Append label for this position
        if (pos > 0) result += ",";
        result += std::to_string(best_label);
        
        // Debug first and last few positions
        if (pos < 3 || pos >= seq_len - 3) {
            LOGD("Position %ld: label=%d, score=%f", (long)pos, best_label, best_score);
        }
    }

    api->ReleaseValue(output_tensor);

    LOGD("Punctuation all labels: %s", result.c_str());

    return env->NewStringUTF(result.c_str());
}

extern "C"
JNIEXPORT void JNICALL
Java_com_kingzcheung_xime_speech_punctuation_PunctuationInference_nativeRelease(
    JNIEnv* env, jobject thiz) {

    std::lock_guard<std::mutex> lock(g_punc_mutex);

    const OrtApi* api = GetPuncApi();

    if (g_punc_session) {
        api->ReleaseSession(g_punc_session);
        g_punc_session = nullptr;
        LOGD("Punctuation session released");
    }

    if (g_punc_env) {
        api->ReleaseEnv(g_punc_env);
        g_punc_env = nullptr;
        LOGD("Punctuation env released");
    }
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_kingzcheung_xime_speech_punctuation_PunctuationInference_nativeIsInitialized(
    JNIEnv* env, jobject thiz) {
    std::lock_guard<std::mutex> lock(g_punc_mutex);
    return g_punc_session ? JNI_TRUE : JNI_FALSE;
}