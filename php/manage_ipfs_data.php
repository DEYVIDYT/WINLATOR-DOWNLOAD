<?php
header('Content-Type: application/json');

$json_file_path = 'ipfs_games.json'; // Path relative to this script, or an absolute path.
                                    // Ensure this file is writable by the web server.

// Function to read games from JSON file
function get_games($file_path) {
    if (!file_exists($file_path)) {
        // Create the file with an empty array if it doesn't exist
        file_put_contents($file_path, json_encode(['games' => [], 'last_id' => 0]));
        return ['games' => [], 'last_id' => 0];
    }
    $json_data = file_get_contents($file_path);
    if ($json_data === false) {
        return null; // Error reading file
    }
    $data = json_decode($json_data, true);
    if ($data === null && json_last_error() !== JSON_ERROR_NONE) {
         // If file contains invalid JSON, reinitialize it.
        file_put_contents($file_path, json_encode(['games' => [], 'last_id' => 0]));
        return ['games' => [], 'last_id' => 0];
    }
    // Ensure structure is what we expect
    if (!isset($data['games']) || !is_array($data['games'])) {
        $data['games'] = [];
    }
    if (!isset($data['last_id']) || !is_numeric($data['last_id'])) {
        $data['last_id'] = 0;
        // Attempt to derive last_id if missing and games exist
        if (!empty($data['games'])) {
            foreach ($data['games'] as $game) {
                if (isset($game['id']) && $game['id'] > $data['last_id']) {
                    $data['last_id'] = $game['id'];
                }
            }
        }
    }
    return $data;
}

// Function to save games to JSON file
function save_games($file_path, $data) {
    $json_string = json_encode($data, JSON_PRETTY_PRINT);
    if ($json_string === false) {
        return false; // JSON encoding error
    }
    // Use LOCK_EX for atomic write
    if (file_put_contents($file_path, $json_string, LOCK_EX) === false) {
        return false; // Error writing file
    }
    return true;
}

$action = null;
if ($_SERVER['REQUEST_METHOD'] === 'GET' && isset($_GET['action'])) {
    $action = $_GET['action'];
} elseif ($_SERVER['REQUEST_METHOD'] === 'POST' && isset($_POST['action'])) {
    $action = $_POST['action'];
}

if ($action === 'list') {
    $data = get_games($json_file_path);
    if ($data === null) {
        echo json_encode(['success' => false, 'message' => 'Error reading game data file.', 'games' => []]);
    } else {
        // Return only the games array for list action, consistent with client expectation
        echo json_encode(['success' => true, 'games' => $data['games']]);
    }
} elseif ($action === 'add' && $_SERVER['REQUEST_METHOD'] === 'POST') {
    $game_name = isset($_POST['game_name']) ? trim($_POST['game_name']) : null;
    $ipfs_hash = isset($_POST['ipfs_hash']) ? trim($_POST['ipfs_hash']) : null;
    $original_filename = isset($_POST['original_filename']) ? trim($_POST['original_filename']) : null;
    $file_size = isset($_POST['file_size']) ? filter_var($_POST['file_size'], FILTER_VALIDATE_INT) : null;

    if (empty($game_name) || empty($ipfs_hash) || $file_size === false || $file_size <= 0) {
        echo json_encode(['success' => false, 'message' => 'Invalid input. Ensure game_name, ipfs_hash, and a valid file_size are provided.']);
        exit();
    }

    $data_store = get_games($json_file_path);
    if ($data_store === null) {
        echo json_encode(['success' => false, 'message' => 'Error accessing game data store for writing.']);
        exit();
    }

    $games = $data_store['games'];
    $last_id = (int)$data_store['last_id'];

    // Check for duplicate IPFS hash
    foreach ($games as $existing_game) {
        if ($existing_game['ipfs_hash'] === $ipfs_hash) {
            echo json_encode(['success' => false, 'message' => 'Error: IPFS hash already exists.']);
            exit();
        }
    }

    $new_id = $last_id + 1;
    date_default_timezone_set('UTC'); // Set timezone for timestamp consistency

    $new_game = [
        'id' => $new_id,
        'game_name' => $game_name,
        'ipfs_hash' => $ipfs_hash,
        'original_filename' => $original_filename,
        'file_size' => $file_size,
        'upload_timestamp' => date('Y-m-d H:i:s')
    ];

    $games[] = $new_game;
    // Sort games by upload_timestamp DESC before saving, so 'list' is naturally ordered
    // However, client-side or SQL-based sorting is usually more robust for 'list'.
    // For JSON file, if we want 'list' to be pre-sorted, we do it here.
    // The current Android client does not rely on this pre-sorting, it takes what it gets.
    // For simplicity and to match the previous SQL version's ORDER BY, let's sort here.
    usort($games, function($a, $b) {
        return strcmp($b['upload_timestamp'], $a['upload_timestamp']);
    });

    $data_store['games'] = $games;
    $data_store['last_id'] = $new_id;

    if (save_games($json_file_path, $data_store)) {
        echo json_encode(['success' => true, 'message' => 'Game metadata stored successfully.']);
    } else {
        echo json_encode(['success' => false, 'message' => 'Error writing game data to file. Check file permissions.']);
    }

} else {
    echo json_encode(['success' => false, 'message' => 'Invalid action or request method.']);
}
?>
