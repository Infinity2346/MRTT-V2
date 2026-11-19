package com.mrtt.client.ui.apps

internal enum class AppRoute {
    Tunnel,

    DirectFiltered,

    Direct,
    ;

    fun next(): AppRoute = when (this) {
        Tunnel -> Direct
        Direct -> DirectFiltered
        DirectFiltered -> Tunnel
    }

    val label: String
        get() = when (this) {
            Tunnel -> "В туннеле"
            DirectFiltered -> "Напрямую, реклама режется"
            Direct -> "Напрямую"
        }

    val shortLabel: String
        get() = when (this) {
            Tunnel -> "В туннеле"
            DirectFiltered -> "Свой интернет + фильтр"
            Direct -> "Свой интернет"
        }

    val isDirect: Boolean get() = this != Tunnel
}

internal data class AppRouting(
    val tunnel: Set<String> = emptySet(),
    val filtered: Set<String> = emptySet(),
    val direct: Set<String> = emptySet(),
) {
    fun routeOf(packageName: String): AppRoute = when {
        packageName in filtered -> AppRoute.DirectFiltered
        packageName in direct -> AppRoute.Direct
        else -> AppRoute.Tunnel
    }

    fun advance(packageName: String): AppRouting = assign(packageName, routeOf(packageName).next())

    fun assign(packageName: String, route: AppRoute): AppRouting {
        val without = AppRouting(
            tunnel = tunnel - packageName,
            filtered = filtered - packageName,
            direct = direct - packageName,
        )
        return when (route) {
            AppRoute.Tunnel -> without.copy(tunnel = without.tunnel + packageName)
            AppRoute.DirectFiltered -> without.copy(filtered = without.filtered + packageName)
            AppRoute.Direct -> without.copy(direct = without.direct + packageName)
        }
    }

    companion object {
        fun parse(tunnel: String, filtered: String, direct: String) = AppRouting(
            tunnel = splitPackages(tunnel),
            filtered = splitPackages(filtered),
            direct = splitPackages(direct),
        )

        private fun splitPackages(value: String): Set<String> =
            value.split(',', '\n', ' ')
                .map(String::trim)
                .filter(String::isNotEmpty)
                .toSet()
    }
}
