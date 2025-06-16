package com.winlator.Download.adapter;

import android.content.Context;
import android.content.Intent;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Filter;
import android.widget.Filterable;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.winlator.Download.R;
import com.winlator.Download.model.CommunityGame;
import com.winlator.Download.service.DownloadService;
import com.winlator.Download.DownloadManagerActivity;

import java.util.ArrayList;
import java.util.List;

public class CommunityGamesAdapter extends RecyclerView.Adapter<CommunityGamesAdapter.ViewHolder> implements Filterable {

    private List<CommunityGame> communityGamesList;
    private List<CommunityGame> communityGamesListFull;
    private Context context;

    public CommunityGamesAdapter(List<CommunityGame> communityGamesList, Context context) {
        this.communityGamesList = new ArrayList<>(communityGamesList);
        this.communityGamesListFull = new ArrayList<>(communityGamesList);
        this.context = context;
    }

    public void setGamesList(List<CommunityGame> games) {
        this.communityGamesList = new ArrayList<>(games);
        this.communityGamesListFull = new ArrayList<>(games);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.list_item_community_game, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        CommunityGame game = communityGamesList.get(position);
        
        holder.tvGameName.setText(game.getName());
        holder.tvGameSize.setText(game.getSize());
        
        holder.btnDownload.setOnClickListener(v -> {
            String gameUrl = game.getUrl();
            String gameName = game.getName();
            Context itemContext = holder.itemView.getContext();

            if (gameUrl != null && !gameUrl.isEmpty()) {
                Intent serviceIntent = new Intent(itemContext, DownloadService.class);

                if (gameUrl.contains("gofile.io/d/") || gameUrl.contains("gofile.io/download/")) {
                    Log.d("CommunityGamesAdapter", "Gofile URL detected for game: '" + gameName + "'. URL: '" + gameUrl + "'");
                    serviceIntent.putExtra(DownloadService.EXTRA_ACTION, DownloadService.ACTION_RESOLVE_AND_START_GOFILE_DOWNLOAD);
                    serviceIntent.putExtra(DownloadService.EXTRA_GOFILE_URL, gameUrl);
                    serviceIntent.putExtra(DownloadService.EXTRA_FILE_NAME, gameName);
                } else if (gameUrl.contains("www.mediafire.com/file/")) { // Added MediaFire check
                    Log.d("CommunityGamesAdapter", "MediaFire URL detected for game: '" + gameName + "'. URL: '" + gameUrl + "'");
                    serviceIntent.putExtra(DownloadService.EXTRA_ACTION, DownloadService.ACTION_RESOLVE_AND_START_MEDIAFIRE_DOWNLOAD);
                    serviceIntent.putExtra(DownloadService.EXTRA_MEDIAFIRE_URL, gameUrl);
                    serviceIntent.putExtra(DownloadService.EXTRA_FILE_NAME, gameName); // Placeholder
                } else {
                    Log.d("CommunityGamesAdapter", "Standard URL detected for game: '" + gameName + "'. URL: '" + gameUrl + "'");
                    serviceIntent.putExtra(DownloadService.EXTRA_ACTION, DownloadService.ACTION_START_DOWNLOAD);
                    serviceIntent.putExtra(DownloadService.EXTRA_URL, gameUrl);
                    serviceIntent.putExtra(DownloadService.EXTRA_FILE_NAME, gameName);
                }

                itemContext.startService(serviceIntent);
                Toast.makeText(itemContext, "Download iniciado: " + gameName, Toast.LENGTH_SHORT).show();

                Intent activityIntent = new Intent(itemContext, DownloadManagerActivity.class);
                itemContext.startActivity(activityIntent);

            } else {
                Log.w("CommunityGamesAdapter", "Game URL is null or empty for: " + gameName);
                Toast.makeText(itemContext, "URL de download inválida para: " + gameName, Toast.LENGTH_SHORT).show();
            }
        });
    }

    @Override
    public int getItemCount() {
        return communityGamesList.size();
    }

    @Override
    public Filter getFilter() {
        return communityGamesFilter;
    }

    private Filter communityGamesFilter = new Filter() {
        @Override
        protected FilterResults performFiltering(CharSequence constraint) {
            List<CommunityGame> filteredList = new ArrayList<>();
            if (constraint == null || constraint.length() == 0) {
                filteredList.addAll(communityGamesListFull);
            } else {
                String filterPattern = constraint.toString().toLowerCase().trim();
                for (CommunityGame item : communityGamesListFull) {
                    if (item.getName().toLowerCase().contains(filterPattern)) {
                        filteredList.add(item);
                    }
                }
            }
            FilterResults results = new FilterResults();
            results.values = filteredList;
            return results;
        }

        @Override
        protected void publishResults(CharSequence constraint, FilterResults results) {
            communityGamesList.clear();
            if (results.values != null) {
                communityGamesList.addAll((List) results.values);
            }
            notifyDataSetChanged();
        }
    };

    public static class ViewHolder extends RecyclerView.ViewHolder {
        TextView tvGameName;
        TextView tvGameSize;
        Button btnDownload;

        public ViewHolder(@NonNull View itemView) {
            super(itemView);
            tvGameName = itemView.findViewById(R.id.tv_game_name);
            tvGameSize = itemView.findViewById(R.id.tv_game_size);
            btnDownload = itemView.findViewById(R.id.btn_download);
        }
    }
}
