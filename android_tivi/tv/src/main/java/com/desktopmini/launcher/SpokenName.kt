package com.desktopmini.launcher

/**
 * Đổi cách đọc tên kênh viết tắt: "hát tê vê chín" -> "htv9", "vê tê vê một" -> "vtv1",
 * "quảng ninh ba" -> "quangninh3". Đầu vào là các từ đã bỏ dấu, chữ thường.
 * Chữ cái chỉ được ghép khi có từ 2 chữ cái đọc liền nhau trở lên, nên "cà mau", "việt nam" không bị đổi.
 * Giống bản Windows (SpokenName trong VoiceText.cs).
 */
object SpokenName {
    // tên chữ cái tiếng Việt (đã bỏ dấu). Cố ý không có "mo", "lo", "ro", "so" vì trùng "mở", "số"...
    private val LETTERS = mapOf(
        "a" to 'a', "be" to 'b', "xe" to 'c', "de" to 'd', "e" to 'e', "ep" to 'f', "ef" to 'f',
        "ge" to 'g', "gie" to 'g', "hat" to 'h', "i" to 'i', "ca" to 'k', "ka" to 'k', "el" to 'l',
        "em" to 'm', "en" to 'n', "no" to 'n', "o" to 'o', "pe" to 'p', "cu" to 'q', "quy" to 'q',
        "qui" to 'q', "er" to 'r', "es" to 's', "et" to 's', "te" to 't', "ti" to 't', "u" to 'u',
        "ve" to 'v', "vi" to 'v', "y" to 'y', "ich" to 'x', "det" to 'z', "zet" to 'z'
    )
    // chữ cái đọc bằng hai từ
    private val BIGRAMS = mapOf("ve kep" to 'w', "ich xi" to 'x', "i dai" to 'y')

    private val UNITS = mapOf("khong" to 0, "mot" to 1, "hai" to 2, "ba" to 3, "bon" to 4,
        "sau" to 6, "bay" to 7, "tam" to 8, "chin" to 9)
    // hàng chục: "hai mươi", "năm mươi"...
    private val TENS = mapOf("hai" to 2, "ba" to 3, "bon" to 4, "nam" to 5, "sau" to 6, "bay" to 7, "tam" to 8, "chin" to 9)
    // hàng đơn vị sau "mười/mươi"
    private val AFTER_TENS = mapOf("mot" to 1, "hai" to 2, "ba" to 3, "bon" to 4, "tu" to 4, "nam" to 5, "lam" to 5,
        "sau" to 6, "bay" to 7, "tam" to 8, "chin" to 9)

    // mã kênh viết liền như "htv", "vtv", "antv", "qpvn": toàn phụ âm (có thể mở đầu bằng "a")
    private val CODE_LIKE = Regex("^a?[bcdfghjklmnpqrstvwxz]{2,5}$")

    fun convert(words: List<String>): List<String> {
        // 1) gộp các chữ cái đọc liền nhau thành một mã
        val toks = ArrayList<String>()
        val codeRun = ArrayList<Boolean>()
        var i = 0
        while (i < words.size) {
            val sb = StringBuilder()
            var j = i
            while (j < words.size) {
                val bc = if (j + 1 < words.size) BIGRAMS[words[j] + " " + words[j + 1]] else null
                val c = LETTERS[words[j]]
                if (bc != null) { sb.append(bc); j += 2 }
                else if (c != null) { sb.append(c); j++ }
                else break
            }
            if (sb.length >= 2) { toks.add(sb.toString()); codeRun.add(true); i = j }
            else { toks.add(words[i]); codeRun.add(false); i++ }
        }

        // 2) đổi số đọc bằng chữ ("chín" -> 9, "hai mươi mốt" -> 21)
        val res = ArrayList<String>()
        var prevCode = false
        var k = 0
        while (k < toks.size) {
            if (!codeRun[k]) {
                val n = tryNumber(toks, k, prevCode)
                if (n != null) {
                    res.add(n.first.toString())
                    prevCode = false
                    k += n.second
                    continue
                }
            }
            res.add(toks[k])
            prevCode = codeRun[k] || CODE_LIKE.matches(toks[k])
            k++
        }
        return res
    }

    /** (giá trị, số từ đã dùng) hoặc null nếu không phải số. */
    private fun tryNumber(t: List<String>, k: Int, prevCode: Boolean): Pair<Int, Int>? {
        val a = t[k]
        if (a == "muoi") {
            val u = if (k + 1 < t.size) AFTER_TENS[t[k + 1]] else null
            return if (u != null) Pair(10 + u, 2) else Pair(10, 1)
        }
        val tens = if (k + 1 < t.size && t[k + 1] == "muoi") TENS[a] else null
        if (tens != null) {
            val u = if (k + 2 < t.size) AFTER_TENS[t[k + 2]] else null
            return if (u != null) Pair(tens * 10 + u, 3) else Pair(tens * 10, 2)
        }
        UNITS[a]?.let { return Pair(it, 1) }
        // "năm" trùng "nam" (Việt Nam): chỉ là số 5 khi đứng ngay sau mã kênh, ví dụ "vtv năm"
        if (a == "nam" && prevCode) return Pair(5, 1)
        return null
    }
}
