package com.wechatdigest.ui

import org.junit.jupiter.api.Test
import kotlin.test.assertNotNull

class TrayIconHelperTest {

    @Test
    fun `createTrayIconPainter produces valid painter`() {
        System.setProperty("java.awt.headless", "true")
        val painter = TrayIconHelper.createTrayIconPainter()
        assertNotNull(painter)
    }
}
