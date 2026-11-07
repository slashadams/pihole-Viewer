package com.devadam.piholeviewer

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListsScreen(
    instances: List<PiHoleInstance>,
    onUntrustedCert: (UntrustedCertException) -> Unit
) {
    val scope = rememberCoroutineScope()

    var selectedInstanceIndex by remember { mutableStateOf(0) }
    val safeIndex = selectedInstanceIndex.coerceIn(0, (instances.size - 1).coerceAtLeast(0))
    val currentInstance = instances.getOrNull(safeIndex)

    var applyToAll by remember { mutableStateOf(true) }
    var lists by remember { mutableStateOf<List<PiHoleListEntry>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var isUpdatingGravity by remember { mutableStateOf(false) }
    var statusBanner by remember { mutableStateOf<String?>(null) }
    var promptGravityAfterChange by remember { mutableStateOf(false) }

    var showAddDialog by remember { mutableStateOf(false) }
    var pendingConfirmation by remember { mutableStateOf<PendingListAction?>(null) }

    fun targetInstances(): List<PiHoleInstance> {
        return if (applyToAll) {
            instances
        } else {
            listOfNotNull(currentInstance)
        }
    }

    fun targetLabel(): String {
        return if (applyToAll) {
            "ALL ${instances.size} instances (${instances.joinToString { it.name }})"
        } else {
            currentInstance?.name ?: "selected instance"
        }
    }

    fun loadLists() {
        val inst = currentInstance ?: return
        scope.launch {
            isLoading = true
            try {
                lists = PiHoleApiClient.fetchLists(inst)
                statusBanner = null
            } catch (certEx: UntrustedCertException) {
                onUntrustedCert(certEx)
            } catch (e: Exception) {
                statusBanner = "Error loading lists from ${inst.name}: ${e.message}"
            } finally {
                isLoading = false
            }
        }
    }

    fun executeConfirmedAction(action: PendingListAction) {
        val targets = targetInstances()
        scope.launch {
            isLoading = true
            statusBanner = null
            val results = targets.map { inst ->
                async {
                    try {
                        val ok = when (action) {
                            is PendingListAction.Add -> PiHoleApiClient.addList(
                                instance = inst,
                                address = action.address,
                                type = action.type,
                                comment = action.comment
                            )
                            is PendingListAction.Toggle -> PiHoleApiClient.updateList(
                                instance = inst,
                                entry = action.entry,
                                newEnabled = action.newEnabled
                            )
                            is PendingListAction.Delete -> PiHoleApiClient.deleteList(
                                instance = inst,
                                address = action.entry.address,
                                type = action.entry.type
                            )
                            is PendingListAction.Gravity -> {
                                isUpdatingGravity = true
                                val (ok, _) = PiHoleApiClient.runGravityUpdate(inst)
                                ok
                            }
                        }
                        inst.name to ok
                    } catch (certEx: UntrustedCertException) {
                        onUntrustedCert(certEx)
                        inst.name to false
                    } catch (e: Exception) {
                        inst.name to false
                    }
                }
            }.awaitAll()

            isUpdatingGravity = false
            val succeeded = results.count { it.second }
            statusBanner = "${action.summaryTitle}: succeeded on $succeeded/${targets.size} instance(s)."

            if (action !is PendingListAction.Gravity && succeeded > 0) {
                promptGravityAfterChange = true
            }
            loadLists()
        }
    }

    LaunchedEffect(safeIndex, instances) {
        loadLists()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .padding(top = 16.dp)
    ) {
        // Instance Selector + Apply to All Toggle Card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = "Viewing Lists From:",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    instances.forEachIndexed { index, inst ->
                        val selected = index == safeIndex
                        FilterChip(
                            selected = selected,
                            onClick = { selectedInstanceIndex = index },
                            label = {
                                Text(
                                    text = inst.name,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        )
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Apply changes to all instances",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = if (applyToAll) {
                                "Add, toggle, delete & gravity apply to all ${instances.size} Pi-holes"
                            } else {
                                "Changes apply only to ${currentInstance?.name ?: ""}"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = applyToAll,
                        onCheckedChange = { applyToAll = it }
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { showAddDialog = true },
                        modifier = Modifier.weight(1f),
                        enabled = !isLoading && !isUpdatingGravity
                    ) {
                        Text("➕ Add List")
                    }
                    Button(
                        onClick = {
                            pendingConfirmation = PendingListAction.Gravity
                        },
                        modifier = Modifier.weight(1f),
                        enabled = !isLoading && !isUpdatingGravity,
                        colors = ButtonDefaults.buttonColors(containerColor = PiHoleGreen)
                    ) {
                        Text(if (isUpdatingGravity) "Updating..." else "↻ Update Gravity")
                    }
                    OutlinedButton(
                        onClick = { loadLists() },
                        enabled = !isLoading && !isUpdatingGravity
                    ) {
                        Text("⟳")
                    }
                }
            }
        }

        // Status / Gravity Reminder Banner
        statusBanner?.let { msg ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = msg,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f)
                    )
                    if (promptGravityAfterChange) {
                        TextButton(
                            onClick = {
                                promptGravityAfterChange = false
                                pendingConfirmation = PendingListAction.Gravity
                            }
                        ) {
                            Text("Run Gravity Now")
                        }
                    }
                }
            }
        }

        if (isLoading && lists.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (lists.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No lists found on ${currentInstance?.name ?: "instance"}")
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(lists, key = { "${it.type}:${it.address}" }) { item ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                    Text(
                                        text = item.address,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                    if (item.comment.isNotBlank()) {
                                        Text(
                                            text = item.comment,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "Type: ${item.type.uppercase()} • Domains: ${item.numberDomains}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (item.type == "allow") PiHoleGreen else PiHoleRed
                                    )
                                }
                                Switch(
                                    checked = item.enabled,
                                    enabled = !isLoading,
                                    onCheckedChange = { newEnabled ->
                                        pendingConfirmation = PendingListAction.Toggle(
                                            entry = item,
                                            newEnabled = newEnabled
                                        )
                                    }
                                )
                                IconButton(
                                    enabled = !isLoading,
                                    onClick = {
                                        pendingConfirmation = PendingListAction.Delete(item)
                                    }
                                ) {
                                    Text("🗑️")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        AddAdlistDialog(
            targetDescription = targetLabel(),
            onDismiss = { showAddDialog = false },
            onSubmit = { address, type, comment ->
                showAddDialog = false
                pendingConfirmation = PendingListAction.Add(
                    address = address,
                    type = type,
                    comment = comment
                )
            }
        )
    }

    pendingConfirmation?.let { action ->
        AlertDialog(
            onDismissRequest = { pendingConfirmation = null },
            title = { Text("Confirm ${action.summaryTitle}") },
            text = {
                Text("${action.describe()}\n\nTarget: ${targetLabel()}")
            },
            confirmButton = {
                Button(
                    onClick = {
                        val toRun = action
                        pendingConfirmation = null
                        executeConfirmedAction(toRun)
                    }
                ) {
                    Text("Confirm")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingConfirmation = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

sealed class PendingListAction(val summaryTitle: String) {
    abstract fun describe(): String

    data class Add(
        val address: String,
        val type: String,
        val comment: String
    ) : PendingListAction("Add List") {
        override fun describe(): String =
            "Add ${type.uppercase()} list:\n$address" +
                if (comment.isNotBlank()) "\nComment: $comment" else ""
    }

    data class Toggle(
        val entry: PiHoleListEntry,
        val newEnabled: Boolean
    ) : PendingListAction(if (newEnabled) "Enable List" else "Disable List") {
        override fun describe(): String =
            "${if (newEnabled) "Enable" else "Disable"} list:\n${entry.address}"
    }

    data class Delete(
        val entry: PiHoleListEntry
    ) : PendingListAction("Delete List") {
        override fun describe(): String =
            "Permanently remove list:\n${entry.address}"
    }

    data object Gravity : PendingListAction("Gravity Update") {
        override fun describe(): String =
            "Trigger a Gravity update (/api/action/gravity) to download lists and rebuild the blocklist database."
    }
}

@Composable
fun AddAdlistDialog(
    targetDescription: String,
    onDismiss: () -> Unit,
    onSubmit: (address: String, type: String, comment: String) -> Unit
) {
    var address by remember { mutableStateOf("") }
    var comment by remember { mutableStateOf("") }
    var type by remember { mutableStateOf("block") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Adlist / Allowlist") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                OutlinedTextField(
                    value = address,
                    onValueChange = { address = it },
                    label = { Text("List URL") },
                    placeholder = { Text("https://raw.githubusercontent.com/...") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = comment,
                    onValueChange = { comment = it },
                    label = { Text("Comment (optional)") },
                    placeholder = { Text("StevenBlack hosts") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Type: ", style = MaterialTheme.typography.bodyMedium)
                    Spacer(modifier = Modifier.width(8.dp))
                    FilterChip(
                        selected = type == "block",
                        onClick = { type = "block" },
                        label = { Text("Blocklist") }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    FilterChip(
                        selected = type == "allow",
                        onClick = { type = "allow" },
                        label = { Text("Allowlist") }
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Will apply to: $targetDescription",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSubmit(address.trim(), type, comment.trim()) },
                enabled = address.isNotBlank()
            ) {
                Text("Next")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
