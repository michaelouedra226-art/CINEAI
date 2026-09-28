package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.SceneItem
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("Agnes Studio", appName)
  }

  @Test
  fun testSceneSerialization() {
    val scenes = listOf(
      SceneItem(
        number = 1,
        title = "Intro",
        description = "Plan séquence",
        image_prompt = "Cyberpunk street",
        video_prompt = "Travelling avant",
        camera_movement = "Travelling",
        status = "done"
      )
    )
    val json = SceneItem.serializeList(scenes)
    val parsed = SceneItem.parseList(json)
    assertEquals(1, parsed.size)
    assertEquals("Intro", parsed[0].title)
    assertEquals("done", parsed[0].status)
  }
}
