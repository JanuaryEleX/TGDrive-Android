package com.yg.tgdrive.ui.nav

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Guard against system Back finishing MainActivity while the settings route is visible. */
class SettingsBackNavigationTest {
    @Test fun settingsRouteInterceptsSystemBackAndReturnsHome() {
        val source = File("src/main/java/com/yg/tdrive/ui/nav/AppRoot.kt").readText()
        assertTrue(
            "Settings route must register BackHandler targeting HOME",
            Regex("Routes\\.SETTINGS\\s*->\\s*\\{\\s*BackHandler\\s*\\{\\s*route\\s*=\\s*Routes\\.HOME\\s*}")
                .containsMatchIn(source),
        )
    }
}
