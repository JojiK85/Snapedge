package com.snapedge.app.model

import android.graphics.Rect

enum class EdgeSide {
    LEFT, RIGHT
}

data class EdgeConfig(
    val enabled: Boolean = false,
    val side: EdgeSide = EdgeSide.RIGHT,
    val verticalPercent: Int = 35,
    val opacityPercent: Int = 65
)

data class SavedClip(
    val id: String,
    val text: String,
    val source: String,
    val timestampMillis: Long
)

enum class SmartEntityType {
    OTP, MATH, URL, PHONE, EMAIL, QR_CODE
}

data class SmartEntity(
    val type: SmartEntityType,
    val label: String,
    val value: String
)

data class SmartAnalysisResult(
    val extractedText: String,
    val qrPayloads: List<String>,
    val entities: List<SmartEntity>,
    val sensitiveRects: List<Rect>
)
