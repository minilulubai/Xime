#include <jni.h>
#include <android/log.h>
#include <onnxruntime_c_api.h>
#include "onnx_env.h"
#include <string>
#include <vector>
#include <cstring>
#include <algorithm>
#include <mutex>
#include <unordered_map>
#define LOG_TAG "OnnxJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

static OrtSession* g_session = nullptr;
static OrtAllocator* g_allocator = nullptr;
static std::mutex g_onnx_mutex;

static std::unordered_map<std::string, int64_t> g_vocab;
static std::vector<std::string> g_id2word;
static int64_t g_bos_id = 1;
static int64_t g_unk_id = 3;

extern "C"
JNIEXPORT void JNICALL
Java_com_kingzcheung_xime_association_NativeOnnxEngine_nativeInitVocab(
    JNIEnv* env, jobject thiz, jobjectArray keys, jintArray values) {

    std::lock_guard<std::mutex> lock(g_onnx_mutex);

    jsize len = env->GetArrayLength(keys);
    jint* val_arr = env->GetIntArrayElements(values, nullptr);

    g_vocab.clear();
    g_vocab.reserve(len);

    int64_t max_id = 0;
    for (jsize i = 0; i < len; i++) {
        jstring jkey = static_cast<jstring>(env->GetObjectArrayElement(keys, i));
        const char* key_str = env->GetStringUTFChars(jkey, nullptr);
        int64_t id = val_arr[i];
        g_vocab[key_str] = id;
        if (id > max_id) max_id = id;
        env->ReleaseStringUTFChars(jkey, key_str);
        env->DeleteLocalRef(jkey);
    }

    g_id2word.assign(max_id + 1, "");
    for (const auto& pair : g_vocab) {
        int64_t id = pair.second;
        if (id >= 0 && static_cast<size_t>(id) < g_id2word.size()) {
            g_id2word[id] = pair.first;
        }
    }

    g_bos_id = g_vocab.count("[BOS]") ? g_vocab["[BOS]"] : 1;
    g_unk_id = g_vocab.count("[UNK]") ? g_vocab["[UNK]"] : 3;

    LOGI("Vocab loaded: %zu tokens, max_id=%lld", g_vocab.size(), (long long)max_id);

    env->ReleaseIntArrayElements(values, val_arr, JNI_ABORT);
}

extern "C"
JNIEXPORT jlongArray JNICALL
Java_com_kingzcheung_xime_association_NativeOnnxEngine_nativeEncode(
    JNIEnv* env, jobject thiz, jstring text) {

    std::lock_guard<std::mutex> lock(g_onnx_mutex);

    const char* utf8_text = env->GetStringUTFChars(text, nullptr);
    jsize utf8_len = env->GetStringUTFLength(text);

    std::vector<int64_t> ids;
    ids.push_back(g_bos_id);

    const char* p = utf8_text;
    const char* end = utf8_text + utf8_len;
    while (p < end) {
        unsigned char c = static_cast<unsigned char>(*p);
        int char_len;
        if ((c & 0x80) == 0) char_len = 1;
        else if ((c & 0xE0) == 0xC0) char_len = 2;
        else if ((c & 0xF0) == 0xE0) char_len = 3;
        else if ((c & 0xF8) == 0xF0) char_len = 4;
        else {
            ids.push_back(g_unk_id);
            p++;
            continue;
        }
        if (p + char_len > end) char_len = static_cast<int>(end - p);

        std::string ch(p, char_len);
        auto it = g_vocab.find(ch);
        ids.push_back(it != g_vocab.end() ? it->second : g_unk_id);
        p += char_len;
    }

    env->ReleaseStringUTFChars(text, utf8_text);

    jlongArray result = env->NewLongArray(ids.size());
    env->SetLongArrayRegion(result, 0, ids.size(), ids.data());
    return result;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_kingzcheung_xime_association_NativeOnnxEngine_nativeInitialize(
    JNIEnv* env, jobject thiz, jstring model_path) {

    std::lock_guard<std::mutex> lock(g_onnx_mutex);

    const OrtApi* api = OnnxGetApi();
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

    OrtEnv* ort_env = OnnxGetSharedEnv();
    if (!ort_env) {
        LOGE("Failed to get shared ONNX Runtime environment");
        env->ReleaseStringUTFChars(model_path, modelPathStr);
        return JNI_FALSE;
    }

    OrtStatus* status = nullptr;

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

    status = api->CreateSession(ort_env, modelPathStr, session_options, &g_session);
    api->ReleaseSessionOptions(session_options);
    env->ReleaseStringUTFChars(model_path, modelPathStr);

    if (status) {
        LOGE("Failed to create session: %s", api->GetErrorMessage(status));
        api->ReleaseStatus(status);
        return JNI_FALSE;
    }

    LOGI("ONNX Runtime initialized successfully");
    return JNI_TRUE;
}

extern "C"
JNIEXPORT jobjectArray JNICALL
Java_com_kingzcheung_xime_association_NativeOnnxEngine_nativePredict(
    JNIEnv* env, jobject thiz, jlongArray input_ids, jint top_k) {

    std::lock_guard<std::mutex> lock(g_onnx_mutex);

    const OrtApi* api = OnnxGetApi();
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

    float* logits = output_data + last_pos * vocab_size;

    std::vector<std::pair<int, float>> scores;
    for (int64_t i = 4; i < vocab_size; i++) {
        scores.emplace_back(static_cast<int>(i), logits[i]);
    }

    std::sort(scores.begin(), scores.end(),
              [](const auto& a, const auto& b) { return a.second > b.second; });

    scores.resize(std::min(static_cast<size_t>(top_k), scores.size()));

    jclass string_class = env->FindClass("java/lang/String");

    size_t valid_count = 0;
    for (size_t i = 0; i < scores.size(); i++) {
        int id = scores[i].first;
        if (id >= 0 && static_cast<size_t>(id) < g_id2word.size() && !g_id2word[id].empty()) {
            valid_count++;
        }
    }

    jobjectArray result = env->NewObjectArray(valid_count * 2, string_class, nullptr);

    size_t out_idx = 0;
    for (size_t i = 0; i < scores.size(); i++) {
        int id = scores[i].first;
        if (!(id >= 0 && static_cast<size_t>(id) < g_id2word.size() && !g_id2word[id].empty())) continue;

        jstring j_word = env->NewStringUTF(g_id2word[id].c_str());
        env->SetObjectArrayElement(result, out_idx * 2, j_word);
        env->DeleteLocalRef(j_word);

        char score_str[32];
        snprintf(score_str, sizeof(score_str), "%f", scores[i].second);
        jstring j_score = env->NewStringUTF(score_str);
        env->SetObjectArrayElement(result, out_idx * 2 + 1, j_score);
        env->DeleteLocalRef(j_score);

        out_idx++;
    }

    api->ReleaseValue(output_tensor);

    LOGD("Predicted %zu candidates", valid_count);
    return result;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_kingzcheung_xime_association_NativeOnnxEngine_nativeRelease(
    JNIEnv* env, jobject thiz) {

    std::lock_guard<std::mutex> lock(g_onnx_mutex);

    const OrtApi* api = OnnxGetApi();

    if (g_session) {
        api->ReleaseSession(g_session);
        g_session = nullptr;
        LOGD("Session released");
    }
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_kingzcheung_xime_association_NativeOnnxEngine_nativeIsInitialized(
    JNIEnv* env, jobject thiz) {
    std::lock_guard<std::mutex> lock(g_onnx_mutex);
    return g_session ? JNI_TRUE : JNI_FALSE;
}
