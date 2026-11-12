package com.orion.store.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import java.io.File
import androidx.compose.ui.zIndex
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.orion.store.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Sky = Color(0xFF0EA5E9)
private data class MyAppsIoCache(
    val builtApks: Map<String, File> = emptyMap(),
    val readyPatchCount: Int = 0
)

private fun filterIcon(f: MyAppsFilter) = when (f) {
    MyAppsFilter.ALL -> Sym.Inventory2; MyAppsFilter.INSTALLED -> Sym.CheckCircle
    MyAppsFilter.DOWNLOADED -> Sym.Download; MyAppsFilter.UPDATES -> Sym.ArrowUpward
    MyAppsFilter.PATCHED -> Sym.Extension
}
private val EMPTY = mapOf(
    MyAppsFilter.ALL to "No apps yet",
    MyAppsFilter.INSTALLED to "No installed apps",
    MyAppsFilter.DOWNLOADED to "No downloaded apps",
    MyAppsFilter.UPDATES to "No pending updates",
    MyAppsFilter.PATCHED to "No patched apps yet"
)

@Composable
private fun tone(r: MyAppRow): Pair<ImageVector, Color> = when {
    r.status == "patching" -> Sym.Build to MaterialTheme.colorScheme.primary
    r.status == "downloading" -> Sym.ArrowDownward to MaterialTheme.colorScheme.primary
    r.status == "ready" -> Sym.Inventory to Safe
    r.hasUpdate -> Sym.ArrowUpward to Caution
    r.isInstalledNow -> Sym.CheckCircle to Sky
    r.libraryStatus == "installed" -> Sym.History to MaterialTheme.colorScheme.onSurfaceVariant
    else -> Sym.FileDownload to MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
fun MyAppsTab(vm: MainViewModel, padding: PaddingValues) {
    val s = LocalS.current; val d = LocalD.current
    val filters = remember(s.myAppsFilters) { myAppsFilterOrder(s.myAppsFilters) }
    var savedFilter by rememberSaveable { mutableStateOf(MyAppsFilter.ALL.name) }
    val pager = rememberPagerState(initialPage = filters.indexOfFirst { it.name == savedFilter }.coerceAtLeast(0)) { filters.size }
    val pageFilter = filters.getOrElse(pager.currentPage) { filters.first() }
    LaunchedEffect(pager.currentPage, filters) { savedFilter = pageFilter.name }
    val scope = rememberCoroutineScope()
    val query = vm.libraryQuery
    var unmanagedOpen by rememberSaveable { mutableStateOf(false) }
    val patchJobs by com.orion.store.morphe.store.JobStore.jobs.collectAsStateWithLifecycle()
    val patchedApps by com.orion.store.morphe.store.PatchedAppStore.apps.collectAsStateWithLifecycle()
    val allApps = vm.allApps()
    val updateIds = remember(vm.updates) { vm.updates.mapTo(HashSet()) { it.id } }
    val jobRowKey = remember(patchJobs) {
        patchJobs.map { "${it.id}:${it.stage}:${it.progress}:${it.outputApkPath.orEmpty()}:${it.createdAt}" }
    }
    val rows = remember(allApps, d, s, updateIds, patchedApps, jobRowKey) {
        buildMyAppsRows(allApps, s, d, updateIds, patchedApps, patchJobs)
    }
    val activeJobsByPkg = remember(patchJobs) {
        buildMap<String, com.orion.store.morphe.model.PatchJob> {
            for (job in patchJobs) {
                if (job.stage.isActive) {
                    val eff = job.params.effectivePackageName()
                    put(eff, job)
                    if (eff == job.params.packageName) put(job.params.packageName, job)
                }
            }
        }
    }
    val latestJobsByPkg = remember(jobRowKey) {
        buildMap<String, com.orion.store.morphe.model.PatchJob> {
            for (job in patchJobs) {
                val eff = job.params.effectivePackageName()
                val prevEff = get(eff)
                if (prevEff == null || job.createdAt > prevEff.createdAt) {
                    put(eff, job)
                }
                if (eff == job.params.packageName) {
                    val prevPkg = get(job.params.packageName)
                    if (prevPkg == null || job.createdAt > prevPkg.createdAt) {
                        put(job.params.packageName, job)
                    }
                }
            }
        }
    }
    val jobIoKey = remember(patchJobs) {
        patchJobs.map { Triple(it.id, it.stage, it.outputApkPath) }
    }
    val rowIoKey = remember(rows) {
        rows.map { Triple(it.app.id, it.status, it.isPatched) }
    }
    val ioCache by produceState(
        initialValue = MyAppsIoCache(),
        key1 = rowIoKey,
        key2 = jobIoKey
    ) {
        value = withContext(Dispatchers.IO) {
            val downloadsFiles = runCatching {
                com.orion.store.platform.Downloads.dir().listFiles()?.filter {
                    it.isFile && it.name.startsWith("Patched_", ignoreCase = true) && it.length() > 0
                }
            }.getOrNull().orEmpty()
            val builtMap = HashMap<String, File>(rows.size)
            for (r in rows) {
                val pkg = s.resolvedPackageNames[r.app.id] ?: r.app.packageName ?: r.app.id
                val targetJob = latestJobsByPkg[pkg]
                val found = targetJob?.outputApkPath?.let { File(it) }?.takeIf { it.exists() && it.length() > 0 }
                    ?: com.orion.store.platform.OrionStorageManager.findPatchedApkForApp(r.app.name, pkg)
                    ?: downloadsFiles.firstOrNull { f ->
                        val cleanAppName = r.app.name.replace(" ", "_")
                        f.name.contains(pkg, ignoreCase = true) || f.name.contains(cleanAppName, ignoreCase = true)
                    }
                if (found != null) builtMap[r.app.id] = found
            }
            val readyCount = patchJobs.count { job ->
                job.stage == com.orion.store.morphe.model.JobStage.SUCCEEDED &&
                    job.outputApkPath?.let { File(it) }?.takeIf { it.exists() && it.length() > 0 } != null
            }
            MyAppsIoCache(builtMap, readyCount)
        }
    }
    val pending = remember(rows) { rows.filter { it.hasUpdate && it.status != "downloading" && it.status != "patching" } }
    val filterCounts = remember(rows) { MyAppsFilter.entries.associateWith { f -> rows.count { it.matches(f) } } }
    val visible = remember(rows, pageFilter, query) { rows.filter { it.matches(pageFilter) && it.matchesQuery(query) } }
    val activePatchCount = remember(patchJobs) { patchJobs.count { it.stage.isActive } }
    val queue = d.activeDownloads.size + d.readyToInstall.size + activePatchCount + ioCache.readyPatchCount
    val ignoredNow = remember(rows, s.ignoredUpdates) { System.currentTimeMillis() }
    val lookup = vm.lookup
    val ignoredList = remember(s.ignoredUpdates, s.appManage, lookup, ignoredNow) {
        s.ignoredUpdates.keys.mapNotNull { id -> lookup[id]?.takeIf { isManaged(id, s) && isUpdateIgnored(it, s, ignoredNow) } }
    }
    val unmanagedList = remember(allApps, s) {
        allApps.filter { !isManaged(it.id, s) && (it.id in s.installedVersions || s.appManage[it.id] == false) }
    }
    val cs = MaterialTheme.colorScheme
    // Long-press an installed app to start picking, tap more to add them, then uninstall them together
    var picked by rememberSaveable { mutableStateOf(emptySet<String>()) }
    val removable = remember(visible) { visible.filter { it.isInstalledNow && it.app.installsHere() } }
    LaunchedEffect(rows) { picked = picked.filterTo(mutableSetOf()) { id -> rows.any { it.app.id == id && it.isInstalledNow } } }
    androidx.activity.compose.BackHandler(picked.isNotEmpty()) { picked = emptySet() }
    Column(Modifier.fillMaxSize().readableWidth()) {
        Column(Modifier.padding(top = padding.calculateTopPadding())) {
            Header(vm)
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                FilledTonalButton({ vm.haptic(H.SELECTION); vm.openSettings("queue") }, shapes = ButtonDefaults.shapes(), contentPadding = ButtonDefaults.SmallContentPadding) {
                    Icon(Sym.Downloading, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Queue")
                    if (queue > 0) { Spacer(Modifier.width(6.dp)); Badge { Text("$queue") } }
                }
                VerticalDivider(Modifier.height(28.dp).padding(horizontal = 4.dp), color = cs.outlineVariant)
                FilterPills(vm, s.myAppsFilters) { f -> Choice(pageFilter == f, "${f.label} · ${filterCounts[f] ?: 0}", filterIcon(f)) {
                    scope.launch { pager.animateScrollToPage(filters.indexOf(f).coerceAtLeast(0)) }
                } }
            }
        }
        if (picked.isNotEmpty()) Surface(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), shape = RoundedCornerShape(28.dp), color = cs.secondaryContainer, contentColor = cs.onSecondaryContainer, shadowElevation = 2.dp) {
            Row(Modifier.padding(start = 4.dp, end = 8.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton({ picked = emptySet(); vm.haptic(H.LIGHT) }) { Icon(Sym.Close, "Stop selecting") }
                Text("${picked.size} selected", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                if (picked.size < removable.size) TextButton({ picked = removable.map { it.app.id }.toSet(); vm.haptic(H.SELECTION) }) { Text("All") }
                Button({ vm.uninstallManyAsk = rows.filter { it.app.id in picked }.map { it.app }; picked = emptySet() }, shapes = ButtonDefaults.shapes(), contentPadding = ButtonDefaults.SmallContentPadding) {
                    Icon(Sym.Delete, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Uninstall")
                }
            }
        }
        HorizontalPager(pager, Modifier.weight(1f)) { page ->
            val filter = filters[page]
            val shown = remember(rows, filter, query) { rows.filter { it.matches(filter) && it.matchesQuery(query) } }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = padding.calculateBottomPadding())) {
                if (filter == MyAppsFilter.UPDATES && pending.isNotEmpty()) item("update-all") {
                    CtaButton("Update all (${pending.size})", { vm.haptic(H.MEDIUM); pending.forEach { vm.download(it.app) } }, Modifier.padding(horizontal = 16.dp, vertical = 4.dp), Sym.SystemUpdate)
                }
                if (vm.loading) item("loading") { StoreSkeleton(false) }
                else if (shown.isEmpty()) item("empty-${filter.name}") {
                    if (query.isNotBlank()) Column(Modifier.fillMaxWidth().padding(40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Sym.SearchOff, null, Modifier.size(48.dp), tint = cs.onSurfaceVariant)
                        Text("No apps matching “$query”", Modifier.padding(top = 12.dp), style = MaterialTheme.typography.titleMedium)
                        TextButton({ vm.libraryQuery = "" }) { Text("Clear search") }
                    } else EmptyState(filterIcon(filter), EMPTY.getValue(filter), if (filter == MyAppsFilter.ALL) "Apps you download from Orion show up here. Swipe sideways for the other lists." else "Swipe sideways for the other lists.", Modifier.padding(top = 32.dp))
                }
                itemsIndexed(shown, key = { _, it -> it.app.id }, contentType = { _, _ -> "library_row" }) { _, r ->
                    val pkg = s.resolvedPackageNames[r.app.id] ?: r.app.packageName ?: r.app.id
                    val can = r.isInstalledNow && r.app.installsHere()
                    LibraryRow(
                        vm = vm,
                        r = r,
                        pkg = pkg,
                        activeJob = activeJobsByPkg[pkg],
                        targetJob = latestJobsByPkg[pkg],
                        builtApk = ioCache.builtApks[r.app.id],
                        modifier = Modifier.animateItem(),
                        picked = if (picked.isEmpty()) null else (r.app.id in picked),
                        onPick = if (!can) null else ({ vm.haptic(H.SELECTION); picked = if (r.app.id in picked) picked - r.app.id else picked + r.app.id })
                    )
                }
                val ignored = if (filter == MyAppsFilter.ALL || filter == MyAppsFilter.UPDATES) ignoredList else emptyList()
                if (ignored.isNotEmpty()) item("ignored-title") {
                    Text("Ignored updates · ${ignored.size}", Modifier.padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 8.dp), style = MaterialTheme.typography.titleMediumEmphasized)
                }
                itemsIndexed(ignored, key = { _, a -> "ignored-${a.id}" }, contentType = { _, _ -> "side_row" }) { i, a ->
                    val ig = s.ignoredUpdates[a.id]
                    val until = when (ig?.type) {
                        "week" -> ((ONE_WEEK_MS - (ignoredNow - (ig.timestamp ?: ignoredNow))) / 86_400_000L + 1).coerceIn(1, 7).let { "Hidden for $it more day${if (it == 1L) "" else "s"}" }
                        "version" -> "Hidden until the next version"; else -> "Hidden for good"
                    }
                    SideRow(a, i, ignored.size, until, "Unignore") { vm.set { it.copy(ignoredUpdates = it.ignoredUpdates - a.id) } }
                }
                if (filter == MyAppsFilter.ALL || filter == MyAppsFilter.INSTALLED) {
                    if (unmanagedList.isNotEmpty()) item("unmanaged-title") {
                        val open = unmanagedOpen
                        Row(Modifier.fillMaxWidth().padding(top = 16.dp).clickable { vm.haptic(H.SELECTION); unmanagedOpen = !open }.padding(start = 24.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text("Not managed · ${unmanagedList.size}", Modifier.weight(1f), style = MaterialTheme.typography.titleMediumEmphasized)
                            Icon(if (open) Sym.ExpandLess else Sym.ExpandMore, if (open) "Collapse" else "Expand", tint = cs.onSurfaceVariant)
                        }
                    }
                    if (unmanagedOpen) itemsIndexed(unmanagedList, key = { _, a -> "unmanaged-${a.id}" }, contentType = { _, _ -> "side_row" }) { i, a ->
                        val why = if (s.appManage[a.id] == false) "Hidden by you" else otherStore(a.id, s)?.let { "Installed by ${vm.tracker.labelOf(it)}" } ?: "Not managed"
                        SideRow(a, i, unmanagedList.size, why, "Manage") { vm.set { it.withManaged(a.id, true) } }
                    }
                }
            }
        }
    }
}

/** A segmented row with one tonal action, for the ignored and not-managed lists. Segmented items draw their own rounded container,
 *  so the corners come out the same on every GPU instead of depending on clip support. */
@Composable
private fun LazyItemScope.SideRow(a: AppItem, i: Int, n: Int, supporting: String, action: String, onAction: () -> Unit) {
    val vm = LocalVm.current; val compact = LocalS.current.compactMode
    SegmentedListItem({ vm.selectApp(a) }, ListItemDefaults.segmentedShapes(i, n), Modifier.animateItem().padding(horizontal = 16.dp).then(if (compact) CompactRowHeight else Modifier),
        supportingContent = { Text(supporting, maxLines = 1, overflow = TextOverflow.Ellipsis) }, leadingContent = { AppIcon(a, if (compact) 36.dp else 44.dp) },
        trailingContent = { FilledTonalButton({ onAction(); vm.haptic(H.SUCCESS) }, shapes = ButtonDefaults.shapes(), contentPadding = ButtonDefaults.SmallContentPadding) { Text(action) } },
        colors = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceContainer), contentPadding = if (compact) CompactRowPadding else ListItemDefaults.ContentPadding,
        verticalAlignment = Alignment.CenterVertically) {
        Text(a.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Filter pills in the saved order. Long-press a pill and drag it sideways to move it; the order saves on release. */
@Composable
private fun FilterPills(vm: MainViewModel, saved: List<String>, pill: @Composable (MyAppsFilter) -> Unit) {
    var order by remember(saved) { mutableStateOf(myAppsFilterOrder(saved)) }
    var dragged by remember { mutableStateOf<MyAppsFilter?>(null) }; var dx by remember { mutableFloatStateOf(0f) }
    val widths = remember { mutableMapOf<MyAppsFilter, Int>() }; val gap = with(LocalDensity.current) { 6.dp.toPx() }
    fun end() { if (dragged != null) vm.set { it.copy(myAppsFilters = order.map { f -> f.name }) }; dragged = null; dx = 0f }
    order.forEach { f ->
        key(f) {
            val on = dragged == f
            Box(Modifier.onSizeChanged { widths[f] = it.width }.zIndex(if (on) 1f else 0f)
                .graphicsLayer { if (on) { translationX = dx; scaleX = 1.06f; scaleY = 1.06f } }
                .pointerInput(f) {
                    detectDragGesturesAfterLongPress({ dragged = f; dx = 0f; vm.haptic(H.MEDIUM) }, ::end, ::end) { c, delta ->
                        c.consume(); dx += delta.x
                        val i = order.indexOf(f); val j = if (dx > 0) i + 1 else i - 1
                        val w = (order.getOrNull(j)?.let { widths[it] } ?: return@detectDragGesturesAfterLongPress) + gap
                        if (kotlin.math.abs(dx) > w / 2) { order = order.toMutableList().apply { add(j, removeAt(i)) }; dx -= if (j > i) w else -w; vm.haptic(H.SELECTION) }
                    }
                }) { pill(f) }
        }
    }
}

@Composable
private fun LibraryRow(
    vm: MainViewModel,
    r: MyAppRow,
    pkg: String,
    activeJob: com.orion.store.morphe.model.PatchJob?,
    targetJob: com.orion.store.morphe.model.PatchJob?,
    builtApk: File?,
    modifier: Modifier,
    picked: Boolean? = null,
    onPick: (() -> Unit)? = null
) {
    val (icon, c) = tone(r); val cs = MaterialTheme.colorScheme; val s = LocalS.current; val compact = s.compactMode
    val bg by animateColorAsState(if (picked == true) cs.secondaryContainer else Color.Transparent, label = "pick")
    var showActiveJobLogSheet by remember { mutableStateOf(false) }

    // While picking a tap toggles the row (rows that can't be uninstalled do nothing); otherwise a long-press starts picking
    Column(modifier.fillMaxWidth().padding(horizontal = 8.dp).background(bg, RoundedCornerShape(20.dp))
        .springClick(onLongClick = if (picked == null) onPick else null) { if (picked == null) vm.selectApp(r.app, "lib") else onPick?.invoke() }
        .padding(horizontal = 12.dp, vertical = if (compact) 4.dp else 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val size = if (compact) 40.dp else 52.dp
            Box(contentAlignment = Alignment.Center) {
                AppIcon(r.app, size, state = true, from = "lib")
                if (picked == true) Box(Modifier.size(size).background(cs.primary, MaterialShapes.Cookie9Sided.toShape()), contentAlignment = Alignment.Center) { Icon(Sym.Check, "Selected", tint = cs.onPrimary) }
            }
            Spacer(Modifier.width(if (compact) 12.dp else 16.dp))
            Column(Modifier.weight(1f)) {
                Text(r.app.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (!compact) {
                    val subtitle = remember(r.app.author, r.app.platform, r.versionDetail) {
                        listOfNotNull(
                            r.app.author.takeIf { it.isNotBlank() },
                            r.app.platform.takeIf { it.isNotBlank() },
                            r.versionDetail.takeIf { it.isNotBlank() }
                        ).joinToString(" · ")
                    }
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(icon, null, Modifier.size(14.dp), tint = c); Spacer(Modifier.width(4.dp))
                    Text(r.statusLabel, style = MaterialTheme.typography.labelMedium, color = c, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            val action = r.primaryAction
            val hasNewBuiltApk = builtApk != null && (
                !r.isInstalledNow ||
                    (targetJob != null && !targetJob.params.autoInstall && targetJob.stage == com.orion.store.morphe.model.JobStage.SUCCEEDED && targetJob.createdAt > (r.installedAt ?: 0L))
                )
            if (r.hasSavedApk && action in setOf("install", "update", "download")) IconButton({ vm.deleteLibraryApk(r.app) }) { Icon(Sym.DeleteOutline, "Delete APK") }
            @Composable fun Primary(label: String, onClick: () -> Unit) =
                FilledTonalButton(onClick, shapes = ButtonDefaults.shapes(), contentPadding = ButtonDefaults.SmallContentPadding) { Text(label, maxLines = 1, softWrap = false) }
            when {
                activeJob != null -> FilledTonalButton({ showActiveJobLogSheet = true }, shapes = ButtonDefaults.shapes(), contentPadding = ButtonDefaults.SmallContentPadding) {
                    Icon(Sym.Terminal, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Logs", maxLines = 1, softWrap = false)
                }
                hasNewBuiltApk -> {
                    val apk = builtApk!!
                    Primary("Install") {
                        if (targetJob != null) vm.installPatchedApk(targetJob) else vm.installFile(r.app, apk.absolutePath)
                    }
                }
                r.status == "downloading" -> FilledTonalIconButton({ vm.cancelDownload(r.app.id) }, shapes = IconButtonDefaults.shapes()) { Icon(Sym.Close, "Cancel download") }
                action == "install" -> Primary("Install") { r.savedFile?.let { vm.installFile(r.app, it) } }
                action == "repatch" -> Primary("Update") {
                    val p = r.app.packageName ?: r.app.id
                    val morpheApp = com.orion.store.morphe.store.MorpheCatalogRepository.getApp(p) ?: com.orion.store.morphe.store.MorpheCatalogApp(packageName = p, name = r.app.name)
                    vm.openMorpheInstall(morpheApp)
                }
                action == "update" -> Primary("Update") { vm.download(r.app) }
                action == "download" -> Primary("Download") { vm.download(r.app) }
                action == "delete" -> IconButton({ vm.deleteLibraryApk(r.app) }) { Icon(Sym.DeleteOutline, "Delete APK") }
                !r.isInstalledNow || !r.app.installsHere() -> Icon(Sym.ChevronRight, "Open details", tint = cs.onSurfaceVariant)
            }
            if (picked == null && r.status != "downloading") Box {
                var menu by remember { mutableStateOf(false) }
                IconButton({ menu = true; vm.haptic(H.LIGHT) }) { Icon(Sym.MoreVert, "More options for ${r.app.name}") }
                DropdownMenu(menu, { menu = false }, shape = RoundedCornerShape(20.dp)) {
                    val resolvedPkg = LocalS.current.resolvedPackageNames[r.app.id] ?: r.app.packageName

                    if (builtApk != null || r.isPatched || r.hasSavedApk) {
                        DropdownMenuItem(
                            text = { Text("Show in Folder") },
                            onClick = {
                                menu = false
                                vm.haptic(H.LIGHT)
                                vm.showInFolder(builtApk ?: com.orion.store.platform.OrionStorageManager.getAppDir(r.app.name, pkg))
                            },
                            leadingIcon = { Icon(Sym.FolderOpen, null) }
                        )
                    }
                    if (builtApk != null) {
                        DropdownMenuItem(
                            text = { Text("Install Patched APK") },
                            onClick = {
                                menu = false
                                vm.haptic(H.LIGHT)
                                if (targetJob != null) vm.installPatchedApk(targetJob) else vm.installFile(r.app, builtApk.absolutePath)
                            },
                            leadingIcon = { Icon(Sym.FileDownload, null) }
                        )
                    }

                    if (targetJob != null) {
                        DropdownMenuItem(
                            text = { Text("View Patch Logs") },
                            onClick = {
                                menu = false
                                vm.haptic(H.LIGHT)
                                showActiveJobLogSheet = true
                            },
                            leadingIcon = { Icon(Sym.Terminal, null) }
                        )
                    }

                    if (builtApk != null || targetJob != null || r.isPatched) {
                        DropdownMenuItem(
                            text = { Text("Remove Patched Build", color = cs.error) },
                            onClick = {
                                menu = false
                                vm.removePatchedBuild(r.app, pkg)
                            },
                            leadingIcon = { Icon(Sym.Delete, null, tint = cs.error) }
                        )
                    }

                    if (resolvedPkg != null && r.isInstalledNow) DropdownMenuItem({ Text("Open") }, { menu = false; runCatching { vm.tracker.launch(resolvedPkg) }.onFailure { vm.error("Could not launch app. It may be restricted.") } },
                        leadingIcon = { Icon(Sym.OpenInNew, null) })
                    DropdownMenuItem({ Text("Patch / Repatch") }, {
                        menu = false
                        val p = resolvedPkg ?: r.app.id
                        val morpheApp = com.orion.store.morphe.store.MorpheCatalogRepository.getApp(p) ?: com.orion.store.morphe.store.MorpheCatalogApp(packageName = p, name = r.app.name)
                        vm.openMorpheInstall(morpheApp)
                    }, leadingIcon = { Icon(Sym.Build, null) })

                    val isAppAutoPatch = LocalS.current.autoPatchApps[pkg] ?: true
                    val pinnedVer = LocalS.current.pinnedAppVersions[pkg]
                    DropdownMenuItem({ Text(if (isAppAutoPatch) "Auto-patch: Enabled" else "Auto-patch: Disabled") }, {
                        menu = false
                        com.orion.store.ui.Store.settings.update { s ->
                            s.copy(autoPatchApps = s.autoPatchApps + (pkg to !isAppAutoPatch))
                        }
                    }, leadingIcon = { Icon(if (isAppAutoPatch) Sym.CheckCircle else Sym.Close, null) })

                    DropdownMenuItem({ Text(if (pinnedVer != null) "Pinned: v$pinnedVer (Tap to unpin)" else "Pin Current Version") }, {
                        menu = false
                        com.orion.store.ui.Store.settings.update { s ->
                            if (pinnedVer != null) {
                                s.copy(pinnedAppVersions = s.pinnedAppVersions - pkg)
                            } else {
                                val verToPin = r.app.version.takeIf { it.isNotBlank() } ?: "Recommended"
                                s.copy(pinnedAppVersions = s.pinnedAppVersions + (pkg to verToPin))
                            }
                        }
                    }, leadingIcon = { Icon(Sym.Lock, null) })

                    if (r.isInstalledNow) {
                        DropdownMenuItem({ Text("Uninstall") }, { menu = false; vm.uninstall(r.app) }, leadingIcon = { Icon(Sym.Delete, null) })
                    }

                    DropdownMenuItem(
                        text = { Text("Delete from My Apps", color = cs.error) },
                        onClick = {
                            menu = false
                            vm.deleteFromMyApps(r.app, pkg)
                        },
                        leadingIcon = { Icon(Sym.DeleteOutline, null, tint = cs.error) }
                    )
                }
            }
        }
        if (r.status == "downloading") Row(Modifier.padding(start = if (compact) 52.dp else 68.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            val p by animateFloatAsState(r.progress / 100f, MaterialTheme.motionScheme.defaultSpatialSpec(), label = "p")
            LinearWavyProgressIndicator({ p }, Modifier.weight(1f)); Spacer(Modifier.width(12.dp))
            Text("${Math.round(r.progress)}%", style = MaterialTheme.typography.labelLarge)
        }
        if (activeJob != null) {
            Column(Modifier.padding(start = if (compact) 52.dp else 68.dp, top = 8.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        activeJob.stageMessage,
                        style = MaterialTheme.typography.labelSmall,
                        color = cs.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        "${activeJob.progress}%",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = cs.onSurface
                    )
                    Spacer(Modifier.width(8.dp))
                    TextButton(
                        onClick = { showActiveJobLogSheet = true },
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Icon(Sym.Terminal, null, Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Live Logs", style = MaterialTheme.typography.labelSmall)
                    }
                }
                Spacer(Modifier.height(4.dp))
                val p by animateFloatAsState(activeJob.progress / 100f, MaterialTheme.motionScheme.defaultSpatialSpec(), label = "jobProgress")
                LinearWavyProgressIndicator({ p }, Modifier.fillMaxWidth())
            }
        }
        val jobForLogs = activeJob ?: targetJob
        if (showActiveJobLogSheet && jobForLogs != null) {
            com.orion.store.morphe.ui.sheets.PatchConsoleLogSheet(
                vm = vm,
                job = jobForLogs,
                onDismiss = { showActiveJobLogSheet = false }
            )
        }
    }
}
