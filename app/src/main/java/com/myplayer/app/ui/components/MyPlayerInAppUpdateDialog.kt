package com.myplayer.app.ui.components

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.myplayer.app.BuildConfig
import com.myplayer.app.data.update.InAppDownloadState
import com.myplayer.app.data.update.InAppUpdateManager
import com.myplayer.app.data.update.UpdateChecker
import com.myplayer.app.data.update.UpdateInfo

private val SheetBg = Color(0xFF121318)
private val CardBg = Color(0xFF1C1E26)
private val CardBorder = Color(0xFF2B2E3B)
private val AccentGreen = Color(0xFF9CF58A)
private val AccentBlue = Color(0xFF5E9EFF)

/**
 * MyPlayer In-App Update Bottom Sheet (`UpdateSheet.kt` + `UpdatePromptSheet.kt` ported to MyPlayer).
 *
 * Allows the user to:
 * 1. View the installed version vs new release version + `STABLE` / `BETA` channel badge.
 * 2. Read the structured "What changed" changelog cards.
 * 3. Inspect the "To Be Installed" APK card (App icon, APK filename, `com.myplayer.app`, and size in MB).
 * 4. Download & install the APK **directly inside the app** with an animated fill-progress pill button
 *    (`Install inside app` -> `Downloading… 48% (14.2 MB / 29.5 MB)` -> `Install Now`), handling
 *    Android's "Install unknown apps" permission automatically.
 * 5. Optionally open the web release page or snooze for 24 hours.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MyPlayerInAppUpdateSheet(
    updateInfo: UpdateInfo,
    inAppUpdateManager: InAppUpdateManager,
    userName: String = "",
    onSnooze24h: (() -> Unit)? = null,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val downloadState by inAppUpdateManager.downloadState.collectAsStateWithLifecycle()
    val isDownloading = downloadState is InAppDownloadState.Downloading
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { !isDownloading }
    )

    LaunchedEffect(updateInfo.versionName, updateInfo.apkUrl) {
        inAppUpdateManager.syncExistingDownloadState(updateInfo)
    }

    ModalBottomSheet(
        onDismissRequest = {
            if (!isDownloading) onDismiss()
        },
        sheetState = sheetState,
        containerColor = SheetBg,
        contentColor = Color.White,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp)
                .padding(bottom = 28.dp)
                .animateContentSize()
        ) {
            // Header Row: App Icon Badge + Version Ring + Close Button
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(56.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .background(CardBg)
                            .border(1.dp, CardBorder, RoundedCornerShape(18.dp))
                    ) {
                        MyPlayerAppIcon(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(12.dp))
                        )
                    }
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "MyPlayer Updater",
                                color = Color.White.copy(alpha = 0.7f),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(Modifier.width(8.dp))
                            Surface(
                                shape = CircleShape,
                                color = (if (updateInfo.isPreRelease) AccentBlue else AccentGreen).copy(alpha = 0.16f),
                                border = BorderStroke(
                                    1.dp,
                                    (if (updateInfo.isPreRelease) AccentBlue else AccentGreen).copy(alpha = 0.55f)
                                )
                            ) {
                                Text(
                                    text = if (updateInfo.isPreRelease) "BETA" else "STABLE",
                                    color = if (updateInfo.isPreRelease) AccentBlue else AccentGreen,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                )
                            }
                        }
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = if (userName.isBlank()) "Update available!" else "Update available, $userName!",
                            color = Color.White,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                }

                if (!isDownloading) {
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "Close",
                            tint = Color.White.copy(alpha = 0.7f)
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // Version Transition Banner Card (Installed -> New Version)
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = CardBg,
                border = BorderStroke(1.dp, CardBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            text = "INSTALLED",
                            color = Color.White.copy(alpha = 0.5f),
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "v${BuildConfig.VERSION_NAME}",
                            color = Color.White.copy(alpha = 0.85f),
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Icon(
                        imageVector = Icons.Filled.SystemUpdate,
                        contentDescription = null,
                        tint = AccentGreen,
                        modifier = Modifier.size(26.dp)
                    )
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = "NEW RELEASE",
                            color = AccentGreen,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = updateInfo.versionName,
                            color = AccentGreen,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            // Changelog Card ("What changed on ...")
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = CardBg,
                border = BorderStroke(1.dp, CardBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 220.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp)
                ) {
                    Text(
                        text = "What changed on ${updateInfo.versionName}",
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(8.dp))
                    val notes = updateInfo.releaseNotes.ifBlank {
                        "• Smooth MyPlayer equal-power & Silk Blend crossfade engine\n" +
                            "• Inside-app direct APK update downloader & installer\n" +
                            "• Performance, audio routing & stability improvements"
                    }
                    MyPlayerChangelogBlock(markdown = notes)
                }
            }

            Spacer(Modifier.height(16.dp))

            // "To Be Installed -" APK Package Card (exact MyPlayer layout)
            Text(
                text = "To Be Installed —",
                color = Color(0xFF9A9EA8),
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(6.dp))

            Surface(
                shape = RoundedCornerShape(18.dp),
                color = CardBg,
                border = BorderStroke(1.dp, CardBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp)
                ) {
                    MyPlayerAppIcon(
                        modifier = Modifier
                            .size(46.dp)
                            .clip(RoundedCornerShape(12.dp))
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = updateInfo.apkName ?: "MyPlayer-${updateInfo.tagName}.apk",
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(2.dp))
                        val sizeLabel = InAppUpdateManager.formatBytes(updateInfo.apkSizeBytes)
                        Text(
                            text = "${BuildConfig.APPLICATION_ID} · $sizeLabel",
                            color = Color(0xFF9A9EA8),
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (downloadState is InAppDownloadState.ReadyToInstall) {
                        Icon(
                            imageVector = Icons.Filled.CheckCircle,
                            contentDescription = "Downloaded",
                            tint = AccentGreen,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }

            // Status / Permission / Error Banner
            when (val st = downloadState) {
                is InAppDownloadState.Downloading -> {
                    Spacer(Modifier.height(12.dp))
                    val dlMb = InAppUpdateManager.formatBytes(st.downloadedBytes)
                    val totMb = if (st.totalBytes > 0L) InAppUpdateManager.formatBytes(st.totalBytes) else "..."
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Downloading inside app… $dlMb / $totMb",
                            color = AccentGreen,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Cancel",
                            color = Color(0xFFFF7B72),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { inAppUpdateManager.cancelDownload() }
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    if (st.progress > 0f) {
                        LinearProgressIndicator(
                            progress = { st.progress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(4.dp)),
                            color = AccentGreen,
                            trackColor = CardBorder
                        )
                    } else {
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(4.dp)),
                            color = AccentGreen,
                            trackColor = CardBorder
                        )
                    }
                }
                is InAppDownloadState.ReadyToInstall -> {
                    if (st.needsInstallPermission && !inAppUpdateManager.canInstallPackages()) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = "Allow \"Install unknown apps\" for MyPlayer, then tap \"Install Here\" again.",
                            color = Color(0xFFFFB86C),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
                is InAppDownloadState.Error -> {
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Filled.ErrorOutline,
                            contentDescription = null,
                            tint = Color(0xFFFF7B72),
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = st.message,
                            color = Color(0xFFFF7B72),
                            fontSize = 12.sp
                        )
                    }
                }
                InAppDownloadState.Idle -> {}
            }

            Spacer(Modifier.height(18.dp))

            // Primary Action Button: MyPlayer Animated Progress Pill ("Install Here" / "Downloading… XX%")
            val rawProgress = (downloadState as? InAppDownloadState.Downloading)?.progress ?: 0f
            val animatedProgress by animateFloatAsState(
                targetValue = rawProgress,
                animationSpec = tween(durationMillis = 120),
                label = "inAppUpdateProgress"
            )

            val hasDirectApk = !updateInfo.apkUrl.isNullOrBlank()
            val primaryLabel = when (val st = downloadState) {
                is InAppDownloadState.Downloading -> "Downloading… ${(animatedProgress * 100).toInt()}%"
                is InAppDownloadState.ReadyToInstall -> {
                    if (!inAppUpdateManager.canInstallPackages()) {
                        "Allow Unknown Apps & Install"
                    } else {
                        "Install Now (${updateInfo.versionName})"
                    }
                }
                is InAppDownloadState.Error -> if (hasDirectApk) "Retry In-App Download" else "Download from Official Website"
                InAppDownloadState.Idle -> if (hasDirectApk) "Install Here (Inside App)" else "Download from Official Website"
            }

            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .clip(RoundedCornerShape(50))
                    .background(
                        if (isDownloading) CardBg else AccentGreen
                    )
                    .border(
                        1.dp,
                        if (isDownloading) AccentGreen.copy(alpha = 0.55f) else Color.Transparent,
                        RoundedCornerShape(50)
                    )
                    .clickable(enabled = !isDownloading) {
                        when (val st = downloadState) {
                            is InAppDownloadState.ReadyToInstall -> {
                                inAppUpdateManager.installApk(st.apkFile, updateInfo.versionName)
                            }
                            else -> {
                                if (hasDirectApk) {
                                    inAppUpdateManager.startDownloadAndInstall(updateInfo, autoInstallOnFinish = true)
                                } else {
                                    openExternalUrl(context, updateInfo.releaseUrl.ifBlank { UpdateChecker.UPDATE_WEBSITE_URL })
                                }
                            }
                        }
                    }
            ) {
                if (isDownloading && animatedProgress > 0f) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .fillMaxHeight()
                            .fillMaxWidth(fraction = animatedProgress.coerceIn(0f, 1f))
                            .background(AccentGreen.copy(alpha = 0.28f))
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    if (!isDownloading) {
                        Icon(
                            imageVector = if (downloadState is InAppDownloadState.ReadyToInstall) {
                                Icons.Filled.SystemUpdate
                            } else if (downloadState is InAppDownloadState.Error) {
                                Icons.Filled.Refresh
                            } else {
                                Icons.Filled.Download
                            },
                            contentDescription = null,
                            tint = Color(0xFF0B0E11),
                            modifier = Modifier.size(19.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(
                        text = primaryLabel,
                        color = if (isDownloading) Color.White else Color(0xFF0B0E11),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            // Secondary Button: Open Web / GitHub Release Page
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .clip(RoundedCornerShape(50))
                        .background(CardBg)
                        .border(1.dp, CardBorder, RoundedCornerShape(50))
                        .clickable(enabled = !isDownloading) {
                            val webUrl = updateInfo.githubReleaseUrl?.takeIf { it.isNotBlank() }
                                ?: updateInfo.releaseUrl.ifBlank { UpdateChecker.UPDATE_WEBSITE_URL }
                            openExternalUrl(context, webUrl)
                        }
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Filled.OpenInNew,
                            contentDescription = null,
                            tint = Color.White.copy(alpha = 0.85f),
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "Open Web",
                            color = Color.White.copy(alpha = 0.9f),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .clip(RoundedCornerShape(50))
                        .background(CardBg)
                        .border(1.dp, CardBorder, RoundedCornerShape(50))
                        .clickable(enabled = !isDownloading) {
                            onSnooze24h?.invoke()
                            onDismiss()
                        }
                ) {
                    Text(
                        text = if (onSnooze24h != null) "Remind in 24h" else "Later",
                        color = Color.White.copy(alpha = 0.75f),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
private fun MyPlayerChangelogBlock(markdown: String) {
    val lines = remember(markdown) {
        markdown.lines().map { it.trim() }.filter { it.isNotBlank() }
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        lines.forEach { rawLine ->
            when {
                rawLine.startsWith("#") -> {
                    val heading = rawLine.trimStart('#').trim()
                    Text(
                        text = heading,
                        color = AccentGreen,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                rawLine.startsWith("-") || rawLine.startsWith("*") || rawLine.startsWith("•") -> {
                    val itemText = rawLine.drop(1).trim().replace("**", "")
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color.White.copy(alpha = 0.04f))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Box(
                            modifier = Modifier
                                .padding(top = 6.dp)
                                .size(5.dp)
                                .clip(CircleShape)
                                .background(AccentGreen)
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = itemText,
                            color = Color.White.copy(alpha = 0.88f),
                            fontSize = 13.sp,
                            lineHeight = 18.sp
                        )
                    }
                }
                else -> {
                    Text(
                        text = rawLine.replace("**", ""),
                        color = Color.White.copy(alpha = 0.82f),
                        fontSize = 13.sp,
                        lineHeight = 18.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun MyPlayerAppIcon(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val iconBitmap = remember(context) {
        runCatching {
            context.packageManager
                .getApplicationIcon(context.packageName)
                .toBitmap(width = 160, height = 160)
                .asImageBitmap()
        }.getOrNull()
    }
    if (iconBitmap != null) {
        Image(
            bitmap = iconBitmap,
            contentDescription = "MyPlayer",
            modifier = modifier
        )
    } else {
        Box(
            contentAlignment = Alignment.Center,
            modifier = modifier.background(AccentGreen.copy(alpha = 0.2f))
        ) {
            Icon(
                imageVector = Icons.Filled.SystemUpdate,
                contentDescription = null,
                tint = AccentGreen,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

private fun openExternalUrl(context: android.content.Context, url: String) {
    runCatching {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
