package com.kakario.videoplayer.ai

/**
 * Qwen3로 자막 줄들을 한국어로 번역 (폰 안에서만).
 * 여러 줄을 번호 붙여 한 번에 번역하고, 결과 형식이 이상하면 한 줄씩 다시 번역한다.
 * 무슨 일이 있었는지 info에 남겨 화면에서 확인할 수 있게 한다.
 */
object AiTranslator {
    data class Result(val ko: List<String?>, val info: String)

    private const val PREFILL = "<think>\n\n</think>\n\n" // Qwen3 생각 모드 끄기 (바로 답)

    fun langName(code: String?) = when (code) {
        "ja" -> "일본어"
        "zh" -> "중국어"
        "en" -> "영어"
        else -> "외국어"
    }

    fun translate(handle: Long, lines: List<String>, context: List<String>, lang: String?): Result {
        if (lines.isEmpty()) return Result(emptyList(), "번역할 줄 없음")
        val sys = "너는 영상 자막 전문 번역가야. 번호가 붙은 각 줄을 자연스러운 한국어 구어체 자막으로 번역해. " +
            "번호와 줄 수를 그대로 지키고, '1. 번역문' 형식으로 번역한 줄만 출력해. 설명이나 원문은 쓰지 마."
        val user = buildString {
            if (context.isNotEmpty()) {
                append("앞 대사 (참고만 하고 번역하지 마):\n")
                context.forEach { append(it).append('\n') }
                append('\n')
            }
            append("번역할 ${langName(lang)} 대사:\n")
            lines.forEachIndexed { i, l -> append(i + 1).append(". ").append(l).append('\n') }
            append("/no_think")
        }
        val maxTokens = (lines.sumOf { it.length } * 3 + 64).coerceIn(128, 1000)
        val raw = LlamaNative.generate(handle, sys.toByteArray(), user.toByteArray(), PREFILL.toByteArray(), maxTokens)
            ?: return Result(List(lines.size) { null }, "번역 엔진이 답을 못 냈어요 (엔진 오류)")
        val text = clean(String(raw, Charsets.UTF_8))
        val parsed = parseNumbered(text, lines.size)
        if (parsed.count { it != null } >= (lines.size + 1) / 2) {
            // 절반 이상 맞으면 빠진 줄만 한 줄씩 다시
            val fixed = parsed.mapIndexed { i, v -> v ?: translateOne(handle, lines[i], lang) }
            return Result(fixed, "정상")
        }
        // 번호 없이 줄 수만 맞게 나온 경우
        val plain = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (plain.size == lines.size) return Result(plain, "정상 (번호 없음)")
        // 형식이 이상하면 한 줄씩
        val one = lines.map { translateOne(handle, it, lang) }
        val head = text.replace('\n', ' ').take(40)
        return Result(one, if (one.any { it != null }) "한 줄씩 번역함 (묶음 응답 형식 이상: \"$head\")" else "번역 실패 (응답: \"$head\")")
    }

    fun translateOne(handle: Long, line: String, lang: String?): String? {
        val sys = "너는 영상 자막 번역가야. 주어진 ${langName(lang)} 문장을 자연스러운 한국어 구어체로 번역해. 번역문 한 줄만 출력해."
        val raw = LlamaNative.generate(
            handle, sys.toByteArray(), "$line\n/no_think".toByteArray(), PREFILL.toByteArray(),
            (line.length * 3 + 48).coerceIn(64, 400),
        ) ?: return null
        return clean(String(raw, Charsets.UTF_8)).lines().map { it.trim() }.firstOrNull { it.isNotEmpty() }
            ?.replace(Regex("""^\s*\d+\s*[.)：:]\s*"""), "")
    }

    private fun clean(s: String): String =
        s.replace(Regex("(?s)<think>.*?</think>"), "")
            .replace("<think>", "").replace("</think>", "")
            .replace("<|im_end|>", "")
            .trim()

    private fun parseNumbered(text: String, n: Int): List<String?> {
        val map = HashMap<Int, String>()
        val re = Regex("""^\s*\(?(\d+)\s*[.)：:．、]\s*(.+)$""")
        text.lines().forEach { l ->
            re.find(l)?.let { m ->
                val k = m.groupValues[1].toIntOrNull()
                val v = m.groupValues[2].trim()
                if (k != null && v.isNotEmpty() && k !in map) map[k] = v
            }
        }
        return List(n) { map[it + 1] }
    }
}
