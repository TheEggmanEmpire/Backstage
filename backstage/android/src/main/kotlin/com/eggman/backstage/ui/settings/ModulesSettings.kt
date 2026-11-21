package com.eggman.backstage.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.eggman.backstage.exec.AppEntry
import com.eggman.backstage.tools.BrowserSearch
import com.eggman.backstage.tools.CalendarInfo
import com.eggman.backstage.tools.WebSearch
import com.eggman.backstage.tools.MemoryItem
import com.eggman.backstage.ui.NumberSetting
import com.eggman.backstage.ui.SettingToggle
import com.eggman.backstage.ui.ToggleRow
import com.eggman.backstage.ui.licenses.appVersionName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Weather, web search, alarms and calendar: the agent's optional tool modules. */
@Composable
internal fun ModulesSection(store: BackstageSettingsStore) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    SettingsSection("Modules", "Optional tools the agent can use. Each one contacts only the service named here, and only when you set it up.") {
        Text("Weather (OpenWeatherMap)", style = MaterialTheme.typography.labelLarge)
        var weatherKey by remember { mutableStateOf(store.weatherApiKey) }
        var location by remember { mutableStateOf(store.weatherDefaultLocation) }
        var units by remember { mutableStateOf(store.weatherUnits) }
        var weatherTest by remember { mutableStateOf<String?>(null) }
        OutlinedTextField(weatherKey, { weatherKey = it; store.weatherApiKey = it; weatherTest = null }, Modifier.fillMaxWidth(),
            label = { Text("OpenWeatherMap API key") }, singleLine = true, visualTransformation = PasswordVisualTransformation(),
            supportingText = { Text("Without a key the agent looks the weather up with web search") })
        OutlinedTextField(location, { location = it; store.weatherDefaultLocation = it }, Modifier.fillMaxWidth(),
            label = { Text("Home location") }, singleLine = true, supportingText = { Text("Used when you do not name a place, e.g. \"Berlin, DE\"") })
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(units == "metric", { units = "metric"; store.weatherUnits = units }, { Text("°C, m/s") })
            FilterChip(units == "imperial", { units = "imperial"; store.weatherUnits = units }, { Text("°F, mph") })
            OutlinedButton(enabled = weatherKey.isNotBlank() && location.isNotBlank(), onClick = {
                weatherTest = "Testing…"
                scope.launch {
                    val r = withContext(Dispatchers.IO) {
                        runCatching { SettingsServices.httpGet(ModuleSettingsLogic.weatherTestUrl(weatherKey, location, units)) }.getOrNull()
                    }
                    weatherTest = ModuleSettingsLogic.weatherTestResult(r, units)
                }
            }) { Text("Test") }
        }
        weatherTest?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

        HorizontalDivider()
        Text("Web search", style = MaterialTheme.typography.labelLarge)
        var provider by remember { mutableStateOf(store.searchProvider) }
        var searchKey by remember { mutableStateOf(store.searchApiKey) }
        var searxng by remember { mutableStateOf(store.searxngUrl) }
        var customUrl by remember { mutableStateOf(store.customSearchUrl) }
        var headerName by remember { mutableStateOf(store.customSearchHeaderName) }
        var headerValue by remember { mutableStateOf(store.customSearchHeaderValue) }
        var searchTest by remember { mutableStateOf<String?>(null) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            CoreSettingsStore.SEARCH_PROVIDERS.forEach { p ->
                FilterChip(provider == p, { provider = p; store.searchProvider = p; searchTest = null }, { Text(ModuleSettingsLogic.searchLabel(p)) })
            }
        }
        if (ModuleSettingsLogic.searchNeedsKey(provider)) {
            OutlinedTextField(searchKey, { searchKey = it; store.searchApiKey = it; searchTest = null }, Modifier.fillMaxWidth(),
                label = { Text("${ModuleSettingsLogic.searchLabel(provider)} API key") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                supportingText = if (provider == "kagi") { { Text("From kagi.com/api/keys; searches are billed to your Kagi API balance") } } else null)
        }
        if (provider == "searxng") {
            OutlinedTextField(searxng, { searxng = it; store.searxngUrl = it }, Modifier.fillMaxWidth(),
                label = { Text("SearXNG address") }, singleLine = true, placeholder = { Text("https://search.example.org") })
        }
        if (provider == "custom") {
            OutlinedTextField(customUrl, { customUrl = it; store.customSearchUrl = it; searchTest = null }, Modifier.fillMaxWidth(),
                label = { Text("Search URL with {query}") }, singleLine = true,
                placeholder = { Text("https://search.example.org/api?q={query}&n={count}") },
                supportingText = { Text("{query}: the search words (URL-encoded); {count}: how many results. JSON result lists and HTML result pages both work.") })
            OutlinedTextField(headerName, { headerName = it; store.customSearchHeaderName = it }, Modifier.fillMaxWidth(),
                label = { Text("Header name (optional)") }, singleLine = true, placeholder = { Text("Authorization") })
            OutlinedTextField(headerValue, { headerValue = it; store.customSearchHeaderValue = it }, Modifier.fillMaxWidth(),
                label = { Text("Header value (optional, stored encrypted)") }, singleLine = true, placeholder = { Text("Bearer …") },
                visualTransformation = PasswordVisualTransformation())
        }
        ModuleSettingsLogic.searchProblems(provider, searchKey, searxng, customUrl, headerName).forEach {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        ModuleSettingsLogic.searchWarnings(provider, customUrl).forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
        OutlinedButton(onClick = {
            searchTest = "Searching…"
            scope.launch {
                val cfg = ModuleSettingsLogic.searchTestConfig(provider, searchKey, searxng, customUrl, headerName, headerValue)
                val r = withContext(Dispatchers.IO) { runCatching { SettingsServices.webSearch(cfg, "weather") }.getOrNull() }
                searchTest = ModuleSettingsLogic.searchTestResult(provider, r, WebSearch.effectiveProvider(cfg))
            }
        }) { Text("Test search") }
        searchTest?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

        Text("Browser searches", style = MaterialTheme.typography.labelLarge)
        Text("When the agent shows you a search in your default browser, it uses this search engine.", style = MaterialTheme.typography.bodySmall)
        var browserUrl by remember { mutableStateOf(store.browserSearchUrl) }
        var browserCustom by remember { mutableStateOf(BrowserSearch.presetOf(store.browserSearchUrl) == null) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            BrowserSearch.PRESETS.forEach { p ->
                FilterChip(!browserCustom && browserUrl.trim() == p.template, {
                    browserCustom = false; browserUrl = p.template; store.browserSearchUrl = p.template
                }, { Text(p.label) })
            }
            FilterChip(browserCustom, { browserCustom = true }, { Text("Custom") })
        }
        if (browserCustom) {
            val problem = ModuleSettingsLogic.browserSearchProblem(browserUrl)
            OutlinedTextField(browserUrl, { browserUrl = it; store.browserSearchUrl = it }, Modifier.fillMaxWidth(),
                label = { Text("Browser search URL with {query}") }, singleLine = true, isError = problem != null,
                placeholder = { Text("https://search.example.org/search?q={query}") },
                supportingText = { Text(problem ?: "Empty = the browser's own search engine") })
        }

        HorizontalDivider()
        Text("Alarms and timers", style = MaterialTheme.typography.labelLarge)
        SettingToggle("Vibrate", null, { store.alarmVibrate }) { store.alarmVibrate = it }
        SettingToggle("Set without opening the clock app", null, { store.alarmSkipUi }) { store.alarmSkipUi = it }

        HorizontalDivider()
        Text("Calendar", style = MaterialTheme.typography.labelLarge)
        NumberSetting("Events per answer (1-100)", store.calendarMaxEvents, Modifier.fillMaxWidth()) { store.calendarMaxEvents = it }
        var reminder by remember { mutableStateOf(store.calendarDefaultReminder?.toString().orEmpty()) }
        OutlinedTextField(reminder, { t ->
            reminder = t.filter(Char::isDigit).take(5)
            store.calendarDefaultReminder = ModuleSettingsLogic.parseReminder(reminder, store.calendarDefaultReminder)
        }, Modifier.fillMaxWidth(), label = { Text("Reminder for new events, minutes before (empty = none)") }, singleLine = true)
        val calendars = remember { SettingsServices.calendars(context) }
        if (calendars.isEmpty()) {
            Text("Allow calendar access in the setup checklist to pick calendars.", style = MaterialTheme.typography.bodySmall)
        } else CalendarChoices(store, calendars)
    }
}

@Composable
private fun CalendarChoices(store: BackstageSettingsStore, calendars: List<CalendarInfo>) {
    var enabled by remember { mutableStateOf(store.calendarEnabledIds) }
    var default by remember { mutableStateOf(store.calendarDefaultId) }
    Text("Calendars the agent reads (none ticked = all)", style = MaterialTheme.typography.labelLarge)
    calendars.forEach { c ->
        ToggleRow(c.name, c.account, c.id in enabled) { on ->
            enabled = if (on) enabled + c.id else enabled - c.id
            store.calendarEnabledIds = enabled
        }
    }
    Text("New events go to", style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        calendars.filter { it.writable }.forEach { c ->
            FilterChip(default == c.id, { default = c.id; store.calendarDefaultId = c.id }, { Text(c.name) })
        }
    }
}

/** Remote MCP servers whose tools the agent may use, with a connection test. */
@Composable
internal fun RemoteMcpSection(store: BackstageSettingsStore) {
    val scope = rememberCoroutineScope()
    var servers by remember { mutableStateOf(store.remoteMcpServers) }
    var editing by remember { mutableStateOf<RemoteMcpServer?>(null) }
    val status = remember { mutableStateOf(mapOf<String, String>()) }
    /** Servers whose tool list is open: null while it loads. */
    val expanded = remember { mutableStateOf(mapOf<String, List<ModuleSettingsLogic.RemoteToolRow>?>()) }
    SettingsSection("Remote MCP servers", "Give the agent the tools of MCP servers you run or trust (Streamable HTTP, optional bearer token). " +
        "Their tools are offered to your AI provider with every task.") {
        if (servers.isEmpty()) Text("No servers yet.", style = MaterialTheme.typography.bodySmall)
        servers.forEach { s ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ToggleRow(s.name, s.url, s.enabled) { on ->
                    servers = servers.map { if (it.id == s.id) it.copy(enabled = on) else it }
                    store.remoteMcpServers = servers
                }
                status.value[s.id]?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = {
                        status.value = status.value + (s.id to "Connecting…")
                        scope.launch {
                            val line = withContext(Dispatchers.IO) { SettingsServices.probeMcp(s) }
                            status.value = status.value + (s.id to line)
                        }
                    }) { Text("Test") }
                    TextButton(onClick = {
                        if (expanded.value[s.id] != null) { expanded.value = expanded.value - s.id; return@TextButton }
                        expanded.value = expanded.value + (s.id to null)
                        status.value = status.value + (s.id to "Loading tools…")
                        scope.launch {
                            val names = withContext(Dispatchers.IO) { runCatching { SettingsServices.listMcpTools(s) } }
                            names.onSuccess { list ->
                                expanded.value = expanded.value + (s.id to ModuleSettingsLogic.remoteToolRows(s, list))
                                status.value = status.value - s.id
                                // Seen: the "new" marks shown this time are gone next time.
                                servers = ModuleSettingsLogic.replaceServer(servers, ModuleSettingsLogic.acknowledgeRemoteTools(s, list))
                                store.remoteMcpServers = servers
                            }.onFailure { status.value = status.value + (s.id to "Failed: ${it.message ?: it.javaClass.simpleName}"); expanded.value = expanded.value - s.id }
                        }
                    }) { Text("Tools") }
                    TextButton(onClick = { editing = s }) { Text("Edit") }
                    TextButton(onClick = {
                        servers = servers.filterNot { it.id == s.id }
                        store.remoteMcpServers = servers
                    }) { Text("Remove") }
                }
                expanded.value[s.id]?.let { rows ->
                    val current = servers.firstOrNull { it.id == s.id } ?: s
                    val names = rows.map { it.name }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = {
                            servers = ModuleSettingsLogic.replaceServer(servers, ModuleSettingsLogic.setAllRemoteTools(current, names, true)); store.remoteMcpServers = servers
                        }) { Text("All on") }
                        TextButton(onClick = {
                            servers = ModuleSettingsLogic.replaceServer(servers, ModuleSettingsLogic.setAllRemoteTools(current, names, false)); store.remoteMcpServers = servers
                        }) { Text("All off") }
                    }
                    if (rows.isEmpty()) Text("This server offers no tools.", style = MaterialTheme.typography.bodySmall)
                    rows.forEach { r ->
                        ToggleRow(r.name, if (r.isNew) "New" else null, r.name !in current.disabledTools) { on ->
                            servers = ModuleSettingsLogic.replaceServer(servers, ModuleSettingsLogic.setRemoteTool(current, r.name, on))
                            store.remoteMcpServers = servers
                        }
                    }
                }
            }
        }
        val e = editing
        if (e == null) OutlinedButton(onClick = { editing = RemoteMcpServer("", "", "https://") }) { Text("Add a server") }
        else key(e.id) {
            HorizontalDivider()
            RemoteServerEditor(e, onCancel = { editing = null }) { saved ->
                servers = ModuleSettingsLogic.upsertServer(servers, saved)
                store.remoteMcpServers = servers
                editing = null
            }
        }
    }
}

/** Inline editor (in the settings list, not a dialog) for one remote server. */
@Composable
private fun RemoteServerEditor(initial: RemoteMcpServer, onCancel: () -> Unit, onSave: (RemoteMcpServer) -> Unit) {
    var name by remember { mutableStateOf(initial.name) }
    var url by remember { mutableStateOf(initial.url) }
    var token by remember { mutableStateOf(initial.token) }
    val problem = ModuleSettingsLogic.urlProblem(url)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(if (initial.id.isEmpty()) "Add MCP server" else "Edit MCP server", style = MaterialTheme.typography.labelLarge)
        OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Name") }, singleLine = true)
        OutlinedTextField(url, { url = it }, Modifier.fillMaxWidth(), label = { Text("Server URL") }, singleLine = true,
            isError = problem != null, supportingText = { (problem ?: ModuleSettingsLogic.insecureWarning(url))?.let { Text(it) } })
        OutlinedTextField(token, { token = it }, Modifier.fillMaxWidth(), label = { Text("Bearer token (optional)") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = problem == null, onClick = { onSave(initial.copy(name = name, url = url, token = token)) }) { Text("Save") }
            TextButton(onClick = onCancel) { Text("Cancel") }
        }
    }
}

/** Per-app rules: instructions the agent sees while a given app is in front. */
@Composable
internal fun RulesSection(store: BackstageSettingsStore) {
    val context = LocalContext.current
    // Blank rules live only here until text is entered (the store drops blank values).
    var rules by remember { mutableStateOf(store.appRules) }
    var picking by remember { mutableStateOf(false) }
    val apps = remember { mutableStateOf<List<AppEntry>>(emptyList()) }
    LaunchedEffect(Unit) { apps.value = withContext(Dispatchers.IO) { SettingsServices.launchableApps(context) } }
    fun label(pkg: String) = apps.value.firstOrNull { it.packageName == pkg }?.label ?: pkg
    SettingsSection("Rules for apps", "Extra instructions the agent follows while a particular app is open, e.g. \"In Spotify, never start a paid trial\".") {
        if (rules.isEmpty()) Text("No app rules yet.", style = MaterialTheme.typography.bodySmall)
        rules.forEach { (pkg, text) ->
            key(pkg) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(label(pkg), style = MaterialTheme.typography.labelLarge)
                    OutlinedTextField(text, { t ->
                        rules = rules + (pkg to t.take(CoreSettingsStore.MAX_RULE))
                        store.appRules = rules
                    }, Modifier.fillMaxWidth(), minLines = 2, label = { Text("Rule for ${label(pkg)}") })
                    TextButton(onClick = { rules = rules - pkg; store.appRules = rules }) { Text("Remove rule") }
                }
            }
        }
        if (!picking) OutlinedButton(onClick = { picking = true }) { Text("Add a rule for an app") }
        else {
            HorizontalDivider()
            AppPicker(apps.value.filterNot { it.packageName in rules }, onCancel = { picking = false }) { app ->
                rules = rules + (app.packageName to "")
                picking = false
            }
        }
    }
}

/** Inline app picker with a search field (the settings page already scrolls, so it shows a bounded number of matches). */
@Composable
private fun AppPicker(apps: List<AppEntry>, onCancel: () -> Unit, onPick: (AppEntry) -> Unit) {
    var query by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Choose an app", style = MaterialTheme.typography.labelLarge)
        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), label = { Text("Search apps") }, singleLine = true)
        val shown = ModuleSettingsLogic.filterApps(apps, query)
        if (shown.isEmpty()) Text("No matching apps.", style = MaterialTheme.typography.bodySmall)
        shown.take(MAX_APPS_SHOWN).forEach { app ->
            Column(Modifier.fillMaxWidth().clickable { onPick(app) }.padding(vertical = 6.dp)) {
                Text(app.label, style = MaterialTheme.typography.bodyLarge)
                Text(app.packageName, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (shown.size > MAX_APPS_SHOWN) Text("${shown.size - MAX_APPS_SHOWN} more: type to narrow the list.", style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = onCancel) { Text("Cancel") }
    }
}

private const val MAX_APPS_SHOWN = 40

/** Long-term memory: on/off, browse, search, delete, export and import (local only). */
@Composable
internal fun MemorySection(store: BackstageSettingsStore) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var enabled by remember { mutableStateOf(store.memoryEnabled) }
    var refresh by remember { mutableIntStateOf(0) }
    var items by remember { mutableStateOf<List<MemoryItem>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    LaunchedEffect(enabled, refresh) {
        items = if (!enabled) emptyList()
        else withContext(Dispatchers.IO) { runCatching { SettingsServices.memory(context)?.all() }.getOrNull().orEmpty() }
    }
    fun withMemory(block: suspend (com.eggman.backstage.tools.MemoryService) -> String) {
        scope.launch {
            val mem = SettingsServices.memory(context)
            message = if (mem == null) "Memory is not available right now." else withContext(Dispatchers.IO) {
                runCatching { block(mem) }.getOrElse { "Failed: ${it.message}" }
            }
            refresh++
        }
    }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) withMemory { mem ->
            val json = ModuleSettingsLogic.exportMemories(mem, appVersionName(context) ?: "", System.currentTimeMillis())
            context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) } ?: error("could not write the file")
            "Exported ${mem.all().size} memories."
        }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) withMemory { mem ->
            val text = context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } ?: error("could not read the file")
            "Imported ${ModuleSettingsLogic.importMemories(mem, text)} new memories."
        }
    }
    SettingsSection("Memory", "Facts the agent saves to remember you (\"my gym is …\"). Stored only on this phone; relevant ones are " +
        "sent to your AI provider with a task.") {
        ToggleRow("Use memory", "Off: the agent neither saves nor looks up memories. Stored memories are kept.", enabled) {
            enabled = it; store.memoryEnabled = it
        }
        if (enabled) {
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), label = { Text("Search memories") }, singleLine = true)
            val shown = ModuleSettingsLogic.filterMemories(items, query)
            Text("${shown.size} of ${items.size} memories", style = MaterialTheme.typography.labelLarge)
            shown.take(MAX_SHOWN).forEach { m ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(m.content, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { withMemory { mem -> if (mem.delete(m.id)) "Deleted." else "Already gone." } }) { Text("Delete") }
                }
            }
            if (shown.size > MAX_SHOWN) Text("Refine the search to see the rest.", style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { exporter.launch("backstage-memories.json") }) { Text("Export") }
                OutlinedButton(onClick = { importer.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }) { Text("Import") }
                OutlinedButton(enabled = items.isNotEmpty(), onClick = { confirmClear = true }) { Text("Delete all") }
            }
        }
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        title = { Text("Delete all memories?") },
        text = { Text("The agent forgets everything it saved. Export first if you may want them back.") },
        confirmButton = {
            TextButton(onClick = {
                confirmClear = false
                withMemory { mem -> val all = mem.all(); all.forEach { mem.delete(it.id) }; "Deleted ${all.size} memories." }
            }) { Text("Delete all") }
        },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
    )
}

private const val MAX_SHOWN = 100
