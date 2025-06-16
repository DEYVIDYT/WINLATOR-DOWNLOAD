<?php
session_start(); // Start session for basic auth persistence

header('Content-Type: application/json');
$jsonFile = 'games.json';
// !! SECURITY WARNING !!
// !! This is a hardcoded password for demonstration. !!
// !! In a real application, use a strong, hashed password stored securely, !!
// !! and proper user management. !!
define('ADMIN_PASSWORD', 'admin123');
define('SESSION_TOKEN_KEY', 'admin_session_token');

// Basic session validation function
function isValidSession() {
    return isset($_SESSION[SESSION_TOKEN_KEY]) && $_SESSION[SESSION_TOKEN_KEY] === generateToken(ADMIN_PASSWORD);
}

// Generate a dummy token (could be more sophisticated)
function generateToken($password) {
    return hash('sha256', $password . 'some_salt'); // Simple example
}

$response = ['success' => false, 'message' => 'Invalid request'];
$action = $_REQUEST['action'] ?? '';

// Login action
if ($_SERVER['REQUEST_METHOD'] === 'POST' && $action === 'login') {
    $password = $_POST['password'] ?? '';
    if ($password === ADMIN_PASSWORD) {
        $_SESSION[SESSION_TOKEN_KEY] = generateToken(ADMIN_PASSWORD);
        $response = ['success' => true, 'message' => 'Login successful', 'token' => $_SESSION[SESSION_TOKEN_KEY]];
    } else {
        unset($_SESSION[SESSION_TOKEN_KEY]);
        $response = ['success' => false, 'message' => 'Invalid password'];
    }
    echo json_encode($response);
    exit;
}

// All actions below require a valid session "token" (passed as GET/POST param for simplicity here)
// In a real app, you'd rely on HTTP session cookies primarily.
$clientToken = $_REQUEST['token'] ?? '';
if (!isset($_SESSION[SESSION_TOKEN_KEY]) || $_SESSION[SESSION_TOKEN_KEY] !== $clientToken) {
     // If it's not a login attempt and token is bad/missing
    if ($action !== 'login') {
       http_response_code(401); // Unauthorized
       echo json_encode(['success' => false, 'message' => 'Authentication required.']);
       exit;
    }
}


// Load games data function
function loadGames($file) {
    if (!file_exists($file)) {
        return [];
    }
    $data = file_get_contents($file);
    $games = json_decode($data, true);
    return $games === null ? [] : $games;
}

// Save games data function
function saveGames($file, $games) {
    return file_put_contents($file, json_encode($games, JSON_PRETTY_PRINT));
}

if ($action === 'list_pending') {
    $games = loadGames($jsonFile);
    $pendingGames = array_filter($games, function($game) {
        return isset($game['status']) && $game['status'] === 'pending';
    });
    echo json_encode(array_values($pendingGames)); // Re-index array
    exit;
}

if ($_SERVER['REQUEST_METHOD'] === 'POST' && ($action === 'approve' || $action === 'reject')) {
    $gameName = $_GET['name'] ?? ''; // Game name from query param for POST for simplicity
    if (empty($gameName)) {
        $response = ['success' => false, 'message' => 'Game name not provided.'];
        echo json_encode($response);
        exit;
    }

    $games = loadGames($jsonFile);
    $gameFound = false;
    foreach ($games as &$game) {
        if ($game['name'] === $gameName) {
            if ($action === 'approve') {
                $game['status'] = 'approved';
                $response = ['success' => true, 'message' => 'Game approved.'];
            } else { // reject
                $game['status'] = 'rejected';
                // Or, to delete: unset($games[$key]); but that needs index management.
                // For now, just marking as rejected.
                $response = ['success' => true, 'message' => 'Game rejected.'];
            }
            $gameFound = true;
            break;
        }
    }

    if ($gameFound) {
        if (!saveGames($jsonFile, $games)) {
            $response = ['success' => false, 'message' => 'Error saving changes.'];
        }
    } else {
        $response = ['success' => false, 'message' => 'Game not found.'];
    }
    echo json_encode($response);
    exit;
}

// If no specific action matched and it wasn't a login attempt that failed authentication earlier
if ($action !== 'login') {
   echo json_encode($response); // Default 'Invalid request'
}
?>
