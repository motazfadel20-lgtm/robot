<?php
// api/joe/upload_audio.php
// Accepts JSON: { file_base64, file_name, mime_type }
header('Content-Type: application/json; charset=utf-8');

$raw = file_get_contents('php://input');
$payload = json_decode($raw, true);
if (!is_array($payload)) {
    http_response_code(400);
    echo json_encode(['ok' => false, 'error' => 'Invalid JSON body']);
    exit;
}

$fileBase64 = trim((string)($payload['file_base64'] ?? ''));
$fileName = trim((string)($payload['file_name'] ?? 'recording')).'.wav';
$mimeType = trim((string)($payload['mime_type'] ?? 'audio/wav'));

if ($fileBase64 === '') {
    http_response_code(400);
    echo json_encode(['ok' => false, 'error' => 'file_base64 is required']);
    exit;
}

$data = base64_decode($fileBase64, true);
if ($data === false) {
    http_response_code(400);
    echo json_encode(['ok' => false, 'error' => 'Invalid base64 content']);
    exit;
}

$uploadsRoot = dirname(__DIR__, 1) . DIRECTORY_SEPARATOR . 'storage' . DIRECTORY_SEPARATOR . 'uploads';
if (!is_dir($uploadsRoot)) {
    @mkdir($uploadsRoot, 0777, true);
}

$ts = time();
$storeName = $ts . '_' . preg_replace('/[^a-zA-Z0-9-_\.]/', '_', basename($fileName));
$path = $uploadsRoot . DIRECTORY_SEPARATOR . $storeName;

if (file_put_contents($path, $data) === false) {
    http_response_code(500);
    echo json_encode(['ok' => false, 'error' => 'Unable to save file']);
    exit;
}

// Return minimal metadata. Further processing (STT, analysis) handled by separate endpoint.
$urlPath = str_replace($_SERVER['DOCUMENT_ROOT'], '', $path);
if ($urlPath === $path) {
    $urlPath = '/api/joe/storage/uploads/' . $storeName;
}

echo json_encode([
    'ok' => true,
    'file_name' => $storeName,
    'mime_type' => $mimeType,
    'size' => filesize($path),
    'path' => $path,
    'url' => $urlPath,
]);
