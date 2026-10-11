package org.camylla.proof

object PairingCode {
    fun normalize(value: String): String {
        val code = value.filterNot(Char::isWhitespace)
        require(code.length == 8 && code.all { it in '0'..'9' }) { "Enter the 8-digit pairing code" }
        return code
    }
}
