#include <jni.h>
#include <android/log.h>
#include <onnxruntime_c_api.h>
#include <string>
#include <vector>
#include <cstring>
#include <algorithm>
#include <mutex>
#include <fstream>
#include <chrono>
#include <iomanip>
#include <sstream>

#define LOG_TAG "OnnxJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

static std::string g_log_path;
static std::mutex g_log_mutex;

static void write_to_file(const std::string& message) {
    std::lock_guard<std::mutex> lock(g_log_mutex);
    if (g_log_path.empty()) return;
    
    try {
        std::ofstream file(g_log_path, std::ios::app);
        if (file.is_open()) {
            auto now = std::chrono::system_clock::now();
            auto time = std::chrono::system_clock::to_time_t(now);
            std::stringstream ss;
            ss << std::put_time(std::localtime(&time), "%Y-%m-%d %H:%M:%S");
            file << ss.str() << " [D] OnnxJNI: " << message << "\n";
            file.close();
        }
    } catch (...) {}
}

#define FILE_LOG(msg) write_to_file(msg)

static OrtEnv* g_env = nullptr;
static OrtSession* g_session = nullptr;
static OrtAllocator* g_allocator = nullptr;
static std::mutex g_onnx_mutex;

static const OrtApi* GetApi() {
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
Java_com_kingzcheung_xime_association_NativeOnnxEngine_nativeInitialize(
    JNIEnv* env, jobject thiz, jstring model_path) {

    std::lock_guard<std::mutex> lock(g_onnx_mutex);

    const OrtApi* api = GetApi();
    if (!api) {
        LOGE("ONNX Runtime API not available");
        return JNI_FALSE;
    }

    if (g_session) {
        LOGD("Session already initialized");
        return JNI_TRUE;
    }

    const char* modelPathStr = env->GetStringUTFChars(model_path, nullptr);
    LOGI("Initializing ONNX Runtime with model: %s", modelPathStr);
    
    std::string model_path_std(modelPathStr);
    size_t last_slash = model_path_std.find_last_of('/');
    if (last_slash != std::string::npos) {
        std::string files_dir = model_path_std.substr(0, last_slash);
        size_t logs_slash = files_dir.find_last_of('/');
        if (logs_slash != std::string::npos) {
            g_log_path = files_dir + "/logs/onnx_jni.log";
            FILE_LOG("Log file path: " + g_log_path);
        }
    }

    OrtStatus* status = nullptr;

    status = api->CreateEnv(ORT_LOGGING_LEVEL_WARNING, "onnx_prediction", &g_env);
    if (status) {
        LOGE("Failed to create OrtEnv: %s", api->GetErrorMessage(status));
        api->ReleaseStatus(status);
        env->ReleaseStringUTFChars(model_path, modelPathStr);
        return JNI_FALSE;
    }

    status = api->GetAllocatorWithDefaultOptions(&g_allocator);
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

    status = api->SetIntraOpNumThreads(session_options, 4);
    if (status) {
        LOGE("Failed to set intra op num threads: %s", api->GetErrorMessage(status));
        api->ReleaseStatus(status);
        api->ReleaseSessionOptions(session_options);
        env->ReleaseStringUTFChars(model_path, modelPathStr);
        return JNI_FALSE;
    }

    status = api->CreateSession(g_env, modelPathStr, session_options, &g_session);
    if (status) {
        LOGE("Failed to create session: %s", api->GetErrorMessage(status));
        api->ReleaseStatus(status);
        api->ReleaseSessionOptions(session_options);
        env->ReleaseStringUTFChars(model_path, modelPathStr);
        return JNI_FALSE;
    }

    api->ReleaseSessionOptions(session_options);
    env->ReleaseStringUTFChars(model_path, modelPathStr);

    LOGI("ONNX Runtime initialized successfully");
    return JNI_TRUE;
}

extern "C"
JNIEXPORT jobjectArray JNICALL
Java_com_kingzcheung_xime_association_NativeOnnxEngine_nativePredict(
    JNIEnv* env, jobject thiz, jlongArray input_ids, jint top_k) {

    std::lock_guard<std::mutex> lock(g_onnx_mutex);

    const OrtApi* api = GetApi();
    if (!api || !g_session) {
        LOGE("ONNX Runtime not initialized");
        return nullptr;
    }

    jsize input_len = env->GetArrayLength(input_ids);
    jlong* input_data = env->GetLongArrayElements(input_ids, nullptr);

    std::vector<int64_t> input_shape = {1, static_cast<int64_t>(input_len)};
    size_t input_shape_len = input_shape.size();

    OrtMemoryInfo* memory_info = nullptr;
    OrtStatus* status = api->CreateMemoryInfo("Cpu", OrtArenaAllocator, 0, OrtMemTypeDefault, &memory_info);
    if (status) {
        LOGE("Failed to create memory info: %s", api->GetErrorMessage(status));
        api->ReleaseStatus(status);
        env->ReleaseLongArrayElements(input_ids, input_data, JNI_ABORT);
        return nullptr;
    }

    OrtValue* input_tensor = nullptr;
    status = api->CreateTensorWithDataAsOrtValue(
        memory_info,
        input_data,
        input_len * sizeof(int64_t),
        input_shape.data(),
        input_shape_len,
        ONNX_TENSOR_ELEMENT_DATA_TYPE_INT64,
        &input_tensor
    );
    api->ReleaseMemoryInfo(memory_info);

    if (status) {
        LOGE("Failed to create input tensor: %s", api->GetErrorMessage(status));
        api->ReleaseStatus(status);
        env->ReleaseLongArrayElements(input_ids, input_data, JNI_ABORT);
        return nullptr;
    }

    const char* input_names[] = {"input_ids"};
    const char* output_names[] = {"logits"};

    OrtValue* output_tensor = nullptr;
    status = api->Run(g_session, nullptr, input_names, (const OrtValue* const*)&input_tensor, 1,
                      output_names, 1, &output_tensor);

    api->ReleaseValue(input_tensor);
    env->ReleaseLongArrayElements(input_ids, input_data, JNI_ABORT);

    if (status) {
        LOGE("Failed to run session: %s", api->GetErrorMessage(status));
        api->ReleaseStatus(status);
        return nullptr;
    }

    OrtTensorTypeAndShapeInfo* output_info = nullptr;
    status = api->GetTensorTypeAndShape(output_tensor, &output_info);
    if (status) {
        LOGE("Failed to get output info: %s", api->GetErrorMessage(status));
        api->ReleaseStatus(status);
        api->ReleaseValue(output_tensor);
        return nullptr;
    }

    size_t dims_count = 0;
    status = api->GetDimensionsCount(output_info, &dims_count);
    if (status) {
        LOGE("Failed to get dimensions count: %s", api->GetErrorMessage(status));
        api->ReleaseStatus(status);
        api->ReleaseTensorTypeAndShapeInfo(output_info);
        api->ReleaseValue(output_tensor);
        return nullptr;
    }

    std::vector<int64_t> output_dims(dims_count);
    status = api->GetDimensions(output_info, output_dims.data(), dims_count);
    api->ReleaseTensorTypeAndShapeInfo(output_info);

    if (status) {
        LOGE("Failed to get dimensions: %s", api->GetErrorMessage(status));
        api->ReleaseStatus(status);
        api->ReleaseValue(output_tensor);
        return nullptr;
    }

    LOGD("Output shape: [%ld, %ld, %ld]", (long)output_dims[0], (long)output_dims[1], (long)output_dims[2]);
    FILE_LOG("Output shape: [" + std::to_string(output_dims[0]) + ", " + std::to_string(output_dims[1]) + ", " + std::to_string(output_dims[2]) + "]");

    float* output_data = nullptr;
    status = api->GetTensorMutableData(output_tensor, (void**)&output_data);
    if (status) {
        LOGE("Failed to get output data: %s", api->GetErrorMessage(status));
        api->ReleaseStatus(status);
        api->ReleaseValue(output_tensor);
        return nullptr;
    }

    int64_t vocab_size = output_dims[2];
    int64_t last_pos = output_dims[1] - 1;
    
    FILE_LOG("vocab_size=" + std::to_string(vocab_size) + ", last_pos=" + std::to_string(last_pos) + ", input_len=" + std::to_string(input_len));

    float* logits = output_data + last_pos * vocab_size;
    
    std::stringstream logits_ss;
    logits_ss << "First 10 logits at position " << last_pos << ": ";
    for (int i = 0; i < 10; i++) {
        logits_ss << logits[i] << " ";
    }
    FILE_LOG(logits_ss.str());

    std::vector<std::pair<int, float>> scores;
    for (int64_t i = 4; i < vocab_size; i++) {
        scores.emplace_back(static_cast<int>(i), logits[i]);
    }

    std::sort(scores.begin(), scores.end(),
              [](const auto& a, const auto& b) { return a.second > b.second; });

    scores.resize(std::min(static_cast<size_t>(top_k), scores.size()));
    
    std::stringstream top_ss;
    top_ss << "Top 5 predictions: ";
    for (int i = 0; i < 5 && i < scores.size(); i++) {
        top_ss << "id=" << scores[i].first << " score=" << scores[i].second << " | ";
    }
    FILE_LOG(top_ss.str());

    jclass string_class = env->FindClass("java/lang/String");
    jobjectArray result = env->NewObjectArray(scores.size() * 2, string_class, nullptr);

    for (size_t i = 0; i < scores.size(); i++) {
        char idx_str[32];
        snprintf(idx_str, sizeof(idx_str), "%d", scores[i].first);
        jstring j_idx = env->NewStringUTF(idx_str);
        env->SetObjectArrayElement(result, i * 2, j_idx);

        char score_str[32];
        snprintf(score_str, sizeof(score_str), "%f", scores[i].second);
        jstring j_score = env->NewStringUTF(score_str);
        env->SetObjectArrayElement(result, i * 2 + 1, j_score);
    }

    api->ReleaseValue(output_tensor);

    LOGD("Predicted %zu candidates", scores.size());
    return result;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_kingzcheung_xime_association_NativeOnnxEngine_nativeRelease(
    JNIEnv* env, jobject thiz) {

    std::lock_guard<std::mutex> lock(g_onnx_mutex);

    const OrtApi* api = GetApi();

    if (g_session) {
        api->ReleaseSession(g_session);
        g_session = nullptr;
        LOGD("Session released");
    }

    if (g_env) {
        api->ReleaseEnv(g_env);
        g_env = nullptr;
        LOGD("Env released");
    }
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_kingzcheung_xime_association_NativeOnnxEngine_nativeIsInitialized(
    JNIEnv* env, jobject thiz) {
    std::lock_guard<std::mutex> lock(g_onnx_mutex);
    return g_session ? JNI_TRUE : JNI_FALSE;
}