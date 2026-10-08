package io.github.psd2live.ui.views

import androidx.compose.runtime.staticCompositionLocalOf

/** The AWT window a composable draws in: the preview asks whether it can sample textures shared with it. */
val LocalAwtWindow = staticCompositionLocalOf<java.awt.Window?> { null }
