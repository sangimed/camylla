package org.camylla.proof

/** Bounded strict JSON grammar validation before Android JSONObject, which otherwise accepts duplicates. */
object StrictJson {
    fun validate(text: String) {
        require(text.toByteArray(Charsets.UTF_8).size in 1..4096)
        Parser(text).parse()
    }
    private class Parser(val input: String) {
        var index = 0
        fun whitespace() { while (index < input.length && input[index] in " \t\r\n") index++ }
        fun take(c: Char) { whitespace(); require(index < input.length && input[index++] == c) { "Invalid JSON" } }
        fun parse() { whitespace(); require(input.getOrNull(index) == '{'); value(0); whitespace(); require(index == input.length) }
        fun value(depth: Int) {
            whitespace(); require(depth < 4 || input.getOrNull(index) !in listOf('{', '[')) { "JSON nesting too deep" }
            when (input.getOrNull(index)) {
                '{' -> {
                    index++; whitespace(); val names = hashSetOf<String>()
                    if (input.getOrNull(index) != '}') while (true) {
                        val name = string(); require(names.add(name)) { "Duplicate JSON field" }
                        take(':'); value(depth + 1); whitespace()
                        if (input.getOrNull(index) != ',') break
                        index++
                    }
                    take('}')
                }
                '[' -> {
                    index++; whitespace()
                    if (input.getOrNull(index) != ']') while (true) {
                        value(depth + 1); whitespace()
                        if (input.getOrNull(index) != ',') break
                        index++
                    }
                    take(']')
                }
                '"' -> string()
                't' -> literal("true")
                'f' -> literal("false")
                'n' -> literal("null")
                else -> {
                    val start = index
                    while (index < input.length && input[index] in "-+0123456789.eE") index++
                    require(input.substring(start, index).matches(Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?"))) { "Invalid JSON number" }
                }
            }
        }
        fun literal(text: String) { require(input.startsWith(text, index)); index += text.length }
        fun string(): String {
            take('"'); val result = StringBuilder()
            while (true) {
                require(index < input.length) { "Unterminated JSON string" }
                val c = input[index++]
                if (c == '"') break
                require(c.code >= 32) { "Invalid JSON string" }
                if (c != '\\') result.append(c)
                else {
                    require(index < input.length)
                    when (val escape = input[index++]) {
                        '"', '\\', '/' -> result.append(escape)
                        'b' -> result.append('\b')
                        'f' -> result.append('\u000c')
                        'n' -> result.append('\n')
                        'r' -> result.append('\r')
                        't' -> result.append('\t')
                        'u' -> {
                            require(index + 4 <= input.length)
                            result.append(input.substring(index, index + 4).toInt(16).toChar()); index += 4
                        }
                        else -> error("Invalid JSON escape")
                    }
                }
            }
            var i = 0
            while (i < result.length) {
                if (result[i].isHighSurrogate()) { require(i + 1 < result.length && result[i + 1].isLowSurrogate()); i += 2 }
                else { require(!result[i].isLowSurrogate()); i++ }
            }
            return result.toString()
        }
    }
}
