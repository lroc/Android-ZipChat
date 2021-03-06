package com.kdoherty.zipchat.services;

import android.content.Context;
import android.os.Handler;
import android.support.annotation.Nullable;
import android.util.Log;

import com.google.gson.Gson;
import com.kdoherty.zipchat.events.AddFavoriteEvent;
import com.kdoherty.zipchat.events.MemberJoinEvent;
import com.kdoherty.zipchat.events.MemberLeaveEvent;
import com.kdoherty.zipchat.events.PublicRoomJoinEvent;
import com.kdoherty.zipchat.events.RemoveFavoriteEvent;
import com.kdoherty.zipchat.events.SocketReconnectTimeout;
import com.kdoherty.zipchat.events.TalkConfirmationEvent;
import com.kdoherty.zipchat.events.TalkEvent;
import com.kdoherty.zipchat.models.Message;
import com.kdoherty.zipchat.models.User;
import com.kdoherty.zipchat.utils.BusProvider;
import com.kdoherty.zipchat.utils.UserManager;
import com.koushikdutta.async.callback.CompletedCallback;
import com.koushikdutta.async.http.WebSocket;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Queue;

/**
 * Created by kdoherty on 8/13/15.
 */
public class RoomSocket {

    private static final String KEEP_ALIVE_MSG = "Beat";
    private static final String TAG = RoomSocket.class.getSimpleName();
    private static final long BACKOFF_MILLIS = 2000;
    private static final int MAX_NUM_RETRY_ATTEMPTS = 4;
    private final long mUserId;
    private final WebSocket.StringCallback mEventCallback = new WebSocket.StringCallback() {
        @Override
        public void onStringAvailable(String s) {
            try {
                Gson gson = new Gson();
                JSONObject stringJson = new JSONObject(s);

                String event = stringJson.getString("event");

                switch (event) {
                    case "talk":
                        String talk = stringJson.getString("message");
                        if (!KEEP_ALIVE_MSG.equals(talk)) {
                            Message msg = gson.fromJson(talk, Message.class);
                            BusProvider.getInstance().post(new TalkEvent(msg));
                        } else {
                            Log.v(TAG, "Received heartbeat from socket");
                        }
                        break;
                    case "talk-confirmation":
                        String uuid = stringJson.getString("uuid");
                        Message msg = gson.fromJson(stringJson.getString("message"), Message.class);
                        BusProvider.getInstance().post(new TalkConfirmationEvent(uuid, msg));
                        break;
                    case "join":
                        User joinedUser = gson.fromJson(stringJson.getString("user"), User.class);
                        if (joinedUser.getUserId() != mUserId) {
                            BusProvider.getInstance().post(new MemberJoinEvent(joinedUser));
                        }
                        break;
                    case "quit":
                        User quitUser = gson.fromJson(stringJson.getString("user"), User.class);
                        if (quitUser.getUserId() != mUserId) {
                            BusProvider.getInstance().post(new MemberLeaveEvent(quitUser));
                        }
                        break;
                    case "joinSuccess":
                        if (stringJson.has("message")) {
                            JSONObject joinJson = new JSONObject(stringJson.getString("message"));

                            User[] roomMembers = gson.fromJson(joinJson.getString("roomMembers"), User[].class);
                            List<User> roomMemberList = new ArrayList<>(Arrays.asList(roomMembers));
                            boolean isSubscribed = joinJson.getBoolean("isSubscribed");
                            User anonUser = gson.fromJson(joinJson.getString("anonUser"), User.class);

                            BusProvider.getInstance().post(new PublicRoomJoinEvent(roomMemberList, anonUser, isSubscribed));
                        }
                        break;
                    case "favorite":
                        User msgFavoritor = gson.fromJson(stringJson.getString("user"), User.class);
                        if (msgFavoritor.getUserId() != mUserId) {
                            BusProvider.getInstance().post(new AddFavoriteEvent(msgFavoritor, Long.parseLong(stringJson.getString("message"))));
                        }
                        break;
                    case "removeFavorite":
                        User msgUnfavoritor = gson.fromJson(stringJson.getString("user"), User.class);
                        if (msgUnfavoritor.getUserId() != mUserId) {
                            BusProvider.getInstance().post(new RemoveFavoriteEvent(msgUnfavoritor, Long.parseLong(stringJson.getString("message"))));
                        }
                        break;
                    case "error":
                        Log.e(TAG, "Error: " + stringJson.getString("message"));
//                        Utils.debugToast(mContext, "Error: " + stringJson.getString("message"));
                        break;
                    default:
//                        Utils.debugToast(mContext, "Default socket event " + s);
                        Log.w(TAG, "DEFAULT RECEIVED: " + s);
                }
            } catch (JSONException e) {
                Log.e(TAG, "Problem parsing socket received JSON: " + s);
            }
        }
    };
    private Queue<JSONObject> mSocketEventQueue = new ArrayDeque<>();
    private boolean mIsReconnecting = false;
    private ChatService mChatService;
    private Context mContext;
    private WebSocket mWebSocket;
    private Handler mHandler = new Handler();
    private int mNumRetryAttempts = 0;
    private Runnable mReconnectRunnable = new Runnable() {
        @Override
        public void run() {
            if (socketIsAvailable()) {
                mIsReconnecting = false;
                mNumRetryAttempts = 0;
            } else if (mNumRetryAttempts++ <= MAX_NUM_RETRY_ATTEMPTS) {
                reconnectWithRetry();
            } else {
                mIsReconnecting = false;
                mNumRetryAttempts = 0;
                BusProvider.getInstance().post(new SocketReconnectTimeout());
                Log.e(TAG, "Done trying to reconnect to the socket after " + MAX_NUM_RETRY_ATTEMPTS + " attempts");
            }
        }
    };
    private final CompletedCallback mClosedCallback = new CompletedCallback() {
        @Override
        public void onCompleted(Exception ex) {
            if (ex != null) {
                Log.w(TAG, "Attempting to recover from " + ex.getMessage());
                reconnect();
            } else {
                Log.w(TAG, "Socket closed!");
            }
        }
    };

    public RoomSocket(Context context, ChatService chatService) {
        this.mContext = context;
        this.mUserId = UserManager.getId(context);
        this.mChatService = chatService;
    }

    public void setWebSocket(@Nullable WebSocket webSocket) {
        this.mWebSocket = webSocket;
        if (webSocket != null) {
            this.mWebSocket.setStringCallback(mEventCallback);
            this.mWebSocket.setClosedCallback(mClosedCallback);
            sendQueuedEvents();
        }
    }

    public void sendTalk(String message, boolean isAnon, String uuid) {
        sendTalk(message, isAnon, uuid, true);
    }

    public void sendTalk(String message, boolean isAnon, String uuid, boolean addToQueue) {
        JSONObject talkEvent = new JSONObject();
        try {
            talkEvent.put("event", "talk");
            talkEvent.put("message", message);
            talkEvent.put("isAnon", isAnon);
            talkEvent.put("uuid", uuid);
        } catch (JSONException e) {
            Log.e(TAG, "Problem creating the chat message JSON: " + e.getMessage());
            return;
        }

        send(talkEvent, addToQueue);
    }

    public void sendFavorite(long messageId, boolean isFavorite) {
        JSONObject favoriteEvent = new JSONObject();
        try {
            favoriteEvent.put("event", "FavoriteNotification");
            favoriteEvent.put("messageId", messageId);
            favoriteEvent.put("action", isFavorite ? "add" : "remove");
        } catch (JSONException e) {
            Log.e(TAG, "Problem creating the favorite event JSON: " + e.getMessage());
            return;
        }

        send(favoriteEvent);
    }

    private void send(JSONObject event) {
        send(event, true);
    }

    private void send(JSONObject event, boolean addToQueue) {
        if (socketIsAvailable()) {
            mWebSocket.send(event.toString());
        } else {
            sendEventSocketNotAvailable(event, addToQueue);
        }
    }

    private boolean socketIsAvailable() {
        return mWebSocket != null && mWebSocket.isOpen();
    }

    private void sendEventSocketNotAvailable(JSONObject event, boolean addToQueue) {
        String err = "WebSocket is closed when trying to send "
                + event.toString() + "... Adding event to queue";
//        Utils.debugToast(mContext, err);
        Log.w(TAG, err);

        if (addToQueue) {
            mSocketEventQueue.add(event);
        }

        reconnect();
    }

    private synchronized void reconnect() {
        if (!mIsReconnecting) {
//            Utils.debugToast(mContext, "ChatService is not currently connecting... Attempting to reconnect");
            mIsReconnecting = true;
            reconnectWithRetry();
        }
    }

    private void reconnectWithRetry() {
        mWebSocket = null;
        mChatService.cancel();
        mChatService = new ChatService(mChatService);

//        Utils.debugToast(mContext, "Reconnect attempt number " + mNumRetryAttempts, Toast.LENGTH_SHORT);

        mHandler.postDelayed(mReconnectRunnable, BACKOFF_MILLIS);
    }

    private void sendQueuedEvents() {
        while (!mSocketEventQueue.isEmpty()) {
            JSONObject event = mSocketEventQueue.poll();
//            Utils.debugToast(mContext, "Sending message from mSocketEventQueue: " + event);
            Log.i(TAG, "Sending message from mSocketEventQueue: " + event);
            send(event);
        }
    }

    public void onPause() {
        if (socketIsAvailable()) {
            Log.i(TAG, "Pausing socket!");
//            Utils.debugToast(mContext, "Pausing socket in on pause", Toast.LENGTH_SHORT);
            mWebSocket.pause();
            mHandler.removeCallbacksAndMessages(null);
        }
    }

    public void onResume() {
        if (mWebSocket != null && mWebSocket.isPaused()) {
            Log.i(TAG, "Resuming socket!");
            mWebSocket.resume();
//            Utils.debugToast(mContext, "Resuming socket in on resume", Toast.LENGTH_SHORT);
        }
    }

    public void onDestroy() {
        if (mWebSocket != null && mWebSocket.getSocket() != null) {
            Log.i(TAG, "onDestroy in RoomSocket");
            mWebSocket.getSocket().close();
        }
    }

}
