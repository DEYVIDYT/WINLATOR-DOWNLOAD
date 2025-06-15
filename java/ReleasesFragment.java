package com.winlator.Download;

import android.content.Context;
import android.content.Intent;
import android.os.AsyncTask;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.winlator.Download.adapter.ReleasesAdapter;
import com.winlator.Download.model.Release;
import com.winlator.Download.service.DownloadService;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ReleasesFragment extends Fragment implements ReleasesAdapter.OnReleaseClickListener {

    private static final String TAG = "ReleasesFragment";
    private static final String ARG_CATEGORY = "category";
    private static final String ARG_RELEASES_LIST = "releases_list"; // Alterado para lista de Releases

    private RecyclerView recyclerView;
    private ProgressBar progressBar;
    private TextView errorTextView;
    private ReleasesAdapter adapter;
    private Context mContext;
    private String category;
    private List<Release> releasesList; // Alterado para List<Release>

    public static ReleasesFragment newInstance(String category, List<Release> releases) { // Alterado para List<Release>
        ReleasesFragment fragment = new ReleasesFragment();
        Bundle args = new Bundle();
        args.putString(ARG_CATEGORY, category);
        args.putSerializable(ARG_RELEASES_LIST, new ArrayList<>(releases)); // Passa a lista de Releases
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        mContext = context;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getArguments() != null) {
            category = getArguments().getString(ARG_CATEGORY);
            releasesList = (List<Release>) getArguments().getSerializable(ARG_RELEASES_LIST); // Obtém a lista de Releases
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.layout_releases_fragment, container, false);

        recyclerView = view.findViewById(R.id.recyclerViewReleases);
        progressBar = view.findViewById(R.id.progressBar);
        errorTextView = view.findViewById(R.id.errorTextView);

        recyclerView.setLayoutManager(new LinearLayoutManager(mContext));
        adapter = new ReleasesAdapter(mContext, this);
        recyclerView.setAdapter(adapter);

        if (getActivity() != null) {
            getActivity().setTitle(category);
        }

        displayReleases(); // Chama um novo método para exibir os releases já carregados

        return view;
    }

    private void displayReleases() {
        if (releasesList != null && !releasesList.isEmpty()) {
            adapter.updateData(releasesList);
            if (recyclerView != null) {
                recyclerView.setVisibility(View.VISIBLE);
            }
            if (progressBar != null) {
                progressBar.setVisibility(View.GONE);
            }
            if (errorTextView != null) {
                errorTextView.setVisibility(View.GONE);
            }
        } else {
            showError("Nenhum release encontrado para esta categoria.");
        }
    }

    private void showError(String message) {
        if (errorTextView != null) {
            errorTextView.setText(message);
            errorTextView.setVisibility(View.VISIBLE);
        }
        if (progressBar != null) {
            progressBar.setVisibility(View.GONE);
        }
        if (recyclerView != null) {
            recyclerView.setVisibility(View.GONE);
        }
        Log.e(TAG, message);
    }

    @Override
    public void onReleaseDownloadClick(Release release) {
        if (release.getDownloadUrl() != null && !release.getDownloadUrl().isEmpty()) {
            Toast.makeText(mContext, "Iniciando download: " + release.getAssetName(), Toast.LENGTH_SHORT).show();
            
            Intent serviceIntent = new Intent(mContext, DownloadService.class);
            serviceIntent.putExtra(DownloadService.EXTRA_ACTION, DownloadService.ACTION_START_DOWNLOAD);
            serviceIntent.putExtra(DownloadService.EXTRA_URL, release.getDownloadUrl());
            serviceIntent.putExtra(DownloadService.EXTRA_FILE_NAME, release.getAssetName());
            mContext.startService(serviceIntent);
            
            Intent activityIntent = new Intent(mContext, DownloadManagerActivity.class);
            startActivity(activityIntent);
        } else {
            Toast.makeText(mContext, "URL de download inválida", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onReleaseItemClick(Release release) {
        if (mContext != null) {
            Intent intent = new Intent(mContext, VersionsActivity.class);
            intent.putExtra(VersionsActivity.EXTRA_RELEASE, release);
            // Passar informações específicas do repositório para filtrar os releases
            intent.putExtra(VersionsActivity.EXTRA_REPOSITORY_NAME, release.getName());
            intent.putExtra(VersionsActivity.EXTRA_REPOSITORY_URL, release.getHtmlUrl());
            requireActivity().startActivity(intent);
        }
    }

    @Override
    public void onDetach() {
        super.onDetach();
        mContext = null;
    }
}

