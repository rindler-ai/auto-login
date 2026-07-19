package ai.rindler.autologin.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

// Redesign §1.2 — ONE family (FontFamily.Default), weights 400/500 only (nothing
// ≥600). Hierarchy comes from size + ink-step, never boldness. Positive-tracked
// caps vs tight-large is the load-bearing premium tell. Max 5 roles per screen.

private val Sans = FontFamily.Default

val AppType = Typography(
    displaySmall = TextStyle(
        fontFamily = Sans, fontWeight = FontWeight.Normal,
        fontSize = 30.sp, lineHeight = 36.sp, letterSpacing = (-0.02).em,
    ),
    // Onboarding / Pair hero headlines only.
    headlineMedium = TextStyle(
        fontFamily = Sans, fontWeight = FontWeight.Normal,
        fontSize = 24.sp, lineHeight = 30.sp, letterSpacing = (-0.02).em,
    ),
    headlineSmall = TextStyle(
        fontFamily = Sans, fontWeight = FontWeight.Medium,
        fontSize = 20.sp, lineHeight = 26.sp, letterSpacing = (-0.01).em,
    ),
    // Top-app-bar titles only.
    titleLarge = TextStyle(
        fontFamily = Sans, fontWeight = FontWeight.Medium,
        fontSize = 22.sp, lineHeight = 28.sp, letterSpacing = (-0.01).em,
    ),
    // Account email, row titles needing weight.
    titleMedium = TextStyle(
        fontFamily = Sans, fontWeight = FontWeight.Medium,
        fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.sp,
    ),
    // Row primary text.
    bodyLarge = TextStyle(
        fontFamily = Sans, fontWeight = FontWeight.Normal,
        fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.sp,
    ),
    // Supporting text, body copy (color = onSurfaceVariant applied at usage).
    bodyMedium = TextStyle(
        fontFamily = Sans, fontWeight = FontWeight.Normal,
        fontSize = 14.sp, lineHeight = 21.sp, letterSpacing = 0.sp,
    ),
    // Buttons.
    labelLarge = TextStyle(
        fontFamily = Sans, fontWeight = FontWeight.Medium,
        fontSize = 15.sp, lineHeight = 18.sp, letterSpacing = 0.sp,
    ),
    // Section headers: ALL CAPS (applied at usage) + 0.08em, the only taxonomy marker.
    labelMedium = TextStyle(
        fontFamily = Sans, fontWeight = FontWeight.Medium,
        fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.08.em,
    ),
    labelSmall = TextStyle(
        fontFamily = Sans, fontWeight = FontWeight.Medium,
        fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.06.em,
    ),
)

// Monospace (tabular), KEPT per §1.2 — the ManualCode 2FA-code field and the
// Advanced pairing-code field render input in Mono (the "monospace for IDs" tell).
val Mono = FontFamily.Monospace
