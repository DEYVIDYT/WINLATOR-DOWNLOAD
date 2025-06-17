<?php
session_start(); // MUST be the very first output.

header('Content-Type: application/json');
$jsonFile = 'games.json';
define('ADMIN_PASSWORD', 'admin123');
define('SESSION_TOKEN_KEY', 'admin_session_token');

// Default response
$response = ['success' => false, 'message' => 'Invalid request or unexpected error.'];

// Function to safely encode and exit
function json_exit($data) {
    echo json_encode($data);
    exit;
}

// Generate a dummy token
function generateToken($password) {
    return hash('sha256', $password . 'some_salt_v2');
}

// Load games data function
function loadGames($file) {
    if (!file_exists($file)) return [];
    $data = file_get_contents($file);
    $games = json_decode($data, true);
    return $games === null ? [] : $games;
}

// Save games data function
function saveGames($file, $games) {
    return file_put_contents($file, json_encode($games, JSON_PRETTY_PRINT));
}

try {
    $action = $_REQUEST['action'] ?? '';

    if ($_SERVER['REQUEST_METHOD'] === 'POST' && $action === 'login') {
        $password = $_POST['password'] ?? '';
        if (empty($password)) {
            $response = ['success' => false, 'message' => 'Password cannot be empty.'];
        } elseif ($password === ADMIN_PASSWORD) {
            session_regenerate_id(true);
            $_SESSION[SESSION_TOKEN_KEY] = generateToken(ADMIN_PASSWORD);
            $response = ['success' => true, 'message' => 'Login successful', 'token' => $_SESSION[SESSION_TOKEN_KEY]];
        } else {
            unset($_SESSION[SESSION_TOKEN_KEY]);
            $response = ['success' => false, 'message' => 'Invalid password.'];
        }
        json_exit($response);
    }

    // For all actions below, check token
    $clientToken = $_REQUEST['token'] ?? null;

    if (!$clientToken && $action !== 'login') { // No token and not a login attempt
         http_response_code(401);
         json_exit(['success' => false, 'message' => 'Authentication token not provided.']);
    }

    if ($action !== 'login') { // For any action other than login, validate token
        if (!isset($_SESSION[SESSION_TOKEN_KEY])) {
            http_response_code(401);
            json_exit(['success' => false, 'message' => 'Session not found or expired. Please login again.']);
        } elseif ($_SESSION[SESSION_TOKEN_KEY] !== $clientToken) {
            http_response_code(401);
            json_exit(['success' => false, 'message' => 'Invalid session token. Please login again.']);
        }
    }

    // --- Authenticated Actions ---
    if ($action === 'list_pending') {
        $games = loadGames($jsonFile);
        $pendingGames = array_filter($games, function($game) {
            return isset($game['status']) && $game['status'] === 'pending';
        });
        json_exit(array_values($pendingGames));
    }

    if ($action === 'list_approved') {
        $games = loadGames($jsonFile);
        $approvedGames = array_filter($games, function($game) {
            return isset($game['status']) && $game['status'] === 'approved';
        });
        json_exit(array_values($approvedGames));
    }

    if ($_SERVER['REQUEST_METHOD'] === 'POST' && $action === 'approve') {
        $gameName = $_REQUEST['name'] ?? '';
        if (empty($gameName)) {
            json_exit(['success' => false, 'message' => 'Game name not provided for approval.']);
        }
        $games = loadGames($jsonFile);
        $gameFound = false;
        foreach ($games as &$game) {
            if ($game['name'] === $gameName) {
                $game['status'] = 'approved';
                $gameFound = true;
                break;
            }
        }
        unset($game); // Unset reference

        if ($gameFound) {
            if (saveGames($jsonFile, $games)) {
                $response = ['success' => true, 'message' => 'Game approved successfully.'];
            } else {
                $response = ['success' => false, 'message' => 'Error saving approved game status.'];
            }
        } else {
            $response = ['success' => false, 'message' => 'Game not found for approval.'];
        }
        json_exit($response);
    }

    // New consolidated delete logic for 'reject' (pending game) and 'delete_game' (any game)
    if ($_SERVER['REQUEST_METHOD'] === 'POST' && ($action === 'reject' || $action === 'delete_game')) {
        $gameName = $_REQUEST['name'] ?? '';
        if (empty($gameName)) {
            json_exit(['success' => false, 'message' => 'Game name not provided for deletion.']);
        }

        $games = loadGames($jsonFile);
        $initialCount = count($games);

        $updatedGames = array_filter($games, function($game) use ($gameName) {
            return $game['name'] !== $gameName;
        });

        if (count($updatedGames) < $initialCount) {
            if (saveGames($jsonFile, array_values($updatedGames))) {
                $response = ['success' => true, 'message' => "Game '${gameName}' deleted successfully."];
            } else {
                $response = ['success' => false, 'message' => 'Error saving data after deleting game.'];
            }
        } else {
            $response = ['success' => false, 'message' => "Game '${gameName}' not found for deletion."];
        }
        json_exit($response);
    }

    // If action is not login and not recognized (and was authenticated):
    if ($action !== 'login' && !in_array($action, ['list_pending', 'list_approved', 'approve', 'reject', 'delete_game'])) {
         if (!empty($action)) {
            json_exit(['success' => false, 'message' => "Unknown action: {$action}"]);
         } else if (empty($action) && $_SERVER['REQUEST_METHOD'] !== 'POST') {
             // Catch cases where admin.php is loaded directly via GET without action (already authenticated)
             json_exit(['success' => false, 'message' => 'Admin script loaded without specific action after authentication.']);
         }
    }


} catch (Exception $e) {
    error_log("Admin Panel Error: " . $e->getMessage());
    json_exit(['success' => false, 'message' => 'An unexpected server error occurred.', 'detail' => $e->getMessage()]);
}

// Fallback for any unhandled case, though ideally all paths should call json_exit or be caught by try-catch.
// This also handles the case where $action === 'login' but method is not POST (e.g. direct GET to admin.php?action=login)
// Or if $action is empty and not caught by other specific messages.
json_exit($response);

?>
