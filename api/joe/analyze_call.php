<?php
// api/joe/analyze_call.php
// Accepts JSON: { file_path } or { file_base64, file_name }
header('Content-Type: application/json; charset=utf-8');
$raw = file_get_contents('php://input');
$payload = json_decode($raw, true);
if (!is_array($payload)) {
    http_response_code(400);
    echo json_encode(['ok' => false, 'error' => 'Invalid JSON body']);
    exit;
}

$filePath = isset($payload['file_path']) ? $payload['file_path'] : null;
$fileBase64 = isset($payload['file_base64']) ? $payload['file_base64'] : null;
$fileName = isset($payload['file_name']) ? $payload['file_name'] : ('recording_'.time().'.wav');

// optional link to an upload record id
$uploadId = isset($payload['upload_id']) ? (int)$payload['upload_id'] : null;

if ($fileBase64 !== null) {
    $data = base64_decode($fileBase64, true);
    if ($data === false) {
        http_response_code(400);
        echo json_encode(['ok'=>false,'error'=>'Invalid base64']);
        exit;
    }
    $uploadsRoot = dirname(__DIR__, 1) . DIRECTORY_SEPARATOR . 'storage' . DIRECTORY_SEPARATOR . 'uploads';
    if (!is_dir($uploadsRoot)) {
        @mkdir($uploadsRoot, 0777, true);
    }
    $storeName = time().'_'.preg_replace('/[^a-zA-Z0-9-_\.]/','_',basename($fileName));
    $path = $uploadsRoot . DIRECTORY_SEPARATOR . $storeName;
    file_put_contents($path, $data);
    $filePath = $path;
}

if (!$filePath || !file_exists($filePath)) {
    http_response_code(400);
    echo json_encode(['ok'=>false,'error'=>'file_path is required and must exist']);
    exit;
}

// Try to run speech-to-text via OpenAI (if configured)
$apiKey = getenv('OPENAI_API_KEY') ?: '';
$openaiUrl = getenv('JOE_OPENAI_URL') ?: 'https://api.openai.com/v1/audio/transcriptions';

if ($apiKey === '' || $apiKey === 'replace-with-your-openai-api-key') {
    // Not configured — return placeholder
    // Still store a record with no transcript
    require_once __DIR__ . '/helpers.php';
    $root = dirname(__DIR__, 2);
    loadEnvFile($root . DIRECTORY_SEPARATOR . '.env');
    loadEnvFile($root . DIRECTORY_SEPARATOR . '.env.example');
    loadEnvFile(dirname(__DIR__) . DIRECTORY_SEPARATOR . '.env');
    $pdo = createDatabaseConnection();
    ensureCallInsightsTable($pdo);

    $transcript = null;
    $insights = [];

    $stmt = $pdo->prepare('INSERT INTO call_insights (upload_id, file_path, transcript, insights_json, numbers_json) VALUES (:upload_id, :file_path, :transcript, :insights_json, :numbers_json)');
    $stmt->execute([
        ':upload_id' => $uploadId,
        ':file_path' => $filePath ?? null,
        ':transcript' => $transcript,
        ':insights_json' => json_encode($insights, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES),
        ':numbers_json' => json_encode([], JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES),
    ]);

    echo json_encode([
        'ok' => true,
        'note' => 'OPENAI_API_KEY not configured. Stored placeholder record.',
        'transcript' => $transcript,
        'insights' => $insights,
        'id' => (int)$pdo->lastInsertId()
    ], JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);
    exit;
}

// If configured, attempt to call OpenAI's audio transcription endpoint
$fh = fopen($filePath, 'rb');
$boundary = '----RoboteBoundary'.time();
$headers = [
    'Authorization: Bearer ' . $apiKey,
    'Content-Type: multipart/form-data; boundary=' . $boundary,
];

$post = '';
$post .= "--{$boundary}\r\n";
$post .= "Content-Disposition: form-data; name=\"file\"; filename=\"".basename($filePath)."\"\r\n";
$post .= "Content-Type: audio/wav\r\n\r\n";
$post .= stream_get_contents($fh) . "\r\n";
$post .= "--{$boundary}\r\n";
$post .= "Content-Disposition: form-data; name=\"model\"\r\n\r\nwhisper-1\r\n";
$post .= "--{$boundary}--\r\n";

$ch = curl_init($openaiUrl);
curl_setopt($ch, CURLOPT_RETURNTRANSFER, true);
curl_setopt($ch, CURLOPT_CUSTOMREQUEST, 'POST');
curl_setopt($ch, CURLOPT_HTTPHEADER, $headers);
curl_setopt($ch, CURLOPT_POSTFIELDS, $post);
$response = curl_exec($ch);
$err = curl_error($ch);
curl_close($ch);

function ensureCallInsightsTable(PDO $pdo): void
{
    $pdo->exec(
        'CREATE TABLE IF NOT EXISTS call_insights (
            id INT AUTO_INCREMENT PRIMARY KEY,
            upload_id INT DEFAULT NULL,
            file_path VARCHAR(1024) DEFAULT NULL,
            transcript LONGTEXT DEFAULT NULL,
            insights_json JSON DEFAULT NULL,
            numbers_json JSON DEFAULT NULL,
            created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci'
    );
}

if ($err) {
    http_response_code(500);
    echo json_encode(['ok'=>false,'error'=>'curl_error','detail'=>$err]);
    exit;
}

$resp = json_decode($response, true);
$transcript = null;
if (is_array($resp) && isset($resp['text'])) {
    $transcript = $resp['text'];
} elseif (is_array($resp) && isset($resp['transcript'])) {
    $transcript = $resp['transcript'];
}

// Very small local analysis: extract named numbers and simple keywords
$insights = [];
if ($transcript) {
    // Extract phone-like numbers
    if (preg_match_all('/\+?[0-9][0-9 \-]{5,}[0-9]/u', $transcript, $m)) {
        $insights['numbers'] = array_values(array_unique($m[0]));
    }
    // Keywords
    $keywords = ['deliver','pharmacy','price','bill','debt','urgent','appointment','medicine','doctor'];
    $found = [];
    foreach ($keywords as $k) {
        if (stripos($transcript, $k) !== false) $found[] = $k;
    }
    $insights['keywords'] = $found;
}
require_once __DIR__ . '/helpers.php';
$root = dirname(__DIR__, 2);
loadEnvFile($root . DIRECTORY_SEPARATOR . '.env');
loadEnvFile($root . DIRECTORY_SEPARATOR . '.env.example');
loadEnvFile(dirname(__DIR__) . DIRECTORY_SEPARATOR . '.env');

$pdo = createDatabaseConnection();
ensureCallInsightsTable($pdo);

$numbers = $insights['numbers'] ?? [];
$stmt = $pdo->prepare('INSERT INTO call_insights (upload_id, file_path, transcript, insights_json, numbers_json) VALUES (:upload_id, :file_path, :transcript, :insights_json, :numbers_json)');
$stmt->execute([
    ':upload_id' => $uploadId,
    ':file_path' => $filePath,
    ':transcript' => $transcript,
    ':insights_json' => json_encode($insights, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES),
    ':numbers_json' => json_encode($numbers, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES),
]);

echo json_encode([
    'ok' => true,
    'transcript' => $transcript,
    'insights' => $insights,
    'raw_openai' => $resp,
    'id' => (int)$pdo->lastInsertId()
], JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);
