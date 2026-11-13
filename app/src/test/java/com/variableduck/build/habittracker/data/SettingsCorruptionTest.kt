package com.variableduck.build.habittracker.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.variableduck.build.habittracker.data.settings.SettingsRepository
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** A damaged settings file must not send the app back to onboarding or break every settings change. */
class SettingsCorruptionTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `a corrupt settings file keeps onboarding done and accepts changes again`() = runTest {
        val file = File(tmp.newFolder(), "settings.preferences_pb").apply { writeText("not a protobuf") }
        val settings = SettingsRepository(
            PreferenceDataStoreFactory.create(corruptionHandler = SettingsRepository.corruptionHandler, produceFile = { file }),
        )

        assertTrue(settings.current().onboardingComplete)
        settings.setOverallGoal("Still works")
        assertEquals("Still works", settings.current().overallGoal)
    }
}
