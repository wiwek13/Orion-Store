package com.orion.store.morphe.nav

import kotlinx.serialization.Serializable

@Serializable
data class NavPillConfig(
    val id: String,
    val visible: Boolean = true
)

@Serializable
data class NavConfigState(
    val schemaVersion: Int = 1,
    val items: List<NavPillConfig> = emptyList()
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
        const val MINIMUM_VISIBLE_DESTINATIONS = 2

        /** Default state populates all core and morphe destinations in default order */
        fun defaultState(registry: List<NavDestination> = NavRegistry.all): NavConfigState {
            return NavConfigState(
                schemaVersion = CURRENT_SCHEMA_VERSION,
                items = registry.map { NavPillConfig(id = it.id, visible = true) }
            )
        }
    }
}

sealed interface NavConfigAction {
    data class ToggleVisibility(val id: String) : NavConfigAction
    data class SetVisibility(val id: String, val visible: Boolean) : NavConfigAction
    data class Move(val fromIndex: Int, val toIndex: Int) : NavConfigAction
    data class MoveUp(val id: String) : NavConfigAction
    data class MoveDown(val id: String) : NavConfigAction
    data object ResetToDefault : NavConfigAction
    data class SyncRegistry(val registry: List<NavDestination> = NavRegistry.all) : NavConfigAction
}

/**
 * Pure reducer function governing navigation customization.
 * Guarantees:
 * 1. Mandatory destinations (e.g. ABOUT) cannot be hidden.
 * 2. Minimum visible destinations threshold (>= 2) is enforced.
 * 3. Moving/reordering pills is bounds-safe.
 * 4. Syncing registry preserves existing user order and appends newly registered destinations.
 */
fun reduceNavConfig(
    state: NavConfigState,
    action: NavConfigAction,
    registry: List<NavDestination> = NavRegistry.all
): NavConfigState {
    val effectiveState = if (state.items.isEmpty()) NavConfigState.defaultState(registry) else state

    return when (action) {
        is NavConfigAction.ResetToDefault -> NavConfigState.defaultState(registry)

        is NavConfigAction.ToggleVisibility -> {
            val destination = registry.firstOrNull { it.id == action.id } ?: return effectiveState
            if (destination.mandatory) return effectiveState // Mandatory destinations cannot be hidden

            val currentItem = effectiveState.items.firstOrNull { it.id == action.id } ?: return effectiveState
            val targetVisible = !currentItem.visible

            // Enforce minimum visible count if turning off
            if (!targetVisible) {
                val currentVisibleCount = effectiveState.items.count { item ->
                    val dest = registry.firstOrNull { it.id == item.id }
                    item.visible && dest != null
                }
                if (currentVisibleCount <= NavConfigState.MINIMUM_VISIBLE_DESTINATIONS) {
                    return effectiveState
                }
            }

            effectiveState.copy(
                items = effectiveState.items.map {
                    if (it.id == action.id) it.copy(visible = targetVisible) else it
                }
            )
        }

        is NavConfigAction.SetVisibility -> {
            val destination = registry.firstOrNull { it.id == action.id } ?: return effectiveState
            if (destination.mandatory && !action.visible) return effectiveState

            if (!action.visible) {
                val currentVisibleCount = effectiveState.items.count { item ->
                    val dest = registry.firstOrNull { it.id == item.id }
                    item.visible && dest != null
                }
                if (currentVisibleCount <= NavConfigState.MINIMUM_VISIBLE_DESTINATIONS) {
                    return effectiveState
                }
            }

            effectiveState.copy(
                items = effectiveState.items.map {
                    if (it.id == action.id) it.copy(visible = action.visible) else it
                }
            )
        }

        is NavConfigAction.Move -> {
            val from = action.fromIndex
            val to = action.toIndex
            if (from !in effectiveState.items.indices || to !in effectiveState.items.indices || from == to) {
                return effectiveState
            }
            val mutable = effectiveState.items.toMutableList()
            val moved = mutable.removeAt(from)
            mutable.add(to, moved)
            effectiveState.copy(items = mutable)
        }

        is NavConfigAction.MoveUp -> {
            val idx = effectiveState.items.indexOfFirst { it.id == action.id }
            if (idx <= 0) return effectiveState
            val mutable = effectiveState.items.toMutableList()
            val moved = mutable.removeAt(idx)
            mutable.add(idx - 1, moved)
            effectiveState.copy(items = mutable)
        }

        is NavConfigAction.MoveDown -> {
            val idx = effectiveState.items.indexOfFirst { it.id == action.id }
            if (idx == -1 || idx >= effectiveState.items.lastIndex) return effectiveState
            val mutable = effectiveState.items.toMutableList()
            val moved = mutable.removeAt(idx)
            mutable.add(idx + 1, moved)
            effectiveState.copy(items = mutable)
        }

        is NavConfigAction.SyncRegistry -> {
            val registeredIds = action.registry.map { it.id }.toSet()
            // Retain existing known items in current user order, filter out deleted destinations
            val retained = effectiveState.items.filter { it.id in registeredIds }
            val existingIds = retained.map { it.id }.toSet()
            // Append any new destinations from registry that were not present previously
            val additions = action.registry
                .filter { it.id !in existingIds }
                .map { NavPillConfig(id = it.id, visible = true) }

            // Ensure mandatory destinations are always forced visible
            val reconciled = (retained + additions).map { item ->
                val dest = action.registry.firstOrNull { it.id == item.id }
                if (dest?.mandatory == true) item.copy(visible = true) else item
            }

            effectiveState.copy(
                schemaVersion = NavConfigState.CURRENT_SCHEMA_VERSION,
                items = reconciled
            )
        }
    }
}

/**
 * Resolves the ordered list of active destinations to be rendered by FloatingNav or NavigationRail.
 *
 * @param config User saved configuration (order + visibility).
 * @param registry Full registry of available destinations.
 * @param morpheEnabled Feature flag state: when false, Morphe destinations are completely stripped.
 */
fun resolveVisibleDestinations(
    config: NavConfigState,
    registry: List<NavDestination> = NavRegistry.all,
    morpheEnabled: Boolean
): List<NavDestination> {
    val effectiveConfig = if (config.items.isEmpty()) NavConfigState.defaultState(registry) else config
    val registryMap = registry.associateBy { it.id }

    return effectiveConfig.items.mapNotNull { item ->
        val dest = registryMap[item.id] ?: return@mapNotNull null
        if (!item.visible) return@mapNotNull null
        // Feature flag gate: When Morphe is off, exclude Morphe destinations entirely
        if (dest.group == NavGroup.MORPHE && !morpheEnabled) return@mapNotNull null
        dest
    }
}
