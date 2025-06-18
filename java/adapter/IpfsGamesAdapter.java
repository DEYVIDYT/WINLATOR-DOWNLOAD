package com.winlator.Download.adapter;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Filter; // For search
import android.widget.Filterable; // For search
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.winlator.Download.R;
import com.winlator.Download.model.IpfsGame;
import java.util.ArrayList;
import java.util.List;

public class IpfsGamesAdapter extends RecyclerView.Adapter<IpfsGamesAdapter.ViewHolder> implements Filterable {

    private List<IpfsGame> gamesList;
    private List<IpfsGame> gamesListFull; // For search filter
    private Context context;
    private OnIpfsGameAction listener; // Listener for download button

    public interface OnIpfsGameAction {
        void onDownloadClicked(IpfsGame game);
        // Add other actions if needed, e.g., void onItemClicked(IpfsGame game);
    }

    public IpfsGamesAdapter(List<IpfsGame> gamesList, Context context, OnIpfsGameAction listener) {
        this.gamesList = gamesList;
        this.gamesListFull = new ArrayList<>(gamesList); // Initialize for search
        this.context = context;
        this.listener = listener;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.list_item_ipfs_game, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        IpfsGame game = gamesList.get(position);
        holder.tvGameName.setText(game.getGameName());
        holder.tvIpfsHash.setText("IPFS Hash: " + game.getIpfsHash());
        holder.tvOriginalFilename.setText("Filename: " + game.getOriginalFilename());
        holder.tvFileSize.setText("Size: " + game.getFormattedFileSize());
        holder.tvUploadTimestamp.setText("Uploaded: " + game.getUploadTimestamp());

        holder.btnDownload.setOnClickListener(v -> {
            if (listener != null) {
                listener.onDownloadClicked(game);
            }
        });
    }

    @Override
    public int getItemCount() {
        return gamesList.size();
    }

    public void setGamesList(List<IpfsGame> newGamesList) {
        this.gamesList.clear();
        if (newGamesList != null) {
            this.gamesList.addAll(newGamesList);
        }
        this.gamesListFull = new ArrayList<>(this.gamesList); // Update full list for search
        notifyDataSetChanged();
    }

    @Override
    public Filter getFilter() {
        return gameFilter;
    }

    private Filter gameFilter = new Filter() {
        @Override
        protected FilterResults performFiltering(CharSequence constraint) {
            List<IpfsGame> filteredList = new ArrayList<>();
            if (constraint == null || constraint.length() == 0) {
                filteredList.addAll(gamesListFull);
            } else {
                String filterPattern = constraint.toString().toLowerCase().trim();
                for (IpfsGame game : gamesListFull) {
                    if (game.getGameName().toLowerCase().contains(filterPattern) ||
                        game.getIpfsHash().toLowerCase().contains(filterPattern)) {
                        filteredList.add(game);
                    }
                }
            }
            FilterResults results = new FilterResults();
            results.values = filteredList;
            return results;
        }

        @Override
        protected void publishResults(CharSequence constraint, FilterResults results) {
            gamesList.clear();
            if (results.values != null) {
                gamesList.addAll((List) results.values);
            }
            notifyDataSetChanged();
        }
    };

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView tvGameName, tvIpfsHash, tvOriginalFilename, tvFileSize, tvUploadTimestamp;
        Button btnDownload;

        ViewHolder(View itemView) {
            super(itemView);
            tvGameName = itemView.findViewById(R.id.tv_ipfs_game_name);
            tvIpfsHash = itemView.findViewById(R.id.tv_ipfs_hash);
            tvOriginalFilename = itemView.findViewById(R.id.tv_ipfs_original_filename);
            tvFileSize = itemView.findViewById(R.id.tv_ipfs_file_size);
            tvUploadTimestamp = itemView.findViewById(R.id.tv_ipfs_upload_timestamp);
            btnDownload = itemView.findViewById(R.id.btn_download_ipfs_game);
        }
    }
}
