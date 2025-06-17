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
    return hash('sha256', $password . 'some_salt_v2'); // Changed salt just in case
}

try {
    $action = $_REQUEST['action'] ?? '';

    if ($_SERVER['REQUEST_METHOD'] === 'POST' && $action === 'login') {
        $password = $_POST['password'] ?? '';
        if (empty($password)) {
            $response = ['success' => false, 'message' => 'Password cannot be empty.'];
        } elseif ($password === ADMIN_PASSWORD) {
            // Regenerate session ID on login for security
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
    // This part of token validation needs to be robust
    $clientToken = $_REQUEST['token'] ?? null;

    if (!$clientToken && $action !== 'login') {
         http_response_code(401);
         json_exit(['success' => false, 'message' => 'Authentication token not provided.']);
    }

    // Check session existence and token match for non-login actions
    if ($action !== 'login') {
        if (!isset($_SESSION[SESSION_TOKEN_KEY])) {
            http_response_code(401);
            // Session might have expired or was never set
            json_exit(['success' => false, 'message' => 'Session not found or expired. Please login again.']);
        } elseif ($_SESSION[SESSION_TOKEN_KEY] !== $clientToken) {
            http_response_code(401);
            // Token mismatch
            json_exit(['success' => false, 'message' => 'Invalid session token. Please login again.']);
        }
    }


    // --- Action handling (list_pending, approve, reject) ---
    // (Load/Save functions remain the same as before)
    function loadGames($file) {
        if (!file_exists($file)) return [];
        $data = file_get_contents($file);
        $games = json_decode($data, true);
        return $games === null ? [] : $games;
    }

    function saveGames($file, $games) {
        return file_put_contents($file, json_encode($games, JSON_PRETTY_PRINT));
    }

    if ($action === 'list_pending') {
        $games = loadGames($jsonFile);
        $pendingGames = array_filter($games, function($game) {
            return isset($game['status']) && $game['status'] === 'pending';
        });
        json_exit(array_values($pendingGames)); // Use json_exit
    }

    if ($_SERVER['REQUEST_METHOD'] === 'POST' && ($action === 'approve' || $action === 'reject')) {
        $gameName = $_REQUEST['name'] ?? ''; // Use $_REQUEST for name for flexibility with GET/POST
        if (empty($gameName)) {
            json_exit(['success' => false, 'message' => 'Game name not provided.']);
        }

        $games = loadGames($jsonFile);
        $gameFound = false;
        // $updatedGames = []; // Create a new array for modification - Not needed if modifying $games by reference/key
         foreach ($games as $key => &$game) { // Ensure $game is by reference to modify directly or use $key
             if ($game['name'] === $gameName) {
                 if ($action === 'approve') {
                     $game['status'] = 'approved'; // Modify $game directly (if by reference) or $games[$key]
                     $response = ['success' => true, 'message' => 'Game approved.'];
                 } else { // reject
                     $game['status'] = 'rejected'; // Modify $game directly (if by reference) or $games[$key]
                     $response = ['success' => true, 'message' => 'Game rejected.'];
                 }
                 $gameFound = true;
                 break;
             }
         }
         unset($game); // Unset reference


        if ($gameFound) {
            if (!saveGames($jsonFile, $games)) { // Save the modified $games array
                $response = ['success' => false, 'message' => 'Error saving changes.'];
            }
        } else {
            $response = ['success' => false, 'message' => 'Game not found.'];
        }
        json_exit($response);
    }

    // If action is not login and not recognized:
    if ($action !== 'login' && !empty($action)) { // Added !empty($action) to avoid triggering for just token presence
         json_exit(['success' => false, 'message' => "Unknown action: {$action}"]);
    } else if (empty($action) && $_SERVER['REQUEST_METHOD'] !== 'POST' && !isset($_REQUEST['token'])) {
         // Catch cases where admin.php is loaded directly via GET without action and not a token based request
         json_exit(['success' => false, 'message' => 'Admin script loaded without specific action.']);
    }


} catch (Exception $e) {
    // Catch any unexpected exceptions and return a JSON error
    error_log("Admin Panel Error: " . $e->getMessage()); // Log error server-side
    json_exit(['success' => false, 'message' => 'An unexpected server error occurred.', 'detail' => $e->getMessage()]);
}
// Fallback for any case not caught, though json_exit should be called before this.
// This line should ideally not be reached if logic is sound.
// json_exit($response); // Commented out as per instruction - all paths should call json_exit

?>
