<?php

header('Content-Type: application/json');

$response = ['success' => false, 'message' => ''];

// Caminho para o arquivo JSON
$jsonFile = 'games.json';

// Verifica se o método da requisição é POST
if ($_SERVER['REQUEST_METHOD'] === 'POST') {
    // Obtém o corpo da requisição JSON
    $input = file_get_contents('php://input');
    $data = json_decode($input, true);

    // Valida os dados recebidos
    if (isset($data['name']) && isset($data['size']) && isset($data['url'])) {
        $gameName = $data['name'];
        $gameSize = $data['size'];
        $gameUrl = $data['url'];

        $games = [];
        // Tenta ler o arquivo JSON existente
        if (file_exists($jsonFile)) {
            $currentData = file_get_contents($jsonFile);
            $games = json_decode($currentData, true);
            if ($games === null) {
                $games = []; // Garante que é um array se o JSON estiver malformado
            }
        }

        // Adiciona ou atualiza o jogo
        $found = false;
        foreach ($games as &$game) {
            if ($game['name'] === $gameName) {
                $game['size'] = $gameSize;
                $game['url'] = $gameUrl;
                $found = true;
                break;
            }
        }

        if (!$found) {
            $games[] = ['name' => $gameName, 'size' => $gameSize, 'url' => $gameUrl];
        }

        // Salva os dados atualizados no arquivo JSON
        if (file_put_contents($jsonFile, json_encode($games, JSON_PRETTY_PRINT))) {
            $response['success'] = true;
            $response['message'] = 'Jogo adicionado/atualizado com sucesso.';
        } else {
            $response['message'] = 'Erro ao salvar o arquivo JSON.';
        }
    } else {
        $response['message'] = 'Dados inválidos. Certifique-se de fornecer nome, tamanho e URL.';
    }
} else {
    $response['message'] = 'Método de requisição não permitido. Use POST.';
}

echo json_encode($response);

?>

