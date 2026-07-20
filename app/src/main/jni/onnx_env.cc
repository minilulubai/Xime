#include "onnx_env.h"
#include <android/log.h>
#include <mutex>

static OrtEnv* g_shared_env = nullptr;
static std::mutex g_env_mutex;
static bool g_env_created = false;

#define LOG_TAG "OnnxEnv"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

const OrtApi* OnnxGetApi() {
    static const OrtApi* api = nullptr;
    if (!api) {
        api = OrtGetApiBase()->GetApi(ORT_API_VERSION);
        if (!api) {
            LOGE("Failed to get ONNX Runtime API");
        }
    }
    return api;
}

OrtEnv* OnnxGetSharedEnv() {
    std::lock_guard<std::mutex> lock(g_env_mutex);
    if (!g_shared_env) {
        const OrtApi* api = OnnxGetApi();
        if (api) {
            OrtStatus* status = api->CreateEnv(ORT_LOGGING_LEVEL_WARNING, "xime_onnx", &g_shared_env);
            if (status) {
                LOGE("Failed to create shared env: %s", api->GetErrorMessage(status));
                api->ReleaseStatus(status);
            } else {
                g_env_created = true;
                LOGD("Onnx shared env created");
            }
        }
    }
    return g_shared_env;
}

void OnnxReleaseSharedEnv() {
    std::lock_guard<std::mutex> lock(g_env_mutex);
    if (g_shared_env) {
        const OrtApi* api = OnnxGetApi();
        if (api) {
            api->ReleaseEnv(g_shared_env);
            g_shared_env = nullptr;
            g_env_created = false;
            LOGD("Onnx shared env released");
        }
    }
}
