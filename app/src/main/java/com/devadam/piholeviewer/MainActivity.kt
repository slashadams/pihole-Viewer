// MainActivity.kt
package com.devadam.piholeviewer

import android.content.Context
import android.graphics.Color as AndroidColor
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

// Pi-hole Colors
val PiHoleRed = Color(0xFF96060C)
val PiHoleGreen = Color(0xFF3FB34F)
val PiHoleDark = Color(0xFF231F20)

private val PiHoleColorScheme = lightColorScheme(
    primary = PiHoleRed,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDAD9),
    onPrimaryContainer = Color(0xFF410003),
    secondary = PiHoleDark,
    onSecondary = Color.White,
    tertiary = PiHoleGreen,
    onTertiary = Color.White,
    error = Color(0xFFBA1A1A),
    background = Color(0xFFFCFDF6),
    onBackground = Color(0xFF1A1C18),
    surface = Color(0xFFFCFDF6),
    onSurface = Color(0xFF1A1C18),
)

@Composable
fun PiHoleTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = PiHoleColorScheme,
        content = content
    )
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(
                AndroidColor.TRANSPARENT,
                AndroidColor.TRANSPARENT
            )
        )
        setContent {
            PiHoleTheme {
                PiHoleApp()
            }
        }
    }
}

data class PiHoleInstance(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val url: String,
    val password: String,
    val pinnedCertSha256: String? = null,
    val sessionToken: String? = null,
    val isOnline: Boolean = false,
    val stats: PiHoleStats? = null,
    val lastError: String? = null
)

data class PiHoleStats(
    val domainsBlocked: Int = 0,
    val dnsQueries: Int = 0,
    val queriesBlocked: Int = 0,
    val percentBlocked: Float = 0f,
    val status: String = "unknown"
)

data class QueryLogEntry(
    val timestampSeconds: Long = 0L,
    val time: String,
    val domain: String,
    val client: String,
    val status: String,
    val instanceName: String
)

/**
 * Preferences Manager with Android Keystore AES-256-GCM encryption for passwords.
 * Session tokens are kept in-memory in PiHoleApiClient rather than persisted to disk.
 */
class PreferencesManager(context: Context) {
    private val prefs = context.getSharedPreferences("pihole_prefs", Context.MODE_PRIVATE)

    fun saveInstances(instances: List<PiHoleInstance>) {
        val json = JSONArray()
        instances.forEach { instance ->
            json.put(JSONObject().apply {
                put("id", instance.id)
                put("name", instance.name)
                put("url", sanitizeUrl(instance.url))
                put("passwordEnc", CryptoHelper.encrypt(instance.password))
                if (!instance.pinnedCertSha256.isNullOrBlank()) {
                    put("pinnedCertSha256", instance.pinnedCertSha256)
                }
            })
        }
        prefs.edit().putString("instances", json.toString()).apply()
    }

    fun loadInstances(): List<PiHoleInstance> {
        val jsonString = prefs.getString("instances", null) ?: return emptyList()
        val json = JSONArray(jsonString)
        var needsResave = false
        val loaded = (0 until json.length()).map { i ->
            val obj = json.getJSONObject(i)
            val password = if (obj.has("passwordEnc")) {
                CryptoHelper.decrypt(obj.getString("passwordEnc"))
            } else {
                // One-time migration from legacy plaintext "password" field
                needsResave = true
                obj.optString("password", "")
            }
            val rawUrl = obj.getString("url")
            val cleanUrl = sanitizeUrl(rawUrl)
            if (cleanUrl != rawUrl) {
                needsResave = true
            }
            PiHoleInstance(
                id = obj.getString("id"),
                name = obj.getString("name"),
                url = cleanUrl,
                password = password,
                pinnedCertSha256 = obj.optString("pinnedCertSha256", "").takeIf { it.isNotBlank() }
            )
        }
        if (needsResave) {
            saveInstances(loaded)
        }
        return loaded
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PiHoleApp() {
    val context = LocalContext.current
    val prefsManager = remember { PreferencesManager(context) }
    val scope = rememberCoroutineScope()

    var instances by remember { mutableStateOf(prefsManager.loadInstances()) }
    var selectedTab by remember { mutableStateOf(0) }
    var showAddDialog by remember { mutableStateOf(false) }
    var pendingCertPrompt by remember { mutableStateOf<UntrustedCertException?>(null) }

    // Load instances on start
    LaunchedEffect(Unit) {
        instances = prefsManager.loadInstances()
        if (instances.isEmpty()) {
            showAddDialog = true
        }
    }

    // Save instances whenever configuration fields change
    LaunchedEffect(instances) {
        prefsManager.saveInstances(instances)
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text("Pi-hole Control", color = Color.White) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = Color.White,
                    actionIconContentColor = Color.White
                ),
                actions = {
                    if (instances.isNotEmpty()) {
                        TextButton(
                            onClick = {
                                scope.launch {
                                    instances.forEach { PiHoleApiClient.logout(it) }
                                    instances = instances.map { it.copy(isOnline = false, stats = null, sessionToken = null) }
                                }
                            }
                        ) {
                            Text("Logout", color = Color.White)
                        }
                    }
                    IconButton(onClick = { showAddDialog = true }) {
                        Text("➕", color = Color.White)
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
        ) {
            if (instances.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("No Pi-hole instances configured")
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(onClick = { showAddDialog = true }) {
                            Text("Add Pi-hole")
                        }
                    }
                }
            } else {
                ScrollableTabRow(
                    selectedTabIndex = selectedTab,
                    edgePadding = 8.dp,
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = Color.White,
                    indicator = { tabPositions ->
                        TabRowDefaults.SecondaryIndicator(
                            Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                            color = Color.White
                        )
                    }
                ) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = { Text("Dashboard", color = Color.White) }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = { Text("Lists", color = Color.White) }
                    )
                    Tab(
                        selected = selectedTab == 2,
                        onClick = { selectedTab = 2 },
                        text = { Text("Instances", color = Color.White) }
                    )
                    Tab(
                        selected = selectedTab == 3,
                        onClick = { selectedTab = 3 },
                        text = { Text("Query Log", color = Color.White) }
                    )
                }

                when (selectedTab) {
                    0 -> DashboardScreen(
                        instances = instances,
                        onInstancesUpdated = { instances = it },
                        onUntrustedCert = { pendingCertPrompt = it }
                    )
                    1 -> ListsScreen(
                        instances = instances,
                        onUntrustedCert = { pendingCertPrompt = it }
                    )
                    2 -> InstancesScreen(
                        instances = instances,
                        onInstancesUpdated = { instances = it },
                        onUntrustedCert = { pendingCertPrompt = it }
                    )
                    3 -> QueryLogScreen(
                        instances = instances,
                        onUntrustedCert = { pendingCertPrompt = it }
                    )
                }
            }
        }
    }

    if (showAddDialog) {
        AddInstanceDialog(
            onDismiss = { showAddDialog = false },
            onAdd = { name, url, password, pinnedCert ->
                instances = instances + PiHoleInstance(
                    name = name,
                    url = sanitizeUrl(url),
                    password = password,
                    pinnedCertSha256 = pinnedCert.takeIf { it.isNotBlank() }
                )
                showAddDialog = false
            }
        )
    }

    // Per-instance Certificate Pinning Dialog (Trust-On-First-Use)
    pendingCertPrompt?.let { certInfo ->
        AlertDialog(
            onDismissRequest = { pendingCertPrompt = null },
            title = { Text("Trust Certificate for ${certInfo.instanceName}?") },
            text = {
                Column {
                    Text(
                        text = "This Pi-hole presented a self-signed or untrusted HTTPS certificate. " +
                            "Would you like to pin this exact certificate for ${certInfo.instanceName} only?",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "SHA-256 Fingerprint:",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = certInfo.fingerprintSha256,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Subject: ${certInfo.subjectDn}",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        instances = instances.map {
                            if (it.id == certInfo.instanceId) {
                                it.copy(pinnedCertSha256 = certInfo.fingerprintSha256)
                            } else it
                        }
                        pendingCertPrompt = null
                    }
                ) {
                    Text("Pin & Trust Certificate")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingCertPrompt = null }) {
                    Text("Reject")
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddInstanceDialog(
    onDismiss: () -> Unit,
    onAdd: (name: String, url: String, password: String, pinnedCertSha256: String) -> Unit,
    existingInstance: PiHoleInstance? = null
) {
    var name by remember { mutableStateOf(existingInstance?.name ?: "") }
    var url by remember { mutableStateOf(existingInstance?.url ?: "") }
    var password by remember { mutableStateOf(existingInstance?.password ?: "") }
    var pinnedCert by remember { mutableStateOf(existingInstance?.pinnedCertSha256 ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existingInstance != null) "Edit Pi-hole" else "Add Pi-hole") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    placeholder = { Text("Primary Pi-hole") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("URL") },
                    placeholder = { Text("https://192.168.1.100") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Web Admin Password") },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = pinnedCert,
                    onValueChange = { pinnedCert = it },
                    label = { Text("Pinned Cert SHA-256 (Optional)") },
                    placeholder = { Text("Leave blank to prompt on first HTTPS connect") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Passwords are encrypted with the Android Keystore. For HTTPS self-signed certs, you will be asked to confirm & pin the certificate on first connect.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onAdd(name, url, password, pinnedCert) },
                enabled = name.isNotBlank() && url.isNotBlank() && password.isNotBlank()
            ) {
                Text(if (existingInstance != null) "Save" else "Add")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

private data class PendingBlockingAction(
    val title: String,
    val message: String,
    val enable: Boolean,
    val durationSeconds: Int? = null,
    val targetInstance: PiHoleInstance? = null // null = all online instances
)

@Composable
fun DashboardScreen(
    instances: List<PiHoleInstance>,
    onInstancesUpdated: (List<PiHoleInstance>) -> Unit,
    onUntrustedCert: (UntrustedCertException) -> Unit
) {
    var updatedInstances by remember { mutableStateOf(instances) }
    var isLoading by remember { mutableStateOf(true) }
    var showPauseDropdown by remember { mutableStateOf(false) }
    var pendingAction by remember { mutableStateOf<PendingBlockingAction?>(null) }
    val scope = rememberCoroutineScope()

    fun loadAllStats() {
        scope.launch {
            isLoading = true
            val results = updatedInstances.map { instance ->
                async {
                    try {
                        val stats = PiHoleApiClient.fetchStatsAndStatus(instance)
                        instance.copy(
                            isOnline = true,
                            stats = stats,
                            sessionToken = PiHoleApiClient.getCachedSession(instance.id),
                            lastError = null
                        )
                    } catch (certEx: UntrustedCertException) {
                        onUntrustedCert(certEx)
                        instance.copy(
                            isOnline = false,
                            stats = null,
                            lastError = "Untrusted HTTPS certificate (tap Refresh to review & pin)"
                        )
                    } catch (e: Exception) {
                        Log.e("PiHole", "Error loading stats for ${instance.name}", e)
                        instance.copy(
                            isOnline = false,
                            stats = null,
                            lastError = e.message ?: e.javaClass.simpleName
                        )
                    }
                }
            }.awaitAll()
            updatedInstances = results
            onInstancesUpdated(results)
            isLoading = false
        }
    }

    fun executeToggleAll(enable: Boolean, duration: Int? = null) {
        scope.launch {
            isLoading = true
            updatedInstances.filter { it.isOnline }.map { instance ->
                async {
                    try {
                        PiHoleApiClient.setBlockingStatus(instance, enable, duration)
                    } catch (certEx: UntrustedCertException) {
                        onUntrustedCert(certEx)
                    } catch (e: Exception) {
                        Log.e("PiHole", "Toggle failed for ${instance.name}", e)
                    }
                }
            }.awaitAll()
            delay(600)
            loadAllStats()
        }
    }

    LaunchedEffect(
        instances.map { "${it.id}:${it.name}:${it.url}:${it.password}:${it.pinnedCertSha256}" }
    ) {
        updatedInstances = instances
        loadAllStats()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .padding(top = 16.dp)
    ) {
        val totalQueries = updatedInstances.sumOf { it.stats?.dnsQueries ?: 0 }
        val totalBlocked = updatedInstances.sumOf { it.stats?.queriesBlocked ?: 0 }
        val combinedPercent = if (totalQueries > 0) {
            (totalBlocked.toFloat() / totalQueries.toFloat()) * 100f
        } else 0f
        val onlineCount = updatedInstances.count { it.isOnline }

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "All Instances",
                        style = MaterialTheme.typography.titleLarge
                    )
                    Text(
                        text = "$onlineCount/${instances.size} Online",
                        style = MaterialTheme.typography.titleMedium,
                        color = if (onlineCount == instances.size)
                            PiHoleGreen
                        else
                            MaterialTheme.colorScheme.error
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Button(
                        onClick = {
                            pendingAction = PendingBlockingAction(
                                title = "Enable Blocking on All Instances?",
                                message = "This will enable DNS blocking on all $onlineCount online Pi-hole instance(s).",
                                enable = true
                            )
                        },
                        modifier = Modifier.weight(1f).padding(end = 4.dp),
                        enabled = !isLoading && onlineCount > 0,
                        colors = ButtonDefaults.buttonColors(containerColor = PiHoleGreen)
                    ) {
                        Text("Enable All")
                    }
                    Button(
                        onClick = {
                            pendingAction = PendingBlockingAction(
                                title = "Disable Blocking Indefinitely?",
                                message = "This will disable DNS blocking on all $onlineCount online Pi-hole instance(s) until you re-enable it.",
                                enable = false
                            )
                        },
                        modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
                        enabled = !isLoading && onlineCount > 0
                    ) {
                        Text("Disable All")
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Box(modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = { showPauseDropdown = true },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isLoading && onlineCount > 0,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                    ) {
                        Text("Pause All...")
                    }
                    DropdownMenu(
                        expanded = showPauseDropdown,
                        onDismissRequest = { showPauseDropdown = false }
                    ) {
                        listOf(5, 15, 30, 45, 60, 90).forEach { mins ->
                            DropdownMenuItem(
                                text = { Text("$mins Minutes") },
                                onClick = {
                                    showPauseDropdown = false
                                    pendingAction = PendingBlockingAction(
                                        title = "Pause All Instances for $mins Minutes?",
                                        message = "DNS blocking will be paused for $mins minutes on all $onlineCount online Pi-hole instance(s).",
                                        enable = false,
                                        durationSeconds = mins * 60
                                    )
                                }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Button(
                    onClick = { loadAllStats() },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isLoading
                ) {
                    Text(if (isLoading) "Loading..." else "⟳ Refresh All")
                }
            }
        }

        if (isLoading && updatedInstances.all { it.stats == null }) {
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {
                item {
                    StatsCard("Combined Blocked", "${String.format("%.1f", combinedPercent)}%")
                    StatsCard("Total Queries", totalQueries.toString())
                    StatsCard("Total Blocked", totalBlocked.toString())

                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Individual Instances",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }

                items(updatedInstances) { instance ->
                    InstanceStatsCard(instance)
                }
            }
        }
    }

    pendingAction?.let { action ->
        AlertDialog(
            onDismissRequest = { pendingAction = null },
            title = { Text(action.title) },
            text = { Text(action.message) },
            confirmButton = {
                Button(
                    onClick = {
                        pendingAction = null
                        executeToggleAll(action.enable, action.durationSeconds)
                    }
                ) {
                    Text("Confirm")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingAction = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun InstanceStatsCard(instance: PiHoleInstance) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = instance.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = if (instance.isOnline) "●" else "○",
                    style = MaterialTheme.typography.titleLarge,
                    color = if (instance.isOnline)
                        PiHoleGreen
                    else
                        MaterialTheme.colorScheme.error
                )
            }

            if (instance.isOnline && instance.stats != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            text = "Status: ${instance.stats.status.uppercase()}",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (instance.stats.status.contains("enabled", ignoreCase = true)) PiHoleGreen else MaterialTheme.colorScheme.error
                        )
                        Text(
                            text = "Blocked: ${String.format("%.1f", instance.stats.percentBlocked)}%",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = "Queries: ${instance.stats.dnsQueries}",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            text = "Blocked: ${instance.stats.queriesBlocked}",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            } else if (!instance.isOnline) {
                Text(
                    text = if (!instance.lastError.isNullOrBlank()) "Offline — ${instance.lastError}" else "Offline",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
fun InstancesScreen(
    instances: List<PiHoleInstance>,
    onInstancesUpdated: (List<PiHoleInstance>) -> Unit,
    onUntrustedCert: (UntrustedCertException) -> Unit
) {
    var updatedInstances by remember { mutableStateOf(instances) }
    var showEditDialog by remember { mutableStateOf<PiHoleInstance?>(null) }
    var instanceToDelete by remember { mutableStateOf<PiHoleInstance?>(null) }
    var pendingBlocking by remember { mutableStateOf<PendingBlockingAction?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun loadAllStats() {
        scope.launch {
            isLoading = true
            val results = updatedInstances.map { instance ->
                async {
                    try {
                        val stats = PiHoleApiClient.fetchStatsAndStatus(instance)
                        instance.copy(
                            isOnline = true,
                            stats = stats,
                            sessionToken = PiHoleApiClient.getCachedSession(instance.id),
                            lastError = null
                        )
                    } catch (certEx: UntrustedCertException) {
                        onUntrustedCert(certEx)
                        instance.copy(
                            isOnline = false,
                            stats = null,
                            lastError = "Untrusted HTTPS certificate"
                        )
                    } catch (e: Exception) {
                        instance.copy(
                            isOnline = false,
                            stats = null,
                            lastError = e.message ?: e.javaClass.simpleName
                        )
                    }
                }
            }.awaitAll()
            updatedInstances = results
            onInstancesUpdated(results)
            isLoading = false
        }
    }

    fun executeToggleInstance(instance: PiHoleInstance, enable: Boolean, duration: Int? = null) {
        scope.launch {
            try {
                PiHoleApiClient.setBlockingStatus(instance, enable, duration)
                delay(600)
                loadAllStats()
            } catch (certEx: UntrustedCertException) {
                onUntrustedCert(certEx)
            } catch (e: Exception) {
                Log.e("PiHole", "Toggle failed for ${instance.name}", e)
            }
        }
    }

    LaunchedEffect(
        instances.map { "${it.id}:${it.name}:${it.url}:${it.password}:${it.pinnedCertSha256}" }
    ) {
        updatedInstances = instances
        loadAllStats()
    }

    Column(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp)
        ) {
            items(updatedInstances) { instance ->
                var showPauseDropdown by remember { mutableStateOf(false) }
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = instance.name,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = instance.url,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (!instance.pinnedCertSha256.isNullOrBlank()) {
                                    Text(
                                        text = "🔒 Cert Pinned (${instance.pinnedCertSha256.take(14)}…)",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = PiHoleGreen
                                    )
                                }
                                Text(
                                    text = if (instance.isOnline) {
                                        "Online"
                                    } else if (!instance.lastError.isNullOrBlank()) {
                                        "Offline — ${instance.lastError}"
                                    } else {
                                        "Offline"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (instance.isOnline)
                                        PiHoleGreen
                                    else
                                        MaterialTheme.colorScheme.error
                                )
                            }

                            Column {
                                IconButton(onClick = { showEditDialog = instance }) {
                                    Text("✏️")
                                }
                                IconButton(
                                    onClick = { instanceToDelete = instance }
                                ) {
                                    Text("🗑️")
                                }
                            }
                        }

                        if (instance.isOnline && instance.stats != null) {
                            Spacer(modifier = Modifier.height(12.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Button(
                                    onClick = {
                                        pendingBlocking = PendingBlockingAction(
                                            title = "Enable Blocking on ${instance.name}?",
                                            message = "Enable DNS blocking on ${instance.name}.",
                                            enable = true,
                                            targetInstance = instance
                                        )
                                    },
                                    enabled = !instance.stats.status.contains("enabled", ignoreCase = true) && !isLoading,
                                    modifier = Modifier.weight(1f).padding(end = 4.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = PiHoleGreen)
                                ) {
                                    Text("Enable")
                                }
                                Button(
                                    onClick = {
                                        pendingBlocking = PendingBlockingAction(
                                            title = "Disable Blocking on ${instance.name}?",
                                            message = "Disable DNS blocking indefinitely on ${instance.name}.",
                                            enable = false,
                                            targetInstance = instance
                                        )
                                    },
                                    enabled = !instance.stats.status.contains("disabled", ignoreCase = true) && !isLoading,
                                    modifier = Modifier.weight(1f).padding(horizontal = 4.dp)
                                ) {
                                    Text("Disable")
                                }
                                Box(modifier = Modifier.weight(1f).padding(start = 4.dp)) {
                                    Button(
                                        onClick = { showPauseDropdown = true },
                                        enabled = !instance.stats.status.contains("disabled", ignoreCase = true) && !isLoading,
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                                        contentPadding = PaddingValues(0.dp)
                                    ) {
                                        Text("Pause")
                                    }
                                    DropdownMenu(
                                        expanded = showPauseDropdown,
                                        onDismissRequest = { showPauseDropdown = false }
                                    ) {
                                        listOf(5, 15, 30, 45, 60, 90).forEach { mins ->
                                            DropdownMenuItem(
                                                text = { Text("$mins Min") },
                                                onClick = {
                                                    showPauseDropdown = false
                                                    pendingBlocking = PendingBlockingAction(
                                                        title = "Pause ${instance.name} for $mins Minutes?",
                                                        message = "DNS blocking on ${instance.name} will be paused for $mins minutes.",
                                                        enable = false,
                                                        durationSeconds = mins * 60,
                                                        targetInstance = instance
                                                    )
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    showEditDialog?.let { instance ->
        AddInstanceDialog(
            onDismiss = { showEditDialog = null },
            onAdd = { name, url, password, pinnedCert ->
                scope.launch {
                    PiHoleApiClient.logout(instance)
                    onInstancesUpdated(
                        instances.map {
                            if (it.id == instance.id) {
                                it.copy(
                                    name = name,
                                    url = sanitizeUrl(url),
                                    password = password,
                                    pinnedCertSha256 = pinnedCert.takeIf { c -> c.isNotBlank() },
                                    sessionToken = null
                                )
                            } else it
                        }
                    )
                    showEditDialog = null
                }
            },
            existingInstance = instance
        )
    }

    instanceToDelete?.let { instance ->
        AlertDialog(
            onDismissRequest = { instanceToDelete = null },
            title = { Text("Remove ${instance.name}?") },
            text = { Text("Are you sure you want to remove ${instance.name} (${instance.url}) from the app? The app will also log out of its current session.") },
            confirmButton = {
                Button(
                    onClick = {
                        val target = instance
                        instanceToDelete = null
                        scope.launch {
                            PiHoleApiClient.logout(target)
                            onInstancesUpdated(instances.filter { it.id != target.id })
                        }
                    }
                ) {
                    Text("Remove")
                }
            },
            dismissButton = {
                TextButton(onClick = { instanceToDelete = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    pendingBlocking?.let { action ->
        AlertDialog(
            onDismissRequest = { pendingBlocking = null },
            title = { Text(action.title) },
            text = { Text(action.message) },
            confirmButton = {
                Button(
                    onClick = {
                        val target = action.targetInstance
                        pendingBlocking = null
                        if (target != null) {
                            executeToggleInstance(target, action.enable, action.durationSeconds)
                        }
                    }
                ) {
                    Text("Confirm")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingBlocking = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun QueryLogScreen(
    instances: List<PiHoleInstance>,
    onUntrustedCert: (UntrustedCertException) -> Unit
) {
    var queryLog by remember { mutableStateOf<List<QueryLogEntry>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()

    fun loadQueryLogs() {
        scope.launch {
            isLoading = true
            val allLogs = instances.map { instance ->
                async {
                    try {
                        PiHoleApiClient.fetchQueryLog(instance, limit = 20)
                    } catch (certEx: UntrustedCertException) {
                        onUntrustedCert(certEx)
                        emptyList()
                    } catch (e: Exception) {
                        emptyList()
                    }
                }
            }.awaitAll().flatten()

            queryLog = allLogs.sortedByDescending { it.timestampSeconds }
            isLoading = false
        }
    }

    LaunchedEffect(instances) {
        loadQueryLogs()
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Button(
            onClick = { loadQueryLogs() },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(top = 16.dp, bottom = 8.dp),
            enabled = !isLoading
        ) {
            Text(if (isLoading) "Loading..." else "⟳ Refresh")
        }

        if (isLoading && queryLog.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else if (queryLog.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text("No query log entries")
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp)
            ) {
                items(queryLog) { entry ->
                    QueryLogItem(entry)
                }
            }
        }
    }
}

@Composable
fun QueryLogItem(entry: QueryLogEntry) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = entry.domain,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = entry.status,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (entry.status.startsWith("Blocked", ignoreCase = true))
                        MaterialTheme.colorScheme.error
                    else
                        PiHoleGreen
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = entry.instanceName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = entry.client,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = entry.time,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun StatsCard(label: String, value: String) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = value,
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                color = if (label.contains("Blocked")) PiHoleRed else MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

fun sanitizeUrl(url: String): String {
    var sanitized = url.trim()
    if (!sanitized.startsWith("http://", ignoreCase = true) &&
        !sanitized.startsWith("https://", ignoreCase = true)
    ) {
        sanitized = "https://$sanitized"
    }
    // Strip query string or fragment if user pasted a browser URL
    sanitized = sanitized.substringBefore("?").substringBefore("#").trimEnd('/')
    // Strip /admin(...) or /api(...) suffixes if user pasted the web admin address from their browser
    sanitized = sanitized.replace(Regex("/(admin|api)(/.*)?$", RegexOption.IGNORE_CASE), "")
    return sanitized.trimEnd('/')
}
