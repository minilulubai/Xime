#pragma once
#include <onnxruntime_c_api.h>

const OrtApi* OnnxGetApi();
OrtEnv* OnnxGetSharedEnv();
void OnnxReleaseSharedEnv();
