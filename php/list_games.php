<?php

header("Content-Type: application/json");

$jsonFile = "games.json";

$games = [];
if (file_exists($jsonFile)) {
    $currentData = file_get_contents($jsonFile);
    $decodedGames = json_decode($currentData, true); // Read into a temporary variable
    if ($decodedGames === null) {
        $decodedGames = []; // Handle malformed JSON
    }

    // Filter for approved games
    foreach ($decodedGames as $game) { // Iterate over decoded, not-yet-filtered games
        if (isset($game['status']) && $game['status'] === 'approved') {
            $games[] = $game; // Add to the final $games list
        }
    }
}

echo json_encode($games, JSON_PRETTY_PRINT);

?>

