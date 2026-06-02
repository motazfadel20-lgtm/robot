<?php
// api/joe/call_insights.php
header('Content-Type: application/json; charset=utf-8');

require_once __DIR__ . '/helpers.php';

$root = dirname(__DIR__, 2);
loadEnvFile($root . DIRECTORY_SEPARATOR . '.env');
loadEnvFile($root . DIRECTORY_SEPARATOR . '.env.example');
loadEnvFile(dirname(__DIR__) . DIRECTORY_SEPARATOR . '.env');

$pdo = createDatabaseConnection();

$method = $_SERVER['REQUEST_METHOD'];

if ($method === 'GET') {
    $id = isset($_GET['id']) ? (int)$_GET['id'] : null;
    if ($id) {
        $stmt = $pdo->prepare('SELECT * FROM call_insights WHERE id = :id LIMIT 1');
        $stmt->execute([':id' => $id]);
        $row = $stmt->fetch(PDO::FETCH_ASSOC);
        echo json_encode(['ok' => true, 'insight' => $row], JSON_UNESCAPED_UNICODE);
        exit;
    }

    $stmt = $pdo->query('SELECT * FROM call_insights ORDER BY created_at DESC LIMIT 200');
    $rows = $stmt->fetchAll(PDO::FETCH_ASSOC);
    echo json_encode(['ok' => true, 'insights' => $rows], JSON_UNESCAPED_UNICODE);
    exit;
}
if ($method === 'DELETE') {
    // accept JSON body { id } or query param id
    $raw = file_get_contents('php://input');
    $data = json_decode($raw, true);
    $id = null;
    if (is_array($data) && isset($data['id'])) $id = (int)$data['id'];
    if (!$id && isset($_GET['id'])) $id = (int)$_GET['id'];
    if (!$id) {
        http_response_code(400);
        echo json_encode(['ok' => false, 'error' => 'id is required'], JSON_UNESCAPED_UNICODE);
        exit;
    }

    $stmt = $pdo->prepare('DELETE FROM call_insights WHERE id = :id');
    $stmt->execute([':id' => $id]);
    echo json_encode(['ok' => true, 'deleted' => $stmt->rowCount()], JSON_UNESCAPED_UNICODE);
    exit;
}

http_response_code(405);
echo json_encode(['ok' => false, 'error' => 'Method not allowed'], JSON_UNESCAPED_UNICODE);
exit;
