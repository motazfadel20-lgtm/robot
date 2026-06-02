package com.robote.joe.mobile

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun CallInsightsScreen(viewModel: JoeViewModel, onClose: () -> Unit = {}) {
    val items by viewModel.callInsights.collectAsState()
    var selected by remember { mutableStateOf<CallInsightEntity?>(null) }
    var filter by remember { mutableStateOf("") }
    val snackbarHostState = remember { SnackbarHostState() }
    var dateFrom by remember { mutableStateOf("") }
    var dateTo by remember { mutableStateOf("") }
    var selectedKeyword by remember { mutableStateOf<String?>(null) }

    Scaffold(topBar = { SmallTopAppBar(title = { Text("تحليلات المكالمات") }) }, snackbarHost = { SnackbarHost(snackbarHostState) }) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize().padding(12.dp)) {
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = filter, onValueChange = { filter = it }, label = { Text("فلتر نصي") }, modifier = Modifier.weight(1f))
                    Button(onClick = { viewModel.syncCallInsights { snackbarHostState.showSnackbar("تمت المزامنة") } }) { Text("مزامنة") }
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = dateFrom, onValueChange = { dateFrom = it }, label = { Text("من (YYYY-MM-DD)") }, modifier = Modifier.weight(1f))
                    OutlinedTextField(value = dateTo, onValueChange = { dateTo = it }, label = { Text("إلى (YYYY-MM-DD)") }, modifier = Modifier.weight(1f))
                }
                Spacer(Modifier.height(8.dp))
                // keyword chips
                val keywords = remember(items) {
                    items.flatMap { item ->
                        try {
                            val json = org.json.JSONObject(item.insightsJson ?: "{}")
                            val arr = json.optJSONArray("keywords") ?: return@flatMap emptyList<String>()
                            (0 until arr.length()).map { i -> arr.optString(i) }
                        } catch (_: Exception) { emptyList() }
                    }.distinct()
                }
                if (keywords.isNotEmpty()) {
                    FlowRow(mainAxisSpacing = 8.dp, crossAxisSpacing = 8.dp) {
                        keywords.forEach { kw ->
                            AssistChip(onClick = { selectedKeyword = if (selectedKeyword == kw) null else kw }, label = { Text(kw) }, modifier = Modifier) }
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
            Spacer(Modifier.height(12.dp))
            LazyColumn {
                val displayed = items.filter { item ->
                    val matchesText = filter.isBlank() || (item.transcript ?: "").contains(filter, ignoreCase = true)
                    val matchesKeyword = selectedKeyword == null || try {
                        val j = org.json.JSONObject(item.insightsJson ?: "{}")
                        val arr = j.optJSONArray("keywords")
                        if (arr == null) false else (0 until arr.length()).any { arr.optString(it).equals(selectedKeyword, true) }
                    } catch (_: Exception) { false }
                    val matchesDateFrom = dateFrom.isBlank() || (item.createdAt ?: "").startsWith(dateFrom)
                    val matchesDateTo = dateTo.isBlank() || (item.createdAt ?: "").startsWith(dateTo)
                    matchesText && matchesKeyword && matchesDateFrom && matchesDateTo
                }
                items(displayed) { item ->
                    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                        Row(modifier = Modifier.padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column(modifier = Modifier.weight(1f).clickable { selected = item }) {
                                Text(item.transcript ?: "(بدون نص)")
                                Spacer(Modifier.height(6.dp))
                                Text(item.createdAt ?: "")
                            }
                            IconButton(onClick = {
                                // confirm delete
                                viewModel.deleteCallInsight(item.id) { ok ->
                                    // show snackbar
                                    val msg = if (ok) "تم الحذف" else "حذف محلي (فشل الحذف عن بعد)"
                                    LaunchedEffect(msg) { snackbarHostState.showSnackbar(msg) }
                                }
                            }) {
                                Icon(Icons.Filled.History, contentDescription = "حذف")
                            }
                        }
                    }
                }
            }
        }
    }

    if (selected != null) {
        AlertDialog(onDismissRequest = { selected = null }, confirmButton = { TextButton(onClick = { selected = null }) { Text("إغلاق") } }, title = { Text("تفاصيل التحليل") }, text = {
            Column {
                Text("نص التفريغ:")
                Text(selected?.transcript ?: "(فارغ)")
                Spacer(Modifier.height(8.dp))
                Text("النتائج:")
                Text(selected?.insightsJson ?: "{}")
            }
        })
    }
}
