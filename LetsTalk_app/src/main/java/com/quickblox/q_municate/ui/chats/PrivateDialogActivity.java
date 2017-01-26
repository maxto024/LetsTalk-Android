package com.quickblox.q_municate.ui.chats;

import android.app.ActionBar;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.database.ContentObserver;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.text.TextUtils;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;

import com.google.android.gms.internal.is;
import com.nostra13.universalimageloader.core.ImageLoader;
import com.quickblox.chat.QBChatService;
import com.quickblox.core.exception.QBResponseException;
import com.quickblox.chat.model.QBDialog;
import com.quickblox.chat.model.QBDialogType;
import com.quickblox.content.model.QBFile;
import com.quickblox.q_municate.R;
import com.quickblox.q_municate.ui.mediacall.CallActivity;
import com.quickblox.q_municate.utils.Consts;
import com.quickblox.q_municate_core.db.managers.ChatDatabaseManager;
import com.quickblox.q_municate_core.db.managers.UsersDatabaseManager;
import com.quickblox.q_municate_core.db.tables.FriendTable;
import com.quickblox.q_municate_core.models.AppSession;
import com.quickblox.q_municate_core.models.MessageCache;
import com.quickblox.q_municate_core.models.MessagesNotificationType;
import com.quickblox.q_municate_core.models.User;
import com.quickblox.q_municate_core.qb.commands.QBAcceptFriendCommand;
import com.quickblox.q_municate_core.qb.commands.QBRejectFriendCommand;
import com.quickblox.q_municate_core.qb.commands.QBUpdateStatusMessageCommand;
import com.quickblox.q_municate_core.qb.helpers.QBPrivateChatHelper;
import com.quickblox.q_municate_core.service.QBService;
import com.quickblox.q_municate_core.service.QBServiceConsts;
import com.quickblox.q_municate.ui.dialogs.AlertDialog;
import com.quickblox.q_municate_core.utils.ChatNotificationUtils;
import com.quickblox.q_municate_core.utils.ConstsCore;
import com.quickblox.q_municate_core.utils.DialogUtils;
import com.quickblox.q_municate_core.utils.ErrorUtils;
import com.quickblox.q_municate.utils.ReceiveFileFromBitmapTask;
import com.quickblox.videochat.webrtc.QBRTCTypes;

import java.io.File;

import de.keyboardsurfer.android.widget.crouton.Crouton;
import se.emilsjolander.stickylistheaders.StickyListHeadersAdapter;

public class PrivateDialogActivity extends BaseDialogActivity implements ReceiveFileFromBitmapTask.ReceiveFileListener {

    private static final String TAG = PrivateDialogActivity.class.getSimpleName();
    private ContentObserver statusContentObserver;
    private ContentObserver friendsTableContentObserver;
    private Cursor friendCursor;
    private FriendOperationAction friendOperationAction;
    public PrivateDialogActivity() {
        super(R.layout.activity_dialog, QBService.PRIVATE_CHAT_HELPER);
    }

    public static void start(Context context, User opponent, QBDialog dialog) {
        Intent intent = new Intent(context, PrivateDialogActivity.class);
        intent.putExtra(QBServiceConsts.EXTRA_OPPONENT, opponent);
        intent.putExtra(QBServiceConsts.EXTRA_DIALOG, dialog);
        context.startActivity(intent);
    }

    @Override
    protected void onFileSelected(Bitmap bitmap) {
        new ReceiveFileFromBitmapTask(PrivateDialogActivity.this).execute(imageUtils, bitmap, true);
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        friendOperationAction = new FriendOperationAction();
        opponentFriend = (User) getIntent().getExtras().getSerializable(QBServiceConsts.EXTRA_OPPONENT);
        dialog = (QBDialog) getIntent().getExtras().getSerializable(QBServiceConsts.EXTRA_DIALOG);
        dialogId = dialog.getDialogId();
        friendCursor = UsersDatabaseManager.getFriendCursorById(this, opponentFriend.getUserId());

        // Check count of messages have been stored in base, if stored messages aren't only contact request then we
        // skip count of messages in base on next dialog's messages request to server this count of messages
        if (!isFirstDialogLaunch()) {
            skipMessages = ChatDatabaseManager.getAllDialogMessagesByDialogId(this, dialog.getDialogId()).getCount();
        }

        initCursorLoaders();
        initActionBar();
        registerContentObservers();
        setCurrentDialog(dialog);
    }

    @Override
    protected void onPause() {
        super.onPause();
        Crouton.cancelAllCroutons();
    }

    @Override
    protected void onUpdateChatDialog() {
        if (messagesAdapter != null && !messagesAdapter.isEmpty()) {
            startUpdateChatDialog();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        currentOpponent = null;
        unregisterStatusChangingObserver();
    }

    @Override
    protected void onFileSelected(Uri originalUri) {
        Bitmap bitmap = ImageLoader.getInstance().loadImageSync(originalUri.toString(), Consts.UIL_DEFAULT_DISPLAY_OPTIONS);
        new ReceiveFileFromBitmapTask(PrivateDialogActivity.this).execute(imageUtils, bitmap, true);
    }

    @Override
    protected void onFileLoaded(QBFile file) {
        try {
            ((QBPrivateChatHelper) baseChatHelper).sendPrivateMessageWithAttachImage(file,
                    opponentFriend.getUserId());
        } catch (QBResponseException exc) {
            ErrorUtils.showError(this, exc);
        }
    }

    @Override
    protected Bundle generateBundleToInitDialog() {
        Bundle bundle = new Bundle();
        if (opponentFriend != null) {
            bundle.putInt(QBServiceConsts.EXTRA_OPPONENT_ID, opponentFriend.getUserId());
        }
        return bundle;
    }

    private void registerContentObservers() {
        statusContentObserver = new ContentObserver(new Handler()) {

            @Override
            public void onChange(boolean selfChange) {
                if (opponentFriend != null) {
                    opponentFriend = UsersDatabaseManager.getUserById(PrivateDialogActivity.this,
                        PrivateDialogActivity.this.opponentFriend.getUserId());
                    setOnlineStatus(opponentFriend);
                }
            }
        };

        friendCursor.registerContentObserver(statusContentObserver);

        friendsTableContentObserver = new ContentObserver(new Handler()) {

            @Override
            public void onChange(boolean selfChange) {
                checkMessageSendingPossibility();
            }
        };
        getContentResolver().registerContentObserver(FriendTable.CONTENT_URI, true, friendsTableContentObserver);
    }

    @Override
    protected void updateActionBar() {
    }

    private void unregisterStatusChangingObserver() {
        if (friendCursor != null && statusContentObserver != null) {
            friendCursor.unregisterContentObserver(statusContentObserver);
        }

        if (friendsTableContentObserver != null) {
            getContentResolver().unregisterContentObserver(friendsTableContentObserver);
        }
    }

    private void setOnlineStatus(User friend) {
        if(friend != null){
            if(getActionBar() != null) {
                getActionBar().setSubtitle(friend.getOnlineStatus(this));
            }
        }
    }

    protected QBDialog getQBDialog() {
        Cursor cursor = null;

        if (messagesAdapter.getCursor().getCount() > ConstsCore.ZERO_INT_VALUE) {
            cursor = (Cursor) messagesAdapter.getItem(messagesAdapter.getCount() - 1);
        }

        MessageCache messageCache = ChatDatabaseManager.getMessageCacheFromCursor(cursor);
        MessagesNotificationType messagesNotificationType = messageCache.getMessagesNotificationType();

        if (messagesNotificationType == null) {
            dialog.setLastMessage(messageCache.getMessage());
        } else if (ChatNotificationUtils.isFriendsNotificationMessage(messagesNotificationType.getCode())) {
            dialog.setLastMessage(resources.getString(R.string.frl_friends_contact_request));
        } else if (ChatNotificationUtils.isUpdateChatNotificationMessage(messagesNotificationType.getCode())) {
            dialog.setLastMessage(resources.getString(R.string.cht_notification_message));
        }

        dialog.setLastMessageDateSent(messageCache.getTime());
        dialog.setUnreadMessageCount(ConstsCore.ZERO_INT_VALUE);
        dialog.setLastMessageUserId(messageCache.getSenderId());
        dialog.setType(QBDialogType.PRIVATE);

        return dialog;
    }

    @Override
    protected void initListView(Cursor messagesCursor) {
        messagesAdapter = new PrivateDialogMessagesAdapter(this, friendOperationAction, messagesCursor, this, dialog);
        messagesListView.setAdapter((StickyListHeadersAdapter) messagesAdapter);
        ((PrivateDialogMessagesAdapter) messagesAdapter).findLastFriendsRequestMessagesPosition();
        isNeedToScrollMessages = true;
        scrollListView();
    }

    private void initActionBar() {
        actionBar.setTitle(opponentFriend.getFullName());
        actionBar.setSubtitle(opponentFriend.getOnlineStatus(this));
        actionBar.setLogo(R.drawable.placeholder_user);
        if (!TextUtils.isEmpty(opponentFriend.getAvatarUrl())) {
            loadLogoActionBar(opponentFriend.getAvatarUrl());
        }
    }

    @Override
    public void onCachedImageFileReceived(File file) {
        startLoadAttachFile(file);
    }

    @Override
    public void onAbsolutePathExtFileReceived(String absolutePath) {
    }

    public void sendMessageOnClick(View view) {
        sendMessage(true);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.private_dialog_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        boolean isFriend = UsersDatabaseManager.isFriendInBase(PrivateDialogActivity.this,
                opponentFriend.getUserId());
        if (!isFriend && item.getItemId() != android.R.id.home) {
            DialogUtils.showLong(PrivateDialogActivity.this, getResources().getString(R.string.dlg_user_is_not_friend));
            return true;
        }
        switch (item.getItemId()) {
            case android.R.id.home:
                navigateToParent();
                return true;
            case R.id.action_attach:
                attachButtonOnClick();
                return true;
            case R.id.action_audio_call:
                callToUser(opponentFriend, QBRTCTypes.QBConferenceType.QB_CONFERENCE_TYPE_AUDIO);
                return true;
            case R.id.action_video_call:
                callToUser(opponentFriend, QBRTCTypes.QBConferenceType.QB_CONFERENCE_TYPE_VIDEO);
                return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        super.onPrepareOptionsMenu(menu);

            menu.findItem(R.id.action_attach).setVisible(isConnectionEnabled());
            menu.findItem(R.id.action_audio_call).setVisible(isConnectionEnabled());
            menu.findItem(R.id.action_video_call).setVisible(isConnectionEnabled());

        return true;
    }

    private void callToUser(User friend, QBRTCTypes.QBConferenceType callType) {
        if (friend.getUserId() != AppSession.getSession().getUser().getId()) {
            if(QBChatService.getInstance().isLoggedIn()) {
                CallActivity.start(PrivateDialogActivity.this, friend, callType);
            }
        }
    }


    @Override
    protected void onResume() {
        super.onResume();
        currentOpponent = opponentFriend.getFullName();
        checkMessageSendingPossibility();
    }

    private void checkMessageSendingPossibility() {
        if (opponentFriend != null){
            boolean isFriend = UsersDatabaseManager.isFriendInBase(PrivateDialogActivity.this,
                    opponentFriend.getUserId());
            messageEditText.setEnabled(isFriend);
            smilePanelImageButton.setEnabled(isFriend);
        } else {
            Log.d(TAG, "Users cache has been already cleaned ");
        }
    }

    private void acceptUser(final int userId) {
        showProgress();
        QBAcceptFriendCommand.start(this, userId);
    }

    private void rejectUser(final int userId) {
        showRejectUserDialog(userId);
    }

    private void showRejectUserDialog(final int userId) {
        User user = UsersDatabaseManager.getUserById(this, userId);
        AlertDialog alertDialog = AlertDialog.newInstance(getResources().getString(
                R.string.frl_dlg_reject_friend, user.getFullName()));
        alertDialog.setPositiveButton(new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                showProgress();
                QBRejectFriendCommand.start(PrivateDialogActivity.this, userId);
            }
        });
        alertDialog.setNegativeButton(new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
            }
        });
        alertDialog.show(getFragmentManager(), null);
    }

    public interface FriendOperationListener {

        void onAcceptUserClicked(int userId);

        void onRejectUserClicked(int userId);
    }

    private class FriendOperationAction implements FriendOperationListener {

        @Override
        public void onAcceptUserClicked(int userId) {
            acceptUser(userId);
        }

        @Override
        public void onRejectUserClicked(int userId) {
            rejectUser(userId);
        }
    }

    @Override
    public void onConnectionChange(boolean isConnected) {
        super.onConnectionChange(isConnected);
        invalidateOptionsMenu();

        sendButton.setActivated(isConnected);
    }
}