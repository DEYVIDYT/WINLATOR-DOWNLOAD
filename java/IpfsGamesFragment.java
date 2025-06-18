package com.winlator.Download;

import android.app.Activity;
import android.app.ProgressDialog; // Added
import android.content.Context; // Added
import android.content.Intent;
import android.database.Cursor;
import android.net.ConnectivityManager; // Added
import android.net.NetworkInfo; // Added
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.util.Log; // Added for logging
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView; // Added
import android.widget.Toast;
import android.content.DialogInterface;


import androidx.annotation.NonNull;
import androidx.annotation.Nullable; // Ensure this is imported for @Nullable
import androidx.appcompat.app.AlertDialog; // For AlertDialog
import androidx.appcompat.widget.SearchView;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout; // Added

import com.google.android.material.floatingactionbutton.FloatingActionButton; // Correct import for FAB
import com.winlator.Download.adapter.IpfsGamesAdapter; // Added
import com.winlator.Download.model.IpfsGame; // Added

import org.json.JSONArray; // Added
import java.io.BufferedReader; // For reading response
import java.io.ByteArrayOutputStream;
import java.io.File; // For file operations
import java.io.FileOutputStream; // For writing file
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader; // For reading response
import java.io.OutputStream;
import android.os.Environment; // For accessing public Downloads directory
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List; // For the result of ipfs.add
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.json.JSONObject; // For parsing JSON response

import io.ipfs.api.IPFS;
import io.ipfs.api.NamedStreamable;
import io.ipfs.multihash.Multihash; // Correct import for Multihash

public class IpfsGamesFragment extends Fragment implements IpfsGamesAdapter.OnIpfsGameAction { // Implement interface

    // private RecyclerView recyclerView; // Renamed
    // private Object adapter; // Replaced
    // private List<Object> gamesList; // Replaced

    private RecyclerView recyclerViewIpfsGames;
    private IpfsGamesAdapter ipfsGamesAdapter;
    private List<IpfsGame> ipfsGamesList;
    private SwipeRefreshLayout swipeRefreshLayoutIpfs;


    // IPFS and File Picker related variables
    private static final int PICK_FILE_REQUEST_CODE = 1001;
    // TODO: Make IPFS_NODE_ADDRESS configurable through settings
    public static final String DEFAULT_IPFS_NODE_ADDRESS = "/ip4/127.0.0.1/tcp/5001";
    private IPFS ipfs;
    private ExecutorService ipfsExecutor;
    private FloatingActionButton fabAddIpfsFile; // Declare FAB (using the correct type)
    private ProgressDialog progressDialog; // Added
    private TextView tvEmptyListMessage; // Added

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_ipfs_games, container, false);

        recyclerViewIpfsGames = view.findViewById(R.id.recycler_view_ipfs_games);
        fabAddIpfsFile = view.findViewById(R.id.fab_add_ipfs_file);
        swipeRefreshLayoutIpfs = view.findViewById(R.id.swipe_refresh_layout_ipfs_games);
        tvEmptyListMessage = view.findViewById(R.id.tv_empty_ipfs_games_list); // Initialized

        return view;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        ipfsGamesList = new ArrayList<>();
        ipfsGamesAdapter = new IpfsGamesAdapter(ipfsGamesList, getContext(), this);
        recyclerViewIpfsGames.setLayoutManager(new LinearLayoutManager(getContext()));
        recyclerViewIpfsGames.setAdapter(ipfsGamesAdapter);

        progressDialog = new ProgressDialog(getContext()); // Initialized
        progressDialog.setCancelable(false);

        try {
            ipfs = new IPFS(DEFAULT_IPFS_NODE_ADDRESS);
            Log.d("IpfsGamesFragment", "IPFS client initialized for: " + DEFAULT_IPFS_NODE_ADDRESS);
        } catch (Exception e) {
            Log.e("IpfsGamesFragment", "Failed to initialize IPFS client. Ensure IPFS daemon is running at " + DEFAULT_IPFS_NODE_ADDRESS, e);
            if (getContext() != null) {
                 Toast.makeText(getContext(), "IPFS node not accessible. Check configuration and network.", Toast.LENGTH_LONG).show();
            }
            if (fabAddIpfsFile != null) {
                fabAddIpfsFile.setEnabled(false);
            }
            // Also, potentially update the empty list view to indicate IPFS issues
            if (tvEmptyListMessage != null && recyclerViewIpfsGames != null) {
                 tvEmptyListMessage.setText("Could not connect to IPFS node. Please check configuration and network.");
                 tvEmptyListMessage.setVisibility(View.VISIBLE);
                 recyclerViewIpfsGames.setVisibility(View.GONE);
            }
        }
        ipfsExecutor = Executors.newSingleThreadExecutor();

        setupFabClickListener();
        loadIpfsGamesList(); // Initial load

        swipeRefreshLayoutIpfs.setOnRefreshListener(this::loadIpfsGamesList);

        SearchView searchView = view.findViewById(R.id.search_view_ipfs_games);
        searchView.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
            @Override
            public boolean onQueryTextSubmit(String query) {
                return false;
            }

            @Override
            public boolean onQueryTextChange(String newText) {
                if (ipfsGamesAdapter != null) {
                     ipfsGamesAdapter.getFilter().filter(newText);
                }
                return false;
            }
        });
    }

    @Override
    public void onDownloadClicked(IpfsGame game) {
        if (!isNetworkAvailable()) {
            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> Toast.makeText(getContext(), "No internet connection.", Toast.LENGTH_SHORT).show());
            }
            return;
        }
        if (ipfs == null) {
            if (getContext() != null) Toast.makeText(getContext(), "IPFS client not initialized. Cannot download.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (getContext() == null || getActivity() == null) {
            Log.e("IpfsGamesFragment", "Context or Activity is null in onDownloadClicked");
            return;
        }

        File downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        if (!downloadsDir.exists()) {
            if (!downloadsDir.mkdirs()) { // Attempt to create it if it doesn't exist
                Log.e("IpfsGamesFragment", "Failed to create Downloads directory.");
                Toast.makeText(getContext(), "Failed to create Downloads directory.", Toast.LENGTH_SHORT).show();
                return;
            }
        }

        String fileName = game.getOriginalFilename();
        if (fileName == null || fileName.isEmpty()) {
            fileName = game.getIpfsHash() + ".dat"; // Fallback filename
        }
        File destinationFile = new File(downloadsDir, fileName);

        // Inform user that download is starting
        // Toast.makeText(getContext(), "Starting download for: " + game.getGameName(), Toast.LENGTH_SHORT).show(); // Replaced by ProgressDialog
        Log.d("IpfsGamesFragment", "Attempting to download IPFS hash: " + game.getIpfsHash() + " to " + destinationFile.getAbsolutePath());

        if (getActivity() != null) {
            getActivity().runOnUiThread(() -> {
                progressDialog.setMessage("Downloading from IPFS...");
                progressDialog.show();
            });
        }

        ipfsExecutor.execute(() -> {
            try {
                byte[] fileContents = ipfs.cat(Multihash.fromBase58(game.getIpfsHash()));

                if (fileContents == null || fileContents.length == 0) {
                    getActivity().runOnUiThread(() ->
                        Toast.makeText(getContext(), "Failed to download: File is empty or not found on IPFS.", Toast.LENGTH_LONG).show()
                    );
                    return;
                }

                // Write the bytes to the destination file
                try (FileOutputStream fos = new FileOutputStream(destinationFile)) {
                    fos.write(fileContents);
                }

                // Notify user of success on UI thread
                getActivity().runOnUiThread(() -> {
                    Toast.makeText(getContext(), game.getGameName() + " downloaded successfully to Downloads folder.", Toast.LENGTH_LONG).show();
                    Log.d("IpfsGamesFragment", "File downloaded: " + destinationFile.getAbsolutePath());
                    // Optionally, send a broadcast to make the file visible to MediaScanner
                    // Intent mediaScanIntent = new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE);
                    // mediaScanIntent.setData(Uri.fromFile(destinationFile));
                    // getContext().sendBroadcast(mediaScanIntent);
                });

            } catch (IOException e) {
                Log.e("IpfsGamesFragment", "File writing failed for " + game.getIpfsHash(), e);
                final String errorMsg = e.getMessage();
                getActivity().runOnUiThread(() ->
                    Toast.makeText(getContext(), "Download failed (File I/O Error): " + errorMsg, Toast.LENGTH_LONG).show()
                );
            } catch (Exception e) { // Catch other exceptions like IPFS client errors, Multihash format errors
                Log.e("IpfsGamesFragment", "IPFS download failed for " + game.getIpfsHash(), e);
                final String errorMsg = e.getMessage();
                if (getActivity() != null) getActivity().runOnUiThread(() -> {
                    if (getContext() != null) Toast.makeText(getContext(), "Download failed: " + errorMsg, Toast.LENGTH_LONG).show();
                });
            } finally {
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        if (progressDialog != null && progressDialog.isShowing()) {
                            progressDialog.dismiss();
                        }
                    });
                }
            }
        });
    }

    private void setupFabClickListener() {
        if (fabAddIpfsFile != null) {
            fabAddIpfsFile.setOnClickListener(v -> {
                if (!isNetworkAvailable()) {
                     if (getActivity() != null) getActivity().runOnUiThread(() -> Toast.makeText(getContext(), "No internet connection.", Toast.LENGTH_SHORT).show());
                    return;
                }
                if (ipfs == null) {
                    if (getContext() != null) Toast.makeText(getContext(), "IPFS client not available. Cannot select file.", Toast.LENGTH_LONG).show();
                    return;
                }
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("*/*"); // Allow any file type
                startActivityForResult(intent, PICK_FILE_REQUEST_CODE);
            });
        } else {
            Log.e("IpfsGamesFragment", "fabAddIpfsFile is null in setupFabClickListener");
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PICK_FILE_REQUEST_CODE && resultCode == Activity.RESULT_OK && data != null && data.getData() != null) {
            Uri fileUri = data.getData();
            String fileName = getFileNameFromUri(fileUri);
            if (getContext() != null) {
                Toast.makeText(getContext(), "Selected file: " + fileName, Toast.LENGTH_SHORT).show();
            }
            uploadFileToIpfs(fileUri, fileName);
        }
    }

    private String getFileNameFromUri(Uri uri) {
        String fileName = null;
        if (getContext() == null || uri == null) return "unknown_file";
        Cursor cursor = null;
        try {
            cursor = getContext().getContentResolver().query(uri, null, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (nameIndex != -1) {
                    fileName = cursor.getString(nameIndex);
                }
            }
        } catch (Exception e) {
            Log.e("IpfsGamesFragment", "Error getting file name from URI", e);
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return fileName != null ? fileName : (uri.getLastPathSegment() != null ? uri.getLastPathSegment() : "unknown_file");
    }

    private void uploadFileToIpfs(Uri fileUri, String originalFileName) {
        if (ipfs == null) {
            if (getContext() != null) Toast.makeText(getContext(), "IPFS client not initialized.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (getContext() == null || getActivity() == null) {
            Log.w("IpfsGamesFragment", "Context or Activity is null in uploadFileToIpfs, cannot proceed.");
            return;
        }
        if (!isNetworkAvailable()) {
            if (getActivity() != null) getActivity().runOnUiThread(() -> Toast.makeText(getContext(), "No internet connection.", Toast.LENGTH_SHORT).show());
            return;
        }

        if (getActivity() != null) {
            getActivity().runOnUiThread(() -> {
                progressDialog.setMessage("Uploading to IPFS...");
                progressDialog.show();
            });
        }
        // Toast for starting upload is removed as ProgressDialog is shown.

        ipfsExecutor.execute(() -> {
            try (InputStream inputStream = getContext().getContentResolver().openInputStream(fileUri)) {
                if (inputStream == null) {
                    if (getActivity() != null) getActivity().runOnUiThread(() -> {
                        if (getContext() != null) Toast.makeText(getContext(), "Failed to open file stream.", Toast.LENGTH_SHORT).show();
                    });
                    return;
                }

                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                byte[] data = new byte[4096]; // Increased buffer size
                int nRead;
                while ((nRead = inputStream.read(data, 0, data.length)) != -1) {
                    buffer.write(data, 0, nRead);
                }
                buffer.flush();
                byte[] fileBytes = buffer.toByteArray();

                NamedStreamable.ByteArrayWrapper fileWrapper = new NamedStreamable.ByteArrayWrapper(originalFileName, fileBytes);
                List<Multihash> result = ipfs.add(fileWrapper, true, false);

                if (result != null && !result.isEmpty()) {
                    Multihash fileHash = result.get(result.size() - 1); // Often the last one if wrapped
                    String cid = fileHash.toBase58();
                    long fileSize = fileBytes.length; // ensure fileSize is captured

                    // NOW, call the new dialog method:
                    final String finalCid = cid; // Variables used in lambda should be final or effectively final
                    final String finalOriginalFileName = originalFileName;
                    final long finalFileSize = fileSize;
                    if (getActivity() != null) {
                        getActivity().runOnUiThread(() -> promptForGameName(finalCid, finalOriginalFileName, finalFileSize));
                    }
                } else {
                    if (getActivity() != null) {
                        getActivity().runOnUiThread(() -> {
                            if (getContext() != null) Toast.makeText(getContext(), "IPFS upload failed. No hash returned.", Toast.LENGTH_SHORT).show();
                        });
                    }
                }

            } catch (IOException e) {
                Log.e("IpfsGamesFragment", "IPFS upload IO failed", e);
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        if (getContext() != null) Toast.makeText(getContext(), "IPFS upload error (IO): " + e.getMessage(), Toast.LENGTH_LONG).show();
                    });
                }
            } catch (Exception e) {
                Log.e("IpfsGamesFragment", "IPFS client error or other exception during upload", e);
                if (getActivity() != null) getActivity().runOnUiThread(() -> {
                    if (getContext() != null) Toast.makeText(getContext(), "IPFS client/general error: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            } finally {
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        if (progressDialog != null && progressDialog.isShowing()) {
                            progressDialog.dismiss();
                        }
                    });
                }
            }
        });
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (ipfsExecutor != null && !ipfsExecutor.isShutdown()) {
            ipfsExecutor.shutdown();
        }
        if (progressDialog != null && progressDialog.isShowing()) { // Dismiss progress dialog
            progressDialog.dismiss();
        }
        // Nullify views to avoid leaks
        recyclerViewIpfsGames = null;
        fabAddIpfsFile = null;
        ipfsGamesAdapter = null;
        ipfsGamesList = null;
        swipeRefreshLayoutIpfs = null;
        tvEmptyListMessage = null; // Added
        Log.d("IpfsGamesFragment", "onDestroyView called, ipfsExecutor shut down.");
    }

    // Remove the old onDestroy method if it only contained the old executor shutdown
    // @Override
    // public void onDestroy() {
    //     super.onDestroy();
    //     // if (executor != null && !executor.isShutdown()) { // Old executor
    //     //     executor.shutdown();
    //     // }
    // }

    private void promptForGameName(String cid, String originalFileName, long fileSize) {
        if (getContext() == null || getActivity() == null) return;

        AlertDialog.Builder builder = new AlertDialog.Builder(getContext());
        builder.setTitle("Enter Game Name");
        builder.setMessage("Enter a name for the game associated with IPFS CID: " + cid);

        // Set up the input
        final EditText input = new EditText(getContext());
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setText(originalFileName); // Pre-fill with original file name
        builder.setView(input);

        // Set up the buttons
        builder.setPositiveButton("OK", (dialog, which) -> {
            String gameName = input.getText().toString().trim();
            if (gameName.isEmpty()) {
                Toast.makeText(getContext(), "Game name cannot be empty.", Toast.LENGTH_SHORT).show();
                // Optionally, re-show dialog or handle differently
            } else {
                storeIpfsGameMetadataOnBackend(gameName, cid, originalFileName, fileSize);
            }
        });
        builder.setNegativeButton("Cancel", (dialog, which) -> {
            dialog.cancel();
            Toast.makeText(getContext(), "IPFS upload complete, but not saved to app list.", Toast.LENGTH_SHORT).show();
        });

        // Ensure dialog is shown on UI thread
        getActivity().runOnUiThread(() -> builder.show());
    }

    private void storeIpfsGameMetadataOnBackend(String gameName, String cid, String originalFileName, long fileSize) {
        if (getContext() == null || getActivity() == null) return;
        if (!isNetworkAvailable()) {
            if (getActivity() != null) getActivity().runOnUiThread(() -> Toast.makeText(getContext(), "No internet connection. Cannot save metadata.", Toast.LENGTH_SHORT).show());
            return;
        }

        ipfsExecutor.execute(() -> {
            String backendUrl = "https://ldgames.x10.mx/add_ipfs_game.php";
            HttpURLConnection conn = null;
            try {
                URL url = new URL(backendUrl);
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");

                StringBuilder postData = new StringBuilder();
                postData.append("game_name=").append(URLEncoder.encode(gameName, StandardCharsets.UTF_8.name()));
                postData.append("&ipfs_hash=").append(URLEncoder.encode(cid, StandardCharsets.UTF_8.name()));
                postData.append("&original_filename=").append(URLEncoder.encode(originalFileName, StandardCharsets.UTF_8.name()));
                postData.append("&file_size=").append(fileSize);

                byte[] postDataBytes = postData.toString().getBytes(StandardCharsets.UTF_8);
                conn.setRequestProperty("Content-Length", String.valueOf(postDataBytes.length));

                try (OutputStream os = conn.getOutputStream()) {
                    os.write(postDataBytes);
                }

                int responseCode = conn.getResponseCode();
                StringBuilder response = new StringBuilder();
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                        String responseLine;
                        while ((responseLine = br.readLine()) != null) {
                            response.append(responseLine.trim());
                        }
                    }
                    JSONObject jsonResponse = new JSONObject(response.toString());
                    boolean success = jsonResponse.optBoolean("success", false);
                    String message = jsonResponse.optString("message", "Unknown response from server.");

                    getActivity().runOnUiThread(() -> {
                        if (getContext() != null) Toast.makeText(getContext(), message, Toast.LENGTH_LONG).show();
                        if (success) {
                            Log.d("IpfsGamesFragment", "Backend storage success: " + message);
                            loadIpfsGamesList();
                        } else {
                            Log.e("IpfsGamesFragment", "Backend storage failed: " + message);
                        }
                    });

                } else {
                     try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getErrorStream(), StandardCharsets.UTF_8))) {
                        String responseLine;
                        while ((responseLine = br.readLine()) != null) {
                            response.append(responseLine.trim());
                        }
                    }
                    final String errorResponse = "HTTP Error: " + responseCode + ". Response: " + response.toString();
                    Log.e("IpfsGamesFragment", errorResponse);
                    if (getActivity() != null) getActivity().runOnUiThread(() -> {
                        if (getContext() != null) Toast.makeText(getContext(), "Server error. Please try again. Details: " + errorResponse , Toast.LENGTH_LONG).show();
                         if (ipfsGamesAdapter != null && ipfsGamesAdapter.getItemCount() == 0) { // Update empty state on error too
                            tvEmptyListMessage.setVisibility(View.VISIBLE);
                            recyclerViewIpfsGames.setVisibility(View.GONE);
                        }
                    });
                }

            } catch (Exception e) {
                Log.e("IpfsGamesFragment", "Error storing metadata on backend", e);
                final String exceptionMsg = e.getMessage();
                if (getActivity() != null) getActivity().runOnUiThread(() -> {
                     if (getContext() != null) Toast.makeText(getContext(), "Client-side error during metadata storage: " + exceptionMsg, Toast.LENGTH_LONG).show();
                     if (ipfsGamesAdapter != null && ipfsGamesAdapter.getItemCount() == 0) {
                        tvEmptyListMessage.setVisibility(View.VISIBLE);
                        recyclerViewIpfsGames.setVisibility(View.GONE);
                    }
                });
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
        });
    }

    private void loadIpfsGamesList() {
        if (getContext() == null || getActivity() == null) return;

        if (!isNetworkAvailable()) {
            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    Toast.makeText(getContext(), "No internet connection.", Toast.LENGTH_SHORT).show();
                    if (swipeRefreshLayoutIpfs != null) swipeRefreshLayoutIpfs.setRefreshing(false);
                     if (ipfsGamesAdapter == null || ipfsGamesAdapter.getItemCount() == 0) { // Check before accessing adapter
                        tvEmptyListMessage.setText("No internet connection. Cannot load games.");
                        tvEmptyListMessage.setVisibility(View.VISIBLE);
                        recyclerViewIpfsGames.setVisibility(View.GONE);
                    }
                });
            }
            return;
        }

        if (swipeRefreshLayoutIpfs != null) swipeRefreshLayoutIpfs.setRefreshing(true);

        ipfsExecutor.execute(() -> {
            String backendUrl = "https://ldgames.x10.mx/list_ipfs_games.php";
            HttpURLConnection conn = null;
            try {
                URL url = new URL(backendUrl);
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(15000);

                int responseCode = conn.getResponseCode();
                StringBuilder response = new StringBuilder();
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                        String responseLine;
                        while ((responseLine = br.readLine()) != null) {
                            response.append(responseLine.trim());
                        }
                    }

                    JSONObject jsonResponse = new JSONObject(response.toString());
                    boolean success = jsonResponse.optBoolean("success", false);
                    if (success) {
                        JSONArray gamesArray = jsonResponse.getJSONArray("games");
                        List<IpfsGame> newGames = new ArrayList<>();
                        for (int i = 0; i < gamesArray.length(); i++) {
                            JSONObject gameObj = gamesArray.getJSONObject(i);
                            newGames.add(new IpfsGame(
                                gameObj.getInt("id"),
                                gameObj.getString("game_name"),
                                gameObj.getString("ipfs_hash"),
                                gameObj.optString("original_filename", ""),
                                gameObj.getLong("file_size"),
                                gameObj.getString("upload_timestamp")
                            ));
                        }
                        if (getActivity() != null) getActivity().runOnUiThread(() -> {
                            if (ipfsGamesAdapter != null) {
                                ipfsGamesAdapter.setGamesList(newGames);
                                if (newGames.isEmpty()) {
                                    tvEmptyListMessage.setText("No IPFS games found. Be the first to share one!");
                                    tvEmptyListMessage.setVisibility(View.VISIBLE);
                                    recyclerViewIpfsGames.setVisibility(View.GONE);
                                } else {
                                    tvEmptyListMessage.setVisibility(View.GONE);
                                    recyclerViewIpfsGames.setVisibility(View.VISIBLE);
                                }
                            }
                        });
                    } else {
                        String message = jsonResponse.optString("message", "Failed to load games.");
                        Log.e("IpfsGamesFragment", "Failed to load IPFS games: " + message);
                        if (getActivity() != null) getActivity().runOnUiThread(() -> {
                            if (getContext() != null) Toast.makeText(getContext(), message, Toast.LENGTH_SHORT).show();
                            if (ipfsGamesAdapter != null && ipfsGamesAdapter.getItemCount() == 0) {
                                tvEmptyListMessage.setText("Failed to load games: " + message);
                                tvEmptyListMessage.setVisibility(View.VISIBLE);
                                recyclerViewIpfsGames.setVisibility(View.GONE);
                            }
                        });
                    }
                } else {
                    Log.e("IpfsGamesFragment", "HTTP Error loading games: " + responseCode);
                     if (getActivity() != null) getActivity().runOnUiThread(() -> {
                        if (getContext() != null) Toast.makeText(getContext(), "Server error loading games: " + responseCode, Toast.LENGTH_SHORT).show();
                        if (ipfsGamesAdapter != null && ipfsGamesAdapter.getItemCount() == 0) {
                            tvEmptyListMessage.setText("Server error loading games. Pull to refresh.");
                            tvEmptyListMessage.setVisibility(View.VISIBLE);
                            recyclerViewIpfsGames.setVisibility(View.GONE);
                        }
                     });
                }
            } catch (Exception e) {
                Log.e("IpfsGamesFragment", "Exception loading IPFS games", e);
                final String errorMsg = e.getMessage();
                if (getActivity() != null) getActivity().runOnUiThread(() -> {
                    if (getContext() != null) Toast.makeText(getContext(), "Error loading games: " + errorMsg, Toast.LENGTH_SHORT).show();
                     if (ipfsGamesAdapter != null && ipfsGamesAdapter.getItemCount() == 0) {
                        tvEmptyListMessage.setText("Error loading games. Pull to refresh.");
                        tvEmptyListMessage.setVisibility(View.VISIBLE);
                        recyclerViewIpfsGames.setVisibility(View.GONE);
                    }
                });
            } finally {
                if (conn != null) conn.disconnect();
                if (getActivity() != null) {
                     getActivity().runOnUiThread(() -> {
                        if (swipeRefreshLayoutIpfs != null) swipeRefreshLayoutIpfs.setRefreshing(false);
                     });
                }
            }
        });
    }

    private boolean isNetworkAvailable() {
        if (getContext() == null) return false;
        ConnectivityManager connectivityManager = (ConnectivityManager) getContext().getSystemService(Context.CONNECTIVITY_SERVICE);
        if (connectivityManager != null) {
            NetworkInfo activeNetworkInfo = connectivityManager.getActiveNetworkInfo();
            return activeNetworkInfo != null && activeNetworkInfo.isConnected();
        }
        return false;
    }
}
