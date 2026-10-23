package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.ReplacementRule
import com.example.service.ColorSampler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExampleRobolectricTest {

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("TextShift", appName)
  }

  @Test
  fun `replacement rule default values`() {
    val rule = ReplacementRule(
      originalText = "$100",
      replacementText = "$10,000"
    )
    assertEquals("$100", rule.originalText)
    assertEquals("$10,000", rule.replacementText)
    assertTrue(rule.isEnabled)
    assertEquals(ReplacementRule.MATCH_EXACT, rule.matchMode)
  }

  @Test
  fun `color luminance calculation`() {
    val whiteLum = ColorSampler.calculateLuminance(android.graphics.Color.WHITE)
    val blackLum = ColorSampler.calculateLuminance(android.graphics.Color.BLACK)
    assertTrue(whiteLum > 0.95)
    assertTrue(blackLum < 0.05)
  }
}
