<?php

header("Content-Type: application/json");

$jsonFile = "community_tests.json";

$tests = [];
if (file_exists($jsonFile)) {
    $currentData = file_get_contents($jsonFile);
    $tests = json_decode($currentData, true);
    if ($tests === null) {
        $tests = [];
    }
}

echo json_encode($tests, JSON_PRETTY_PRINT);

?>
