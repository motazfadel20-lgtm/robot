<?php

declare(strict_types=1);

header('Content-Type: application/json; charset=utf-8');

if ($_SERVER['REQUEST_METHOD'] !== 'GET') {
    http_response_code(405);
    echo json_encode([
        'ok' => false,
        'error' => 'Only GET is allowed.',
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

$root = dirname(__DIR__, 2);
loadEnvFile($root . DIRECTORY_SEPARATOR . '.env');
loadEnvFile($root . DIRECTORY_SEPARATOR . '.env.example');
loadEnvFile(dirname(__DIR__) . DIRECTORY_SEPARATOR . '.env');

$pdo = createDatabaseConnection();

try {
    $files = [];
    $stmt = $pdo->query('SELECT id, created_at, file_name, mime_type, file_size, file_path, reply FROM file_uploads ORDER BY created_at DESC LIMIT 40');
    foreach ($stmt->fetchAll(PDO::FETCH_ASSOC) as $row) {
        $files[] = [
            'id' => (int)$row['id'],
            'type' => 'file',
            'file_name' => $row['file_name'] ?? '',
            'mime_type' => $row['mime_type'] ?? '',
            'file_size' => (int)$row['file_size'],
            'created_at' => $row['created_at'] ?? '',
            'reply' => $row['reply'] ?? '',
            'file_path' => $row['file_path'] ?? '',
        ];
    }

    $images = [];
    $stmt = $pdo->query('SELECT id, created_at, width, height, file_size, mime_type, reply FROM image_uploads ORDER BY created_at DESC LIMIT 20');
    foreach ($stmt->fetchAll(PDO::FETCH_ASSOC) as $row) {
        $images[] = [
            'id' => (int)$row['id'],
            'type' => 'image',
            'file_name' => sprintf('image_%s_%s', $row['width'] ?? 'unknown', $row['height'] ?? 'unknown'),
            'mime_type' => $row['mime_type'] ?? '',
            'file_size' => (int)$row['file_size'],
            'created_at' => $row['created_at'] ?? '',
            'reply' => $row['reply'] ?? '',
            'file_path' => null,
        ];
    }

    jsonResponse(200, [
        'ok' => true,
        'uploads' => array_merge($files, $images),
    ]);
} catch (PDOException $exception) {
    jsonResponse(500, [
        'ok' => false,
        'error' => 'Unable to fetch uploads: ' . $exception->getMessage(),
    ]);
}
