package com.nuvio.tv.data.anilist.mobiletest

// Preserve kotlin.test argument ordering in the Mobile fixtures without adding a dependency.
fun <T> assertEquals(expected: T, actual: T, message: String? = null) = org.junit.Assert.assertEquals(message, expected, actual)
fun assertNull(value: Any?, message: String? = null) = org.junit.Assert.assertNull(message, value)
fun assertTrue(value: Boolean, message: String? = null) = org.junit.Assert.assertTrue(message, value)
