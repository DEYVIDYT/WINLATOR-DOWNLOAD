package com.winlator.Download.adapter;

import android.content.Context;
import android.content.Intent;
import android.net.Uri; // Added for Uri.parse
import android.util.Log;
// import com.winlator.Download.DownloadManagerActivity; // Removed
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Filter; // Added
import android.widget.Filterable; // Added

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.winlator.Download.R;
import com.winlator.Download.model.CommunityGame;
// import com.winlator.Download.service.DownloadService; // Removed

import java.util.ArrayList; // Added
import java.util.List;

public class CommunityGamesAdapter extends RecyclerView.Adapter<CommunityGamesAdapter.ViewHolder> implements Filterable { // Implemented Filterable

    private List<CommunityGame> communityGamesList; // Renamed for clarity, holds filtered list
    private List<CommunityGame> communityGamesListFull; // For the original list
    private Context context;

    public CommunityGamesAdapter(List<CommunityGame> communityGamesList, Context context) {
        // When the adapter is created, the passed list is initially both full and filtered.
        this.communityGamesList = new ArrayList<>(communityGamesList);
        this.communityGamesListFull = new ArrayList<>(communityGamesList);
        this.context = context;
    }

    // Method to update the list if needed from fragment, e.g., after fetching new data
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
        CommunityGame game = communityGamesList.get(position); // Use filtered list
        
        holder.tvGameName.setText(game.getName());
        holder.tvGameSize.setText(game.getSize());
        
        holder.btnDownload.setOnClickListener(v -> {
            String gameUrl = game.getUrl();
            if (gameUrl != null && !gameUrl.isEmpty()) {
                try {
                    Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(gameUrl));
                    holder.itemView.getContext().startActivity(browserIntent);
                    Log.d("CommunityGamesAdapter", "Opening URL in browser. Game: '" + game.getName() + "', URL: '" + gameUrl + "'");
                } catch (Exception e) {
                    Log.e("CommunityGamesAdapter", "Could not open URL: " + gameUrl, e);
                }
            } else {
                Log.w("CommunityGamesAdapter", "Game URL is null or empty for: " + game.getName());
            }
        });
    }

    @Override
    public int getItemCount() {
        return communityGamesList.size(); // Use filtered list
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

