<?php

header("Content-Type: application/json");

$jsonFile = "community_fixes.json";

$fixes = [];
if (file_exists($jsonFile)) {
    $currentData = file_get_contents($jsonFile);
    $fixes = json_decode($currentData, true);
    if ($fixes === null) {
        $fixes = [];
    }
}

echo json_encode($fixes, JSON_PRETTY_PRINT);

?>
