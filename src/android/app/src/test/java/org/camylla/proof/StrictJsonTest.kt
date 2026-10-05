package org.camylla.proof

import org.junit.Assert.*
import org.junit.Test

class StrictJsonTest {
    @Test fun validMessagesAndEscapedNamesAreAccepted() {
        StrictJson.validate("{\"type\":\"start\",\"version\":3,\"modes\":[\"1280x720@30\"],\"nested\":{\"x\":true}}")
        StrictJson.validate("{\"name\":\"phone \\uD83D\\uDCF1\"}")
    }
    @Test fun duplicateDecodedFieldsAndLenientJsonAreRejected() {
        for (value in listOf("{\"type\":1,\"type\":2}", "{\"cap\":1,\"\\u0063ap\":2}",
            "{\"a\":1,}", "{\"a\":[1,]}", "{a:1}", "{\"a\":01}", "{\"a\":NaN}",
            "{\"a\":\"\\uD800\"}", "{}{}", "{\"a\":{\"b\":{\"c\":{\"d\":{}}}}}"))
            assertThrows("Rejected: $value", RuntimeException::class.java) { StrictJson.validate(value) }
    }
    @Test fun sizeIsBoundedBeforePlatformParsing() {
        assertThrows(IllegalArgumentException::class.java) { StrictJson.validate("{\"value\":\"${"x".repeat(4096)}\"}") }
    }
}
