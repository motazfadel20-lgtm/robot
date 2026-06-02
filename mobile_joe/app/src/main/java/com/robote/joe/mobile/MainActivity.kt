package com.robote.joe.mobile

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.graphics.Bitmap
import android.provider.OpenableColumns
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Attachment
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.ui.text.input.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.LocalDate
import java.util.Locale

class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {
    private var tts: TextToSpeech? = null
    private val viewModel by viewModels<JoeViewModel> { JoeViewModel.factory(application) }

    private val voiceLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val transcript = result.data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
            ?.trim()
            .orEmpty()

        if (transcript.isNotEmpty()) {
            viewModel.handleUserMessage(transcript, ::speak)
        }
    }

    private val imageLauncher = registerForActivityResult(
        ActivityResultContracts.TakePicturePreview()
    ) { bitmap: Bitmap? ->
        bitmap?.let {
            viewModel.uploadImage(it, ::speak)
        }
    }

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            contentResolver.openInputStream(it)?.use { input ->
                val bytes = input.readBytes()
                if (bytes.isNotEmpty()) {
                    val mimeType = contentResolver.getType(it) ?: "application/octet-stream"
                    val fileName = queryFileName(it)
                    viewModel.uploadFile(fileName, mimeType, bytes, ::speak)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        tts = TextToSpeech(this, this)

        setContent {
            JoeApp(
                viewModel = viewModel,
                onStartVoice = ::startVoiceRecognition,
                onStartCamera = { imageLauncher.launch(null) },
                onStartFile = { filePickerLauncher.launch(arrayOf(
                    "application/pdf",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    "application/vnd.ms-excel",
                    "text/csv",
                    "image/*"
                )) },
                onStartUploads = { viewModel.fetchUploadHistory() }
            )
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale("ar")
        }
    }

    private fun queryFileName(uri: Uri): String {
    contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0) {
                return cursor.getString(index) ?: uri.lastPathSegment.orEmpty()
            }
        }
    }
    return uri.lastPathSegment.orEmpty().ifBlank { "document" }
}

private fun startVoiceRecognition() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ar")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "ar")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "تحدث إلى جو")
        }
        voiceLauncher.launch(intent)
    }

    private fun speak(text: String) {
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "joe_mobile_reply")
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }
}

@Composable
private fun JoeApp(
    viewModel: JoeViewModel,
    onStartVoice: () -> Unit,
    onStartCamera: () -> Unit,
    onStartFile: () -> Unit,
    onStartUploads: () -> Unit
) {
    val snapshot by viewModel.snapshot.collectAsStateWithLifecycle()
    val conversation by viewModel.conversation.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val currentUser by viewModel.currentUser.collectAsStateWithLifecycle()
    val uploadHistory by viewModel.uploadHistory.collectAsStateWithLifecycle()
    val uploadHistoryError by viewModel.uploadHistoryError.collectAsStateWithLifecycle()

    MaterialTheme {
        val currentUserValue = currentUser
        if (currentUserValue == null) {
            LoginScreen(onLogin = { username, password -> viewModel.login(username, password) })
        } else {
            JoeHomeScreen(
                viewModel = viewModel,
                snapshot = snapshot,
                conversation = conversation,
                uiState = uiState,
                uploadHistory = uploadHistory,
                uploadHistoryError = uploadHistoryError,
                onSendMessage = { text -> viewModel.handleUserMessage(text) },
                onStartVoice = onStartVoice,
                onStartCamera = onStartCamera,
                onStartFile = onStartFile,
                onStartUploads = onStartUploads,
                onLogout = { viewModel.logout() },
                currentUser = currentUserValue
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun JoeHomeScreen(
    viewModel: JoeViewModel,
    snapshot: HomeSnapshot,
    conversation: List<ConversationMessage>,
    uiState: JoeUiState,
    uploadHistory: List<UploadRecord>,
    uploadHistoryError: String?,
    onSendMessage: (String) -> Unit,
    onStartVoice: () -> Unit,
    onStartCamera: () -> Unit,
    onStartFile: () -> Unit,
    onStartUploads: () -> Unit,
    onLogout: () -> Unit,
    currentUser: String
) {
    var input by remember { mutableStateOf("") }
    val quickActions = remember {
        listOf(
            "شو عندي اليوم؟",
            "ملخص اليوم",
            "سجل دين على أبو رامي 300 دولار بعد شهر",
            "أضف سكر إلى المشتريات",
            "سجل تذكير زيارة الطبيب غدا",
            "هل عندي ديون متأخرة؟"
        )
    }

    var showPharmacyDialog by remember { mutableStateOf(false) }
    var showPharmacyScreen by remember { mutableStateOf(false) }
    var showCallInsightsScreen by remember { mutableStateOf(false) }
    val snackbarHostState = remember { androidx.compose.material3.SnackbarHostState() }
    var showUploadsDialog by remember { mutableStateOf(false) }
    var uploadRecords by remember { mutableStateOf<List<UploadRecord>>(emptyList()) }
    var uploadError by remember { mutableStateOf<String?>(null) }

    Scaffold(
        snackbarHost = { androidx.compose.material3.SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("جو", fontWeight = FontWeight.Bold)
                        Text("مساعد إداري ذكي عبر OpenAI", style = MaterialTheme.typography.labelMedium)
                    }
                },
                actions = {
                    IconButton(onClick = { showPharmacyScreen = true }) {
                        Icon(Icons.Filled.List, contentDescription = "الصيدليات")
                    }
                    IconButton(onClick = { showCallInsightsScreen = true }) {
                        Icon(Icons.Outlined.Mic, contentDescription = "تحليلات المكالمات")
                    }
                    IconButton(onClick = {
                        viewModel.syncPharmaciesFromServer { success ->
                            if (!success) {
                                // report via snackbar
                                // set local state to show snackbar below
                                uploadError = "فشل مزامنة الصيدليات"
                            }
                        }
                    }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "مزامنة الصيدليات")
                    }
                    IconButton(onClick = {
                        uploadError = null
                        uploadRecords = emptyList()
                        showUploadsDialog = true
                        onStartUploads()
                    }) {
                        Icon(Icons.Filled.History, contentDescription = "سجل التحميلات")
                    }
                    IconButton(onClick = onStartFile) {
                        Icon(Icons.Filled.Attachment, contentDescription = "رفع ملف")
                    }
                    IconButton(onClick = onStartCamera) {
                        Icon(Icons.Filled.CameraAlt, contentDescription = "كاميرا")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFFE6F0E8)
                )
            )
        }
    ) { padding ->
        var dialogItems by remember { mutableStateOf<List<String>?>(null) }

        if (dialogItems != null) {
            AlertDialog(
                onDismissRequest = { dialogItems = null },
                confirmButton = {
                    TextButton(onClick = { dialogItems = null }) { Text("إغلاق") }
                },
                title = { Text("قائمة العناصر") },
                text = {
                    LazyColumn {
                        items(dialogItems!!) { item ->
                            Text(item, modifier = Modifier.padding(6.dp))
                        }
                    }
                }
            )
        }

        if (showPharmacyDialog) {
            PharmacyDialog(
                pharmacies = snapshot.pharmacies,
                onClose = { showPharmacyDialog = false },
                onAdd = { name, medication, price, currency, notes ->
                    viewModel.addPharmacy(name, medication, price, currency, notes)
                }
            )
        }

        if (showPharmacyScreen) {
            // full screen Pharmacy view
            PharmacyScreen(viewModel = viewModel, onClose = { showPharmacyScreen = false }, onPushFailed = { msg ->
                // show a simple dialog as fallback for Snackbar
                uploadError = msg
            })
        }

        if (showCallInsightsScreen) {
            CallInsightsScreen(viewModel = viewModel, onClose = { showCallInsightsScreen = false })
        }

        if (showUploadsDialog) {
            UploadHistoryDialog(
                uploads = uploadHistory,
                error = uploadHistoryError,
                onClose = { showUploadsDialog = false }
            )
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFFF7F5F0))
                .padding(padding),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                HeroSection(
                    snapshot = snapshot,
                    uiState = uiState,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                )
            }

            item {
                SnapshotGrid(snapshot = snapshot, modifier = Modifier.padding(horizontal = 16.dp)) { items -> dialogItems = items }
            }

            item {
                QuickActionRow(
                    actions = quickActions,
                    onPick = {
                        input = it
                        onSendMessage(it)
                        input = ""
                    },
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }

            item {
                CommandComposer(
                    value = input,
                    isBusy = uiState.isBusy,
                    onValueChange = { input = it },
                    onSend = {
                        val message = input.trim()
                        if (message.isNotEmpty()) {
                            onSendMessage(message)
                            input = ""
                        }
                    },
                    onStartVoice = onStartVoice,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )

                SessionBanner(currentUser = currentUser, onLogout = onLogout, modifier = Modifier.padding(horizontal = 16.dp))

                StatisticsSection(snapshot = snapshot, modifier = Modifier.padding(horizontal = 16.dp))
            }

            item {
                SectionTitle("لوحة اليوم", Modifier.padding(horizontal = 16.dp))
            }

            item {
                TodayBoard(snapshot = snapshot, modifier = Modifier.padding(horizontal = 16.dp))
            }

            item {
                SectionTitle("المحادثة التنفيذية", Modifier.padding(horizontal = 16.dp))
            }

            items(conversation) { message ->
                ConversationCard(message = message, modifier = Modifier.padding(horizontal = 16.dp))
            }

            item {
                Box(modifier = Modifier.heightIn(min = 24.dp))
            }
        }
        LaunchedEffect(uploadError) {
            if (!uploadError.isNullOrBlank()) {
                snackbarHostState.showSnackbar(uploadError ?: "خطأ")
                uploadError = null
            }
        }
    }
}

@Composable
private fun HeroSection(
    snapshot: HomeSnapshot,
    uiState: JoeUiState,
    modifier: Modifier = Modifier
) {
    ElevatedCard(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(containerColor = Color.Transparent)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(
                        listOf(Color(0xFF264653), Color(0xFF4C956C), Color(0xFFF4A261))
                    )
                )
                .padding(20.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "مركز قيادة علاء",
                    style = MaterialTheme.typography.headlineMedium,
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "اليوم لديك ${snapshot.todayReminders} تذكيرات، ${snapshot.overdueDebts} ديون متأخرة، و${snapshot.shoppingItems} عناصر بيت تحتاج متابعة.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White.copy(alpha = 0.92f)
                )
                Text(
                    text = uiState.aiStatus,
                    style = MaterialTheme.typography.labelLarge,
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold
                )
                if (uiState.isBusy) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        color = Color.White,
                        trackColor = Color.White.copy(alpha = 0.28f)
                    )
                }
            }
        }
    }
}

@Composable
private fun SnapshotGrid(
    snapshot: HomeSnapshot,
    modifier: Modifier = Modifier,
    onTileClick: (List<String>) -> Unit = {}
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SnapshotTile("تذكيرات اليوم", snapshot.todayReminders.toString(), Color(0xFFD9ED92), Modifier.weight(1f)) {
                onTileClick(snapshot.reminders.map { "${it.title} - ${it.dueDate.formatArabic()}" })
            }
            SnapshotTile("ديون اليوم", snapshot.dueTodayDebts.toString(), Color(0xFFFFD166), Modifier.weight(1f)) {
                onTileClick(snapshot.debts.map { "${it.personName} - ${formatAmount(it.amount)} ${it.currency} - ${it.dueDate.formatArabic()}" })
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SnapshotTile("المتأخرات", snapshot.overdueDebts.toString(), Color(0xFFF4978E), Modifier.weight(1f)) {
                onTileClick(snapshot.debts.filter { it.dueDate.isBefore(LocalDate.now()) }.map { "${it.personName} - ${formatAmount(it.amount)} ${it.currency} - ${it.dueDate.formatArabic()}" })
            }
            SnapshotTile("فواتير مفتوحة", snapshot.openBills.toString(), Color(0xFFA9DEF9), Modifier.weight(1f)) {
                onTileClick(snapshot.bills.map { "${it.vendorName} - ${formatAmount(it.amount)} ${it.currency} - ${it.billDate.formatArabic()}" })
            }
        }
    }
}

@Composable
private fun SnapshotTile(
    title: String,
    value: String,
    color: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {}
) {
    Card(
        modifier = modifier.clickable { onClick() },
        colors = CardDefaults.cardColors(containerColor = color),
        shape = RoundedCornerShape(24.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge)
            Text(value, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun QuickActionRow(
    actions: List<String>,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionTitle("أوامر سريعة")
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            actions.forEach { action ->
                Button(onClick = { onPick(action) }) {
                    Text(action)
                }
            }
        }
    }
}

@Composable
private fun CommandComposer(
    value: String,
    isBusy: Boolean,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    onStartVoice: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        color = Color.White,
        shadowElevation = 3.dp
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("أرسل أمرًا طبيعيًا كما يتكلم علاء", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                enabled = !isBusy,
                label = { Text("مثال: سجل دين على أبو رامي 300 دولار بعد شهر") }
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                Button(onClick = onSend, modifier = Modifier.weight(1f), enabled = !isBusy) {
                    Icon(Icons.Outlined.Send, contentDescription = null)
                    Box(modifier = Modifier.width(8.dp))
                    Text(if (isBusy) "جارٍ التنفيذ" else "تنفيذ")
                }
                Button(onClick = onStartVoice, modifier = Modifier.weight(1f), enabled = !isBusy) {
                    Icon(Icons.Outlined.Mic, contentDescription = null)
                    Box(modifier = Modifier.width(8.dp))
                    Text("صوت")
                }
            }
        }
    }
}

@Composable
private fun TodayBoard(
    snapshot: HomeSnapshot,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        InsightSection(
            title = "الديون ذات الأولوية",
            rows = snapshot.debts
                .filterNot { it.isPaid }
                .sortedWith(compareBy<DebtEntity> { !it.dueDate.isBefore(LocalDate.now()) }.thenBy { it.dueDate })
                .take(3)
                .map {
                    val badge = if (it.dueDate.isBefore(LocalDate.now())) "متأخر" else if (it.dueDate == LocalDate.now()) "اليوم" else "لاحقًا"
                    "${it.personName} - ${formatAmount(it.amount)} ${it.currency} - $badge"
                }
        )
        InsightSection(
            title = "تذكيرات اليوم",
            rows = snapshot.reminders.take(3).map { "${it.title} - ${it.dueDate.formatArabic()}" }
        )
        InsightSection(
            title = "مشتريات البيت",
            rows = snapshot.shopping.take(5).map { "${it.itemName} - أضافه ${it.addedBy.ifBlank { "البيت" }}" }
        )
    }
}

@Composable
private fun InsightSection(
    title: String,
    rows: List<String>
) {
    Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if (rows.isEmpty()) {
                Text("لا توجد بيانات بعد.", color = Color.Gray)
            } else {
                rows.forEachIndexed { index, row ->
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF4C956C))
                            )
                            Text(row, style = MaterialTheme.typography.bodyLarge)
                        }
                        if (index != rows.lastIndex) {
                            Divider(color = Color(0xFFE9ECEF))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConversationCard(
    message: ConversationMessage,
    modifier: Modifier = Modifier
) {
    val isJoe = message.sender == "جو"
    val containerColor = if (isJoe) Color(0xFFE3F2E8) else Color.White
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        shape = RoundedCornerShape(22.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = message.sender,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = if (isJoe) Color(0xFF2D6A4F) else Color(0xFF6C757D)
            )
            Text(
                text = message.text,
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Start
            )
        }
    }
}

@Composable
private fun SessionBanner(
    currentUser: String,
    onLogout: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFDFF6FF)),
        shape = RoundedCornerShape(24.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text("مرحبًا يا أستاذ $currentUser", fontWeight = FontWeight.Bold)
                Text("الوصول محمي وصلاحيات كاملة", style = MaterialTheme.typography.bodyMedium)
            }
            Button(onClick = onLogout) {
                Text("تسجيل خروج")
            }
        }
    }
}

@Composable
private fun StatisticsSection(
    snapshot: HomeSnapshot,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionTitle("التقرير الإحصائي", Modifier.padding(bottom = 8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatCard("التذكيرات", snapshot.todayReminders.toString(), Color(0xFFD9ED92), Modifier.weight(1f))
            StatCard("الديون اليوم", snapshot.dueTodayDebts.toString(), Color(0xFFFFD166), Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatCard("الديون المتأخرة", snapshot.overdueDebts.toString(), Color(0xFFF4978E), Modifier.weight(1f))
            StatCard("مجموع الديون", formatAmount(snapshot.totalOpenDebtAmount), Color(0xFFA9DEF9), Modifier.weight(1f))
        }
    }
}

@Composable
private fun StatCard(
    title: String,
    value: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = color),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge)
            Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun PharmacyDialog(
    pharmacies: List<PharmacyEntity>,
    onClose: () -> Unit,
    onAdd: (String, String, Double, String, String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var medication by remember { mutableStateOf("") }
    var priceText by remember { mutableStateOf("") }
    var currency by remember { mutableStateOf("USD") }
    var notes by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("جدول الصيدليات والأسعار") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                LazyColumn(modifier = Modifier.heightIn(max = 220.dp)) {
                    items(pharmacies) { pharmacy ->
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            colors = CardDefaults.cardColors(containerColor = Color.White),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(pharmacy.name, fontWeight = FontWeight.Bold)
                                Text("${pharmacy.medication} - ${formatAmount(pharmacy.price)} ${pharmacy.currency}")
                                if (pharmacy.notes.isNotBlank()) {
                                    Text(pharmacy.notes, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
                Text("أضف صيدلية جديدة", fontWeight = FontWeight.Bold)
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("اسم الصيدلية") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = medication, onValueChange = { medication = it }, label = { Text("الدواء") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = priceText, onValueChange = { priceText = it }, label = { Text("السعر") }, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                OutlinedTextField(value = currency, onValueChange = { currency = it }, label = { Text("العملة") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = notes, onValueChange = { notes = it }, label = { Text("ملاحظات") }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val price = priceText.toDoubleOrNull() ?: 0.0
                if (name.isNotBlank() && medication.isNotBlank() && price > 0.0) {
                    onAdd(name, medication, price, currency.ifBlank { "USD" }, notes)
                    name = ""
                    medication = ""
                    priceText = ""
                    notes = ""
                }
            }) {
                Text("حفظ")
            }
        },
        dismissButton = {
            TextButton(onClick = onClose) { Text("إغلاق") }
        }
    )
}

@Composable
private fun LoginScreen(onLogin: (String, String) -> Unit) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFFF7F7F7)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center, modifier = Modifier.padding(20.dp)) {
            Text("تسجيل دخول الأستاذ علاء", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                label = { Text("اسم المستخدم") },
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp)
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("كلمة المرور") },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
            )
            Button(onClick = { onLogin(username, password) }, modifier = Modifier.padding(top = 16.dp)) {
                Text("تسجيل دخول")
            }
        }
    }
}

@Composable
private fun UploadHistoryDialog(
    uploads: List<UploadRecord>,
    error: String?,
    onClose: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("سجل التحميلات") },
        text = {
            if (error != null) {
                Text("حدث خطأ: $error", color = Color.Red)
            } else if (uploads.isEmpty()) {
                Text("لا توجد تحميلات بعد.")
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                    items(uploads) { upload ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            colors = CardDefaults.cardColors(containerColor = Color.White),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("${upload.type.uppercase(Locale.getDefault())}: ${upload.fileName}", fontWeight = FontWeight.Bold)
                                Text("${upload.mimeType} • ${upload.fileSize} بايت")
                                Text(upload.reply, style = MaterialTheme.typography.bodySmall)
                                Text(upload.createdAt, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onClose) { Text("إغلاق") }
        }
    )
}

@Composable
private fun SectionTitle(
    text: String,
    modifier: Modifier = Modifier
) {
    Text(
        text = text,
        modifier = modifier,
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
        color = Color(0xFF1F2937)
    )
}
