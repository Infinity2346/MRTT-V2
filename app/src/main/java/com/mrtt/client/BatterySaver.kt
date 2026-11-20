package com.mrtt.client

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object BatterySaver {
    // «Авто» (the default): with the phone's own battery saver. «Свой уровень»: at or below the chosen charge.
    const val MODE_AUTO = "auto"
    const val MODE_LEVEL = "level"
    const val MODE_OFF = "off"
    const val DEFAULT_MODE = MODE_AUTO

    val MODES = listOf(MODE_AUTO to "Авто", MODE_LEVEL to "Свой уровень", MODE_OFF to "Выкл")

    const val DEFAULT_PERCENT = 30
    val PERCENT_CHOICES = listOf(10, 15, 20, 25, 30, 40, 50)

    const val TITLE = "Экономия батареи"
    const val LEVEL_TITLE = "Включать при заряде"

    const val DESCRIPTION =
        "Когда экономия включена, MRTT перестаёт держать процессор телефона включённым ради туннеля.\n\n" +
            "С выключенным экраном телефон расходует заметно меньше заряда, но туннель засыпает вместе с ним: " +
            "сообщения, звонки и уведомления, которые идут через VPN, могут приходить с большой задержкой, а загрузки " +
            "останавливаются. Когда экран включается, туннель продолжает работу.\n\n" +
            "Авто — экономия работает, пока включён режим энергосбережения телефона. Свой уровень — когда заряд " +
            "опустится до выбранного значения, а телефон не заряжается. Выкл — туннель никогда не засыпает."

    const val BADGE = "Экономия батареи: туннель может засыпать при выключенном экране"

    private val inForce = MutableStateFlow(false)

    val active: StateFlow<Boolean> = inForce.asStateFlow()

    fun shouldSave(mode: String, percent: Int, level: Int, charging: Boolean, systemSaver: Boolean): Boolean =
        when (mode) {
            MODE_AUTO -> systemSaver
            MODE_LEVEL -> !charging && level in 0..percent
            else -> false
        }

    internal fun setActive(value: Boolean) {
        inForce.value = value
    }
}
