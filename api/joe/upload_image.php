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

function ensureImageUploadsTable(PDO $pdo): void
{
    $pdo->exec(
        'CREATE TABLE IF NOT EXISTS image_uploads (
            id INT AUTO_INCREMENT PRIMARY KEY,
            created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
            width INT NULL,
            height INT NULL,
            file_size INT NOT NULL,
            mime_type VARCHAR(64) NOT NULL,
            metadata_json JSON NULL,
            analysis_prompt TEXT NULL,
            openai_response LONGTEXT NULL,
            reply TEXT NULL
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci'
    );
}

function storeUploadRecord(PDO $pdo, array $record): void
{
    $stmt = $pdo->prepare(
        'INSERT INTO image_uploads (
            width, height, file_size, mime_type, metadata_json, analysis_prompt, openai_response, reply
        ) VALUES (:width, :height, :file_size, :mime_type, :metadata_json, :analysis_prompt, :openai_response, :reply)'
    );

    $stmt->execute([
        ':width' => $record['width'],
        ':height' => $record['height'],
        ':file_size' => $record['file_size'],
        ':mime_type' => $record['mime_type'],
        ':metadata_json' => $record['metadata_json'],
        ':analysis_prompt' => $record['analysis_prompt'],
        ':openai_response' => $record['openai_response'],
        ':reply' => $record['reply'],
    ]);
}

$root = dirname(__DIR__, 2);
loadEnvFile($root . DIRECTORY_SEPARATOR . '.env');
loadEnvFile($root . DIRECTORY_SEPARATOR . '.env.example');
loadEnvFile(dirname(__DIR__) . DIRECTORY_SEPARATOR . '.env');

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
ensureImageUploadsTable($pdo);

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

$imageBase64 = trim((string)($payload['image_base64'] ?? ''));
$metadata = $payload['metadata'] ?? [];

if ($imageBase64 === '') {
    jsonResponse(400, [
        'ok' => false,
        'error' => 'image_base64 is required.',
    ]);
}

$imageData = base64_decode($imageBase64, true);
if ($imageData === false) {
    jsonResponse(400, [
        'ok' => false,
        'error' => 'Invalid base64 image data.',
    ]);
}

$imageInfo = @getimagesizefromstring($imageData);
$width = $imageInfo[0] ?? null;
$height = $imageInfo[1] ?? null;
$fileSize = strlen($imageData);
$mimeType = $imageInfo['mime'] ?? 'unknown';

$metadataJson = json_encode($metadata, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);
$metadataDesc = '';
if (is_array($metadata)) {
    foreach ($metadata as $key => $value) {
        $metadataDesc .= "- {$key}: " . json_encode($value, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES) . "\n";
    }
}

$analysisPrompt = <<<PROMPT
أنت مساعد إداري. تحليل الصورة التالية بناءً على البيانات التقنية المتاحة فقط: لا تبتعد عن التفاصيل التقنية ولا تذكر محتوى الصورة بشكل تخيلي.
- العرض: {$width}
- الارتفاع: {$height}
- الحجم بالبايت: {$fileSize}
- نوع الصورة: {$mimeType}
{$metadataDesc}
أجب بالعربية المختصرة فقط، وأذكر فقط ما يمكن استنتاجه من هذه البيانات.
PROMPT;

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

$reply = $outputText !== '' ? $outputText : 'تم استلام الصورة، لكن لم يتمكن النظام من استخراج تفاصيل إضافية.';

storeUploadRecord($pdo, [
    'width' => $width,
    'height' => $height,
    'file_size' => $fileSize,
    'mime_type' => $mimeType,
    'metadata_json' => $metadataJson,
    'analysis_prompt' => $analysisPrompt,
    'openai_response' => $response,
    'reply' => $reply,
]);

jsonResponse(200, [
    'ok' => true,
    'provider' => 'openai',
    'mode_label' => 'OpenAI متصل',
    'reply' => $reply,
    'width' => $width,
    'height' => $height,
    'file_size' => $fileSize,
    'mime_type' => $mimeType,
    'metadata' => $metadata,
]);
