package dev.shizzi

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertFalse as notTrue
import org.junit.Test

class RouterPinVerifierTest {
    @Test fun derivedKeyIsDeterministicAndPinSensitive() {
        val salt = byteArrayOf(2, 4, 6, 8, 10, 12, 14, 16)
        val a = RouterPinVerifier.derive("654321", salt, 1000)
        val b = RouterPinVerifier.derive("654321", salt, 1000)
        val c = RouterPinVerifier.derive("654320", salt, 1000)
        assertArrayEquals(a, b)
        notTrue(a.contentEquals(c))
    }

    @Test fun refusesMalformedAndWrongPins() {
        assertFalse(RouterPinVerifier.matches(""))
        assertFalse(RouterPinVerifier.matches("a1b2c3"))
        assertFalse(RouterPinVerifier.matches("1111111"))
        assertFalse(RouterPinVerifier.matches("000000"))
    }
}
