<?php

declare(strict_types=1);

header('Content-Type: application/json; charset=utf-8');

if ($_SERVER['REQUEST_METHOD'] !== 'POST') {
    http_response_code(405);
    echo json_encode([
        'ok' => false,
        'error' => 'Only POST is allowed.',
    ], JSON_UNESCAPED_UNICODE);
    exit;
}

function loadEnvFile(string $path): void
{
    if (!is_file($path)) {
        return;
    }

    $lines = file($path, FILE_IGNORE_NEW_LINES | FILE_SKIP_EMPTY_LINES);
    if ($lines === false) {
        return;
    }

    foreach ($lines as $line) {
        $trimmed = trim($line);
        if ($trimmed === '' || str_starts_with($trimmed, '#') || !str_contains($trimmed, '=')) {
            continue;
        }

        [$key, $value] = explode('=', $trimmed, 2);
        $key = trim($key);
        if ($key === '' || getenv($key) !== false) {
            continue;
        }

        $value = trim($value, " \t\n\r\0\x0B\"'");
        putenv($key . '=' . $value);
        $_ENV[$key] = $value;
        $_SERVER[$key] = $value;
    }
}

function envValue(string $key, string $default = ''): string
{
    $value = getenv($key);
    if ($value === false || $value === '') {
        return $default;
    }

    return $value;
}

function jsonResponse(int $status, array $data): void
{
    http_response_code($status);
    echo json_encode($data, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);
    exit;
}

function createDatabaseConnection(): PDO
{
    $host = envValue('DB_HOST', '127.0.0.1');
    $port = envValue('DB_PORT', '3306');
    $database = envValue('DB_DATABASE', 'joe');
    $username = envValue('DB_USERNAME', 'root');
    $password = envValue('DB_PASSWORD', '');
    $charset = 'utf8mb4';

    $dsn = "mysql:host={$host};port={$port};dbname={$database};charset={$charset}";

    try {
        $pdo = new PDO($dsn, $username, $password, [
            PDO::ATTR_ERRMODE => PDO::ERRMODE_EXCEPTION,
            PDO::ATTR_DEFAULT_FETCH_MODE => PDO::FETCH_ASSOC,
            PDO::ATTR_EMULATE_PREPARES => false,
        ]);
    } catch (PDOException $exception) {
        jsonResponse(500, [
            'ok' => false,
            'error' => 'فشل الاتصال بقاعدة البيانات: ' . $exception->getMessage(),
            'mode_label' => 'MySQL غير متصل',
        ]);
    }

    return $pdo;
}

function ensureFileUploadsTable(PDO $pdo): void
{
    $pdo->exec(
        'CREATE TABLE IF NOT EXISTS file_uploads (
            id INT AUTO_INCREMENT PRIMARY KEY,
            created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
            file_name VARCHAR(255) NULL,
            mime_type VARCHAR(128) NOT NULL,
            file_size INT NOT NULL,
            file_path VARCHAR(512) NULL,
            metadata_json JSON NULL,
            analysis_prompt TEXT NULL,
            parsed_text LONGTEXT NULL,
            row_count INT NULL,
            column_headers JSON NULL,
            openai_response LONGTEXT NULL,
            reply TEXT NULL
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci'
    );

    $columns = [
        'file_path VARCHAR(512) NULL',
        'parsed_text LONGTEXT NULL',
        'row_count INT NULL',
        'column_headers JSON NULL'
    ];

    foreach ($columns as $column) {
        try {
            $pdo->exec('ALTER TABLE file_uploads ADD COLUMN IF NOT EXISTS ' . $column);
        } catch (PDOException $exception) {
            // ignore if column already exists or unsupported by MySQL version
        }
    }
}

function saveUploadedFile(string $base64, string $fileName): string
{
    $storageRoot = dirname(__DIR__, 2) . DIRECTORY_SEPARATOR . 'storage' . DIRECTORY_SEPARATOR . 'uploads';
    if (!is_dir($storageRoot) && !mkdir($storageRoot, 0755, true) && !is_dir($storageRoot)) {
        jsonResponse(500, [
            'ok' => false,
            'error' => 'Unable to create upload storage directory.',
            'mode_label' => 'تخزين غير متاح',
        ]);
    }

    $safeName = preg_replace('/[^A-Za-z0-9\.\-_\s]/u', '_', basename($fileName));
    $storageName = sprintf('%s_%s', time(), $safeName);
    $filePath = $storageRoot . DIRECTORY_SEPARATOR . $storageName;

    $data = base64_decode($base64, true);
    if ($data === false) {
        jsonResponse(400, [
            'ok' => false,
            'error' => 'Unable to decode file content for storage.',
        ]);
    }

    if (file_put_contents($filePath, $data) === false) {
        jsonResponse(500, [
            'ok' => false,
            'error' => 'Unable to save uploaded file.',
            'mode_label' => 'فشل حفظ الملف',
        ]);
    }

    return $filePath;
}

function extractSpreadsheetDetails(string $filePath): array
{
    $reader = \PhpOffice\PhpSpreadsheet\IOFactory::createReaderForFile($filePath);
    $spreadsheet = $reader->load($filePath);
    $sheet = $spreadsheet->getActiveSheet();
    $rows = $sheet->toArray(null, true, true, true);
    $headers = [];
    $previewRows = [];
    $count = count($rows);

    foreach ($rows as $index => $row) {
        $values = array_values($row);
        if ($index === 1) {
            $headers = array_filter($values, fn($value) => $value !== null && $value !== '');
        }
        $previewRows[] = implode(' | ', $values);
        if (count($previewRows) >= 20) {
            break;
        }
    }

    return [
        'parsed_text' => implode("\n", $previewRows),
        'row_count' => $count,
        'column_headers' => json_encode(array_values($headers), JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES)
    ];
}

function extractPdfText(string $filePath): ?string
{
    if (class_exists('Smalot\\PdfParser\\Parser')) {
        $parser = new \Smalot\PdfParser\Parser();
        $pdf = $parser->parseFile($filePath);
        return mb_substr(trim($pdf->getText()), 0, 5000);
    }

    return getTextPreviewFromPdf($filePath);
}

function getTextPreviewFromCsv(string $data): ?string
{
    $lines = preg_split('/\r\n|\n|\r/', trim($data));
    if ($lines === false || count($lines) === 0) {
        return null;
    }

    $preview = array_slice($lines, 0, 20);
    return implode("\n", $preview);
}

function buildFilePreview(string $filePath, string $mimeType, string $extension): ?string
{
    $content = @file_get_contents($filePath);
    if ($content === false) {
        return null;
    }

    if ($extension === 'csv' || $mimeType === 'text/csv') {
        return getTextPreviewFromCsv($content);
    }

    if (in_array($extension, ['xls', 'xlsx'], true)) {
        $details = extractSpreadsheetDetails($filePath);
        return $details['parsed_text'];
    }

    if ($extension === 'pdf' || str_contains($mimeType, 'pdf')) {
        return extractPdfText($filePath);
    }

    return null;
}

function storeFileRecord(PDO $pdo, array $record): void
{
    $stmt = $pdo->prepare(
        'INSERT INTO file_uploads (
            file_name, mime_type, file_size, file_path, metadata_json, analysis_prompt, parsed_text, row_count, column_headers, openai_response, reply
        ) VALUES (:file_name, :mime_type, :file_size, :file_path, :metadata_json, :analysis_prompt, :parsed_text, :row_count, :column_headers, :openai_response, :reply)'
    );

    $stmt->execute([
        ':file_name' => $record['file_name'],
        ':mime_type' => $record['mime_type'],
        ':file_size' => $record['file_size'],
        ':file_path' => $record['file_path'],
        ':metadata_json' => $record['metadata_json'],
        ':analysis_prompt' => $record['analysis_prompt'],
        ':parsed_text' => $record['parsed_text'],
        ':row_count' => $record['row_count'],
        ':column_headers' => $record['column_headers'],
        ':openai_response' => $record['openai_response'],
        ':reply' => $record['reply'],
    ]);
}

$root = dirname(__DIR__, 2);
loadEnvFile($root . DIRECTORY_SEPARATOR . '.env');
loadEnvFile($root . DIRECTORY_SEPARATOR . '.env.example');
loadEnvFile(dirname(__DIR__) . DIRECTORY_SEPARATOR . '.env');

$autoload = $root . DIRECTORY_SEPARATOR . 'vendor' . DIRECTORY_SEPARATOR . 'autoload.php';
if (file_exists($autoload)) {
    require_once $autoload;
}

$apiKey = envValue('OPENAI_API_KEY');
$model = envValue('JOE_OPENAI_MODEL', 'gpt-5.4-mini');
$endpoint = envValue('JOE_OPENAI_URL', 'https://api.openai.com/v1/responses');

if ($apiKey === '' || $apiKey === 'replace-with-your-openai-api-key') {
    jsonResponse(500, [
        'ok' => false,
        'error' => 'OPENAI_API_KEY is missing on the server.',
        'mode_label' => 'OpenAI غير مضبوط',
    ]);
}

$pdo = createDatabaseConnection();
ensureFileUploadsTable($pdo);

$rawBody = file_get_contents('php://input');
if ($rawBody === false || trim($rawBody) === '') {
    jsonResponse(400, [
        'ok' => false,
        'error' => 'Request body is required.',
    ]);
}

$payload = json_decode($rawBody, true);
if (!is_array($payload)) {
    jsonResponse(400, [
        'ok' => false,
        'error' => 'Invalid JSON body.',
    ]);
}

$fileBase64 = trim((string)($payload['file_base64'] ?? ''));
$fileName = trim((string)($payload['file_name'] ?? 'document'));
$mimeType = trim((string)($payload['mime_type'] ?? 'application/octet-stream'));
$metadata = $payload['metadata'] ?? [];

if ($fileBase64 === '') {
    jsonResponse(400, [
        'ok' => false,
        'error' => 'file_base64 is required.',
    ]);
}

$fileData = base64_decode($fileBase64, true);
if ($fileData === false) {
    jsonResponse(400, [
        'ok' => false,
        'error' => 'Invalid base64 file data.',
    ]);
}

$fileSize = strlen($fileData);
$extension = strtolower(pathinfo($fileName, PATHINFO_EXTENSION));
$filePath = saveUploadedFile($fileBase64, $fileName);

$textPreview = buildFilePreview($filePath, $mimeType, $extension);

$metadataJson = json_encode($metadata, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);
$metadataDesc = '';
if (is_array($metadata)) {
    foreach ($metadata as $key => $value) {
        $metadataDesc .= "- {$key}: " . json_encode($value, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES) . "\n";
    }
}

if (str_contains($mimeType, 'pdf') || $extension === 'pdf') {
    $fileTypeLabel = 'وثيقة PDF';
} elseif (str_contains($mimeType, 'spreadsheet') || in_array($extension, ['xls', 'xlsx'], true)) {
    $fileTypeLabel = 'جدول بيانات';
} elseif (str_contains($mimeType, 'csv') || $extension === 'csv') {
    $fileTypeLabel = 'ملف CSV';
} elseif (str_contains($mimeType, 'image')) {
    $fileTypeLabel = 'صورة';
} else {
    $fileTypeLabel = 'ملف عام';
}

$analysisPrompt = "أنت مساعد إداري محترف. لديك ملف لتحليله للمدير. " .
    "لا تبتعد عن المحتوى الحقيقي وأجب بالعربية المختصرة.\n" .
    "- اسم الملف: {$fileName}\n" .
    "- نوع الملف: {$mimeType}\n" .
    "- تصنيف الملف: {$fileTypeLabel}\n" .
    "- الحجم بالبايت: {$fileSize}\n" .
    ($metadataDesc !== '' ? "- معلومات إضافية:\n{$metadataDesc}" : '');

if ($textPreview !== null) {
    $analysisPrompt .= "\n- معاينة محتوى CSV:\n" . $textPreview . "\n";
    $analysisPrompt .= "استخرج أهم الأعمدة والصفوف التي تظهر في المعاينة، واذكر نوع المعلومات التي يحتويها الملف. " .
        "إذا كان الملف كبيرًا فأعطِ نظرة عامة عن تنسيقه ومحتواه.";
    $analysisPrompt .= "\n";
} else {
    $analysisPrompt .= "\nإذا كان الملف من نوع PDF أو Excel فلا يمكن قراءة المحتوى الثنائي هنا بالكامل، فاستجب بناءً على اسم الملف والنوع والحجم.\n";
}

$requestBody = [
    'model' => $model,
    'input' => $analysisPrompt,
];

$ch = curl_init($endpoint);
curl_setopt_array($ch, [
    CURLOPT_POST => true,
    CURLOPT_HTTPHEADER => [
        'Content-Type: application/json',
        'Authorization: Bearer ' . $apiKey,
    ],
    CURLOPT_POSTFIELDS => json_encode($requestBody, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES),
    CURLOPT_RETURNTRANSFER => true,
    CURLOPT_TIMEOUT => 60,
]);

$response = curl_exec($ch);
$curlError = curl_error($ch);
$statusCode = (int)curl_getinfo($ch, CURLINFO_RESPONSE_CODE);
curl_close($ch);

if ($response === false) {
    jsonResponse(502, [
        'ok' => false,
        'error' => 'OpenAI request failed: ' . $curlError,
        'mode_label' => 'تعذر الاتصال بـ OpenAI',
    ]);
}

if ($statusCode < 200 || $statusCode >= 300) {
    jsonResponse(502, [
        'ok' => false,
        'error' => 'OpenAI HTTP ' . $statusCode . ': ' . $response,
        'mode_label' => 'OpenAI أعاد خطأ',
    ]);
}

$responseJson = json_decode($response, true);
if (!is_array($responseJson)) {
    jsonResponse(502, [
        'ok' => false,
        'error' => 'Unreadable JSON from OpenAI.',
        'mode_label' => 'استجابة OpenAI غير صالحة',
    ]);
}

$outputText = trim((string)($responseJson['output_text'] ?? ''));
if ($outputText === '' && isset($responseJson['output']) && is_array($responseJson['output'])) {
    $parts = [];
    foreach ($responseJson['output'] as $item) {
        if (!isset($item['content']) || !is_array($item['content'])) {
            continue;
        }
        foreach ($item['content'] as $content) {
            $text = trim((string)($content['text'] ?? ''));
            if ($text !== '') {
                $parts[] = $text;
            }
        }
    }
    $outputText = trim(implode("\n", $parts));
}

if ($outputText === '') {
    jsonResponse(502, [
        'ok' => false,
        'error' => 'OpenAI returned empty output.',
        'mode_label' => 'OpenAI لم يرجع نصًا',
    ]);
}

storeFileRecord($pdo, [
    'file_name' => $fileName,
    'mime_type' => $mimeType,
    'file_size' => $fileSize,
    'metadata_json' => $metadataJson,
    'analysis_prompt' => $analysisPrompt,
    'openai_response' => $response,
    'reply' => $outputText,
]);

jsonResponse(200, [
    'ok' => true,
    'reply' => $outputText,
    'file_name' => $fileName,
    'mime_type' => $mimeType,
    'file_size' => $fileSize,
]);
