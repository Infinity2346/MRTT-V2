package com.mrtt.client

/**
 * Игровой режим в приложении: какие игры выбраны, когда режим должен включиться сам
 * и как это превращается в одну команду для Rust-клиента.
 *
 * Вся логика здесь чистая (без Android API), чтобы её можно было проверить юнит-тестами:
 * правило простое — режим активен, если он включён вручную, либо включён автодетект и
 * на экране сейчас игра из списка.
 */
internal object GameModePolicy {

    /** Порты игр по умолчанию: их знают почти все клиенты, даже без списка приложений. */
    val DEFAULT_GAME_PORTS: List<Int> = listOf(
        3074,   // Xbox Live
        3478,   // PlayStation Network / STUN
        3479,
        3480,
        3658,   // PlayStation Network
        4379,
        4380,
        27000,  // Steam / Source
        27015,  // Steam / Source (CS, TF2)
        27031,
        27036,
        25565,  // Minecraft
        19132,  // Minecraft Bedrock (UDP)
        19133,
        7777,   // Unreal Tournament / многие UE-игры
        9000,
        24000,
        28960,  // Call of Duty
        10012,
        50000,
    ).sorted()

    /** Строка настроек -> множество пакетов игр. */
    fun parsePackages(raw: String): Set<String> =
        raw.split(',', ';', ' ', '\n', '\t')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()

    fun packagesToString(packages: Set<String>): String = packages.sorted().joinToString(",")

    /** Пользовательские порты поверх встроенного набора: строкой через запятую. */
    fun parsePorts(raw: String): List<Int> =
        raw.split(',', ';', ' ', '\n', '\t')
            .mapNotNull { it.trim().toIntOrNull() }
            .filter { it in 1..65535 }
            .distinct()

    /** Все порты для команды клиенту: встроенные + пользовательские. */
    fun effectivePorts(extra: String): List<Int> =
        (DEFAULT_GAME_PORTS + parsePorts(extra)).distinct().sorted()

    fun portsCommandValue(extra: String): String = effectivePorts(extra).joinToString(",")

    /** Пакет сейчас на экране — игра из выбранного списка? */
    fun isGameForeground(foregroundPackages: Collection<String>, games: Set<String>): Boolean =
        games.isNotEmpty() && foregroundPackages.any { it in games }

    /**
     * Активен ли игровой режим прямо сейчас.
     *
     * @param manual включён переключатель в настройках;
     * @param auto включён автодетект;
     * @param games выбранные игры;
     * @param foreground пакеты на экране (для Android 10+); пустой список — данных нет.
     */
    fun isActive(
        manual: Boolean,
        auto: Boolean,
        games: Set<String>,
        foreground: Collection<String>,
    ): Boolean = manual || (auto && isGameForeground(foreground, games))

    /** Команда клиенту: `GAME_MODE|<0/1>|<порты>`. */
    fun command(active: Boolean, extraPorts: String): String =
        "GAME_MODE|${if (active) 1 else 0}|${portsCommandValue(extraPorts)}"
}
