package com.kingzcheung.xime.rime

/**
 * 九宫格数字→拼音映射
 *
 * 纯函数式、无状态、无 JNI 调用的轻量模块。
 * 替代 T9Decoder 的数字→拼音解码功能。
 *
 * 设计决策：
 * - 不引入模糊音。九键本身已通过「一个数字对应 3-4 个字母」提供容错，
 *   叠加模糊音会膨胀左侧候选列并引入无效拼音。
 * - 所有有效拼音与字母→数字映射均内聚在本对象中，避免依赖外部数据源。
 *
 * 使用示例：
 *   T9PinyinMap.firstSyllableOptions("54")  → [SyllableOption("ji", 2), SyllableOption("li", 2), ...]
 *   T9PinyinMap.candidates("54")            → ["ji", "li", "j", "k", "l"]
 */
object T9PinyinMap {

    /** 音节选项 */
    data class SyllableOption(
        val pinyin: String,
        val digitLength: Int
    )

    /** 数字→字母映射 */
    val digitToLetters: Map<Char, List<Char>> = mapOf(
        '2' to listOf('a', 'b', 'c'), '3' to listOf('d', 'e', 'f'),
        '4' to listOf('g', 'h', 'i'), '5' to listOf('j', 'k', 'l'),
        '6' to listOf('m', 'n', 'o'), '7' to listOf('p', 'q', 'r', 's'),
        '8' to listOf('t', 'u', 'v'), '9' to listOf('w', 'x', 'y', 'z')
    )

    private const val MAX_PINYIN_LEN = 6

    /**
     * 获取数字序列的首音节候选选项
     *
     * 从长到短搜索精确匹配的拼音，优先返回完整编码匹配。
     * 同时包含首键字母回退，允许用户选择单个声母。
     *
     * @param digits 数字序列，如 "54"
     * @param maxResults 最大返回数
     * @return 音节选项列表
     */
    fun firstSyllableOptions(digits: String, maxResults: Int = 12): List<SyllableOption> {
        if (digits.isEmpty()) return emptyList()

        val seen = mutableSetOf<String>()
        val result = mutableListOf<SyllableOption>()

        // 从长到短搜索精确匹配的拼音
        for (len in minOf(MAX_PINYIN_LEN, digits.length) downTo 1) {
            val code = digits.substring(0, len)
            val pinyins = codeToPinyins[code]
            if (pinyins != null) {
                for (p in pinyins) {
                    if (p in seen) continue
                    seen.add(p)
                    result.add(SyllableOption(p, len))
                    if (result.size >= maxResults) return result
                }
            }
        }

        // 首键字母回退
        if (result.size < maxResults) {
            val letters = digitToLetters[digits[0]] ?: return result
            for (l in letters) {
                val ch = l.toString()
                if (ch !in seen) {
                    seen.add(ch)
                    result.add(SyllableOption(ch, 1))
                    if (result.size >= maxResults) return result
                }
            }
        }

        return result
    }

    /**
     * 获取数字序列对应的拼音候选字符串列表。
     *
     * 这是 [firstSyllableOptions] 的便捷封装，供只需要显示文本、
     * 不需要知道消费位数的调用方使用。
     */
    fun candidates(digits: String, maxResults: Int = 12): List<String> {
        return firstSyllableOptions(digits, maxResults).map { it.pinyin }
    }

    /** pinyinToDigitCode 结果缓存（惰性填充，有效拼音结果才缓存） */
    private val pinyinCodeCache = mutableMapOf<String, String>()

    /**
     * 将拼音字符串转为九宫格数字编码。
     *
     * 例如 "ji" → "54"，"gua" → "482"。
     * 若包含无法映射的字符则返回 null。
     */
    fun pinyinToDigitCode(pinyin: String): String? {
        val key = pinyin.lowercase()
        pinyinCodeCache[key]?.let { return it }
        val code = buildString {
            for (ch in key) {
                val digit = LETTER_TO_DIGIT[ch] ?: return null
                append(digit)
            }
        }
        pinyinCodeCache[key] = code
        return code
    }

    /** 编码→拼音映射（精确匹配） */
    private val codeToPinyins: Map<String, List<String>> by lazy {
        PINYIN_LIST.groupBy { pinyin ->
            pinyin.map { LETTER_TO_DIGIT[it] ?: it }.joinToString("")
        }
    }

    /**
     * 贪婪分割：将数字序列分割为多个音节
     *
     * 每次从剩余序列中取最长匹配音节，直到无法继续。
     * 例如 "54482" → ["ji"(2), "gua"(3)] 或 ["ji"(2), "hua"(3)]
     * 分割结果用分隔符 ' 连接后发送给 RIME 引擎。
     *
     * TODO: 当前为贪婪策略。后续可引入基于词频/语言模型的最优切分（DP），
     *       作为后备任务计划跟踪。
     *
     * @param digits 数字序列
     * @return 音节选项列表，每个元素包含拼音和数字长度
     */
    fun greedySplit(digits: String): List<SyllableOption> {
        val result = mutableListOf<SyllableOption>()
        var remaining = digits
        while (remaining.isNotEmpty()) {
            val options = firstSyllableOptions(remaining, maxResults = 1)
            val best = options.firstOrNull() ?: break
            result.add(best)
            remaining = remaining.drop(best.digitLength)
        }
        return result
    }

    /**
     * 判断两个拼音的 T9 数字编码是否匹配（一个编码是另一个的前缀）。
     *
     * 九键中"g"和"ge"同属数字"4"+"3"="43"区，但"g"只映射"4"。
     * 这解决场景6中精确拼音比较"ge"=="g"失败的问题。
     */
    fun areDigitCodesMatching(a: String, b: String): Boolean {
        val codeA = pinyinToDigitCode(a) ?: return false
        val codeB = pinyinToDigitCode(b) ?: return false
        return codeA == codeB || codeA.startsWith(codeB) || codeB.startsWith(codeA)
    }

    /** 字母→数字映射 */
    private val LETTER_TO_DIGIT = mapOf(
        'a' to '2', 'b' to '2', 'c' to '2',
        'd' to '3', 'e' to '3', 'f' to '3',
        'g' to '4', 'h' to '4', 'i' to '4',
        'j' to '5', 'k' to '5', 'l' to '5',
        'm' to '6', 'n' to '6', 'o' to '6',
        'p' to '7', 'q' to '7', 'r' to '7', 's' to '7',
        't' to '8', 'u' to '8', 'v' to '8',
        'w' to '9', 'x' to '9', 'y' to '9', 'z' to '9'
    )

    /** 有效拼音列表 */
    private val PINYIN_LIST = setOf(
        "a", "ai", "an", "ang", "ao",
        "ba", "bai", "ban", "bang", "bao", "bei", "ben", "beng", "bi", "bian", "biao",
        "bie", "bin", "bing", "bo", "bu",
        "ca", "cai", "can", "cang", "cao", "ce", "cen", "ceng", "cha", "chai", "chan",
        "chang", "chao", "che", "chen", "cheng", "chi", "chong", "chou", "chu", "chua",
        "chuai", "chuan", "chuang", "chui", "chun", "chuo", "ci", "cong", "cou", "cu",
        "cuan", "cui", "cun", "cuo",
        "da", "dai", "dan", "dang", "dao", "de", "dei", "den", "deng", "di", "dia",
        "dian", "diao", "die", "ding", "diu", "dong", "dou", "du", "duan", "dui", "dun", "duo",
        "e", "ei", "en", "eng", "er",
        "fa", "fan", "fang", "fei", "fen", "feng", "fiao", "fo", "fou", "fu",
        "ga", "gai", "gan", "gang", "gao", "ge", "gei", "gen", "geng", "gong", "gou",
        "gu", "gua", "guai", "guan", "guang", "gui", "gun", "guo",
        "ha", "hai", "han", "hang", "hao", "he", "hei", "hen", "heng", "hong", "hou",
        "hu", "hua", "huai", "huan", "huang", "hui", "hun", "huo",
        "ji", "jia", "jian", "jiang", "jiao", "jie", "jin", "jing", "jiong", "jiu",
        "ju", "juan", "jue", "jun",
        "ka", "kai", "kan", "kang", "kao", "ke", "kei", "ken", "keng", "kong", "kou",
        "ku", "kua", "kuai", "kuan", "kuang", "kui", "kun", "kuo",
        "la", "lai", "lan", "lang", "lao", "le", "lei", "leng", "li", "lia", "lian",
        "liang", "liao", "lie", "lin", "ling", "liu", "lo", "long", "lou", "lu",
        "luan", "lve", "lun", "luo", "lv",
        "ma", "mai", "man", "mang", "mao", "me", "mei", "men", "meng", "mi", "mian",
        "miao", "mie", "min", "ming", "miu", "mo", "mou", "mu",
        "na", "nai", "nan", "nang", "nao", "ne", "nei", "nen", "neng", "ni", "nian",
        "niang", "niao", "nie", "nin", "ning", "niu", "nong", "nou", "nu", "nuan",
        "nve", "nun", "nuo", "nv",
        "o", "ou",
        "pa", "pai", "pan", "pang", "pao", "pei", "pen", "peng", "pi", "pian", "piao",
        "pie", "pin", "ping", "po", "pou", "pu",
        "qi", "qia", "qian", "qiang", "qiao", "qie", "qin", "qing", "qiong", "qiu",
        "qu", "quan", "que", "qun",
        "ran", "rang", "rao", "re", "ren", "reng", "ri", "rong", "rou", "ru", "rua",
        "ruan", "rui", "run", "ruo",
        "sa", "sai", "san", "sang", "sao", "se", "sen", "seng", "sha", "shai", "shan",
        "shang", "shao", "she", "shei", "shen", "sheng", "shi", "shou", "shu", "shua",
        "shuai", "shuan", "shuang", "shui", "shun", "shuo", "si", "song", "sou", "su",
        "suan", "sui", "sun", "suo",
        "ta", "tai", "tan", "tang", "tao", "te", "tei", "teng", "ti", "tian", "tiao",
        "tie", "ting", "tong", "tou", "tu", "tuan", "tui", "tun", "tuo",
        "wa", "wai", "wan", "wang", "wei", "wen", "weng", "wo", "wu",
        "xi", "xia", "xian", "xiang", "xiao", "xie", "xin", "xing", "xiong", "xiu",
        "xu", "xuan", "xue", "xun",
        "ya", "yan", "yang", "yao", "ye", "yi", "yin", "ying", "yo", "yong", "you",
        "yu", "yuan", "yue", "yun",
        "za", "zai", "zan", "zang", "zao", "ze", "zei", "zen", "zeng", "zha", "zhai",
        "zhan", "zhang", "zhao", "zhe", "zhei", "zhen", "zheng", "zhi", "zhong", "zhou",
        "zhu", "zhua", "zhuai", "zhuan", "zhuang", "zhui", "zhun", "zhuo", "zi", "zong",
        "zou", "zu", "zuan", "zui", "zun", "zuo"
    )
}
