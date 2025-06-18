<?php
header('Content-Type: application/json');

// IMPORTANT: Replace with your actual database credentials!
$servername = "your_server_name"; // e.g., localhost or your hosting provider's DB server
$username = "your_db_username";
$password = "your_db_password";
$dbname = "your_db_name";

// Create connection
$conn = new mysqli($servername, $username, $password, $dbname);

// Check connection
if ($conn->connect_error) {
    echo json_encode(['success' => false, 'message' => 'Database connection failed: ' . $conn->connect_error]);
    exit();
}

// Check if data is received via POST
if ($_SERVER['REQUEST_METHOD'] == 'POST') {
    $game_name = isset($_POST['game_name']) ? $_POST['game_name'] : null;
    $ipfs_hash = isset($_POST['ipfs_hash']) ? $_POST['ipfs_hash'] : null;
    $original_filename = isset($_POST['original_filename']) ? $_POST['original_filename'] : null;
    $file_size = isset($_POST['file_size']) ? filter_var($_POST['file_size'], FILTER_VALIDATE_INT) : null;

    if ($game_name && $ipfs_hash && $file_size !== false && $file_size > 0) {
        // Prepare and bind
        $stmt = $conn->prepare("INSERT INTO ipfs_games (game_name, ipfs_hash, original_filename, file_size) VALUES (?, ?, ?, ?)");
        if ($stmt === false) {
            echo json_encode(['success' => false, 'message' => 'Prepare statement failed: ' . $conn->error]);
            exit();
        }

        $stmt->bind_param("sssi", $game_name, $ipfs_hash, $original_filename, $file_size);

        if ($stmt->execute()) {
            echo json_encode(['success' => true, 'message' => 'Game metadata stored successfully.']);
        } else {
            if ($conn->errno == 1062) { // Error code for duplicate entry
                echo json_encode(['success' => false, 'message' => 'Error: IPFS hash already exists.']);
            } else {
                echo json_encode(['success' => false, 'message' => 'Error storing metadata: ' . $stmt->error]);
            }
        }
        $stmt->close();
    } else {
        echo json_encode(['success' => false, 'message' => 'Invalid input. Ensure game_name, ipfs_hash, and a valid file_size are provided.']);
    }
} else {
    echo json_encode(['success' => false, 'message' => 'Invalid request method. Only POST is accepted.']);
}

$conn->close();
?>
