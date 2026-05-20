package com.mica.music.ui.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val SansFamily = FontFamily.Default
private val MonoFamily = FontFamily.Monospace

data class HifiTypography(
    val display: TextStyle = TextStyle(
        fontFamily = SansFamily, fontWeight = FontWeight.Bold,
        fontSize = 28.sp, lineHeight = 36.sp
    ),
    val titleLg: TextStyle = TextStyle(
        fontFamily = SansFamily, fontWeight = FontWeight.Bold,
        fontSize = 24.sp, lineHeight = 32.sp
    ),
    val titleMd: TextStyle = TextStyle(
        fontFamily = SansFamily, fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp, lineHeight = 26.sp
    ),
    val titleSm: TextStyle = TextStyle(
        fontFamily = SansFamily, fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp, lineHeight = 24.sp
    ),
    val bodyLg: TextStyle = TextStyle(
        fontFamily = SansFamily, fontWeight = FontWeight.Medium,
        fontSize = 16.sp, lineHeight = 24.sp
    ),
    val bodyMd: TextStyle = TextStyle(
        fontFamily = SansFamily, fontWeight = FontWeight.Normal,
        fontSize = 14.sp, lineHeight = 20.sp
    ),
    val bodySm: TextStyle = TextStyle(
        fontFamily = SansFamily, fontWeight = FontWeight.Normal,
        fontSize = 13.sp, lineHeight = 18.sp
    ),
    val caption: TextStyle = TextStyle(
        fontFamily = SansFamily, fontWeight = FontWeight.Normal,
        fontSize = 12.sp, lineHeight = 16.sp
    ),
    val monoMd: TextStyle = TextStyle(
        fontFamily = MonoFamily, fontWeight = FontWeight.Normal,
        fontSize = 12.sp, lineHeight = 16.sp
    ),
    val monoSm: TextStyle = TextStyle(
        fontFamily = MonoFamily, fontWeight = FontWeight.Normal,
        fontSize = 11.sp, lineHeight = 14.sp
    ),
    val lyricCurrent: TextStyle = TextStyle(
        fontFamily = SansFamily, fontWeight = FontWeight.Bold,
        fontSize = 22.sp, lineHeight = 32.sp
    ),
    val lyricOther: TextStyle = TextStyle(
        fontFamily = SansFamily, fontWeight = FontWeight.Normal,
        fontSize = 16.sp, lineHeight = 24.sp
    ),
)
