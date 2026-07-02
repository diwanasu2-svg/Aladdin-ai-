package com.aladdin.vision.accessibility

import android.graphics.Rect

data class ScreenContent(
    val packageName: String,
    val windowTitle: String,
    val elements: List<UIElement>
) {
    val allText: String
        get() = elements
            .filter { it.text.isNotBlank() || it.contentDescription.isNotBlank() }
            .joinToString("\n") {
                (it.text.takeIf(String::isNotBlank) ?: it.contentDescription).trim()
            }

    val clickableElements: List<UIElement>
        get() = elements.filter { it.isClickable }

    val editableElements: List<UIElement>
        get() = elements.filter { it.isEditable }

    fun findByText(text: String, ignoreCase: Boolean = true): List<UIElement> =
        elements.filter { el ->
            el.text.contains(text, ignoreCase) ||
                    el.contentDescription.contains(text, ignoreCase)
        }

    fun findByClassName(className: String): List<UIElement> =
        elements.filter { it.className.contains(className, ignoreCase = true) }

    fun findById(resourceId: String): UIElement? =
        elements.find { it.resourceId == resourceId }
}

data class UIElement(
    val text: String,
    val contentDescription: String,
    val className: String,
    val resourceId: String,
    val bounds: Rect,
    val depth: Int,
    val isClickable: Boolean,
    val isEditable: Boolean,
    val isEnabled: Boolean,
    val isFocused: Boolean,
    val isScrollable: Boolean,
    val isSelected: Boolean,
    val isChecked: Boolean
) {
    val centerX: Int get() = bounds.centerX()
    val centerY: Int get() = bounds.centerY()
    val displayText: String get() = text.takeIf(String::isNotBlank) ?: contentDescription
}
