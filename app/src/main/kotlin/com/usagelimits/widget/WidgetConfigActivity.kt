package com.usagelimits.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.usagelimits.UsageLimitsApp
import com.usagelimits.core.database.AccountUsage
import com.usagelimits.core.database.WidgetConfigEntity
import com.usagelimits.core.model.ProviderId
import com.usagelimits.core.model.WidgetScope
import com.usagelimits.ui.ProviderLogo
import com.usagelimits.ui.components.SectionHeader
import com.usagelimits.ui.components.ToggleRow
import com.usagelimits.ui.components.UsageCard
import com.usagelimits.ui.theme.UsageColors
import com.usagelimits.ui.theme.UsageLimitsTheme
import kotlinx.coroutines.launch
import org.json.JSONArray

class WidgetConfigActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT), navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT))
        val widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        val result = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
        setResult(RESULT_CANCELED, result)
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) { finish(); return }
        val container = (application as UsageLimitsApp).container
        val receiver = AppWidgetManager.getInstance(this).getAppWidgetInfo(widgetId)?.provider?.className.orEmpty()
        val kind = when {
            receiver.contains("MiniRings") -> "Mini Rings"
            receiver.contains("Minimal") -> "Account Rings"
            receiver.contains("Compact") -> "Usage Summary"
            else -> "Usage Bars"
        }
        setContent {
            UsageLimitsTheme {
                var initial by remember { mutableStateOf<WidgetConfigEntity?>(null) }
                var loaded by remember { mutableStateOf(false) }
                val accounts by container.repository.observeAccountUsage().collectAsState(emptyList())
                var saving by remember { mutableStateOf(false) }
                var error by remember { mutableStateOf<String?>(null) }
                LaunchedEffect(widgetId) {
                    try { initial = container.widgetConfigDao.get(widgetId); loaded = true }
                    catch (_: Exception) { error = "Could not load this widget. Please try again." }
                }
                if (loaded) WidgetConfigScreen(accounts, initial, kind, saving, error,
                    onCancel = { finish() }, onSave = { scope, accountId, providerId, transparent, custom ->
                        if (!saving) {
                            saving = true
                            lifecycleScope.launch {
                                try {
                                    container.widgetConfigDao.upsert(WidgetConfigEntity(
                                        appWidgetId = widgetId, scope = scope.name, accountId = accountId,
                                        provider = providerId, transparent = transparent, updatedAt = System.currentTimeMillis(),
                                        customAccountIdsJson = JSONArray(custom).toString()))
                                    WidgetUpdater.refreshAll(this@WidgetConfigActivity)
                                    setResult(RESULT_OK, result); finish()
                                } catch (_: Exception) { saving = false; error = "Could not save the widget. Try again." }
                            }
                        }
                    })
                else Box(Modifier.fillMaxSize().background(UsageColors.Background).safeDrawingPadding(), contentAlignment = Alignment.Center) {
                    if (error == null) CircularProgressIndicator() else Text(error!!)
                }
            }
        }
    }
}

@Composable
private fun WidgetConfigScreen(accounts: List<AccountUsage>, initial: WidgetConfigEntity?, kind: String,
    saving: Boolean, error: String?, onCancel: () -> Unit,
    onSave: (WidgetScope, String?, String?, Boolean, List<String>) -> Unit) {
    var scopeName by rememberSaveable { mutableStateOf(WidgetScope.fromName(initial?.scope).name) }
    var accountId by rememberSaveable { mutableStateOf(initial?.accountId) }
    var providerId by rememberSaveable { mutableStateOf(initial?.provider) }
    var transparent by rememberSaveable { mutableStateOf(initial?.transparent ?: false) }
    val savedIds = remember(initial) { runCatching {
        val a = JSONArray(initial?.customAccountIdsJson ?: "[]"); (0 until a.length()).map { a.getString(it) }
    }.getOrDefault(emptyList()) }
    var selected by rememberSaveable { mutableStateOf(savedIds) }
    var order by rememberSaveable { mutableStateOf(savedIds) }
    val currentOrder = order.filter { id -> accounts.any { it.account.localId == id } } + accounts.map { it.account.localId }.filter { it !in order }
    val liveOrder by rememberUpdatedState(currentOrder)
    val scope = WidgetScope.fromName(scopeName)
    val preview = WidgetDataBuilder.build(accounts, System.currentTimeMillis(), scope, accountId, providerId,
        customAccountIds = currentOrder.filter { it in selected })
    val valid = when (scope) {
        WidgetScope.ACCOUNT -> accounts.any { it.account.localId == accountId }
        WidgetScope.PROVIDER -> accounts.any { it.account.provider.id == providerId }
        WidgetScope.CUSTOM -> preview.accounts.isNotEmpty()
        else -> true
    }
    fun move(id: String, direction: Int) {
        val ids = liveOrder.toMutableList(); val from = ids.indexOf(id)
        if (from < 0) return
        val to = (from + direction).coerceIn(0, ids.lastIndex)
        if (from != to) { ids.add(to, ids.removeAt(from)); order = ids }
    }
    Scaffold(containerColor = UsageColors.Background, bottomBar = {
        Row(Modifier.fillMaxWidth().background(UsageColors.Surface).navigationBarsPadding().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(onClick = onCancel, enabled = !saving) { Text("Cancel") }
            Button(modifier = Modifier.weight(1f), enabled = valid && !saving,
                onClick = { onSave(scope, accountId, providerId, transparent, currentOrder.filter { it in selected }) }) {
                Text(if (saving) "Saving…" else "Save widget")
            }
        }
    }) { inset ->
        LazyColumn(Modifier.fillMaxSize().padding(inset), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { Text(kind, style = MaterialTheme.typography.headlineLarge, color = UsageColors.TextPrimary) }
            item { ContentPreview(preview, kind, transparent) }
            item { UsageCard { ToggleRow("Transparent background", "Show your wallpaper behind the whole widget", transparent) { transparent = it } } }
            item { SectionHeader("Widget content") }
            item { ScopeChoice("Closest Resets", "Automatically shows whatever account resets next", scope == WidgetScope.CLOSEST_RESETS) { scopeName = WidgetScope.CLOSEST_RESETS.name } }
            item { ScopeChoice("All accounts", "Same order as Overview", scope == WidgetScope.ALL_ACCOUNTS) { scopeName = WidgetScope.ALL_ACCOUNTS.name } }
            item { SectionHeader("One provider") }
            items(accounts.map { it.account.provider }.distinct(), key = { "provider-${it.id}" }) { provider ->
                ScopeChoice(provider.displayName, "All ${provider.displayName} accounts", scope == WidgetScope.PROVIDER && providerId == provider.id, provider) {
                    providerId = provider.id; scopeName = WidgetScope.PROVIDER.name
                }
            }
            if (kind != "Usage Summary") {
                item { ScopeChoice("Custom", "Choose accounts and drag to reorder", scope == WidgetScope.CUSTOM) { scopeName = WidgetScope.CUSTOM.name } }
                if (scope == WidgetScope.CUSTOM) items(currentOrder, key = { "custom-$it" }) { id ->
                    val account = accounts.first { it.account.localId == id }.account
                    val step = with(LocalDensity.current) { 78.dp.toPx() }
                    Row(Modifier.fillMaxWidth().height(68.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(id in selected, onCheckedChange = { selected = if (it) selected + id else selected - id })
                        ProviderLogo(account.provider, Modifier.size(24.dp))
                        Column(Modifier.weight(1f).padding(start = 10.dp)) {
                            Text(account.label, maxLines = 1, color = UsageColors.TextPrimary)
                            Text(account.provider.displayName, style = MaterialTheme.typography.bodySmall, color = UsageColors.TextSecondary)
                        }
                        TextButton(onClick = { move(id, -1) }, enabled = currentOrder.first() != id, modifier = Modifier.size(40.dp)) { Text("↑") }
                        TextButton(onClick = { move(id, 1) }, enabled = currentOrder.last() != id, modifier = Modifier.size(40.dp)) { Text("↓") }
                        Box(Modifier.size(44.dp).pointerInput(id) {
                            var travel = 0f
                            detectDragGesturesAfterLongPress(onDragStart = { travel = 0f }, onDrag = { change, amount ->
                                change.consume(); travel += amount.y
                                while (kotlin.math.abs(travel) >= step) {
                                    val direction = if (travel > 0) 1 else -1
                                    move(id, direction); travel -= direction * step
                                }
                            })
                        }, contentAlignment = Alignment.Center) { Text("≡", color = UsageColors.TextSecondary) }
                    }
                }
            }
            if (accounts.isNotEmpty()) {
                item { SectionHeader("One account") }
                items(accounts, key = { "account-${it.account.localId}" }) { usage ->
                    ScopeChoice(usage.account.label, usage.account.provider.displayName,
                        scope == WidgetScope.ACCOUNT && accountId == usage.account.localId, usage.account.provider) {
                        accountId = usage.account.localId; scopeName = WidgetScope.ACCOUNT.name
                    }
                }
            }
            error?.let { item { Text(it, color = UsageColors.RedText) } }
        }
    }
}

@Composable
private fun ScopeChoice(title: String, subtitle: String, selected: Boolean, provider: ProviderId? = null, onClick: () -> Unit) {
    UsageCard(modifier = Modifier.clickable(onClick = onClick)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (provider != null) { ProviderLogo(provider, Modifier.size(28.dp)); Spacer(Modifier.width(12.dp)) }
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = UsageColors.TextPrimary)
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = UsageColors.TextSecondary)
            }
            RadioButton(selected, onClick = onClick)
        }
    }
}

@Composable
private fun ContentPreview(snapshot: WidgetSnapshot, kind: String, transparent: Boolean) {
    Column(Modifier.fillMaxWidth().background(if (transparent) UsageColors.SurfaceElevated else UsageColors.Background).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Preview · ${snapshot.accountCount} selected", style = MaterialTheme.typography.labelMedium, color = UsageColors.TextSecondary)
        if (snapshot.accounts.isEmpty()) Text("Select accounts to see your widget", color = UsageColors.TextSecondary)
        else if (kind.contains("Rings")) Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            snapshot.accounts.take(4).forEach { account ->
                val row = account.rows.minByOrNull { it.remainingPercent ?: Double.MAX_VALUE }
                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(progress = { ((row?.remainingPercent ?: 0.0) / 100).toFloat() }, modifier = Modifier.fillMaxSize(), color = UsageColors.Terracotta, trackColor = UsageColors.ProgressTrack, strokeWidth = 4.dp)
                    ProviderId.fromId(account.providerId)?.let { ProviderLogo(it, Modifier.size(22.dp)) }
                }
            }
        } else snapshot.accounts.take(if (kind == "Usage Summary") 1 else 2).forEach { account ->
            Text(account.title, color = UsageColors.TextPrimary)
            account.rows.take(2).forEach { row ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(row.label, Modifier.width(64.dp), style = MaterialTheme.typography.bodySmall, color = UsageColors.TextSecondary)
                    LinearProgressIndicator(progress = { ((row.remainingPercent ?: 0.0) / 100).toFloat() }, modifier = Modifier.weight(1f), color = UsageColors.Terracotta, trackColor = UsageColors.ProgressTrack)
                    Text(com.usagelimits.core.model.percentLabel(row.remainingPercent), color = UsageColors.TextPrimary)
                }
            }
        }
    }
}
