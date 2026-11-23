package com.example.chapter07;

/**
 * AI 服务配置（风险识别用）。
 *
 * 为什么单独抽一个文件：
 *   原来的代码把 OpenAI 的地址和模型名分散写在三个分析器里（FraudDetectionAnalyzer、
 *   FraudAudioTranscriptionAnalyzer、FraudVideoAnalyzer），想换服务商要改三处，
 *   很容易漏。现在全部集中到这里，换服务商只需要改这一个文件。
 *
 * 当前用的是硅基流动（https://cloud.siliconflow.cn），它提供 OpenAI 兼容接口，
 * 所以请求格式不需要改，只换地址和模型名即可。
 *
 * API Key 不写在这里，而是通过 gradle.properties 的 AI_API_KEY 在编译时注入，
 * 避免把密钥提交到代码仓库。
 */
public final class AiConfig {

    private AiConfig() {
    }

    // ========== 服务地址 ==========
    /** 接口根地址（OpenAI 兼容） */
    public static final String BASE_URL = "https://api.siliconflow.cn/v1";

    // ========== 语音转文字（ASR）==========
    public static final String ASR_URL = BASE_URL + "/audio/transcriptions";

    /**
     * 语音转写模型。
     * FunAudioLLM/SenseVoiceSmall 是阿里出的中文语音模型，中文效果好、价格低。
     * 备选：Qwen/Qwen3-ASR-1.7B
     */
    public static final String ASR_MODEL = "FunAudioLLM/SenseVoiceSmall";

    /** 音频语言；留空则由模型自动判断 */
    public static final String ASR_LANGUAGE = "zh";

    // ========== 文字风险判断（LLM）==========
    public static final String CHAT_URL = BASE_URL + "/chat/completions";

    /**
     * 文本模型（judge 通话内容是否存在诈骗特征）。
     * 备选：Qwen/Qwen3.8-27B、zai-org/GLM-5.2、deepseek-ai/DeepSeek-V4-Flash
     */
    public static final String CHAT_MODEL = "deepseek-ai/DeepSeek-V3.2";

    // ========== 画面风险判断（视觉模型）==========
    /**
     * 视觉模型（判断画面里有没有转账界面、身份证、验证码等风险特征）。
     * 备选：Qwen/Qwen3-VL-32B-Instruct、Qwen/Qwen3-VL-30B-A3B-Instruct
     */
    public static final String VISION_MODEL = "Qwen/Qwen3-VL-8B-Instruct";

    // ========== API Key ==========
    /** 编译期占位符；在 gradle.properties 里设置 AI_API_KEY 后会被替换 */
    public static final String PLACEHOLDER = "PLACEHOLDER_SET_AI_API_KEY_IN_GRADLE_PROPERTIES";

    /**
     * 取 API Key。没配置时返回空字符串，调用方应当据此提示用户而不是发无效请求。
     */
    public static String apiKey() {
        String key = BuildConfig.AI_API_KEY;
        if (key == null) {
            return "";
        }
        key = key.trim();
        if (key.isEmpty() || key.startsWith("PLACEHOLDER")) {
            return "";
        }
        return key;
    }

    /** 是否已配置好可用的 Key */
    public static boolean isConfigured() {
        return !apiKey().isEmpty();
    }
}
