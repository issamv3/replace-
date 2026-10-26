package com.example

import org.junit.Assert.*
import org.junit.Test

class ExampleUnitTest {
  @Test
  fun addition_isCorrect() {
    assertEquals(4, 2 + 2)
  }

  @Test
  fun dateDetector_containsDateRegexWorks() {
    val text = "Event occurred at 2026-10-23 09:45 successfully."
    assertTrue(com.example.service.DateDetector.containsDate(text))
    assertFalse(com.example.service.DateDetector.containsDate("No timestamps here"))
  }
}
