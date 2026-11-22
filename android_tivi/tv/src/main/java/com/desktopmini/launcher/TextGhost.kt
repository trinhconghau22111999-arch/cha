package com.desktopmini.launcher

/**
 * Chữ gõ từ điện thoại lúc KHÔNG có ô nhập nào đang chọn bị bỏ. Nhưng bàn phím điện thoại vẫn nhớ "từ đang gõ" (Telex),
 * nên khi ô nhập được chọn và gõ tiếp, nó gửi lệnh "xoá n ký tự + chèn lại cả từ" -> chữ cũ bị dồn vào ô.
 * Ở đây nhớ phần chữ đã bị bỏ ([ghost]); khi lệnh tiếp theo thay đúng từ đó thì chỉ chèn phần mới, không chèn lại chữ cũ.
 */
object TextGhost {
    private var ghost: String? = null

    /** Gọi khi lệnh gõ bị bỏ vì không có ô nhập. */
    @Synchronized fun dropped(ins: String) { ghost = if (ins.isNotEmpty()) ins else null }

    /** Gọi khi có ô nhập để áp dụng. Trả về (số ký tự cần xoá, chữ cần chèn) đã loại phần chữ cũ bị bỏ. */
    @Synchronized fun applied(del: Int, ins: String): Pair<Int, String> {
        val g = ghost; ghost = null
        if (g != null && del == g.length) {
            var p = 0
            while (p < g.length && p < ins.length && g[p] == ins[p]) p++
            return 0 to ins.substring(p)
        }
        return del to ins
    }

    @Synchronized fun clear() { ghost = null }
}
