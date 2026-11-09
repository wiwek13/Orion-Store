package com.orion.store.morphe.ui

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.orion.store.morphe.ui.tabs.MyPatchedAppsTab as MyPatchedAppsContent
import com.orion.store.morphe.ui.tabs.MorpheStoreTab as MorpheStoreContent
import com.orion.store.morphe.ui.tabs.PatchStudioTab as PatchStudioContent
import com.orion.store.morphe.ui.tabs.QueueTab as QueueContent
import com.orion.store.morphe.ui.tabs.UpdatesTab as UpdatesContent
import com.orion.store.ui.*

/**
 * Common state container for Morphe tab screens: handles loading, empty, offline, and ready content states.
 */
@Composable
fun MorpheScreenContainer(
    title: String,
    subtitle: String,
    innerPadding: PaddingValues,
    isLoading: Boolean = false,
    isEmpty: Boolean = false,
    isOffline: Boolean = false,
    emptyMessage: String = "No items available.",
    onRetry: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit
) {
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier
            .fillMaxSize()
            .padding(top = innerPadding.calculateTopPadding(), bottom = innerPadding.calculateBottomPadding())
    ) {
        when {
            isLoading -> {
                Column(
                    Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator(color = cs.primary)
                    Spacer(Modifier.height(16.dp))
                    Text("Loading $title...", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                }
            }
            isOffline -> {
                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(Sym.WifiOff, null, Modifier.size(48.dp), tint = cs.error)
                    Spacer(Modifier.height(16.dp))
                    Text("You're offline", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Text("Connect to the internet to load online patch repositories.", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, textAlign = TextAlign.Center)
                    if (onRetry != null) {
                        Spacer(Modifier.height(16.dp))
                        FilledTonalButton(onClick = onRetry) { Text("Retry") }
                    }
                }
            }
            isEmpty -> {
                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(Sym.Extension, null, Modifier.size(48.dp), tint = cs.primary)
                    Spacer(Modifier.height(16.dp))
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Text(emptyMessage, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, textAlign = TextAlign.Center)
                }
            }
            else -> {
                content()
            }
        }
    }
}

@Composable
fun MorpheStoreTab(vm: MainViewModel, innerPadding: PaddingValues) {
    Box(
        Modifier
            .fillMaxSize()
            .padding(top = innerPadding.calculateTopPadding(), bottom = innerPadding.calculateBottomPadding())
    ) {
        Column(Modifier.fillMaxSize()) {
            Header(vm)
            Box(Modifier.weight(1f)) {
                MorpheStoreContent(vm)
            }
        }
    }
}

@Composable
fun PatchStudioTab(vm: MainViewModel, innerPadding: PaddingValues) {
    Box(
        Modifier
            .fillMaxSize()
            .padding(top = innerPadding.calculateTopPadding(), bottom = innerPadding.calculateBottomPadding())
    ) {
        Column(Modifier.fillMaxSize()) {
            Header(vm)
            Box(Modifier.weight(1f)) {
                PatchStudioContent(vm)
            }
        }
    }
}

@Composable
fun MyPatchedAppsTab(vm: MainViewModel, innerPadding: PaddingValues) {
    Box(
        Modifier
            .fillMaxSize()
            .padding(top = innerPadding.calculateTopPadding(), bottom = innerPadding.calculateBottomPadding())
    ) {
        Column(Modifier.fillMaxSize()) {
            Header(vm)
            Box(Modifier.weight(1f)) {
                MyPatchedAppsContent(vm)
            }
        }
    }
}

@Composable
fun PatchQueueTab(vm: MainViewModel, innerPadding: PaddingValues) {
    Box(
        Modifier
            .fillMaxSize()
            .padding(top = innerPadding.calculateTopPadding(), bottom = innerPadding.calculateBottomPadding())
    ) {
        Column(Modifier.fillMaxSize()) {
            Header(vm)
            Box(Modifier.weight(1f)) {
                QueueContent(vm)
            }
        }
    }
}

@Composable
fun PatchUpdatesTab(vm: MainViewModel, innerPadding: PaddingValues) {
    Box(
        Modifier
            .fillMaxSize()
            .padding(top = innerPadding.calculateTopPadding(), bottom = innerPadding.calculateBottomPadding())
    ) {
        Column(Modifier.fillMaxSize()) {
            Header(vm)
            Box(Modifier.weight(1f)) {
                UpdatesContent(vm)
            }
        }
    }
}
