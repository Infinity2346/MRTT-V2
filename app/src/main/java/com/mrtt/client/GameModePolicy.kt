package com.mrtt.client

internal object GameModePolicy {

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

    fun parsePackages(raw: String): Set<String> =
        raw.split(',', ';', ' ', '\n', '\t')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()

    fun packagesToString(packages: Set<String>): String = packages.sorted().joinToString(",")

    fun parsePorts(raw: String): List<Int> =
        raw.split(',', ';', ' ', '\n', '\t')
            .mapNotNull { it.trim().toIntOrNull() }
            .filter { it in 1..65535 }
            .distinct()

    fun effectivePorts(extra: String): List<Int> =
        (DEFAULT_GAME_PORTS + parsePorts(extra)).distinct().sorted()

    fun portsCommandValue(extra: String): String = effectivePorts(extra).joinToString(",")

    fun isGameForeground(foregroundPackages: Collection<String>, games: Set<String>): Boolean =
        games.isNotEmpty() && foregroundPackages.any { it in games }

    fun isActive(
        manual: Boolean,
        auto: Boolean,
        games: Set<String>,
        foreground: Collection<String>,
    ): Boolean = manual || (auto && isGameForeground(foreground, games))

    fun command(active: Boolean, extraPorts: String): String =
        "GAME_MODE|${if (active) 1 else 0}|${portsCommandValue(extraPorts)}"
}
