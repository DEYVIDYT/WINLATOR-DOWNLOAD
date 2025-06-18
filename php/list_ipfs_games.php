<?php
header('Content-Type: application/json');

// IMPORTANT: Replace with your actual database credentials!
$servername = "your_server_name"; // e.g., localhost
$username = "your_db_username";
$password = "your_db_password";
$dbname = "your_db_name";

$conn = new mysqli($servername, $username, $password, $dbname);

if ($conn->connect_error) {
    echo json_encode(['success' => false, 'message' => 'Database connection failed: ' . $conn->connect_error, 'games' => []]);
    exit();
}

$games = [];
$sql = "SELECT id, game_name, ipfs_hash, original_filename, file_size, upload_timestamp FROM ipfs_games ORDER BY upload_timestamp DESC";
$result = $conn->query($sql);

if ($result) {
    while ($row = $result->fetch_assoc()) {
        // Ensure file_size is treated as a number (long in Java)
        $row['file_size'] = (int)$row['file_size']; // Or use floatval if sizes can be non-integer, though BIGINT implies int
        $games[] = $row;
    }
    echo json_encode(['success' => true, 'games' => $games]);
} else {
    echo json_encode(['success' => false, 'message' => 'Error fetching games: ' . $conn->error, 'games' => []]);
}

$conn->close();
?>
