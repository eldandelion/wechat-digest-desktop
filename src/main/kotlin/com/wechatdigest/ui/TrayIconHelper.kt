package com.wechatdigest.ui

import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toComposeImageBitmap
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage

object TrayIconHelper {
    fun createTrayIconPainter(): Painter {
        val size = 32
        val image = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.color = Color(0, 106, 96) // Primary teal
            g.fillRoundRect(2, 2, 28, 28, 8, 8)
            g.color = Color.WHITE
            g.font = Font("SansSerif", Font.BOLD, 18)
            g.drawString("W", 8, 23)
        } finally {
            g.dispose()
        }
        return BitmapPainter(image.toComposeImageBitmap())
    }
}
