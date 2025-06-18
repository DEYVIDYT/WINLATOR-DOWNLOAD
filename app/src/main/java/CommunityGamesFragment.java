package com.winlator.Download;

// import android.app.AlertDialog; // To be removed
import androidx.appcompat.app.AlertDialog; // Added for explicit typing
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import android.content.Intent;
// import android.content.SharedPreferences; // Removed
// import android.database.Cursor; // Removed for file selection
// import android.net.Uri; // Removed for file selection
import android.os.Bundle;
// import android.provider.OpenableColumns; // Removed for file selection
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText; // Keep for game name
import android.widget.SeekBar;
import android.widget.Spinner; // Added for Spinner
import android.widget.AdapterView; // Added for Spinner listener
import android.widget.TextView;
import android.widget.Toast;
import android.net.Uri; // Added for torrent file selection
import android.app.Activity; // Added for Activity.RESULT_OK

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.appcompat.widget.SearchView; // Added

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.textfield.TextInputEditText;
import com.winlator.Download.adapter.CommunityGamesAdapter;
import com.winlator.Download.db.UploadRepository;
import com.winlator.Download.model.CommunityGame;
// import com.winlator.Download.model.UploadStatus; // Not directly used for creation here anymore
import com.winlator.Download.service.UploadService;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File; // For file operations
import java.io.FileOutputStream; // For copying file
import java.io.InputStream; // For copying file
import java.io.OutputStream; // For copying file
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

// jLibtorrent imports
import com.frostwire.jlibtorrent.TorrentInfo;
import com.frostwire.jlibtorrent.Sha1Hash; // Though toHex() is on InfoHash directly in newer versions
import com.frostwire.jlibtorrent.LibTorrent; // For version or session state if needed

public class CommunityGamesFragment extends Fragment {

    // private static final int PICK_FILE_REQUEST = 1001; // Removed
    private static final int PICK_TORRENT_FILE_REQUEST = 1002; // New request code for torrent files
    private static final int PICK_SAVE_PATH_REQUEST = 1003; // New request code for save path directory

    private Uri selectedTorrentFileUri; // To store the selected torrent file URI
    private Uri selectedSavePathUri;    // To store the selected save path URI for game data
    private String cachedTorrentFilePath; // To store the file system path of the cached .torrent file

    private Button btnDialogSelectTorrent; // Button to trigger torrent file selection
    private TextView tvDialogSelectedTorrentFile; // TextView to display selected torrent file name
    private Button btnDialogSelectSavePath; // Button to trigger save path selection
    private TextView tvDialogSelectedSavePath; // TextView to display selected save path

    // Torrent metadata
    private String torrentInfoHash;
    private long torrentTotalSize;
    private String torrentNameFromMeta;

    private RecyclerView recyclerView;
    private CommunityGamesAdapter adapter;
    private List<CommunityGame> gamesList;
    private ExecutorService executor;
    private FloatingActionButton fabUpload;
    private UploadRepository uploadRepository;

    // Dialog variables - updated
    private EditText etDialogGameName;
    private TextInputEditText etDialogGameLink;
    private SeekBar sbDialogGameSize;
    private TextView tvDialogSelectedSize;
    private TextInputEditText etDialogManualGameSize;
    private Spinner spinnerDialogSizeUnit;
    private Button btnDialogUpload;

    // Flags to prevent listener loops
    private boolean isUpdatingFromSeekBar = false;
    private boolean isUpdatingFromEditText = false;
    private boolean isUpdatingFromSpinner = false;

    // Constants for size units and SeekBar limits
    private static final int MAX_MB_SEEKBAR = 1024; // e.g. up to 1GB in MB mode for finer control
    private static final int MAX_GB_SEEKBAR = 100;  // e.g. up to 100GB in GB mode
    private static final int GB_SPINNER_POSITION = 1;
    private static final int MB_SPINNER_POSITION = 0;


    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_community_games, container, false);

        // RecyclerView and FAB are initialized here
        recyclerView = view.findViewById(R.id.recycler_view_community_games);
        fabUpload = view.findViewById(R.id.fab_upload);

        // Adapter and LayoutManager setup
        recyclerView.setLayoutManager(new LinearLayoutManager(getContext()));

        gamesList = new ArrayList<>();
        adapter = new CommunityGamesAdapter(gamesList, getContext()); // gamesList is initialized before this
        recyclerView.setAdapter(adapter);

        // Executor and Repository initialization
        executor = Executors.newSingleThreadExecutor();
        uploadRepository = new UploadRepository(getContext()); // Assuming this is still needed or used elsewhere

        // Setup for FAB and initial data load
        setupFabClickListener();
        loadCommunityGames(); // This will populate gamesList and communityGamesListFull in adapter via setGamesList

        return view;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        SearchView searchView = view.findViewById(R.id.search_view_community_games);
        searchView.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
            @Override
            public boolean onQueryTextSubmit(String query) {
                // Optional: Handle search query submission.
                // Usually, filtering is live with onQueryTextChange.
                return false;
            }

            @Override
            public boolean onQueryTextChange(String newText) {
                if (adapter != null) {
                    adapter.getFilter().filter(newText);
                }
                return false;
            }
        });
    }

    private void setupFabClickListener() {
        fabUpload.setOnClickListener(v -> showUploadDialog());
    }

    private void showUploadDialog() {
        // SharedPreferences prefs = requireContext().getSharedPreferences("community_games", requireContext().MODE_PRIVATE); // Removed IA creds
        // String accessKey = prefs.getString("access_key", ""); // Removed
        // String secretKey = prefs.getString("secret_key", ""); // Removed
        // String itemIdentifier = prefs.getString("item_identifier", ""); // Removed

        // if (accessKey.isEmpty() || secretKey.isEmpty() || itemIdentifier.isEmpty()) { // Removed IA check
        //     Toast.makeText(getContext(), "Configure primeiro as credenciais do Internet Archive nas configurações", Toast.LENGTH_LONG).show();
        //     Intent intent = new Intent(getContext(), SettingsActivity.class);
        //     startActivity(intent);
        //     return;
        // }

        // AlertDialog.Builder builder = new AlertDialog.Builder(getContext()); // Old
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(requireContext()); // New
        View dialogView = LayoutInflater.from(getContext()).inflate(R.layout.dialog_upload_game, null);
        builder.setView(dialogView);

        androidx.appcompat.app.AlertDialog dialog = builder.create(); // Changed variable type

        etDialogGameName = dialogView.findViewById(R.id.et_dialog_game_name);
        etDialogGameLink = dialogView.findViewById(R.id.et_dialog_game_link);
        sbDialogGameSize = dialogView.findViewById(R.id.sb_dialog_game_size);
        tvDialogSelectedSize = dialogView.findViewById(R.id.tv_dialog_selected_size);
        etDialogManualGameSize = dialogView.findViewById(R.id.et_dialog_game_size);
        spinnerDialogSizeUnit = dialogView.findViewById(R.id.spinner_dialog_size_unit); // Added
        Button btnCancel = dialogView.findViewById(R.id.btn_dialog_cancel);
        btnDialogUpload = dialogView.findViewById(R.id.btn_dialog_upload);

        // New views for torrent selection
        btnDialogSelectTorrent = dialogView.findViewById(R.id.btn_dialog_select_torrent);
        tvDialogSelectedTorrentFile = dialogView.findViewById(R.id.tv_dialog_selected_torrent_file);
        btnDialogSelectSavePath = dialogView.findViewById(R.id.btn_dialog_select_save_path);
        tvDialogSelectedSavePath = dialogView.findViewById(R.id.tv_dialog_selected_save_path);

        // Hide or disable direct link and manual size input for torrent upload
        etDialogGameLink.setVisibility(View.GONE); // Or setEnabled(false)
        sbDialogGameSize.setVisibility(View.GONE);
        tvDialogSelectedSize.setVisibility(View.GONE);
        etDialogManualGameSize.setVisibility(View.GONE);
        spinnerDialogSizeUnit.setVisibility(View.GONE);

        // Initialize UI states
        isUpdatingFromSeekBar = false; // Reset flags
        isUpdatingFromEditText = false;
        isUpdatingFromSpinner = false;

        // spinnerDialogSizeUnit.setSelection(GB_SPINNER_POSITION); // Default to GB - Not needed for torrent
        // sbDialogGameSize.setMax(MAX_GB_SEEKBAR); // Not needed for torrent
        // etDialogManualGameSize.setText("0"); // Not needed for torrent
        // sbDialogGameSize.setProgress(0); // Not needed for torrent
        // updateSelectedSizeTextView(0, "GB"); // Initial display - Not needed for torrent

        selectedTorrentFileUri = null; // Reset torrent URI
        selectedSavePathUri = null; // Reset save path URI
        cachedTorrentFilePath = null; // Reset cached torrent file path
        torrentInfoHash = null; // Reset torrent metadata
        torrentTotalSize = 0;
        torrentNameFromMeta = null;

        tvDialogSelectedTorrentFile.setText("Nenhum arquivo torrent selecionado");
        tvDialogSelectedSavePath.setText("Nenhum diretório de dados selecionado");
        updateUploadButtonState(); // Initial button state


        TextWatcher formValidationWatcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) { updateUploadButtonState(); }
        };

        etDialogGameName.addTextChangedListener(formValidationWatcher);
        // etDialogGameLink.addTextChangedListener(formValidationWatcher); // No longer used for torrent

        // Listeners for size input are removed as size comes from torrent file.
        // sbDialogGameSize.setOnSeekBarChangeListener(...)
        // etDialogManualGameSize.addTextChangedListener(...)
        // spinnerDialogSizeUnit.setOnItemSelectedListener(...)

        btnDialogSelectTorrent.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("application/x-bittorrent"); // Specific MIME type for torrent files
            // As a fallback if the specific MIME type doesn't work on all devices/file managers:
            // intent.setType("*/*");
            // String[] mimeTypes = {"application/x-bittorrent", "application/octet-stream"}; // Example for multiple types
            // intent.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes);
            try {
                startActivityForResult(intent, PICK_TORRENT_FILE_REQUEST);
            } catch (android.content.ActivityNotFoundException ex) {
                // Fallback if no app handles "application/x-bittorrent"
                intent.setType("*/*"); // General file type
                 try {
                    startActivityForResult(intent, PICK_TORRENT_FILE_REQUEST);
                } catch (android.content.ActivityNotFoundException ex2) {
                    Toast.makeText(getContext(), "Nenhum gerenciador de arquivos encontrado para torrents.", Toast.LENGTH_SHORT).show();
                }
            }
        });

        btnDialogSelectSavePath.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            // Optionally, specify an initial URI to start from
            // intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, initialUri);
            try {
                startActivityForResult(intent, PICK_SAVE_PATH_REQUEST);
            } catch (android.content.ActivityNotFoundException ex) {
                Toast.makeText(getContext(), "Nenhum gerenciador de arquivos encontrado para selecionar diretório.", Toast.LENGTH_SHORT).show();
            }
        });

        btnCancel.setOnClickListener(v -> {
            dialog.dismiss();
            // cleanupDialogViewReferences(); // Handled by onDismiss
        });

        btnDialogUpload.setOnClickListener(v -> {
            String gameName = etDialogGameName.getText().toString().trim();

            if (TextUtils.isEmpty(gameName)) {
                etDialogGameName.setError("Nome do jogo é obrigatório");
                Toast.makeText(getContext(), "Nome do jogo é obrigatório", Toast.LENGTH_SHORT).show();
                return;
            }
            if (selectedTorrentFileUri == null || cachedTorrentFilePath == null) {
                Toast.makeText(getContext(), "Selecione um arquivo torrent válido", Toast.LENGTH_SHORT).show();
                return;
            }
            if (torrentInfoHash == null) { // Check if torrent was processed
                Toast.makeText(getContext(), "Arquivo torrent não processado ou inválido", Toast.LENGTH_SHORT).show();
                return;
            }
            if (selectedSavePathUri == null) {
                Toast.makeText(getContext(), "Selecione o diretório de dados do jogo", Toast.LENGTH_SHORT).show();
                return;
            }

            String finalGameName = TextUtils.isEmpty(etDialogGameName.getText().toString().trim()) ? torrentNameFromMeta : etDialogGameName.getText().toString().trim();
             if (TextUtils.isEmpty(finalGameName)) {
                 etDialogGameName.setError("Nome do jogo é obrigatório");
                 Toast.makeText(getContext(), "Nome do jogo não pode ser vazio", Toast.LENGTH_SHORT).show();
                return;
            }

            // UploadService Intent
            Intent uploadIntent = new Intent(getContext(), UploadService.class);
            uploadIntent.putExtra("game_name", finalGameName);
            uploadIntent.putExtra("info_hash", torrentInfoHash);
            uploadIntent.putExtra("game_size_bytes", torrentTotalSize);
            uploadIntent.putExtra("file_name", finalGameName); // Or torrentNameFromMeta
            uploadIntent.putExtra("torrent_file_path", cachedTorrentFilePath); // Path to the .torrent file itself
            uploadIntent.putExtra("save_path_uri", selectedSavePathUri.toString()); // URI of the selected game data directory


            ContextCompat.startForegroundService(requireContext(), uploadIntent);

            Toast.makeText(getContext(), "Registro do torrent iniciado...", Toast.LENGTH_SHORT).show();
            dialog.dismiss();
            // cleanupDialogViewReferences(); // Handled by onDismiss
        });

        dialog.setOnDismissListener(dialogInterface -> cleanupDialogViewReferences());
        dialog.show();
    }


    @Override
    public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PICK_TORRENT_FILE_REQUEST && resultCode == Activity.RESULT_OK && data != null && data.getData() != null) {
            selectedTorrentFileUri = data.getData();
            String fileName = getFileNameFromUri(selectedTorrentFileUri);
            if (tvDialogSelectedTorrentFile != null) {
                tvDialogSelectedTorrentFile.setText(fileName);
            }
            // TODO: Call a method here to parse the torrent file using jLibtorrent
            // e.g., parseTorrentFile(selectedTorrentFileUri);
            // This method would extract info hash, files, total size, etc.
            processTorrentFile(selectedTorrentFileUri);
            // updateUploadButtonState(); // Called within processTorrentFile or its callbacks
        } else if (requestCode == PICK_SAVE_PATH_REQUEST && resultCode == Activity.RESULT_OK && data != null && data.getData() != null) {
            selectedSavePathUri = data.getData();
            // Persist permission for the selected directory (optional, but good for long-term access)
            // final int takeFlags = intent.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            // getContext().getContentResolver().takePersistableUriPermission(selectedSavePathUri, takeFlags);

            if (tvDialogSelectedSavePath != null) {
                // Displaying the URI directly might be long. Consider getting a displayable name if possible.
                // For ACTION_OPEN_DOCUMENT_TREE, the path segment of the URI is often human-readable enough.
                String path = selectedSavePathUri.getPath(); // This might give a decent representation
                 // Or use DocumentFile to get name: DocumentFile.fromTreeUri(getContext(), selectedSavePathUri).getName();
                tvDialogSelectedSavePath.setText("Caminho: " + path);
            }
            updateUploadButtonState();
        }
    }

    private void processTorrentFile(Uri torrentUri) {
        if (getContext() == null || torrentUri == null) {
            Toast.makeText(getContext(), "Erro: Contexto ou URI do torrent nulo.", Toast.LENGTH_SHORT).show();
            torrentInfoHash = null; // Ensure hash is null on error
            updateUploadButtonState();
            return;
        }

        cachedTorrentFilePath = null; // Reset before attempting to copy
        File tempFile = copyUriToCache(torrentUri, "selected_torrent.torrent");
        if (tempFile == null) {
            Toast.makeText(getContext(), "Falha ao copiar arquivo torrent.", Toast.LENGTH_SHORT).show();
            selectedTorrentFileUri = null; // Reset as processing failed
            if (tvDialogSelectedTorrentFile != null) tvDialogSelectedTorrentFile.setText("Nenhum arquivo torrent selecionado");
            torrentInfoHash = null; // Clear previous torrent data
            updateUploadButtonState();
            return;
        }
        cachedTorrentFilePath = tempFile.getAbsolutePath(); // Store the path of the cached .torrent file

        try {
            TorrentInfo ti = new TorrentInfo(tempFile);
            torrentInfoHash = ti.infoHash().toHex();
            torrentTotalSize = ti.totalSize();
            torrentNameFromMeta = ti.name();

            // No need to delete tempFile immediately, its path (cachedTorrentFilePath) will be passed to UploadService
            // UploadService or TorrentSeedingService can manage its lifecycle if needed (e.g., copy it elsewhere or delete after use)

            if (getActivity() != null) {
                 getActivity().runOnUiThread(() -> {
                    updateDialogWithTorrentData();
                    updateUploadButtonState();
                    Toast.makeText(getContext(), "Arquivo torrent processado.", Toast.LENGTH_SHORT).show();
                });
            } else { // If activity is not available, log and potentially handle error
                 Log.e("CommunityGamesFragment", "Activity not available to update UI after torrent processing.");
                 // This state might be problematic, consider how to handle if UI can't be updated.
            }

        } catch (Exception e) {
            Log.e("CommunityGamesFragment", "Erro ao processar arquivo torrent: " + e.getMessage(), e);
            Toast.makeText(getContext(), "Erro ao ler arquivo torrent: " + e.getMessage(), Toast.LENGTH_LONG).show();
            selectedTorrentFileUri = null;
            if (tvDialogSelectedTorrentFile != null) tvDialogSelectedTorrentFile.setText("Nenhum arquivo torrent selecionado");
            torrentInfoHash = null;
            cachedTorrentFilePath = null; // Clear path if processing failed
            updateUploadButtonState();
        }
        // Removed finally block that deleted tempFile, as we need its path for UploadService
    }

    private void updateDialogWithTorrentData() {
        if (etDialogGameName != null && !TextUtils.isEmpty(torrentNameFromMeta)) {
            etDialogGameName.setText(torrentNameFromMeta);
        }
        if (tvDialogSelectedSize != null) { // Re-purpose this TextView or add a new one for torrent size
            tvDialogSelectedSize.setText(formatFileSize(torrentTotalSize));
            tvDialogSelectedSize.setVisibility(View.VISIBLE); // Make sure it's visible
        }
        // Hide manual size input if torrent data is successfully loaded
        if (sbDialogGameSize != null) sbDialogGameSize.setVisibility(View.GONE);
        if (etDialogManualGameSize != null) etDialogManualGameSize.setVisibility(View.GONE);
        if (spinnerDialogSizeUnit != null) spinnerDialogSizeUnit.setVisibility(View.GONE);
    }

    private File copyUriToCache(Uri uri, StringfileName) {
        if (getContext() == null) return null;
        File cacheDir = getContext().getCacheDir();
        File tempFile = new File(cacheDir, fileName);
        try {
            InputStream inputStream = getContext().getContentResolver().openInputStream(uri);
            OutputStream outputStream = new FileOutputStream(tempFile);
            if (inputStream != null) {
                byte[] buf = new byte[1024 * 4]; // 4KB buffer
                int len;
                while ((len = inputStream.read(buf)) > 0) {
                    outputStream.write(buf, 0, len);
                }
                outputStream.close();
                inputStream.close();
                return tempFile;
            }
        } catch (Exception e) {
            Log.e("CommunityGamesFragment", "Falha ao copiar URI para o cache", e);
            if (tempFile.exists()) { // Clean up partially written file
                tempFile.delete();
            }
            return null;
        }
        return null; // Should not be reached if inputStream is null and not caught by exception
    }


    private String getFileNameFromUri(Uri uri) {
        String fileName = null;
        if (getContext() == null) return "Arquivo Torrent"; // Fallback

        if (uri.getScheme() != null && uri.getScheme().equals("content")) {
            try (android.database.Cursor cursor = getContext().getContentResolver().query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                    if (nameIndex != -1) {
                         fileName = cursor.getString(nameIndex);
                    }
                }
            } catch (Exception e) {
                Log.e("CommunityGamesFragment", "Erro ao obter nome do arquivo da Uri: " + e.getMessage());
            }
        }
        if (fileName == null) {
            fileName = uri.getPath();
            if (fileName != null) {
                int cut = fileName.lastIndexOf('/');
                if (cut != -1) {
                    fileName = fileName.substring(cut + 1);
                }
            }
        }
        return fileName != null ? fileName : "Arquivo Torrent";
    }

    // Method to update the selected size TextView (tvDialogSelectedSize) - Now less relevant for torrents directly
    private void updateSelectedSizeTextView(double value, String unit) {
        if (tvDialogSelectedSize == null) return;
        if ("MB".equalsIgnoreCase(unit)) {
            // For MB, typically whole numbers are expected by users in this context
            tvDialogSelectedSize.setText(String.format(java.util.Locale.getDefault(), "%.0f MB", value));
        } else { // GB
            // For GB, allow one decimal place
            tvDialogSelectedSize.setText(String.format(java.util.Locale.getDefault(), "%.1f GB", value));
        }
    }

    private void updateUploadButtonState() {
        if (btnDialogUpload == null || etDialogGameName == null) { // etDialogGameLink and size inputs removed from check
            return; // Dialog not fully initialized or already dismissed
        }
        String gameName = etDialogGameName.getText().toString().trim();

        // Enable upload if game name is present, torrent has been successfully processed (infoHash is available),
        // AND a save path for game data has been selected.
        btnDialogUpload.setEnabled(!TextUtils.isEmpty(gameName) && torrentInfoHash != null && selectedSavePathUri != null);
    }

    // updateSeekBarFromManualInput was removed, logic integrated into listeners. - Still relevant if non-torrent path exists
    // Re-adding a refined version or ensuring listeners cover all sync.
    // The TextWatcher for etDialogManualGameSize already updates the SeekBar.
    // The Spinner's onItemSelected listener also updates the SeekBar based on current EditText value and new unit.

    private boolean isValidNumber(String str) {
        if (str == null || str.trim().isEmpty()) return false;
        try {
            Double.parseDouble(str.trim());
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private void cleanupDialogViewReferences() {
        etDialogGameName = null;
        etDialogGameLink = null;
        sbDialogGameSize = null;
        tvDialogSelectedSize = null;
        etDialogManualGameSize = null;
        spinnerDialogSizeUnit = null; // Added
        btnDialogUpload = null;
        btnDialogSelectTorrent = null; // Added for cleanup
        tvDialogSelectedTorrentFile = null; // Added for cleanup
        btnDialogSelectSavePath = null; // Added for cleanup
        tvDialogSelectedSavePath = null; // Added for cleanup
        selectedTorrentFileUri = null; // Reset URI on cleanup
        selectedSavePathUri = null; // Reset save path URI
        cachedTorrentFilePath = null; // Reset cached torrent file path
        torrentInfoHash = null; // Reset torrent data
        torrentTotalSize = 0;
        torrentNameFromMeta = null;
    }

    // formatFileSize can be kept if used elsewhere, or removed if only for old dialog - Less relevant for torrent display directly
    private String formatFileSize(long size) {
        if (size < 1024) return size + " B"; // Ensure this utility is still needed. UploadService has one.
        if (size < 1024 * 1024) return String.format("%.1f KB", size / 1024.0);
        if (size < 1024 * 1024 * 1024) return String.format("%.1f MB", size / (1024.0 * 1024.0));
        return String.format("%.1f GB", size / (1024.0 * 1024.0 * 1024.0));
    }

    private void loadCommunityGames() {
        executor.execute(() -> {
            try {
                URL url = new URL("https://ldgames.x10.mx/list_games.php");
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(10000);
                connection.setReadTimeout(10000);

                int responseCode = connection.getResponseCode();
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
                    StringBuilder response = new StringBuilder();
                    String line;

                    while ((line = reader.readLine()) != null) {
                        response.append(line);
                    }
                    reader.close();

                    parseGamesJson(response.toString());
                } else {
                    if (getActivity() != null) {
                        getActivity().runOnUiThread(() ->
                            Toast.makeText(getContext(), "Erro ao carregar jogos da comunidade: " + responseCode, Toast.LENGTH_SHORT).show() // Added responseCode
                        );
                    }
                }
                connection.disconnect();
            } catch (Exception e) {
                Log.e("CommunityGamesFragment", "Erro ao carregar jogos: ", e); // Log exception
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() ->
                        Toast.makeText(getContext(), "Erro de conexão ao carregar jogos", Toast.LENGTH_SHORT).show() // More specific
                    );
                }
            }
        });
    }

    private void parseGamesJson(String jsonString) {
        try {
            JSONArray jsonArray = new JSONArray(jsonString);
            List<CommunityGame> newGamesList = new ArrayList<>();

            for (int i = 0; i < jsonArray.length(); i++) {
                JSONObject gameObject = jsonArray.getJSONObject(i);
                String name = gameObject.getString("name");
                String size = gameObject.getString("size"); // Consider if this field is still relevant if all uploads are torrents
                String url = gameObject.getString("url");   // This URL might now point to a torrent info page or be unused

                CommunityGame game = new CommunityGame(name, size, url);
                newGamesList.add(game);
            }

            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    // if (newGamesList != null) { // newGamesList is always initialized
                    // java.util.Collections.reverse(newGamesList); // Consider if reversing is desired
                    // }
                    if (adapter != null) {
                         adapter.setGamesList(newGamesList); // Update adapter with new list
                    } else {
                        // Initialize adapter if it's null and we have data (e.g., if view was recreated)
                        // This might be redundant if onCreateView always sets up the adapter.
                        // adapter = new CommunityGamesAdapter(newGamesList, getContext());
                        // recyclerView.setAdapter(adapter);
                    }
                });
            }
        } catch (JSONException e) {
            Log.e("CommunityGamesFragment", "Erro ao parsear JSON de jogos: ", e); // Log exception
            if (getActivity() != null) {
                getActivity().runOnUiThread(() ->
                    Toast.makeText(getContext(), "Erro ao processar dados dos jogos", Toast.LENGTH_SHORT).show() // More specific
                );
            }
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        cleanupDialogViewReferences();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (executor != null && !executor.isShutdown()) {
            executor.shutdown();
        }
    }
}
