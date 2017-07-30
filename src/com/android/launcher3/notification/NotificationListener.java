/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.launcher3.notification;

import android.annotation.TargetApi;
import android.app.Notification;
import android.app.NotificationChannel;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.support.annotation.Nullable;
import android.text.TextUtils;
import android.util.ArraySet;
import android.util.Log;
import android.util.Pair;
import com.android.launcher3.LauncherModel;
import com.android.launcher3.config.FeatureFlags;
import com.android.launcher3.util.PackageUserKey;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * A {@link NotificationListenerService} that sends updates to its
 * {@link NotificationsChangedListener} when notifications are posted or canceled,
 * as well and when this service first connects. An instance of NotificationListener,
 * and its methods for getting notifications, can be obtained via {@link #getInstanceIfConnected()}.
 */
@TargetApi(Build.VERSION_CODES.O)
public class NotificationListener extends NotificationListenerService {

    public static final String TAG = "NotificationListener";

    private static final int MSG_NOTIFICATION_POSTED = 1;
    private static final int MSG_NOTIFICATION_REMOVED = 2;
    private static final int MSG_NOTIFICATION_FULL_REFRESH = 3;

    private static NotificationListener sNotificationListenerInstance = null;
    private static NotificationsChangedListener sNotificationsChangedListener;
    private static boolean sIsConnected;

    private final Handler mWorkerHandler;
    private final Handler mUiHandler;

    private final Ranking mTempRanking = new Ranking();

    private final Handler.Callback mWorkerCallback = new Handler.Callback() {
        @Override
        public boolean handleMessage(Message message) {
            switch (message.what) {
                case MSG_NOTIFICATION_POSTED:
                    mUiHandler.obtainMessage(message.what, message.obj).sendToTarget();
                    break;
                case MSG_NOTIFICATION_REMOVED:
                    mUiHandler.obtainMessage(message.what, message.obj).sendToTarget();
                    break;
                case MSG_NOTIFICATION_FULL_REFRESH:
                    List<StatusBarNotification> activeNotifications;
                    if (sIsConnected) {
                        try {
                            activeNotifications =  filterNotifications(getActiveNotifications());
                        } catch (SecurityException ex) {
                            Log.e(TAG, "SecurityException: failed to fetch notifications");
                            activeNotifications = new ArrayList<StatusBarNotification>();

                        }
                    } else {
                        activeNotifications = new ArrayList<StatusBarNotification>();
                    }

                    mUiHandler.obtainMessage(message.what, activeNotifications).sendToTarget();
                    break;
            }
            return true;
        }
    };

    private final Handler.Callback mUiCallback = new Handler.Callback() {
        @Override
        public boolean handleMessage(Message message) {
            switch (message.what) {
                case MSG_NOTIFICATION_POSTED:
                    if (sNotificationsChangedListener != null) {
                        NotificationPostedMsg msg = (NotificationPostedMsg) message.obj;
                        sNotificationsChangedListener.onNotificationPosted(msg.packageUserKey,
                                msg.notificationKey, msg.shouldBeFilteredOut);
                    }
                    break;
                case MSG_NOTIFICATION_REMOVED:
                    if (sNotificationsChangedListener != null) {
                        Pair<PackageUserKey, NotificationKeyData> pair
                                = (Pair<PackageUserKey, NotificationKeyData>) message.obj;
                        sNotificationsChangedListener.onNotificationRemoved(pair.first, pair.second);
                    }
                    break;
                case MSG_NOTIFICATION_FULL_REFRESH:
                    if (sNotificationsChangedListener != null) {
                        sNotificationsChangedListener.onNotificationFullRefresh(
                                (List<StatusBarNotification>) message.obj);
                    }
                    break;
            }
            return true;
        }
    };

    public NotificationListener() {
        super();
        mWorkerHandler = new Handler(LauncherModel.getWorkerLooper(), mWorkerCallback);
        mUiHandler = new Handler(Looper.getMainLooper(), mUiCallback);
        sNotificationListenerInstance = this;
    }

    public static @Nullable NotificationListener getInstanceIfConnected() {
        return sIsConnected ? sNotificationListenerInstance : null;
    }

    public static void setNotificationsChangedListener(NotificationsChangedListener listener) {
        if (!FeatureFlags.BADGE_ICONS) {
            return;
        }
        sNotificationsChangedListener = listener;

        NotificationListener notificationListener = getInstanceIfConnected();
        if (notificationListener != null) {
            notificationListener.onNotificationFullRefresh();
        }
    }

    public static void removeNotificationsChangedListener() {
        sNotificationsChangedListener = null;
    }

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        sIsConnected = true;
        onNotificationFullRefresh();
    }

    private void onNotificationFullRefresh() {
        mWorkerHandler.obtainMessage(MSG_NOTIFICATION_FULL_REFRESH).sendToTarget();
    }

    @Override
    public void onListenerDisconnected() {
        super.onListenerDisconnected();
        sIsConnected = false;
    }

    @Override
    public void onNotificationPosted(final StatusBarNotification sbn) {
        super.onNotificationPosted(sbn);
        mWorkerHandler.obtainMessage(MSG_NOTIFICATION_POSTED, new NotificationPostedMsg(sbn))
                .sendToTarget();
    }

    /**
     * An object containing data to send to MSG_NOTIFICATION_POSTED targets.
     */
    private class NotificationPostedMsg {
        final PackageUserKey packageUserKey;
        final NotificationKeyData notificationKey;
        final boolean shouldBeFilteredOut;

        NotificationPostedMsg(StatusBarNotification sbn) {
            packageUserKey = PackageUserKey.fromNotification(sbn);
            notificationKey = NotificationKeyData.fromNotification(sbn);
            shouldBeFilteredOut = shouldBeFilteredOut(sbn);
        }
    }

    @Override
    public void onNotificationRemoved(final StatusBarNotification sbn) {
        super.onNotificationRemoved(sbn);
        Pair<PackageUserKey, NotificationKeyData> packageUserKeyAndNotificationKey
                = new Pair<>(PackageUserKey.fromNotification(sbn),
                        NotificationKeyData.fromNotification(sbn));
        mWorkerHandler.obtainMessage(MSG_NOTIFICATION_REMOVED, packageUserKeyAndNotificationKey)
                .sendToTarget();
    }

    /** This makes a potentially expensive binder call and should be run on a background thread. */
    public List<StatusBarNotification> getNotificationsForKeys(List<NotificationKeyData> keys) {
        StatusBarNotification[] notifications = NotificationListener.this
                .getActiveNotifications(NotificationKeyData.extractKeysOnly(keys)
                        .toArray(new String[keys.size()]));
        return notifications == null
            ? Collections.<StatusBarNotification>emptyList() : Arrays.asList(notifications);
    }

    /**
     * Filter out notifications that don't have an intent
     * or are headers for grouped notifications.
     *
     * @see #shouldBeFilteredOut(StatusBarNotification)
     */
    private List<StatusBarNotification> filterNotifications(
            StatusBarNotification[] notifications) {
        if (notifications == null) return null;
        Set<Integer> removedNotifications = new ArraySet<>();
        for (int i = 0; i < notifications.length; i++) {
            if (shouldBeFilteredOut(notifications[i])) {
                removedNotifications.add(i);
            }
        }
        List<StatusBarNotification> filteredNotifications = new ArrayList<>(
                notifications.length - removedNotifications.size());
        for (int i = 0; i < notifications.length; i++) {
            if (!removedNotifications.contains(i)) {
                filteredNotifications.add(notifications[i]);
            }
        }
        return filteredNotifications;
    }

    private boolean shouldBeFilteredOut(StatusBarNotification sbn) {
        getCurrentRanking().getRanking(sbn.getKey(), mTempRanking);
        if (!mTempRanking.canShowBadge()) {
            return true;
        }
        Notification notification = sbn.getNotification();
        if (mTempRanking.getChannel().getId().equals(NotificationChannel.DEFAULT_CHANNEL_ID)) {
            // Special filtering for the default, legacy "Miscellaneous" channel.
            if ((notification.flags & Notification.FLAG_ONGOING_EVENT) != 0) {
                return true;
            }
        }
        boolean isGroupHeader = (notification.flags & Notification.FLAG_GROUP_SUMMARY) != 0;
        CharSequence title = notification.extras.getCharSequence(Notification.EXTRA_TITLE);
        CharSequence text = notification.extras.getCharSequence(Notification.EXTRA_TEXT);
        boolean missingTitleAndText = TextUtils.isEmpty(title) && TextUtils.isEmpty(text);
        return (isGroupHeader || missingTitleAndText);
    }

    public interface NotificationsChangedListener {
        void onNotificationPosted(PackageUserKey postedPackageUserKey,
                NotificationKeyData notificationKey, boolean shouldBeFilteredOut);
        void onNotificationRemoved(PackageUserKey removedPackageUserKey,
                NotificationKeyData notificationKey);
        void onNotificationFullRefresh(List<StatusBarNotification> activeNotifications);
    }
}
