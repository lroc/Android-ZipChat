package com.kdoherty.zipchat.adapters;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.support.v7.widget.RecyclerView;
import android.text.format.DateUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import com.kdoherty.zipchat.R;
import com.kdoherty.zipchat.activities.MessageDetailActivity;
import com.kdoherty.zipchat.activities.UserDetailsActivity;
import com.kdoherty.zipchat.models.Message;
import com.kdoherty.zipchat.models.User;
import com.kdoherty.zipchat.utils.UserUtils;
import com.kdoherty.zipchat.views.CircleProfilePictureView;

import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Created by kdoherty on 12/26/14.
 */
public class MessageAdapter extends RecyclerView.Adapter<MessageAdapter.MessageCellViewHolder> {

    public interface MessageFavoriteListener {
        void sendFavoriteEvent(long messageId, boolean isFavorite);
    }

    private static final String TAG = MessageAdapter.class.getSimpleName();

    private final LayoutInflater mInflater;
    private final List<Message> mMessages;
    private MessageFavoriteListener mMessageFavListener;
    private final Handler mFavoriteEventHandler = new Handler();
    private boolean mHasPendingFavorite;
    private SendFavoriteEventRunnable mSendFavoriteEvent;
    private Message.FavoriteState mInitialFavoriteState;

    private static final Semaphore sendFavoriteLock = new Semaphore(1);
    private static final int SEND_FAVORITE_LOCK_TIMEOUT_SECONDS = 1;


    private class SendFavoriteEventRunnable implements Runnable {

        private long messageId;
        private boolean isAddFavorite;

        private SendFavoriteEventRunnable(long messageId, boolean isAddFavorite) {
            this.messageId = messageId;
            this.isAddFavorite = isAddFavorite;
        }

        @Override
        public void run() {
            try {
                boolean lockAquired = sendFavoriteLock.tryAcquire(SEND_FAVORITE_LOCK_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                if (!lockAquired) {
                    Log.d(TAG, "Timed out while aquiring send favorite lock in runnable");
                    return;
                }
            } catch (InterruptedException e) {
                Log.e(TAG, "Interrupted while aquiring the send favorite lock in runnable: " + e);
                return;
            }

            boolean userFavorited = mInitialFavoriteState == Message.FavoriteState.USER_FAVORITED;
            if (isAddFavorite != userFavorited) {
                mMessageFavListener.sendFavoriteEvent(messageId, isAddFavorite);
            }

            mInitialFavoriteState = null;
            mHasPendingFavorite = false;
            sendFavoriteLock.release();
        }
    }

    private Context mContext;

    private static final long SEND_FAVORITE_EVENT_DELAY = 1000; // MS

    public MessageAdapter(Context context, List<Message> messages, MessageFavoriteListener messageFavoriteListener) {
        mInflater = LayoutInflater.from(context);
        mMessages = messages;
        mContext = context;
        mMessageFavListener = messageFavoriteListener;
    }

    @Override
    public MessageCellViewHolder onCreateViewHolder(ViewGroup viewGroup, final int position) {
        View view = mInflater.inflate(R.layout.cell_message, viewGroup, false);
        view.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                //Log.d(TAG, "Sending message: " + getMessage(position) + " to the message details activity");
                //MessageDetailActivity.startActivity(mContext, getMessage(position));
            }
        });
        return new MessageCellViewHolder(view);
    }

    @Override
    public void onBindViewHolder(final MessageCellViewHolder messageCellViewHolder, int i) {
        final Message message = mMessages.get(i);
        final User sender = message.getSender();

        messageCellViewHolder.name.setText(sender.getName());
        messageCellViewHolder.profilePicture.setProfileId(sender.getFacebookId());
        messageCellViewHolder.profilePicture.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                UserDetailsActivity.startActivity(mContext, sender);
            }
        });

        messageCellViewHolder.message.setText(message.getMessage());

        long userId = UserUtils.getId(mContext);
        messageCellViewHolder.favorite.setOnClickListener(new FavoriteClickListener(message, userId));

        Message.FavoriteState favoriteState = message.getFavoriteState(userId);

        Drawable favoriteDrawable = getMessageDrawable(favoriteState);
        messageCellViewHolder.favorite.setImageDrawable(favoriteDrawable);

        int favoriteCount = message.getFavoriteCount();

        if (favoriteCount > 0) {
            messageCellViewHolder.favoriteCount.setVisibility(View.VISIBLE);
        } else if (messageCellViewHolder.favoriteCount.getVisibility() == View.VISIBLE) {
            messageCellViewHolder.favoriteCount.setVisibility(View.GONE);
        }

        messageCellViewHolder.favoriteCount.setText(String.valueOf(message.getFavoriteCount()));

        CharSequence timeAgo = DateUtils.getRelativeTimeSpanString(
                message.getTimeStamp() * 1000);
        messageCellViewHolder.timestamp.setText(timeAgo);
    }

    @Override
    public int getItemCount() {
        return mMessages.size();
    }

    public void addMessage(Message message) {
        int position = mMessages.size();
        mMessages.add(message);
        notifyItemInserted(position);
    }

    private static int getMessageDrawableId(Message.FavoriteState state) {
        switch (state) {
            case FAVORITED:
                return R.drawable.ic_favorite_grey600_24dp;
            case USER_FAVORITED:
                return R.drawable.ic_favorite_red600_24dp;
            case UNFAVORITED:
                return R.drawable.ic_favorite_outline_grey600_24dp;
            default:
                throw new AssertionError("Default switch case on Message.FavoriteState");
        }
    }

    public Message getMessage(int position) {
        return mMessages.get(position);
    }

    private Drawable getMessageDrawable(Message.FavoriteState state) {
        return mContext.getResources().getDrawable(getMessageDrawableId(state));
    }

    public void favoriteMessage(User user, long messageId, long selfId) {
        favoriteHelper(user, messageId, true, selfId);
    }

    public void removeFavorite(User user, long messageId, long selfId) {
        favoriteHelper(user, messageId, false, selfId);
    }

    private void favoriteHelper(User user, long messageId, boolean isAdd, long selfId) {
        int messageIndex = indexOfMessageById(messageId);

        if (messageIndex != -1) {
            Message message = getMessage(indexOfMessageById(messageId));

            if (isAdd) {
                message.addFavorite(user, selfId);
            } else {
                message.removeFavorite(user, selfId);
            }
            notifyItemChanged(messageIndex);
        }
    }

    public int indexOfMessageById(long messageId) {
        int size = mMessages.size();
        for (int i = 0; i < size; i++) {
            if (messageId == mMessages.get(i).getMessageId()) {
                return i;
            }
        }
        return -1;
    }

    private class FavoriteClickListener implements View.OnClickListener {
        private final Message message;
        private final long userId;

        public FavoriteClickListener(Message message, long userId) {
            this.message = message;
            this.userId = userId;
        }

        @Override
        public void onClick(View v) {
            try {
                boolean lockAquired = sendFavoriteLock.tryAcquire(SEND_FAVORITE_LOCK_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                if (!lockAquired) {
                    Log.d(TAG, "Timed out while aquiring send favorite lock on click");
                    return;
                }
            } catch (InterruptedException e) {
                Log.e(TAG, "Interrupted while aquiring the send favorite lock in on click: " + e);
                return;
            }

            final Message.FavoriteState favoriteState = message.getFavoriteState(userId);
            final boolean isAddFavorite = favoriteState != Message.FavoriteState.USER_FAVORITED;

            if (isAddFavorite) {
                favoriteMessage(UserUtils.getSelf(mContext), message.getMessageId(), userId);
            } else {
                removeFavorite(UserUtils.getSelf(mContext), message.getMessageId(), userId);
            }

            if (mHasPendingFavorite) {
                mFavoriteEventHandler.removeCallbacks(mSendFavoriteEvent);
                mSendFavoriteEvent = new SendFavoriteEventRunnable(message.getMessageId(), isAddFavorite);
                mFavoriteEventHandler.postDelayed(mSendFavoriteEvent, SEND_FAVORITE_EVENT_DELAY);
            } else {
                mHasPendingFavorite = true;
                mInitialFavoriteState = favoriteState;
                mSendFavoriteEvent = new SendFavoriteEventRunnable(message.getMessageId(), isAddFavorite);
                mFavoriteEventHandler.postDelayed(mSendFavoriteEvent, SEND_FAVORITE_EVENT_DELAY);
            }

            sendFavoriteLock.release();
        }
    }

    public class MessageCellViewHolder extends RecyclerView.ViewHolder {

        private CircleProfilePictureView profilePicture;
        private TextView name;
        private TextView message;
        private ImageView favorite;
        private TextView favoriteCount;
        private TextView timestamp;

        public MessageCellViewHolder(View itemView) {
            super(itemView);
            profilePicture = (CircleProfilePictureView) itemView.findViewById(R.id.message_picture);
            name = (TextView) itemView.findViewById(R.id.message_sender);
            message = (TextView) itemView.findViewById(R.id.message_text);
            favorite = (ImageView) itemView.findViewById(R.id.message_favorite);
            favoriteCount = (TextView) itemView.findViewById(R.id.message_favorite_count);
            timestamp = (TextView) itemView.findViewById(R.id.message_timestamp);
        }
    }
}
