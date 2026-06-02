package com.robote.joe.mobile

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.graphics.Bitmap
import android.util.Base64
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

data class ConversationMessage(
    val sender: String,
    val text: String
)

data class UploadRecord(
    val id: Int,
    val type: String,
    val fileName: String,
    val mimeType: String,
    val fileSize: Int,
    val createdAt: String,
    val reply: String
)

data class JoeUiState(
    val isBusy: Boolean = false,
    val aiStatus: String = "OpenAI جاهز",
    val lastSource: String = "startup"
)

class JoeViewModel(
    application: Application
) : AndroidViewModel(application) {
    private val repository = JoeRepository(JoeDatabase.get(application).dao())
    private val assistant = JoeSmartAssistant(
        repository = repository,
        remoteBrain = JoeRemoteBrain(BuildConfig.JOE_API_BASE_URL),
        localBrain = JoeLocalBrain(repository)
    )

    val snapshot: StateFlow<HomeSnapshot> = repository.observeSnapshot()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeSnapshot())

    val callInsights: StateFlow<List<CallInsightEntity>> = repository.observeCallInsights()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _conversation = MutableStateFlow(
        listOf(
            ConversationMessage("جو", "أنا جاهز يا سيدي. أرسل أمرًا طبيعيًا وسأحاول فهمه عبر OpenAI ثم أنفذه داخل التطبيق.")
        )
    )
    val conversation = _conversation.asStateFlow()

    private val _currentUser = MutableStateFlow<String?>(null)
    val currentUser = _currentUser.asStateFlow()

    private val _uploadHistory = MutableStateFlow<List<UploadRecord>>(emptyList())
    val uploadHistory = _uploadHistory.asStateFlow()

    private val _uploadHistoryError = MutableStateFlow<String?>(null)
    val uploadHistoryError = _uploadHistoryError.asStateFlow()

    private val _uiState = MutableStateFlow(JoeUiState())
    val uiState = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.ensureSeedData()
        }
    }

    fun login(username: String, password: String, onResult: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isBusy = true, aiStatus = "جارٍ المصادقة...")
            val ok = repository.validateUser(username.trim(), password)
            if (ok) {
                _currentUser.value = username.trim()
                _conversation.value = _conversation.value + ConversationMessage("جو", "تم تسجيل الدخول كمستخدم: $username")
            } else {
                _conversation.value = _conversation.value + ConversationMessage("جو", "فشل تسجيل الدخول. تأكد من اسم المستخدم وكلمة المرور.")
            }
            _uiState.value = _uiState.value.copy(isBusy = false, aiStatus = "OpenAI جاهز")
            onResult(ok)
        }
    }

    fun logout() {
        _currentUser.value = null
        _conversation.value = listOf(ConversationMessage("جو", "تم تسجيل الخروج."))
    }

    fun handleUserMessage(text: String, onReplyReady: (String) -> Unit = {}) {
        if (text.isBlank()) return
        viewModelScope.launch {
            val userText = text.trim()
            _uiState.value = _uiState.value.copy(isBusy = true, aiStatus = "جارٍ التفكير...")
            _conversation.value = _conversation.value + ConversationMessage("علاء", userText)

            val result = assistant.handle(userText, snapshot.value)

            _conversation.value = _conversation.value + ConversationMessage("جو", result.reply)
            _uiState.value = JoeUiState(
                isBusy = false,
                aiStatus = result.modeLabel,
                lastSource = result.source
            )
            onReplyReady(result.reply)
        }
    }

    fun addPharmacy(name: String, medication: String, price: Double, currency: String = "USD", notes: String = "") {
        viewModelScope.launch {
            repository.addPharmacy(name, medication, price, currency, notes)
            _conversation.value = _conversation.value + ConversationMessage("جو", "تم إضافة صيدلية جديدة: $name")
        }
    }

    fun syncPharmaciesFromServer(onDone: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isBusy = true, aiStatus = "جارٍ مزامنة الصيدليات...")
            repository.fetchRemotePharmacies(BuildConfig.JOE_API_BASE_URL)
            _uiState.value = _uiState.value.copy(isBusy = false, aiStatus = "OpenAI جاهز")
            onDone(true)
        }
    }

    fun syncCallInsights(onDone: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isBusy = true, aiStatus = "جارٍ جلب تحليلات المكالمات...")
            repository.fetchRemoteCallInsights(BuildConfig.JOE_API_BASE_URL)
            _uiState.value = _uiState.value.copy(isBusy = false, aiStatus = "OpenAI جاهز")
            onDone(true)
        }
    }

    fun deleteCallInsight(id: Long, onResult: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            // try remote delete first
            val remoteOk = repository.deleteCallInsightRemote(BuildConfig.JOE_API_BASE_URL, id)
            // delete local regardless
            repository.deleteCallInsightLocal(id)
            onResult(remoteOk)
        }
    }

    fun pushPharmacyToServer(pharmacy: PharmacyEntity, onResult: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isBusy = true, aiStatus = "جارٍ إرسال الصيدلية إلى الخادم...")
            val ok = repository.pushPharmacyRemote(BuildConfig.JOE_API_BASE_URL, pharmacy)
            _uiState.value = _uiState.value.copy(isBusy = false, aiStatus = "OpenAI جاهز")
            onResult(ok)
        }
    }

    fun uploadImage(bitmap: Bitmap, onResult: (String) -> Unit = {}) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isBusy = true, aiStatus = "جارٍ رفع الصورة...")
            _conversation.value = _conversation.value + ConversationMessage("علاء", "[تم إرسال صورة للتحليل]")
            val responseText = withContext(Dispatchers.IO) {
                runCatching {
                    val endpoint = "${BuildConfig.JOE_API_BASE_URL.trimEnd('/')}/api/joe/upload_image.php"
                    val url = URL(endpoint)
                    val connection = (url.openConnection() as HttpURLConnection).apply {
                        requestMethod = "POST"
                        connectTimeout = 30_000
                        readTimeout = 60_000
                        doInput = true
                        doOutput = true
                        setRequestProperty("Content-Type", "application/json; charset=utf-8")
                        setRequestProperty("Accept", "application/json")
                    }

                    val jpgBytes = ByteArrayOutputStream().use {
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 80, it)
                        it.toByteArray()
                    }
                    val body = JSONObject().apply {
                        put("image_base64", Base64.encodeToString(jpgBytes, Base64.NO_WRAP))
                        put("metadata", JSONObject().apply {
                            put("width", bitmap.width)
                            put("height", bitmap.height)
                        })
                    }.toString()

                    OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { writer ->
                        writer.write(body)
                    }

                    val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
                    val resultText = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                    connection.disconnect()
                    if (connection.responseCode !in 200..299) {
                        throw IllegalStateException("HTTP ${connection.responseCode}: $resultText")
                    }

                    val json = JSONObject(resultText)
                    json.optString("reply", "تم استلام الصورة.")
                }.getOrElse { error ->
                    "تعذر إرسال الصورة: ${error.message?.take(120) ?: "خطأ غير معروف"}"
                }
            }

            _conversation.value = _conversation.value + ConversationMessage("جو", responseText)
            _uiState.value = _uiState.value.copy(isBusy = false, aiStatus = "OpenAI جاهز")
            onResult(responseText)
        }
    }

    fun uploadFile(fileName: String, mimeType: String, fileBytes: ByteArray, onResult: (String) -> Unit = {}) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isBusy = true, aiStatus = "جارٍ رفع المستند...")
            _conversation.value = _conversation.value + ConversationMessage("علاء", "[تم إرسال ملف للتحليل: $fileName]")
            val responseText = withContext(Dispatchers.IO) {
                runCatching {
                    val endpoint = "${BuildConfig.JOE_API_BASE_URL.trimEnd('/')}/api/joe/upload_file.php"
                    val url = URL(endpoint)
                    val connection = (url.openConnection() as HttpURLConnection).apply {
                        requestMethod = "POST"
                        connectTimeout = 30_000
                        readTimeout = 60_000
                        doInput = true
                        doOutput = true
                        setRequestProperty("Content-Type", "application/json; charset=utf-8")
                        setRequestProperty("Accept", "application/json")
                    }

                    val body = JSONObject().apply {
                        put("file_base64", Base64.encodeToString(fileBytes, Base64.NO_WRAP))
                        put("file_name", fileName)
                        put("mime_type", mimeType)
                        put("metadata", JSONObject().apply {
                            put("file_size", fileBytes.size)
                        })
                    }.toString()

                    OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { writer ->
                        writer.write(body)
                    }

                    val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
                    val resultText = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                    connection.disconnect()
                    if (connection.responseCode !in 200..299) {
                        throw IllegalStateException("HTTP ${connection.responseCode}: $resultText")
                    }

                    val json = JSONObject(resultText)
                    json.optString("reply", "تم استلام المستند.")
                }.getOrElse { error ->
                    "تعذر رفع المستند: ${error.message?.take(120) ?: "خطأ غير معروف"}"
                }
            }

            _conversation.value = _conversation.value + ConversationMessage("جو", responseText)
            _uiState.value = _uiState.value.copy(isBusy = false, aiStatus = "OpenAI جاهز")
            onResult(responseText)
        }
    }

    fun fetchUploadHistory(onResult: (List<UploadRecord>, String?) -> Unit = { _, _ -> }) {
        viewModelScope.launch {
            _uploadHistoryError.value = null
            _uiState.value = _uiState.value.copy(isBusy = true, aiStatus = "جارٍ جلب سجل التحميلات...")
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val endpoint = "${BuildConfig.JOE_API_BASE_URL.trimEnd('/')}/api/joe/uploads.php"
                    val url = URL(endpoint)
                    val connection = (url.openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"
                        connectTimeout = 20_000
                        readTimeout = 40_000
                        doInput = true
                        setRequestProperty("Accept", "application/json")
                    }

                    val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
                    val responseText = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                    connection.disconnect()
                    if (connection.responseCode !in 200..299) {
                        throw IllegalStateException("HTTP ${connection.responseCode}: $responseText")
                    }

                    val json = JSONObject(responseText)
                    val list = mutableListOf<UploadRecord>()
                    val uploads = json.optJSONArray("uploads")
                    if (uploads != null) {
                        for (i in 0 until uploads.length()) {
                            val item = uploads.optJSONObject(i) ?: continue
                            list += UploadRecord(
                                id = item.optInt("id"),
                                type = item.optString("type", "file"),
                                fileName = item.optString("file_name", ""),
                                mimeType = item.optString("mime_type", ""),
                                fileSize = item.optInt("file_size", 0),
                                createdAt = item.optString("created_at", ""),
                                reply = item.optString("reply", "")
                            )
                        }
                    }
                    list
                }.getOrElse { error ->
                    _uploadHistoryError.value = error.message?.take(160) ?: "خطأ غير معروف"
                    emptyList()
                }
            }

            _uploadHistory.value = result
            _uiState.value = _uiState.value.copy(isBusy = false, aiStatus = "OpenAI جاهز")
            onResult(result, _uploadHistoryError.value)
        }
    }

    companion object {
        fun factory(application: Application): ViewModelProvider.Factory {
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return JoeViewModel(application) as T
                }
            }
        }
    }
}
