package com.kingzcheung.xime.model

object Models {

    val PREDICTIVE_TEXT = ModelInfo(
        id = "predictive-text-small",
        name = "智能联想模型",
        description = "基于 ONNX 的 AI 联想词预测模型，int8 量化",
        category = ModelCategory.PREDICTION,
        size = "30+ MB",
        storageDir = "",
        files = listOf(
            ModelFile(
                name = "vocab.json",
                downloadUrl = "https://www.modelscope.cn/models/bikeand/predictive-text-small/resolve/master/vocab.json"
            ),
            ModelFile(
                name = "model_int8_dynamic.onnx",
                downloadUrl = "https://www.modelscope.cn/models/bikeand/predictive-text-small/resolve/master/model_int8_dynamic.onnx"
            )
        )
    )

    val ASR_ZIPFORMER_ZH = ModelInfo(
        id = "zipformer-zh-int8",
        name = "中文 Zipformer int8",
        description = "Zipformer 架构实时语音识别模型，int8 量化",
        category = ModelCategory.ASR,
        size = "36 MB",
        storageDir = "asr_models/zipformer-zh-int8",
        files = listOf(
            ModelFile(name = "encoder.int8.onnx", downloadUrl = ""),
            ModelFile(name = "decoder.onnx", downloadUrl = ""),
            ModelFile(name = "joiner.int8.onnx", downloadUrl = ""),
            ModelFile(name = "tokens.txt", downloadUrl = "")
        ),
        archiveUrl = "https://www.modelscope.cn/models/bikeand/asr/resolve/master/sherpa-onnx-streaming-zipformer-zh-int8-2025-06-30.tar.bz2"
    )

    val PUNCTUATION = ModelInfo(
        id = "punctuation_int8",
        name = "标点预测模型 int8",
        description = "基于 Transformer 的中文标点预测模型，int8 量化",
        category = ModelCategory.PUNCTUATION,
        size = "2.2 MB",
        storageDir = "punctuation_models",
        files = listOf(
            ModelFile(
                name = "punctuation_int8.onnx",
                downloadUrl = "https://www.modelscope.cn/models/bikeand/srf-punctuation/resolve/master/punctuation_int8.onnx"
            ),
            ModelFile(
                name = "vocab.json",
                downloadUrl = "https://www.modelscope.cn/models/bikeand/srf-punctuation/resolve/master/vocab.json"
            )
        )
    )

    val ALL: List<ModelInfo> = listOf(
        PREDICTIVE_TEXT,
        ASR_ZIPFORMER_ZH,
        PUNCTUATION
    )

    private val byId: Map<String, ModelInfo> = ALL.associateBy { it.id }

    fun getById(id: String): ModelInfo? = byId[id]

    fun getByCategory(category: ModelCategory): List<ModelInfo> =
        ALL.filter { it.category == category }
}
