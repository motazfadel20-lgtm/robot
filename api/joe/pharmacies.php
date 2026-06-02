<?php
// api/joe/pharmacies.php
// Simple CRUD API for pharmacies table
header('Content-Type: application/json; charset=utf-8');

require_once __DIR__ . '/helpers.php';

$root = dirname(__DIR__, 2);
loadEnvFile($root . DIRECTORY_SEPARATOR . '.env');
loadEnvFile($root . DIRECTORY_SEPARATOR . '.env.example');
loadEnvFile(dirname(__DIR__) . DIRECTORY_SEPARATOR . '.env');

$pdo = createDatabaseConnection();

$method = $_SERVER['REQUEST_METHOD'];

function jsonErr($code, $msg) {
    http_response_code($code);
    echo json_encode(['ok' => false, 'error' => $msg], JSON_UNESCAPED_UNICODE);
    exit;
}

$input = json_decode(file_get_contents('php://input'), true) ?: [];

if ($method === 'GET') {
    $id = $_GET['id'] ?? null;
    if ($id) {
        $stmt = $pdo->prepare('SELECT * FROM pharmacies WHERE id = :id LIMIT 1');
        $stmt->execute([':id' => (int)$id]);
        $row = $stmt->fetch(PDO::FETCH_ASSOC);
        echo json_encode(['ok' => true, 'pharmacy' => $row], JSON_UNESCAPED_UNICODE);
        exit;
    }

    $stmt = $pdo->query('SELECT * FROM pharmacies ORDER BY name ASC');
    $rows = $stmt->fetchAll(PDO::FETCH_ASSOC);
    echo json_encode(['ok' => true, 'pharmacies' => $rows], JSON_UNESCAPED_UNICODE);
    exit;
}

if ($method === 'POST') {
    $name = trim((string)($input['name'] ?? ''));
    if ($name === '') jsonErr(400, 'name is required');
    $med = trim((string)($input['medication'] ?? ''));
    $price = isset($input['price']) ? (float)$input['price'] : null;
    $currency = trim((string)($input['currency'] ?? 'EGP'));
    $notes = trim((string)($input['notes'] ?? ''));

    $stmt = $pdo->prepare('INSERT INTO pharmacies (name, medication, address, latitude, longitude, price, currency, notes) VALUES (:name, :medication, :address, :lat, :lng, :price, :currency, :notes)');
    $stmt->execute([
        ':name' => $name,
        ':medication' => $med,
        ':address' => $input['address'] ?? null,
        ':lat' => isset($input['latitude']) ? (float)$input['latitude'] : null,
        ':lng' => isset($input['longitude']) ? (float)$input['longitude'] : null,
        ':price' => $price,
        ':currency' => $currency,
        ':notes' => $notes,
    ]);

    $id = (int)$pdo->lastInsertId();
    echo json_encode(['ok' => true, 'id' => $id], JSON_UNESCAPED_UNICODE);
    exit;
}

if ($method === 'PUT' || $method === 'PATCH') {
    $id = isset($input['id']) ? (int)$input['id'] : null;
    if (!$id) jsonErr(400, 'id is required');

    $fields = [];
    $params = [':id' => $id];
    $allowed = ['name','medication','address','latitude','longitude','price','currency','notes'];
    foreach ($allowed as $f) {
        if (array_key_exists($f, $input)) {
            $fields[] = "$f = :$f";
            $params[":$f"] = $input[$f];
        }
    }
    if (empty($fields)) jsonErr(400, 'no fields to update');

    $sql = 'UPDATE pharmacies SET ' . implode(', ', $fields) . ' WHERE id = :id';
    $stmt = $pdo->prepare($sql);
    $stmt->execute($params);
    echo json_encode(['ok' => true, 'updated' => $stmt->rowCount()], JSON_UNESCAPED_UNICODE);
    exit;
}

if ($method === 'DELETE') {
    // accept JSON { id } or query param
    $id = isset($input['id']) ? (int)$input['id'] : (isset($_GET['id']) ? (int)$_GET['id'] : null);
    if (!$id) jsonErr(400, 'id is required');
    $stmt = $pdo->prepare('DELETE FROM pharmacies WHERE id = :id');
    $stmt->execute([':id' => $id]);
    echo json_encode(['ok' => true, 'deleted' => $stmt->rowCount()], JSON_UNESCAPED_UNICODE);
    exit;
}

jsonErr(405, 'Method not allowed');
