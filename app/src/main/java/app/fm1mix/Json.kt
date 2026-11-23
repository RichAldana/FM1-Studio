package app.fm1mix

/**
 * A small JSON reader (RFC 8259) for the firmware lists: objects as maps, arrays as lists, numbers as doubles.
 * No Android in here (org.json is only a stub on a computer, so the catalogue could not be tested with it).
 */
object Json {
    class Bad(msg: String) : Exception(msg)

    fun parse(s: String): Any? {
        val p = P(s)
        p.ws()
        val v = p.value()
        p.ws()
        if (p.i != s.length) throw Bad("text after the value at ${p.i}")
        return v
    }

    private class P(val s: String) {
        var i = 0

        fun ws() { while (i < s.length && (s[i] == ' ' || s[i] == '\n' || s[i] == '\r' || s[i] == '\t')) i++ }

        fun value(): Any? {
            if (i >= s.length) throw Bad("end of text")
            return when (s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> word("true", true)
                'f' -> word("false", false)
                'n' -> word("null", null)
                else -> num()
            }
        }

        fun word(w: String, v: Any?): Any? {
            if (!s.startsWith(w, i)) throw Bad("unexpected at $i")
            i += w.length
            return v
        }

        fun obj(): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>()
            i++
            ws()
            if (i < s.length && s[i] == '}') { i++; return m }
            while (true) {
                ws()
                if (i >= s.length || s[i] != '"') throw Bad("a key expected at $i")
                val k = str()
                ws()
                if (i >= s.length || s[i] != ':') throw Bad("':' expected at $i")
                i++
                ws()
                m[k] = value()
                ws()
                if (i >= s.length) throw Bad("end of text in an object")
                if (s[i] == ',') { i++; continue }
                if (s[i] == '}') { i++; return m }
                throw Bad("',' or '}' expected at $i")
            }
        }

        fun arr(): List<Any?> {
            val a = ArrayList<Any?>()
            i++
            ws()
            if (i < s.length && s[i] == ']') { i++; return a }
            while (true) {
                ws()
                a.add(value())
                ws()
                if (i >= s.length) throw Bad("end of text in an array")
                if (s[i] == ',') { i++; continue }
                if (s[i] == ']') { i++; return a }
                throw Bad("',' or ']' expected at $i")
            }
        }

        fun str(): String {
            val b = StringBuilder()
            i++
            while (true) {
                if (i >= s.length) throw Bad("end of text in a string")
                val c = s[i++]
                when (c) {
                    '"' -> return b.toString()
                    '\\' -> {
                        if (i >= s.length) throw Bad("end of text in an escape")
                        when (val e = s[i++]) {
                            '"' -> b.append('"'); '\\' -> b.append('\\'); '/' -> b.append('/')
                            'b' -> b.append('\b'); 'f' -> b.append('\u000C'); 'n' -> b.append('\n')
                            'r' -> b.append('\r'); 't' -> b.append('\t')
                            'u' -> {
                                if (i + 4 > s.length) throw Bad("a short \\u escape")
                                b.append(s.substring(i, i + 4).toInt(16).toChar())
                                i += 4
                            }
                            else -> throw Bad("escape \\$e")
                        }
                    }
                    else -> b.append(c)
                }
            }
        }

        fun num(): Double {
            val st = i
            if (i < s.length && s[i] == '-') i++
            while (i < s.length && (s[i].isDigit() || s[i] == '.' || s[i] == 'e' || s[i] == 'E' || s[i] == '+' || s[i] == '-')) i++
            if (i == st) throw Bad("unexpected '${s[i]}' at $i")
            return s.substring(st, i).toDoubleOrNull() ?: throw Bad("a bad number at $st")
        }
    }

    // reading helpers
    @Suppress("UNCHECKED_CAST")
    fun obj(v: Any?): Map<String, Any?>? = v as? Map<String, Any?>
    @Suppress("UNCHECKED_CAST")
    fun arr(v: Any?): List<Any?>? = v as? List<Any?>
    fun str(m: Map<String, Any?>, k: String): String? = (m[k] as? String)?.takeIf { it.isNotEmpty() }
    fun num(m: Map<String, Any?>, k: String): Double? = m[k] as? Double
    fun bool(m: Map<String, Any?>, k: String): Boolean = m[k] == true

    /** a JSON string literal */
    fun quote(s: String): String {
        val b = StringBuilder("\"")
        for (c in s) when {
            c == '"' -> b.append("\\\""); c == '\\' -> b.append("\\\\"); c == '\n' -> b.append("\\n")
            c == '\r' -> b.append("\\r"); c == '\t' -> b.append("\\t")
            c < ' ' -> b.append("\\u%04x".format(c.code))
            else -> b.append(c)
        }
        return b.append('"').toString()
    }
}
