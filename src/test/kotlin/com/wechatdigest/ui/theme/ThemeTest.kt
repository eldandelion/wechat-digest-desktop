package com.wechatdigest.ui.theme

import com.wechatdigest.core.model.EventPriority
import org.junit.jupiter.api.Test
import kotlin.test.assertNotNull

class ThemeTest {

    @Test
    fun `priority colors are correctly defined for all priorities in light and dark mode`() {
        for (priority in EventPriority.entries) {
            val lightColor = PriorityColors.getColor(priority, isDark = false)
            val darkColor = PriorityColors.getColor(priority, isDark = true)
            val lightContainer = PriorityColors.getContainerColor(priority, isDark = false)
            val darkContainer = PriorityColors.getContainerColor(priority, isDark = true)

            assertNotNull(lightColor)
            assertNotNull(darkColor)
            assertNotNull(lightContainer)
            assertNotNull(darkContainer)
        }
    }
}
