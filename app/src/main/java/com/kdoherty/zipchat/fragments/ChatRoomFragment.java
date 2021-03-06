package com.kdoherty.zipchat.fragments;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.support.v4.app.Fragment;
import android.support.v7.widget.DefaultItemAnimator;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.kdoherty.zipchat.R;
import com.kdoherty.zipchat.activities.MessageDetailsActivity;
import com.kdoherty.zipchat.activities.ZipChatApplication;
import com.kdoherty.zipchat.adapters.MessageAdapter;
import com.kdoherty.zipchat.events.AddFavoriteEvent;
import com.kdoherty.zipchat.events.PublicRoomJoinEvent;
import com.kdoherty.zipchat.events.RemoveFavoriteEvent;
import com.kdoherty.zipchat.events.SocketReconnectTimeout;
import com.kdoherty.zipchat.events.TalkConfirmationEvent;
import com.kdoherty.zipchat.events.TalkEvent;
import com.kdoherty.zipchat.models.AbstractRoom;
import com.kdoherty.zipchat.models.Message;
import com.kdoherty.zipchat.models.User;
import com.kdoherty.zipchat.services.ChatService;
import com.kdoherty.zipchat.services.RoomSocket;
import com.kdoherty.zipchat.services.ZipChatApi;
import com.kdoherty.zipchat.utils.BusProvider;
import com.kdoherty.zipchat.utils.FacebookManager;
import com.kdoherty.zipchat.utils.NetworkManager;
import com.kdoherty.zipchat.utils.UserManager;
import com.kdoherty.zipchat.utils.Utils;
import com.kdoherty.zipchat.views.AnimateFirstDisplayListener;
import com.kdoherty.zipchat.views.DividerItemDecoration;
import com.koushikdutta.async.http.AsyncHttpClient;
import com.koushikdutta.async.http.WebSocket;
import com.nostra13.universalimageloader.core.DisplayImageOptions;
import com.nostra13.universalimageloader.core.ImageLoader;
import com.nostra13.universalimageloader.core.listener.ImageLoadingListener;
import com.squareup.otto.Subscribe;

import java.util.List;
import java.util.UUID;

import de.hdodenhof.circleimageview.CircleImageView;
import retrofit.Callback;
import retrofit.RetrofitError;
import retrofit.client.Response;

/**
 * Created by kevindoherty on 2/2/15.
 */
public class ChatRoomFragment extends Fragment implements AsyncHttpClient.WebSocketConnectCallback, View.OnClickListener, MessageAdapter.MessageCellClickListener {

    private static final String TAG = ChatRoomFragment.class.getSimpleName();

    private static final int MESSAGE_LIMIT = 25;
    private static final int ITEM_VIEW_CACHE_SIZE = 25;

    private static final String ARG_ROOM = "fragments.ChatRoomFragment.arg.ABSTRACT_ROOM";

    private MessageAdapter mMessageAdapter;
    private RecyclerView mMessagesRv;
    private EditText mMessageBoxEt;

    private RoomSocket mRoomSocket;

    private int mMessageOffset = 0;
    private long mSelfId;

    private boolean mMessagesLoading = true;
    private boolean mLoadedAllMessages = false;

    private CircleImageView mAnonToggleCv;
    private ImageLoadingListener mAnimateFirstListener = new AnimateFirstDisplayListener();
    private DisplayImageOptions options;

    private boolean mIsAnon;
    private String mProfilePicUrl;
    private AbstractRoom mRoom;

    private ProgressBar mMessageLoadingPb;

    private User mAnonSelf;

    private Callback<List<Message>> mGetMessagesCallback = new Callback<List<Message>>() {
        @Override
        public void success(List<Message> messages, Response response) {
            hideMessageLoadingPb();
            populateMessageList(messages);
            int numMessagesLoaded = messages.size();
            mMessageOffset += messages.size();
            mMessagesLoading = true;

            if (numMessagesLoaded < MESSAGE_LIMIT) {
                mLoadedAllMessages = true;
            }


        }

        @Override
        public void failure(RetrofitError error) {
            hideMessageLoadingPb();
            NetworkManager.handleErrorResponse(TAG, "Getting " + mRoom.getType() + " room chat messages", error, getActivity());
            mMessagesLoading = true;
        }
    };

    public ChatRoomFragment() {
    }

    public static ChatRoomFragment newInstance(AbstractRoom room) {
        Bundle args = new Bundle();
        args.putParcelable(ARG_ROOM, room);

        ChatRoomFragment fragment = new ChatRoomFragment();
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onAttach(Activity activity) {
        super.onAttach(activity);
        ZipChatApplication.initImageLoader(activity);

        mProfilePicUrl = "http://graph.facebook.com/" + FacebookManager.getFacebookId(activity) + "/picture?type=square";
        mSelfId = UserManager.getId(activity);

        Bundle args = getArguments();
        mRoom = args.getParcelable(ARG_ROOM);
        if (mRoom == null) {
            throw new IllegalArgumentException("Room must be passed as an argument to ChatRoomFragment");
        }

        ChatService chatService = new ChatService(mSelfId, mRoom, UserManager.getAuthToken(activity), this);
        mRoomSocket = new RoomSocket(activity, chatService);
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        options = new DisplayImageOptions.Builder()
                .showImageOnLoading(R.drawable.com_facebook_profile_picture_blank_portrait)
                .showImageForEmptyUri(R.drawable.com_facebook_profile_picture_blank_portrait)
                .showImageOnFail(R.drawable.com_facebook_profile_picture_blank_portrait)
                .cacheInMemory(true)
                .cacheOnDisk(true)
                .considerExifParams(true)
                .build();
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        super.onCreateView(inflater, container, savedInstanceState);
        View rootView = inflater.inflate(R.layout.fragment_chat_room, container, false);

        mMessagesRv = (RecyclerView) rootView.findViewById(R.id.messages_rv);
        mMessageBoxEt = (EditText) rootView.findViewById(R.id.message_box_et);
        mAnonToggleCv = (CircleImageView) rootView.findViewById(R.id.anon_toggle_civ);
        mMessageLoadingPb = (ProgressBar) rootView.findViewById(R.id.messages_loading_pb);

        return rootView;
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        mMessagesRv.setItemViewCacheSize(ITEM_VIEW_CACHE_SIZE);
        mMessagesRv.setItemAnimator(new DefaultItemAnimator());
        mMessagesRv.addItemDecoration(new DividerItemDecoration(getResources().getDrawable(R.drawable.message_list_divider), true, true));
        LinearLayoutManager layoutManager = new LinearLayoutManager(getActivity());
        mMessagesRv.setLayoutManager(layoutManager);
        mMessagesRv.addOnScrollListener(new MessagesScrollListener(layoutManager));

        populateMessageList();

        mMessageBoxEt.setOnEditorActionListener(new TextView.OnEditorActionListener() {

            @Override
            public boolean onEditorAction(TextView v, int actionId,
                                          KeyEvent event) {
                if (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER) {
                    sendMessage();
                    return true;
                }
                return false;
            }
        });

        view.findViewById(R.id.message_send_btn).setOnClickListener(this);
        if (mRoom.isPublic()) {
            mAnonToggleCv.setOnClickListener(this);
            ImageLoader.getInstance().displayImage(mProfilePicUrl, mAnonToggleCv,
                    options, mAnimateFirstListener);
        } else {
            mAnonToggleCv.setVisibility(View.GONE);
        }
    }

    @Override
    public void onClick(View view) {
        int id = view.getId();
        switch (id) {
            case R.id.message_send_btn:
                sendMessage();
                break;
            case R.id.anon_toggle_civ:
                mIsAnon = !mIsAnon;
                if (mIsAnon) {
                    mAnonToggleCv.setImageDrawable(getResources().getDrawable(R.drawable.com_facebook_profile_picture_blank_square));
                } else {
                    ImageLoader.getInstance().displayImage(mProfilePicUrl, mAnonToggleCv,
                            options, mAnimateFirstListener);
                }
                break;
        }
    }

    private void populateMessageList(List<Message> messageList) {
        if (mMessageAdapter == null) {
            Activity activity = getActivity();
            if (activity != null) {
                if (mAnonSelf != null) {
                    mMessageAdapter = new MessageAdapter(activity, messageList, mAnonSelf.getUserId(), this);
                } else {
                    mMessageAdapter = new MessageAdapter(activity, messageList, this);
                }
                mMessagesRv.setAdapter(mMessageAdapter);
                mMessagesRv.scrollToPosition(mMessageAdapter.getItemCount() - 1);
            }
        } else {
            mMessageAdapter.addMessagesToStart(messageList);
        }
    }

    private void populateMessageList() {
        if (!NetworkManager.checkOnline(getActivity())) {
            return;
        }

        if (mMessageOffset == 0) {
            showMessageLoadingPb();
        }

        if (mRoom.isPublic()) {
            ZipChatApi.INSTANCE.getPublicRoomMessages(UserManager.getAuthToken(getActivity()), mRoom.getRoomId(),
                    MESSAGE_LIMIT, mMessageOffset, mGetMessagesCallback);
        } else {
            ZipChatApi.INSTANCE.getPrivateRoomMessages(UserManager.getAuthToken(getActivity()), mRoom.getRoomId(),
                    MESSAGE_LIMIT, mMessageOffset, mGetMessagesCallback);
        }
    }

    private void showMessageLoadingPb() {
        mMessageLoadingPb.setVisibility(View.VISIBLE);
    }

    private void hideMessageLoadingPb() {
        mMessageLoadingPb.setVisibility(View.GONE);
    }

    @Subscribe
    @SuppressWarnings("unused")
    public void onAddFavoriteEvent(final AddFavoriteEvent event) {

        getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (mMessageAdapter != null) {
                    mMessageAdapter.favoriteMessage(event.getUser(), event.getMessageId(), mSelfId);
                } else {
                    Log.w(TAG, "mMessageAdapter was null in favoriteMessage");
                }
            }
        });
    }

    @Subscribe
    @SuppressWarnings("unused")
    public void onRemoveFavoriteEvent(final RemoveFavoriteEvent event) {

        getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (mMessageAdapter != null) {
                    mMessageAdapter.removeFavorite(event.getUser(), event.getMessageId(), mSelfId);
                } else {
                    Log.w(TAG, "mMessageAdapter was null in removeFavorite");
                }
            }
        });
    }

    @Subscribe
    @SuppressWarnings("unused")
    public void onTalkConfirmation(TalkConfirmationEvent event) {
        if (mMessageAdapter != null) {
            mMessageAdapter.confirmMessage(event.getUuid(), event.getMessage());
        } else {
            Log.w(TAG, "mMessageAdapter is null when confirming a talk message");
        }
    }

    private void sendMessage() {
        String messageContent = mMessageBoxEt.getText().toString().trim();
        if (!messageContent.isEmpty()) {
            String uuid = UUID.randomUUID().toString();
            addMessageLocally(messageContent, uuid);
            sendTalkEvent(messageContent, mIsAnon, uuid);
            Utils.hideKeyboard(getActivity(), mMessageBoxEt);
            mMessageBoxEt.setText("");
        }
    }

    private void addMessageLocally(String messageContent, String uuid) {
        User sender = mIsAnon ? mAnonSelf : UserManager.getSelf(getActivity());
        Message userMessage = new Message(messageContent, sender, uuid);
        addMessage(userMessage);
    }

    @Subscribe
    @SuppressWarnings("unused")
    public void onTalkEvent(final TalkEvent talkEvent) {
        addMessage(talkEvent.getMessage());
    }

    private void addMessage(final Message message) {
        getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (mMessageAdapter != null) {
                    mMessageAdapter.addMessageToEnd(message);
                    mMessagesRv.scrollToPosition(mMessageAdapter.getItemCount() - 1);
                    mMessageOffset++;
                } else {
                    Log.w(TAG, "mMessageAdapter was null in addMessage");
                }
            }
        });
    }

    public void sendTalkEvent(String text, boolean isAnon, String uuid) {
        mRoomSocket.sendTalk(text, isAnon, uuid);
    }

    @Override
    public void onFavoriteClick(long messageId, boolean isFavorite) {
        mRoomSocket.sendFavorite(messageId, isFavorite);
    }

    @Override
    public void onResendMessageClick(Message message) {
        mRoomSocket.sendTalk(message.getMessage(), message.getSender().isAnon(), message.getUuid(), false);
    }

    @Override
    public void onMessageClick(Message message) {
        Intent intent;
        Activity activity = getActivity();
        if (activity == null) {
            Log.w(TAG, "Activity is null in onMessageClick");
            return;
        }
        if (mRoom.isPublic()) {
            intent = MessageDetailsActivity.getIntent(activity, message, mRoom, mAnonSelf.getUserId());
        } else {
            intent = MessageDetailsActivity.getIntent(activity, message, mRoom);
        }
        activity.startActivityForResult(intent, MessageDetailsActivity.MESSAGE_FAVORITED_RESULT);
    }

    @Override
    public void onCompleted(Exception exception, WebSocket webSocket) {
        if (exception != null) {
            Log.e(TAG, "Problem connecting to the web socket: " + exception.getMessage());
            return;
        }
//        Utils.debugToast(getActivity(), "Connected to the socket!");
        mRoomSocket.setWebSocket(webSocket);
    }

    @Subscribe
    @SuppressWarnings("unused")
    public void onPublicRoomJoinSuccess(PublicRoomJoinEvent event) {
        mAnonSelf = event.getAnonUser();
        if (mMessageAdapter != null) {
            mMessageAdapter.setAnonUserId(mAnonSelf.getUserId());
        }
    }

    @Subscribe
    @SuppressWarnings("unused")
    public void onSocketReconnectTimeout(SocketReconnectTimeout event) {
        failUnconfirmedMessages();
    }

    private void failUnconfirmedMessages() {
        if (mMessageAdapter != null) {
            mMessageAdapter.timeoutUnconfirmedMessages();
        } else {
            Log.w(TAG, "mMessageAdapter is null when failing unconfirmed messages");
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        if (mMessageAdapter != null) {
            mMessageAdapter.sendPendingEvents();
        }
        mRoomSocket.onPause();
        BusProvider.getInstance().unregister(this);
    }

    @Override
    public void onResume() {
        super.onResume();
        mRoomSocket.onResume();
        BusProvider.getInstance().register(this);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        mRoomSocket.onDestroy();
        AnimateFirstDisplayListener.clearImages();
    }

    public void updateMessage(Message message) {
        mMessageAdapter.updateMessage(message);
    }

    private class MessagesScrollListener extends RecyclerView.OnScrollListener {

        private LinearLayoutManager mLayoutManager;
        private int pastVisiblesItems;
        private int visibleItemCount;
        private int totalItemCount;

        private MessagesScrollListener(LinearLayoutManager mLayoutManager) {
            this.mLayoutManager = mLayoutManager;
        }

        @Override
        public void onScrolled(RecyclerView recyclerView, int dx, int dy) {
            visibleItemCount = mLayoutManager.getChildCount();
            totalItemCount = mLayoutManager.getItemCount();
            pastVisiblesItems = mLayoutManager.findFirstVisibleItemPosition();

            if (mMessagesLoading) {
                if (!mLoadedAllMessages && (visibleItemCount + pastVisiblesItems) >= totalItemCount) {
                    mMessagesLoading = false;
                    populateMessageList();
                }
            }
        }
    }
}
