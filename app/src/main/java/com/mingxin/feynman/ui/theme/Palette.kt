package com.mingxin.feynman.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/* ------------------------------------------------------------------ */
/*  One palette, four named colors; every tint is derived from them.   */
/* ------------------------------------------------------------------ */

val Paper = Color(0xFFFAF8F3)
val Ink = Color(0xFF20362E)
val Jade = Color(0xFF3B7965)
val JadeDeep = Color(0xFF2F5D4D)
val JadeBright = Color(0xFF4A8B74)
val Gold = Color(0xFF9E793E)
val Vermilion = Color(0xFFB4432F)
val JadeGrad = listOf(JadeBright, JadeDeep)

val CardRadius = 20.dp
val CardShape = RoundedCornerShape(CardRadius)
val BtnShape = RoundedCornerShape(50)

val Ink70 = Ink.copy(alpha = 0.70f)
val GoldText = Color(0xFF84652F)
val Ink12 = Ink.copy(alpha = 0.12f)
val Ink8 = Ink.copy(alpha = 0.08f)
val Jade10 = Jade.copy(alpha = 0.10f)
val Gold12 = Gold.copy(alpha = 0.12f)
val Vermilion12 = Vermilion.copy(alpha = 0.12f)
