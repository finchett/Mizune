package app.mizune.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Spacer
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.widthIn
import app.mizune.player.MpvOwner
import app.mizune.player.ThumbnailGenerator
import androidx.compose.runtime.MutableState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.round
import kotlinx.coroutines.launch
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.documentfile.provider.DocumentFile
import app.mizune.App
import app.mizune.Prefs
import app.mizune.dictionary.BundledDictionaries
import app.mizune.library.LibraryFile
import app.mizune.library.NaturalOrder
import app.mizune.library.Progress
import app.mizune.library.RecentVideos
import app.mizune.library.Titles
import androidx.compose.foundation.lazy.grid.GridItemSpan
import app.mizune.library.formatTime
import app.mizune.library.isVideoName
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class Listing(
    val folders: List<LibraryFile>,
    val videos: List<LibraryFile>,
    val all: List<LibraryFile>,
    val episodeCounts: Map<String, Int>,
    /** How many of each folder's videos are watched, and how far through each one, in order (see [Progress]). */
    val watchedCounts: Map<String, Int>,
    val shares: Map<String, List<List<Float>>>,
    /** The first video inside each folder, whose frame stands for it in the grid. */
    val covers: Map<String, LibraryFile>,
)

private sealed interface Entry {
    data class Folder(val file: LibraryFile, val episodes: Int, val cover: LibraryFile?, val watched: Int, val shares: List<List<Float>>) : Entry
    /** [number]: its place among the folder's videos, from 1. */
    data class Video(val file: LibraryFile, val number: Int) : Entry
}

/**
 * A video stopped partway, to pick up again: its show (null for one at the top), name, and how far
 * in. [folders] lead from the library down to its folder, which holds [siblings], to play on from it.
 */
private data class Resume(
    val video: LibraryFile,
    val folders: List<LibraryFile>,
    val siblings: List<LibraryFile>,
    val show: String?,
    val title: String,
    val left: String,
    val progress: Float,
)

@Composable
fun LibraryScreen(
    app: App,
    onOpenVideo: (LibraryFile, List<LibraryFile>) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val context = LocalContext.current
    var treeUri by remember { mutableStateOf(app.prefs.libraryTreeUri) }
    val root = remember(treeUri) {
        treeUri?.let(Uri::parse)
            ?.takeIf { runCatching { DocumentFile.fromTreeUri(context, it)?.canRead() == true }.getOrDefault(false) }
            ?.let(LibraryFile::root)
    }
    var path by remember(root) { mutableStateOf(listOfNotNull(root)) }
    // reopen the folder you were last in (and don't save until that's done)
    var restoring by remember(root) { mutableStateOf(true) }
    LaunchedEffect(root) {
        val names = app.prefs.libraryPath?.split('/')?.filter { it.isNotEmpty() }.orEmpty()
        if (root == null || names.isEmpty()) {
            restoring = false
            return@LaunchedEffect
        }
        val start: LibraryFile = root
        val restored = withContext(Dispatchers.IO) {
            var current = start
            val stack = mutableListOf(start)
            for (name in names) {
                current = LibraryFile.list(context, current).firstOrNull { it.name == name }?.takeIf { it.isDirectory } ?: break
                stack.add(current)
            }
            stack
        }
        path = restored
        restoring = false
    }
    LaunchedEffect(path, restoring) {
        if (!restoring && path.isNotEmpty()) {
            app.prefs.libraryPath = path.drop(1).joinToString("/") { it.name }
        }
    }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            app.prefs.libraryTreeUri = uri.toString()
            treeUri = uri.toString()
        }
    }

    BackHandler(enabled = path.size > 1) { path = path.dropLast(1) }

    val controlsVisible = remember { mutableStateOf(true) }
    DisposableEffect(Unit) {
        onDispose { ThumbnailGenerator.requestAbort() }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize()) {
            if (root == null) {
                Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Your library", style = MaterialTheme.typography.headlineMedium)
                        Text(
                            "Choose the folder that holds your shows, e.g. Movies/Immersion.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(6.dp))
                        Button(onClick = { pickFolder.launch(null) }) { Text("Choose folder") }
                    }
                }
            } else if (!restoring) {
                // each folder is its own grid, so one can slide out while the next slides in.
                // Held back until the folder you were last in is known, so opening the app doesn't
                // animate from the root to it.
                AnimatedContent(
                    targetState = path,
                    contentKey = { it.lastOrNull()?.uri?.toString() },
                    transitionSpec = { Motion.intoFolder(deeper = targetState.size >= initialState.size) },
                    label = "folder",
                ) { folder ->
                    folder.lastOrNull()?.let {
                        FolderGrid(
                            app = app,
                            folder = it,
                            title = if (folder.size > 1) app.title(it.name) else "Your library",
                            atRoot = folder.size == 1,
                            controlsVisible = controlsVisible,
                            onOpenFolder = { child -> path = path + child },
                            onGoToFolders = { path = it },
                            onOpenVideo = onOpenVideo,
                        )
                    }
                }
            }

            AnimatedVisibility(
                visible = controlsVisible.value,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.TopCenter),
            ) {
                // safeDrawing takes in the camera cutout, so nothing here sits under a hole punch,
                // on whichever edge it ends up in landscape
                Row(
                    Modifier.fillMaxWidth().safeDrawingPadding().padding(start = 14.dp, end = 10.dp, top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // the folder's own name is the page title; the chip is the way back up, and
                    // gives up its width first when an update needs room
                    Box(Modifier.weight(1f)) {
                        if (path.size > 1) {
                            FolderChip(
                                name = if (path.size > 2) app.title(path[path.size - 2].name) else "Library",
                                onUp = { path = path.dropLast(1) },
                            )
                        }
                    }
                    if (app.prefs.updatesInHeader) UpdatePill(app)
                    SettingsButton(onOpenSettings)
                }
            }

            val setupStatus by BundledDictionaries.status.collectAsState()
            JustUpdated(app)
            setupStatus?.let {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.92f),
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.align(Alignment.BottomCenter).safeDrawingPadding().padding(bottom = 12.dp),
                ) {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.tertiary,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }
}

/** The last listing of each folder, kept while the app runs so navigation doesn't wait on SAF. */
private object LibraryCache {
    private const val KEEP = 16
    private val folders = object : LinkedHashMap<String, Listing>(0, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, Listing>) = size > KEEP
    }

    operator fun get(uri: String): Listing? = synchronized(folders) { folders[uri] }

    operator fun set(uri: String, listing: Listing) {
        synchronized(folders) { folders[uri] = listing }
    }

    /** What Continue watching showed last at the top of each library, null for nothing to show. */
    private val resumes = HashMap<String, Resume?>()

    fun lookedForResume(root: String): Boolean = synchronized(resumes) { root in resumes }

    fun resume(root: String): Resume? = synchronized(resumes) { resumes[root] }

    fun setResume(root: String, resume: Resume?) {
        synchronized(resumes) { resumes[root] = resume }
    }
}

/** One folder's contents: its own listing, thumbnails and scroll position. */
@Composable
private fun FolderGrid(
    app: App,
    folder: LibraryFile,
    title: String,
    atRoot: Boolean,
    controlsVisible: MutableState<Boolean>,
    onOpenFolder: (LibraryFile) -> Unit,
    onGoToFolders: (List<LibraryFile>) -> Unit,
    onOpenVideo: (LibraryFile, List<LibraryFile>) -> Unit,
) {
    val context = LocalContext.current
    // what this folder held last time, so coming back from a video paints at once; the listing is
    // then refreshed in the background in case the folder has changed
    var listing by remember(folder) { mutableStateOf(LibraryCache[folder.uri.toString()]) }
    // counts changes made from a card's menu, to list again and look again for what Continue watching shows
    var changes by remember(folder) { mutableIntStateOf(0) }
    LaunchedEffect(folder, changes) {
        val fresh = withContext(Dispatchers.IO) { list(context, app.prefs, folder) }
        LibraryCache[folder.uri.toString()] = fresh
        listing = fresh
    }
    // only at the top of the library. What it showed last paints at once, as the listing does; the
    // first time, the grid waits for it, so Continue watching doesn't push the folders down
    val key = folder.uri.toString()
    val wanted = atRoot && app.prefs.continueWatching
    var resume by remember(folder) { mutableStateOf(if (wanted) LibraryCache.resume(key) else null) }
    var looked by remember(folder) { mutableStateOf(!wanted || LibraryCache.lookedForResume(key)) }
    val scope = rememberCoroutineScope()
    fun changeAllUnder(dir: LibraryFile, change: (String) -> Unit) = scope.launch {
        withContext(Dispatchers.IO) { videosUnder(context, dir).forEach { change(it.uri.toString()) } }
        resume = null
        changes++
    }
    LaunchedEffect(folder, changes) {
        if (!wanted) return@LaunchedEffect
        val found = withContext(Dispatchers.IO) { lastStopped(context, app, folder) }
        LibraryCache.setResume(key, found)
        resume = found
        looked = true
    }

    // warm the cache so scrolling never waits on a decode
    LaunchedEffect(listing, resume) {
        val videos = listing?.let { it.videos + it.covers.values } ?: return@LaunchedEffect
        withContext(Dispatchers.IO) {
            resume?.let { app.thumbnails.load(leftOffKey(it.video)) }
            videos.forEach { app.thumbnails.load(it.uri.toString()) }
        }
    }

    // fill in missing thumbnails while the library is open (libmpv is free then), folder covers too.
    // Continue watching's frame from where it was left comes first, and is taken again once it's been
    // watched further on; every other card keeps the video's own frame, a fifth of the way in
    LaunchedEffect(listing, resume) {
        val videos = listing?.let { it.videos + it.covers.values } ?: return@LaunchedEffect
        withContext(Dispatchers.IO) {
            resume?.video?.let { video ->
                val key = leftOffKey(video)
                val watched = app.prefs.position(video.uri.toString())
                val needed = !app.thumbnails.exists(key) || kotlin.math.abs(app.prefs.thumbnailPosition(key) - watched) > 30.0
                if (needed && !generate(context, app, video.uri.toString(), key, watched)) return@withContext
            }
            for (video in videos) {
                // left for another folder: its thumbnails come first
                if (!isActive) return@withContext
                val uri = video.uri.toString()
                // before Continue watching had its own frame, a watched video's was taken where it was left
                val needed = !app.thumbnails.exists(uri) || app.prefs.thumbnailPosition(uri) > 0
                if (needed && !generate(context, app, uri, uri, 0.0)) return@withContext
            }
        }
    }

    val gridState = rememberLazyGridState()
    // controls hide as you scroll into the grid and come back when you scroll up
    var lastIndex by remember { mutableIntStateOf(0) }
    var lastOffset by remember { mutableIntStateOf(0) }
    LaunchedEffect(gridState) {
        snapshotFlow { gridState.firstVisibleItemIndex to gridState.firstVisibleItemScrollOffset }
            .collect { (index, offset) ->
                val scrollingDown = index > lastIndex || (index == lastIndex && offset > lastOffset + 8)
                val scrollingUp = index < lastIndex || (index == lastIndex && offset < lastOffset - 8)
                if (scrollingDown && index > 0) controlsVisible.value = false
                if (scrollingUp) controlsVisible.value = true
                lastIndex = index
                lastOffset = offset
            }
    }

    val shown = listing
    if (shown == null || !looked) {
        // the folder is listed on the IO thread; leave the space empty rather than flash a spinner
        Box(Modifier.fillMaxSize())
        return
    }
    val tidy = app.prefs.tidyNames
    val videoTitles = remember(shown, tidy) {
        val names = shown.videos.map { it.name }
        if (tidy) Titles.episodes(names) else names.map { it.substringBeforeLast('.') }
    }
    val folders = shown.folders.map {
        val uri = it.uri.toString()
        Entry.Folder(it, shown.episodeCounts[uri] ?: 0, shown.covers[uri], shown.watchedCounts[uri] ?: 0, shown.shares[uri].orEmpty())
    }
    val videos = shown.videos.mapIndexed { i, video -> Entry.Video(video, i + 1) }
    val detail = listOfNotNull(count(folders.size, "folder"), count(videos.size, "video")).joinToString(" · ")
    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Adaptive(200.dp),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 60.dp, bottom = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(key = "title", span = { GridItemSpan(maxLineSpan) }) {
            Column(Modifier.padding(start = 2.dp, bottom = 2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.headlineMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    detail.ifEmpty { "Nothing here." },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        resume?.let { item ->
            item(key = "continue", span = { GridItemSpan(maxLineSpan) }) { GroupLabel("Continue watching") }
            // the same card as any video, so it sits in the grid's rhythm; it plays from where it stopped
            item(key = "resume") {
                val play = {
                    // back from the player comes out in the video's folder, as it would from there
                    app.prefs.libraryPath = item.folders.drop(1).joinToString("/") { it.name }
                    onOpenVideo(item.video, item.siblings)
                }
                VideoCard(
                    app, item.video, item.show ?: item.title, number = null,
                    detail = item.show?.let { "${item.title} · ${item.left}" } ?: item.left, leftOff = true,
                    menu = { close ->
                        VideoMenu(
                            app, item.video, close, onPlay = play,
                            onChanged = { resume = null; changes++ },
                            onGoToFolder = { onGoToFolders(item.folders) },
                        )
                    },
                    onClick = play,
                )
            }
        }
        // labelled groups only when there are two kinds to tell apart, Continue watching among them
        val kinds = listOf(resume != null, folders.isNotEmpty(), videos.isNotEmpty()).count { it }
        if (kinds > 1 && folders.isNotEmpty()) item(key = "folders", span = { GridItemSpan(maxLineSpan) }) { GroupLabel("Folders") }
        items(folders, key = { it.file.uri.toString() }) { entry ->
            FolderCard(
                app, entry,
                onMarkWatched = { changeAllUnder(entry.file, app.prefs::markWatched) },
                onClearProgress = { changeAllUnder(entry.file, app.prefs::clearProgress) },
            ) { onOpenFolder(entry.file) }
        }
        if (kinds > 1 && videos.isNotEmpty()) item(key = "videos", span = { GridItemSpan(maxLineSpan) }) { GroupLabel("Videos") }
        items(videos, key = { it.file.uri.toString() }) { entry ->
            val name = videoTitles[entry.number - 1]
            // a title that already says which episode it is needs no place number beside it
            val number = entry.number.takeIf { app.prefs.episodeNumbers && !namesEpisode(name) }
            // drawn again when a menu clears a video's progress, which the card reads as it's drawn
            key(changes) {
                VideoCard(
                    app, entry.file, name, number,
                    menu = { close -> VideoMenu(app, entry.file, close, onPlay = { onOpenVideo(entry.file, shown.all) }, onChanged = { changes++ }) },
                ) { onOpenVideo(entry.file, shown.all) }
            }
        }
    }
}

/**
 * What a long press on a video offers, under its file's own name, which a tidy title hides. [onGoToFolder] for
 * one under Continue watching, which can be taken out of it and found in its folder.
 */
@Composable
private fun VideoMenu(
    app: App,
    video: LibraryFile,
    close: () -> Unit,
    onPlay: () -> Unit,
    onChanged: () -> Unit,
    onGoToFolder: (() -> Unit)? = null,
) {
    val uri = video.uri.toString()
    MenuName(video.name)
    val partway = app.prefs.position(uri) > 0
    val watched = app.prefs.watched(uri)
    if (partway) Choice("Play from the start", close) { app.prefs.clearProgress(uri); onChanged(); onPlay() } else Choice("Play", close, onPlay)
    if (!watched) Choice("Mark as watched", close) { app.prefs.markWatched(uri); onChanged() }
    if (partway || watched) Choice("Clear progress", close) { app.prefs.clearProgress(uri); onChanged() }
    if (onGoToFolder != null) {
        Choice("Go to folder", close, onGoToFolder)
        Choice("Remove from row", close) { app.prefs.forgetRecent(uri); onChanged() }
    }
}

/** A file's own name at the head of its menu, quiet, since a tidy title may hide it. */
@Composable
private fun MenuName(name: String) = Text(
    name,
    style = MaterialTheme.typography.bodySmall,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    maxLines = 2,
    overflow = TextOverflow.Ellipsis,
    modifier = Modifier.padding(start = 10.dp, end = 10.dp, top = 6.dp, bottom = 4.dp),
)

/** A card's long-press menu, opening [at] where the finger was, in the card's frame. */
@Composable
private fun BoxScope.CardMenu(open: Boolean, at: Offset, onDismiss: () -> Unit, menu: @Composable (close: () -> Unit) -> Unit) {
    // a point to hang the menu from, since a menu opens below its anchor
    Box(Modifier.align(Alignment.TopStart).offset { at.round() }.size(0.dp)) {
        MenuCard(expanded = open, onDismiss = onDismiss, width = 280.dp) {
            CompositionLocalProvider(LocalMenuRowHeight provides 44.dp) { menu(onDismiss) }
        }
    }
}

/** Notes where a finger goes down, without taking the touch, so a long press's menu can open there. */
private fun Modifier.trackPress(onPress: (Offset) -> Unit) = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.type == PointerEventType.Press) onPress(event.changes.first().position)
        }
    }
}

/** A menu row that closes the menu as it does what it says. */
@Composable
private fun Choice(title: String, close: () -> Unit, action: () -> Unit) =
    MenuChoice(title, selected = false) { close(); action() }

/** Current folder, floating over the grid; tapping it goes up. */
@Composable
private fun FolderChip(name: String, onUp: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.92f),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.clickable(onClick = onUp),
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("‹  ", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleSmall)
            Text(
                name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 320.dp),
            )
        }
    }
}

@Composable
private fun SettingsButton(onOpenSettings: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.92f),
        shape = CircleShape,
        modifier = Modifier.clickable(onClick = onOpenSettings),
    ) {
        Box(Modifier.padding(10.dp)) { Cog(MaterialTheme.colorScheme.onSurface, diameter = 20.dp) }
    }
}

/** A show: a frame from the first video in it, its name, and how many videos are inside. */
@Composable
private fun FolderCard(app: App, folder: Entry.Folder, onMarkWatched: () -> Unit, onClearProgress: () -> Unit, onClick: () -> Unit) {
    val thumbnail = folder.cover?.let { rememberThumbnail(app, it.uri.toString()) }
    val scheme = MaterialTheme.colorScheme
    var menuOpen by remember { mutableStateOf(false) }
    var pressedAt by remember { mutableStateOf(Offset.Zero) }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).trackPress { pressedAt = it }
            .combinedClickable(onLongClick = { menuOpen = true }, onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp)).background(scheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            if (thumbnail != null) {
                Image(
                    bitmap = thumbnail.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                FolderMark(scheme.onSurfaceVariant.copy(alpha = 0.55f))
            }
            // its videos a slice each, filled as far as each has been watched; once they all are, a tick alone
            // each kind of mark can be turned off in settings, or all of them
            val done = folder.episodes > 0 && folder.watched >= folder.episodes
            if (done && app.prefs.showProgress && app.prefs.progressChecks) WatchedMark(Modifier.align(Alignment.TopEnd).padding(8.dp))
            else if (!done && app.prefs.showProgress && app.prefs.folderProgress && folder.shares.any { group -> group.any { it > 0f } }) ProgressSlices(folder.shares, Modifier.align(Alignment.BottomStart))
            CardMenu(menuOpen, pressedAt, onDismiss = { menuOpen = false }) { close ->
                MenuName(folder.file.name)
                Choice("Open", close, onClick)
                Choice("Mark all as watched", close, onMarkWatched)
                Choice("Clear progress", close, onClearProgress)
            }
        }
        Column(Modifier.padding(start = 2.dp, end = 2.dp, bottom = 4.dp)) {
            Text(
                app.title(folder.file.name),
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Progress.folderDetail(folder.episodes, if (app.prefs.showProgress) folder.watched else 0)?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            }
        }
    }
}

/** Where Continue watching's frame of [video], from where it was left, is kept beside its own. */
private fun leftOffKey(video: LibraryFile) = video.uri.toString() + "#left"

/**
 * Takes a frame of [uri] [at] seconds in (a fifth of the way in for 0) and keeps it under [key].
 * False when libmpv is busy with the player, so the caller stops for now.
 */
private fun generate(context: Context, app: App, uri: String, key: String, at: Double): Boolean {
    if (!MpvOwner.tryAcquire("thumbnails")) return false
    try {
        val start = if (at > 0) at.toInt().toString() else "20%"
        if (ThumbnailGenerator.generate(context, uri, app.thumbnails, start, key)) app.prefs.setThumbnailPosition(key, at)
    } finally {
        MpvOwner.release("thumbnails")
    }
    return true
}

/** The cached frame for a video, kept up to date as thumbnails are generated. */
@Composable
private fun rememberThumbnail(app: App, key: String): android.graphics.Bitmap? {
    var thumbnail by remember(key) { mutableStateOf(app.thumbnails.cached(key)) }
    // reloads only when this video's thumbnail changes
    val version = app.thumbnails.versions[key] ?: 0L
    LaunchedEffect(key, version) {
        thumbnail = withContext(Dispatchers.IO) { app.thumbnails.load(key) }
    }
    return thumbnail
}

/**
 * A video: its frame, how far in, and its name; [detail] in place of its times when given, and
 * with [leftOff] the frame from where it was left.
 */
@Composable
private fun VideoCard(
    app: App, video: LibraryFile, title: String, number: Int?,
    detail: String? = null, leftOff: Boolean = false,
    // what a long press offers, if anything, given a way to close it
    menu: (@Composable (close: () -> Unit) -> Unit)? = null,
    onClick: () -> Unit,
) {
    val uri = video.uri.toString()
    val position = app.prefs.position(uri)
    val duration = app.prefs.duration(uri)
    val progress = if (duration > 0) (position / duration).toFloat().coerceIn(0f, 1f) else 0f
    val watched = app.prefs.watched(uri)
    val thumbnail = rememberThumbnail(app, if (leftOff) leftOffKey(video) else uri)

    val scheme = MaterialTheme.colorScheme
    var menuOpen by remember { mutableStateOf(false) }
    var pressedAt by remember { mutableStateOf(Offset.Zero) }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).trackPress { pressedAt = it }
            .combinedClickable(onLongClick = menu?.let { { menuOpen = true } }, onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp)).background(scheme.surfaceVariant)) {
                val image = thumbnail
                if (image != null) {
                    Image(
                        bitmap = image.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                    if (number != null) {
                        Text(
                            "$number",
                            style = MaterialTheme.typography.labelMedium,
                            color = scheme.onSurface,
                            modifier = Modifier.align(Alignment.TopStart).padding(8.dp)
                                .background(scheme.background.copy(alpha = 0.82f), RoundedCornerShape(6.dp))
                                .padding(horizontal = 7.dp, vertical = 2.dp),
                        )
                    }
                } else {
                    // not seen yet: its place in the folder, quietly, on the plain frame
                    Text(
                        number?.toString() ?: "▶",
                        style = MaterialTheme.typography.headlineMedium,
                        color = scheme.onSurfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
                if (menu != null) CardMenu(menuOpen, pressedAt, onDismiss = { menuOpen = false }, menu)
                // each kind of mark can be turned off in settings, or all of them
                if (watched && app.prefs.showProgress && app.prefs.progressChecks) WatchedMark(Modifier.align(Alignment.TopEnd).padding(8.dp))
                if (progress > 0f && app.prefs.showProgress && app.prefs.videoProgress) ProgressLine(progress, Modifier.align(Alignment.BottomStart))
            }
            Column(Modifier.padding(start = 2.dp, end = 2.dp, bottom = 4.dp)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    when {
                        detail != null -> detail
                        position > 0 && duration > 0 -> "${formatTime(position)} / ${formatTime(duration)}"
                        position > 0 -> "resume ${formatTime(position)}"
                        duration > 0 -> formatTime(duration)
                        else -> " "
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (position > 0 && detail == null) scheme.primary else scheme.onSurfaceVariant,
                )
            }
    }
}

@Composable
private fun GroupLabel(text: String) {
    Text(
        text.uppercase(),
        fontSize = 11.sp,
        lineHeight = 14.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 1.4.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 2.dp, top = 6.dp),
    )
}

/** How a file or folder called [name] is shown. */
private fun App.title(name: String): String =
    if (prefs.tidyNames) Titles.clean(name) else if (isVideoName(name)) name.substringBeforeLast('.') else name

private fun namesEpisode(title: String) = title.startsWith("Episode ") || Regex("""^S\d+ E\d+""").containsMatchIn(title)

private fun count(n: Int, noun: String) = when (n) {
    0 -> null
    1 -> "1 $noun"
    else -> "$n ${noun}s"
}

/** A folder one level down, as the listing sums it up: its videos, a group for its own and one for each season. */
private class Show(val uri: String, val groups: List<List<LibraryFile>>, val cover: LibraryFile?) {
    val episodes get() = groups.flatten()
}

private suspend fun list(context: Context, prefs: Prefs, folder: LibraryFile): Listing = coroutineScope {
    val all = LibraryFile.list(context, folder)
    val folders = all.filter { it.isDirectory }.sortedWith(compareBy(NaturalOrder) { it.name })
    val videos = all.filter { it.isVideo }.sortedWith(compareBy(NaturalOrder) { it.name })
    // each show and its seasons: enough for "24 videos" and a cover under each show without a slow full
    // scan. The shows are listed side by side, since each one waits on the storage provider.
    val shows = folders.map { child ->
        async(Dispatchers.IO) {
            val files = LibraryFile.list(context, child)
            val episodes = files.filter { it.isVideo }.sortedWith(compareBy(NaturalOrder) { it.name })
            val seasons = files.filter { it.isDirectory }.sortedWith(compareBy(NaturalOrder) { it.name })
                .map { videosUnder(context, it, depth = 2) }
            // a show split into seasons has no videos of its own: take the cover from its first season
            Show(child.uri.toString(), listOf(episodes) + seasons, episodes.firstOrNull() ?: coverIn(context, files))
        }
    }.awaitAll()
    fun share(video: LibraryFile) = video.uri.toString().let { Progress.share(prefs.position(it), prefs.duration(it), prefs.watched(it)) }
    Listing(
        folders, videos, all,
        episodeCounts = shows.associate { it.uri to it.episodes.size },
        watchedCounts = shows.associate { show -> show.uri to show.episodes.count { prefs.watched(it.uri.toString()) } },
        shares = shows.associate { show -> show.uri to show.groups.map { group -> group.map(::share) } },
        covers = shows.mapNotNull { show -> show.cover?.let { show.uri to it } }.toMap(),
    )
}

/** The video stopped partway most recently that's still in the library under [root], or null. Call it off the main thread. */
private fun lastStopped(context: Context, app: App, root: LibraryFile): Resume? =
    app.prefs.recent.asSequence()
        .filter { app.prefs.position(it) > 0 && app.prefs.duration(it) > 0 }
        .firstNotNullOfOrNull { resumeAt(context, app, root, Uri.parse(it)) }

/** The video at [uri] for Continue watching, found by walking down from [root]; null if it isn't in there now. */
private fun resumeAt(context: Context, app: App, root: LibraryFile, uri: Uri): Resume? {
    // one opened from another app, or from a library folder chosen before this one, is in another tree or none
    if (uri.authority != root.uri.authority || !DocumentsContract.isTreeUri(uri) ||
        DocumentsContract.getTreeDocumentId(uri) != DocumentsContract.getTreeDocumentId(root.uri)
    ) return null
    // the documents from the top of the tree down to the video; a provider that can't say gets no card
    val ids = runCatching { DocumentsContract.findDocumentPath(context.contentResolver, uri)?.path }.getOrNull() ?: return null
    val below = ids.drop(ids.indexOf(DocumentsContract.getDocumentId(root.uri)) + 1)
    if (below.isEmpty()) return null
    // each folder on the way is listed, which also says it's still there
    val folders = mutableListOf(root)
    var siblings = LibraryFile.list(context, root)
    for (id in below.dropLast(1)) {
        val next = siblings.firstOrNull { it.isDirectory && DocumentsContract.getDocumentId(it.uri) == id } ?: return null
        folders += next
        siblings = LibraryFile.list(context, next)
    }
    val video = siblings.firstOrNull { it.uri == uri } ?: return null
    val names = siblings.filter { it.isVideo }.map { it.name }.sortedWith(NaturalOrder)
    val title = RecentVideos.title(video.name, names, app.prefs.tidyNames)
    val key = uri.toString()
    val duration = app.prefs.duration(key)
    return Resume(
        video = video,
        folders = folders,
        siblings = siblings,
        show = RecentVideos.show(if (folders.size > 1) app.title(folders.last().name) else null, title),
        title = title,
        left = RecentVideos.timeLeft(duration - app.prefs.position(key)),
        progress = app.prefs.progress(key, duration),
    )
}

/** The first video in the subfolders among [files], looking at most [depth] levels down. */
/** The videos in [folder] and the folders under it, at most [depth] levels down, as a show split into seasons has. */
private fun videosUnder(context: Context, folder: LibraryFile, depth: Int = 3): List<LibraryFile> {
    if (depth == 0) return emptyList()
    val files = LibraryFile.list(context, folder).sortedWith(compareBy(NaturalOrder) { it.name })
    return files.filter { it.isVideo } + files.filter { it.isDirectory }.flatMap { videosUnder(context, it, depth - 1) }
}

private fun coverIn(context: Context, files: List<LibraryFile>, depth: Int = 3): LibraryFile? {
    if (depth == 0) return null
    return files.filter { it.isDirectory }.sortedWith(compareBy(NaturalOrder) { it.name })
        .firstNotNullOfOrNull { dir ->
            val children = LibraryFile.list(context, dir)
            children.filter { it.isVideo }.minWithOrNull(compareBy(NaturalOrder) { it.name })
                ?: coverIn(context, children, depth - 1)
        }
}
