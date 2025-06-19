<?php

header('Content-Type: application/json');

$response = ['success' => false, 'message' => ''];

// Path to the JSON file
$jsonFile = 'community_fixes.json';

// Check if the request method is POST
if ($_SERVER['REQUEST_METHOD'] === 'POST') {
    // Get the JSON request body
    $input = file_get_contents('php://input');
    $data = json_decode($input, true);

    // Validate the received data
    if (isset($data['fixName']) && isset($data['description']) && isset($data['downloadUrl'])) {
        $fixName = $data['fixName'];
        $description = $data['description'];
        $downloadUrl = $data['downloadUrl'];

        $fixes = [];
        // Try to read the existing JSON file
        if (file_exists($jsonFile)) {
            $currentData = file_get_contents($jsonFile);
            $fixes = json_decode($currentData, true);
            if ($fixes === null) {
                $fixes = []; // Ensure it's an array if JSON is malformed
            }
        }

        // Add the new fix
        $fixes[] = ['fixName' => $fixName, 'description' => $description, 'downloadUrl' => $downloadUrl];

        // Save the updated data to the JSON file
        if (file_put_contents($jsonFile, json_encode($fixes, JSON_PRETTY_PRINT))) {
            $response['success'] = true;
            $response['message'] = 'Community fix added successfully.';
        } else {
            $response['message'] = 'Error saving the JSON file.';
        }
    } else {
        $response['message'] = 'Invalid data. Make sure to provide fixName, description, and downloadUrl.';
    }
} else {
    $response['message'] = 'Request method not allowed. Use POST.';
}

echo json_encode($response);

?>
