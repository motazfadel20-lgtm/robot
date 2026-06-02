package com.robote.joe.mobile

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PharmacyScreen(viewModel: JoeViewModel, onClose: () -> Unit = {}, onPushFailed: (String) -> Unit = {}) {
    val snapshot by viewModel.snapshot.collectAsState()
    var showDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("الصيدليات") }) },
        floatingActionButton = {
            FloatingActionButton(onClick = { showDialog = true }) {
                Icon(Icons.Filled.Add, contentDescription = "إضافة")
            }
        }
    ) { padding ->
        LazyColumn(modifier = Modifier.padding(padding).fillMaxSize().padding(12.dp)) {
            items(snapshot.pharmacies) { p ->
                Card(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(text = p.name, style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(4.dp))
                        Text(text = p.medication, style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(6.dp))
                        Text(text = "${p.price} ${p.currency}", style = MaterialTheme.typography.bodySmall)
                        if (p.notes.isNotBlank()) {
                            Spacer(Modifier.height(6.dp))
                            Text(text = p.notes, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }

    val scope = rememberCoroutineScope()

    if (showDialog) {
        AddPharmacyDialog(onDismiss = { showDialog = false }, onAdd = { name, med, price, currency, notes ->
            viewModel.addPharmacy(name, med, price.toDoubleOrNull() ?: 0.0, currency, notes)
            // push to server and report failure via callback
            viewModel.pushPharmacyToServer(PharmacyEntity(name = name, medication = med, price = price.toDoubleOrNull() ?: 0.0, currency = currency, notes = notes)) { ok ->
                if (!ok) {
                    scope.launch { onPushFailed("فشل إرسال الصيدلية إلى الخادم") }
                }
            }
            showDialog = false
        })
    }
}

@Composable
fun AddPharmacyDialog(onDismiss: () -> Unit, onAdd: (String, String, String, String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var med by remember { mutableStateOf("") }
    var price by remember { mutableStateOf("") }
    var currency by remember { mutableStateOf("USD") }
    var notes by remember { mutableStateOf("") }

    AlertDialog(onDismissRequest = onDismiss, title = { Text("إضافة صيدلية") }, text = {
        Column {
            OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("الاسم") })
            OutlinedTextField(value = med, onValueChange = { med = it }, label = { Text("الدواء") })
            OutlinedTextField(value = price, onValueChange = { price = it }, label = { Text("السعر") })
            OutlinedTextField(value = currency, onValueChange = { currency = it }, label = { Text("العملة") })
            OutlinedTextField(value = notes, onValueChange = { notes = it }, label = { Text("ملاحظات") })
        }
    }, confirmButton = {
        TextButton(onClick = { if (name.isNotBlank()) onAdd(name, med, price, currency, notes) }) { Text("إضافة") }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } })
}

// Helper to obtain Application in composable without importing android.content.Context repeatedly.

