package com.bilibili.pegasus.components.windowpane

import androidx.window.core.layout.WindowSizeClass

class WindowPaneStyleComponent {
    var layoutSize: WindowSizeClass? = null
        private set
    var refreshCount: Int = 0
        private set
    private var refreshSize: WindowSizeClass? = null

    fun f(size: WindowSizeClass, allowRefresh: Boolean) {
        val previous = refreshSize
        if (previous == null || allowRefresh) {
            if (previous != null && previous != size &&
                (previous == WindowSizeClass.NORMAL || size == WindowSizeClass.NORMAL)
            ) {
                refreshCount++
            }
            refreshSize = size
        }
        layoutSize = size
    }
}
