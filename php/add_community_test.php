<?php

header('Content-Type: application/json');

$response = ['success' => false, 'message' => ''];

// Path to the JSON file
$jsonFile = 'community_tests.json';

// Check if the request method is POST
if ($_SERVER['REQUEST_METHOD'] === 'POST') {
    // Get the JSON request body
    $input = file_get_contents('php://input');
    $data = json_decode($input, true);

    // Validate the received data
    if (isset($data['gameName']) && isset($data['youtubeUrl']) && isset($data['description'])) {
        $gameName = $data['gameName'];
        $youtubeUrl = $data['youtubeUrl'];
        $description = $data['description'];

        $tests = [];
        // Try to read the existing JSON file
        if (file_exists($jsonFile)) {
            $currentData = file_get_contents($jsonFile);
            $tests = json_decode($currentData, true);
            if ($tests === null) {
                $tests = []; // Ensure it's an array if JSON is malformed
            }
        }

        // Add the new test
        $tests[] = ['gameName' => $gameName, 'youtubeUrl' => $youtubeUrl, 'description' => $description];

        // Save the updated data to the JSON file
        if (file_put_contents($jsonFile, json_encode($tests, JSON_PRETTY_PRINT))) {
            $response['success'] = true;
            $response['message'] = 'Community test added successfully.';
        } else {
            $response['message'] = 'Error saving the JSON file.';
        }
    } else {
        $response['message'] = 'Invalid data. Make sure to provide gameName, youtubeUrl, and description.';
    }
} else {
    $response['message'] = 'Request method not allowed. Use POST.';
}

echo json_encode($response);

?>
