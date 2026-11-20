package com.mrtt.client.ui.design

import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.dp

@Immutable
object MrttShapes {
    val Small = RoundedCornerShape(18.dp)
    val Control = RoundedCornerShape(percent = 50)
    val Card = RoundedCornerShape(28.dp)
    val LargeCard = RoundedCornerShape(28.dp)
    val Dialog = RoundedCornerShape(28.dp)
    val Pill = RoundedCornerShape(percent = 50)
}

val MrttMaterialShapes = Shapes(
    extraSmall = MrttShapes.Small,
    small = MrttShapes.Small,
    medium = MrttShapes.Control,
    large = MrttShapes.Card,
    extraLarge = MrttShapes.Dialog,
)

@Immutable
data class MrttGeometry(
    val card: CornerBasedShape,
    val control: CornerBasedShape,
    val dialog: CornerBasedShape,
    val small: CornerBasedShape,
)

val MrttDefaultGeometry = MrttGeometry(
    card = MrttShapes.Card,
    control = MrttShapes.Control,
    dialog = MrttShapes.Dialog,
    small = MrttShapes.Small,
)

val MrttSoftGeometry = MrttGeometry(
    card = RoundedCornerShape(34.dp),
    control = RoundedCornerShape(percent = 50),
    dialog = RoundedCornerShape(36.dp),
    small = RoundedCornerShape(22.dp),
)

val MrttSharpGeometry = MrttGeometry(
    card = RoundedCornerShape(10.dp),
    control = RoundedCornerShape(10.dp),
    dialog = RoundedCornerShape(12.dp),
    small = RoundedCornerShape(8.dp),
)

val MrttBubbleGeometry = MrttGeometry(
    card = RoundedCornerShape(40.dp),
    control = RoundedCornerShape(percent = 50),
    dialog = RoundedCornerShape(42.dp),
    small = RoundedCornerShape(26.dp),
)

val MrttCrispGeometry = MrttGeometry(
    card = RoundedCornerShape(18.dp),
    control = RoundedCornerShape(14.dp),
    dialog = RoundedCornerShape(22.dp),
    small = RoundedCornerShape(12.dp),
)

val MrttBevelGeometry = MrttGeometry(
    card = androidx.compose.foundation.shape.CutCornerShape(topStart = 22.dp, bottomEnd = 22.dp, topEnd = 4.dp, bottomStart = 4.dp),
    control = androidx.compose.foundation.shape.CutCornerShape(topStart = 14.dp, bottomEnd = 14.dp, topEnd = 3.dp, bottomStart = 3.dp),
    dialog = androidx.compose.foundation.shape.CutCornerShape(topStart = 26.dp, bottomEnd = 26.dp, topEnd = 4.dp, bottomStart = 4.dp),
    small = androidx.compose.foundation.shape.CutCornerShape(topStart = 10.dp, bottomEnd = 10.dp, topEnd = 2.dp, bottomStart = 2.dp),
)

val LocalMrttGeometry = staticCompositionLocalOf { MrttDefaultGeometry }

fun materialShapesFor(geometry: MrttGeometry): Shapes = Shapes(
    extraSmall = geometry.small,
    small = geometry.small,
    medium = geometry.control,
    large = geometry.card,
    extraLarge = geometry.dialog,
)

private val currentGeometry = mutableStateOf(MrttDefaultGeometry)

object MrttStyleShapes {
    val geometry: MrttGeometry
        get() = currentGeometry.value

    val card: CornerBasedShape
        get() = geometry.card

    val control: CornerBasedShape
        get() = geometry.control

    val dialog: CornerBasedShape
        get() = geometry.dialog

    val small: CornerBasedShape
        get() = geometry.small

    internal fun update(value: MrttGeometry) {
        if (currentGeometry.value != value) currentGeometry.value = value
    }
}
