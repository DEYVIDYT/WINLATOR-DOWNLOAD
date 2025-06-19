package com.winlator.Download.adapter;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Filter;
import android.widget.Filterable;
import android.widget.FrameLayout;
import android.widget.ImageView; // Added for play icon
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

// Removed: com.pierfrancescosoffritti.androidyoutubeplayer imports

import com.winlator.Download.R;
import com.winlator.Download.model.CommunityTest;

import java.util.ArrayList;
import java.util.List;
// Removed: regex imports for video ID extraction if no longer needed for display

public class CommunityTestAdapter extends RecyclerView.Adapter<CommunityTestAdapter.ViewHolder> implements Filterable {

    private List<CommunityTest> testList;
    private List<CommunityTest> testListFull;
    private Context context;

    public CommunityTestAdapter(List<CommunityTest> testList, Context context) {
        this.testList = new ArrayList<>(testList);
        this.testListFull = new ArrayList<>(testList);
        this.context = context;
    }

    public void setTestList(List<CommunityTest> tests) {
        this.testList = new ArrayList<>(tests);
        this.testListFull = new ArrayList<>(tests);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.list_item_community_test, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        CommunityTest test = testList.get(position);
        holder.tvGameName.setText(test.getGameName());
        holder.tvDescription.setText(test.getDescription());

        // Set an OnClickListener to open the YouTube link
        holder.youtubePlayerContainer.setOnClickListener(v -> {
            String youtubeUrl = test.getYoutubeUrl();
            if (youtubeUrl != null && !youtubeUrl.isEmpty()) {
                try {
                    // Ensure the URL is well-formed
                    if (!youtubeUrl.startsWith("http://") && !youtubeUrl.startsWith("https://")) {
                        youtubeUrl = "https://" + youtubeUrl;
                    }
                    Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(youtubeUrl));
                    // Check if there's an app to handle this intent
                    if (intent.resolveActivity(context.getPackageManager()) != null) {
                        context.startActivity(intent);
                    } else {
                        Toast.makeText(context, "No app found to open YouTube link", Toast.LENGTH_SHORT).show();
                    }
                } catch (Exception e) {
                    Toast.makeText(context, "Invalid YouTube URL", Toast.LENGTH_SHORT).show();
                    e.printStackTrace();
                }
            } else {
                Toast.makeText(context, "YouTube URL is missing", Toast.LENGTH_SHORT).show();
            }
        });

        // Optionally, you can set a default image or icon in youtube_play_icon
        // holder.ivPlayIcon.setImageResource(R.drawable.ic_play_arrow); // Example
    }

    // Removed: extractVideoId method as it's not used for direct playback

    @Override
    public int getItemCount() {
        return testList.size();
    }

    @Override
    public Filter getFilter() {
        return testFilter;
    }

    private Filter testFilter = new Filter() {
        @Override
        protected FilterResults performFiltering(CharSequence constraint) {
            List<CommunityTest> filteredList = new ArrayList<>();
            if (constraint == null || constraint.length() == 0) {
                filteredList.addAll(testListFull);
            } else {
                String filterPattern = constraint.toString().toLowerCase().trim();
                for (CommunityTest item : testListFull) {
                    if (item.getGameName().toLowerCase().contains(filterPattern) ||
                        item.getDescription().toLowerCase().contains(filterPattern)) {
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
            testList.clear();
            if (results.values != null) {
                testList.addAll((List) results.values);
            }
            notifyDataSetChanged();
        }
    };

    // Removed: onViewRecycled logic related to YouTubePlayerView release

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView tvGameName;
        TextView tvDescription;
        FrameLayout youtubePlayerContainer; // This will act as a button
        ImageView ivPlayIcon; // Added for visual cue

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            tvGameName = itemView.findViewById(R.id.tv_test_game_name);
            tvDescription = itemView.findViewById(R.id.tv_test_description);
            youtubePlayerContainer = itemView.findViewById(R.id.youtube_player_container);
            ivPlayIcon = itemView.findViewById(R.id.iv_play_icon); // Initialize ImageView
        }
    }
}
