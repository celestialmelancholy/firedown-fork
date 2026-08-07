package com.solarized.firedown.ui.adapters;

import android.content.Context;
import android.net.Uri;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.widget.AppCompatImageView;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.load.resource.bitmap.RoundedCorners;
import com.bumptech.glide.request.RequestOptions;
import com.solarized.firedown.GlideHelper;
import com.solarized.firedown.R;
import com.solarized.firedown.data.entity.GeckoStateEntity;
import com.solarized.firedown.utils.UrlStringUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.function.Consumer;

/**
 * "Jump back in" — a horizontal strip of recently-browsed (non-home) tabs for
 * the custom Firefox-style home. Renders each tab's thumbnail preview, title
 * and favicon, ordered by last access (most recent first). Driven entirely by
 * real tab data (GeckoStateEntity), never hardcoded; a tap opens the session.
 */
public class JumpBackInAdapter extends RecyclerView.Adapter<JumpBackInAdapter.RecentTabViewHolder> {

    private final List<GeckoStateEntity> mItems = new ArrayList<>();
    private final RequestOptions mThumbOptions;
    private final RequestOptions mIconOptions;
    private final Consumer<Integer> mOnOpen;

    public JumpBackInAdapter(Context context, Consumer<Integer> onOpen) {
        mOnOpen = onOpen;
        // Thumbnail corners are clipped by the view's ShapeableImageView
        // (HomeJumpThumbnail overlay) — do NOT also round the bitmap here, or
        // the two roundings fight and the visible curve reads more rounded
        // than the card (on-device review).
        mThumbOptions = new RequestOptions();
        int rounded = context.getResources().getDimensionPixelOffset(R.dimen.icon_rounded);
        mIconOptions = RequestOptions.bitmapTransform(new RoundedCorners(Math.max(2, rounded / 4)));
    }

    /** Replace the recents strip with the current non-home tabs, most recently
     *  accessed first. Full rebind is fine — the set is small (≤8).
     *
     *  <p>DEDUPES by entity id: the repository can hold duplicate/stale
     *  GeckoState objects for the same tab (navigation/restore churn), so the
     *  raw list can show the same site twice. The most recently accessed copy
     *  wins — and because the id shown is the LIVE repository id, tapping a
     *  card resolves through getGeckoState(sessionId) and opens the session. */
    public void setTabs(List<GeckoStateEntity> tabs) {
        mItems.clear();
        if (tabs != null) {
            // Most-recent-first so the first copy of each id wins the dedupe.
            List<GeckoStateEntity> sorted = new ArrayList<>(tabs);
            sorted.sort((a, b) -> Long.compare(b.getLastAccess(), a.getLastAccess()));
            HashSet<Integer> seen = new HashSet<>();
            for (GeckoStateEntity t : sorted) {
                if (t.isHome() || t.isIncognito() || TextUtils.isEmpty(t.getUri())) {
                    continue;
                }
                int id = t.getId();
                if (id <= 0 || !seen.add(id)) {
                    continue; // duplicate/stale copy of a tab we already have
                }
                mItems.add(t);
                if (mItems.size() >= 8) break;
            }
        }
        notifyDataSetChanged();
    }

    public int size() {
        return mItems.size();
    }

    @NonNull
    @Override
    public RecentTabViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_home_jump_back_in, parent, false);
        return new RecentTabViewHolder(view, mOnOpen);
    }

    @Override
    public void onBindViewHolder(@NonNull RecentTabViewHolder holder, int position) {
        GeckoStateEntity item = mItems.get(position);
        holder.sessionId = item.getId();
        String url = item.getUri();
        String title = item.getTitle();
        String shown = UrlStringUtils.isBlankTitle(title) ? url : title;
        holder.title.setText(shown);
        holder.url.setText(url);
        holder.itemView.setContentDescription(shown);
        // Thumbnail preview (file path from the tab's stored thumbnail), falling
        // back to the favicon; both load via Glide like the tabs screen.
        String thumb = item.getThumb();
        if (!TextUtils.isEmpty(thumb)) {
            GlideHelper.load(thumb, url, holder.thumb, mThumbOptions);
        } else {
            GlideHelper.load(item.getIcon(), url, holder.thumb, mThumbOptions);
        }
        GlideHelper.load(item.getIcon(), url, holder.favicon, mIconOptions);
    }

    @Override
    public void onViewRecycled(@NonNull RecentTabViewHolder holder) {
        super.onViewRecycled(holder);
        GlideHelper.clearSafe(holder.thumb);
        GlideHelper.clearSafe(holder.favicon);
    }

    @Override
    public int getItemCount() {
        return mItems.size();
    }

    static class RecentTabViewHolder extends RecyclerView.ViewHolder {
        final AppCompatImageView thumb;
        final AppCompatImageView favicon;
        final TextView title;
        final TextView url;
        int sessionId;

        RecentTabViewHolder(@NonNull View itemView, Consumer<Integer> onOpen) {
            super(itemView);
            thumb = itemView.findViewById(R.id.jump_thumb);
            favicon = itemView.findViewById(R.id.jump_favicon);
            title = itemView.findViewById(R.id.jump_title);
            url = itemView.findViewById(R.id.jump_url);
            itemView.setOnClickListener(v -> {
                if (sessionId > 0 && onOpen != null) {
                    onOpen.accept(sessionId);
                }
            });
        }
    }
}
