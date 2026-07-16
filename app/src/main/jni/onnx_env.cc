#include "onnx_env.h"
#include <android/log.h>
#include <mutex>

static OrtEnv* g_shared_env = nullptr;
static std::once_flag g_env_once;

#define LOG_TAG "OnnxEnv"
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
    std::call_once(g_env_once, []() {
        const OrtApi* api = OnnxGetApi();
        if (api) {
            OrtStatus* status = api->CreateEnv(ORT_LOGGING_LEVEL_WARNING, "xime_onnx", &g_shared_env);
            if (status) {
                LOGE("Failed to create shared env: %s", api->GetErrorMessage(status));
                api->ReleaseStatus(status);
            }
        }
    });
    return g_shared_env;
}

void OnnxReleaseSharedEnv() {
    const OrtApi* api = OnnxGetApi();
    if (api && g_shared_env) {
        api->ReleaseEnv(g_shared_env);
        g_shared_env = nullptr;
    }
}
