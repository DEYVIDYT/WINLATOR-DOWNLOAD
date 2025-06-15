<?php

header("Content-Type: application/json");

$jsonFile = "games.json";

$games = [];
if (file_exists($jsonFile)) {
    $currentData = file_get_contents($jsonFile);
    $games = json_decode($currentData, true);
    if ($games === null) {
        $games = [];
    }
}

echo json_encode($games, JSON_PRETTY_PRINT);

?>

