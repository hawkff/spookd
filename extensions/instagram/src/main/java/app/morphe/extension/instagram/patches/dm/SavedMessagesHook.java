/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.instagram.patches.dm;

import android.content.Context;
import android.content.Intent;

import com.instagram.common.session.UserSession;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

import static app.morphe.extension.instagram.utils.IgStr.str;

import app.morphe.extension.crimera.PikoUtils;
import app.morphe.extension.crimera.sharedPreference.SharedPref;
import app.morphe.extension.instagram.db.SpookdMessageDb;
import app.morphe.extension.instagram.entity.DirectItem;
import app.morphe.extension.instagram.entity.DirectItemWrappers;
import app.morphe.extension.instagram.patches.dm.vault.MediaEligibility;
import app.morphe.extension.instagram.patches.dm.vault.MediaVault;
import app.morphe.extension.instagram.entity.UserData;
import app.morphe.extension.instagram.settings.ActivityHook;
import app.morphe.extension.instagram.settings.Settings;
import app.morphe.extension.instagram.utils.MediaUrls;
import app.morphe.extension.instagram.utils.Pref;

/** Runtime hooks for "Save deleted messages". Fields are resolved at patch time. */
@SuppressWarnings("unused")
public class SavedMessagesHook {

    static final String NOTIFICATION_CHANNEL_ID = "piko_deleted_messages";

    private static volatile String sMyUserId;
    private static volatile java.lang.ref.WeakReference<UserSession> sCurrentSession =
            new java.lang.ref.WeakReference<>(null);
    // Senders whose picture was stored in this process; worker thread only.
    private static final java.util.Set<String> sPictureSaved = new java.util.HashSet<>();
    private static android.os.HandlerThread sWorkerThread;
    private static android.os.Handler sWorker;

    private static void spookd(String message) {
        android.util.Log.e("spookd", message);
    }

    public static void openDeletedMessages(Context context) {
        try {
            if (context == null) context = PikoUtils.getContext();
            if (context == null) return;
            ActivityHook.startDeletedMessagesActivity(context);
        } catch (Exception e) {
            spookd("SavedMessagesHook.openDeletedMessages: " + e);
        }
    }

    /** Called just before Instagram's session manager returns the session it made current. */
    public static void onCurrentSession(UserSession session) {
        sCurrentSession = new java.lang.ref.WeakReference<>(session);
    }

    /** The session Instagram made current last, or null when the hook is missing. */
    static UserSession currentSession() {
        return sCurrentSession.get();
    }

    /** Harvest participant id-to-username mappings from the thread user list. */
    public static void noteThreadUsers(final java.util.List<?> users) {
        if (users == null || users.isEmpty() || !Pref.saveDeletedMessages()) return;
        final java.util.ArrayList<Object> copy;
        try {
            copy = new java.util.ArrayList<Object>(users);
        } catch (Throwable ignored) {
            return;
        }
        getWorker().post(() -> {
            try {
                SpookdMessageDb db = SpookdMessageDb.getInstance(PikoUtils.getContext());
                for (Object user : copy) {
                    if (user == null) continue;
                    try {
                        UserData data = new UserData(user);
                        String id = data.getUserId();
                        if (id == null || !id.matches("\\d{6,14}")) continue;
                        String name = data.getUsername();
                        if (name != null && !name.isEmpty()) db.putUsername(id, name);
                    } catch (Throwable ignored) {
                    }
                }
            } catch (Throwable ignored) {
            }
        });
    }

    private static String myUserId() {
        if (sMyUserId == null) {
            try {
                Context context = PikoUtils.getContext();
                if (context != null) {
                    String value = context.getSharedPreferences("piko_dm", Context.MODE_PRIVATE)
                            .getString("my_user_id", null);
                    if (value != null && !value.isEmpty()) sMyUserId = value;
                }
            } catch (Exception ignored) {
            }
        }
        return sMyUserId;
    }

    private static void rememberMyUserId(String id) {
        if (id == null || id.isEmpty() || id.equals(sMyUserId)) return;
        sMyUserId = id;
        try {
            Context context = PikoUtils.getContext();
            if (context != null) {
                context.getSharedPreferences("piko_dm", Context.MODE_PRIVATE)
                        .edit().putString("my_user_id", id).apply();
            }
        } catch (Exception ignored) {
        }
    }

    static boolean shouldCaptureReceived(boolean sentByViewer, String senderId, String viewerId) {
        return !sentByViewer && (isBlank(senderId) || !senderId.equals(viewerId));
    }

    static String resolveViewerId(boolean sentByViewer, String senderId,
                                  String sessionViewerId) {
        if (sentByViewer) rememberMyUserId(senderId);
        return !isBlank(sessionViewerId) ? sessionViewerId : myUserId();
    }

    static boolean shouldCaptureStored(String senderId, String viewerId) {
        return senderId == null || viewerId == null || !senderId.equals(viewerId);
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static synchronized android.os.Handler getWorker() {
        if (sWorker == null) {
            sWorkerThread = new android.os.HandlerThread("spookd-dm-hook");
            sWorkerThread.start();
            sWorker = new android.os.Handler(sWorkerThread.getLooper());
            sWorker.post(SavedMessagesHook::startMediaVault);
            sWorker.post(SavedMessagesHook::warmUpRevokeCache);
        }
        return sWorker;
    }

    /** REST thread-history path. */
    public static void onMessageReceived(final Object item) {
        enqueueReceivedItem(item, null, null, true);
    }

    /** MQTT path. Parameters match A0P's contiguous p0..p2 registers. */
    public static void onMessageReceived(final Object item, final UserSession userSession,
                                         final Object delta) {
        enqueueReceivedItem(item, userSession, delta, false);
    }

    private static void enqueueReceivedItem(final Object item, final UserSession userSession,
                                            final Object delta, final boolean restPath) {
        boolean enabled = Pref.saveDeletedMessages();
        if (item == null || !enabled) return;
        boolean acceptedClass = item.getClass().getName().startsWith("X.");
        if (!acceptedClass) return;
        // REST items carry no session, so the one current while they are parsed names their account.
        final UserSession session = userSession != null ? userSession : restPath ? currentSession() : null;
        String itemSessionId = null;
        String currentSessionId = null;
        try {
            itemSessionId = userSession != null ? userSession.getUserId() : null;
            currentSessionId = session != null ? session.getUserId() : null;
        } catch (Exception ignored) {}
        final String viewerId = ReactionViewer.atEnqueue(itemSessionId, currentSessionId, restPath);
        if (!isBlank(viewerId)) rememberMyUserId(viewerId);
        final boolean restHidden = restPath && restoreRevokedItem(item);
        getWorker().post(() -> processReceivedItem(
                item, resolveThreadIdHint(delta), viewerId, session, restPath, restHidden));
    }

    /** Runs on the REST parser thread before the item is returned; memory lookups only. */
    private static boolean restoreRevokedItem(Object item) {
        boolean hidden = false;
        try {
            DirectItem directItem = new DirectItem(item);
            hidden = directItem.isHideInThread();
            if (!hidden) return false;
            RevokeCache.Saved saved = RevokeCache.INSTANCE.get(
                    directItem.getItemId(), directItem.getClientContext());
            if (saved != null) antiRevokeItem(directItem, saved.content);
        } catch (Exception e) {
            spookd("SavedMessagesHook.restoreRevokedItem: " + e);
        }
        return hidden;
    }

    /** Resumes downloads and cleanup left from an earlier run, without waiting for a new item. */
    private static void startMediaVault() {
        try {
            if (Pref.dmMediaVault()) MediaVault.get().start();
        } catch (Exception e) {
            spookd("SavedMessagesHook.startMediaVault: " + e);
        }
    }

    private static void warmUpRevokeCache() {
        try {
            SpookdMessageDb db = SpookdMessageDb.getInstance(PikoUtils.getContext());
            // Oldest first, so the newest rows end up most recently used.
            android.database.Cursor c = db.getReadableDatabase().rawQuery(
                    "SELECT message_id, server_id, client_context, content FROM"
                            + " (SELECT id, message_id, server_id, client_context, content"
                            + " FROM saved_messages ORDER BY id DESC LIMIT 2048) ORDER BY id ASC",
                    null);
            try {
                while (c.moveToNext()) {
                    String content = c.getString(3);
                    RevokeCache.INSTANCE.put(c.getString(1), c.getString(2), c.getString(0),
                            isBlank(content) ? null : content);
                }
            } finally {
                c.close();
            }
        } catch (Exception e) {
            spookd("SavedMessagesHook.warmUpRevokeCache: " + e);
        }
    }

    static String resolveThreadIdHint(Object delta) {
        if (delta == null) return null;
        try {
            Field field = delta.getClass().getDeclaredField(deltaThreadIdFieldName());
            if (field.getType() != String.class || Modifier.isStatic(field.getModifiers())) return null;
            field.setAccessible(true);
            return (String) field.get(delta);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String deltaThreadIdFieldName() {
        return "deltaThreadIdField";
    }

    private static Object lookupCachedUser(UserSession session, String senderId) {
        // Replaced with Instagram's session-scoped user cache lookup at patch time.
        return null;
    }

    /** A user from Instagram's in-memory cache of the session's account, or null; no network. */
    static UserData cachedUser(UserSession session, String userId, String viewerId) throws Exception {
        if (session == null || isBlank(userId)
                || isBlank(viewerId) || !viewerId.equals(session.getUserId())) return null;
        Object user = lookupCachedUser(session, userId);
        if (user == null) return null;
        UserData data = new UserData(user);
        return userId.equals(data.getUserId()) ? data : null;
    }

    /** Worker thread: stores the sender's picture from the in-memory user cache, once per sender and process. */
    private static void savePicture(SpookdMessageDb db, String senderId, UserSession session,
                                    String viewerId) {
        if (isBlank(senderId) || sPictureSaved.contains(senderId)) return;
        try {
            UserData data = cachedUser(session, senderId, viewerId);
            if (data == null) return;
            String url = data.getAvatarUrl();
            if (url != null && db.putPicture(senderId, data.getUsername(), url)) {
                sPictureSaved.add(senderId);
            }
        } catch (Exception ignored) {
        }
    }

    private static String resolveSenderUsername(SpookdMessageDb db, String senderId,
                                                String threadId, UserSession session,
                                                String viewerId) {
        String username = db.getUsername(senderId);
        if (username == null) username = db.getThreadUsername(threadId, senderId);
        if (username != null || isBlank(senderId) || session == null) return username;
        try {
            UserData data = cachedUser(session, senderId, viewerId);
            if (data == null) return null;
            username = data.getUsername();
            if (!isBlank(username)) {
                db.putUsername(senderId, username);
                return username;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static void processReceivedItem(Object item, String threadIdHint,
                                            String sessionViewerId, UserSession session,
                                            boolean restPath, boolean restHidden) {
        try {
            DirectItem directItem = new DirectItem(item);
            String senderId = directItem.getUserId();
            boolean sentByViewer = directItem.isSentByViewer();
            String viewerId = resolveViewerId(sentByViewer, senderId, sessionViewerId);
            if (!shouldCaptureReceived(sentByViewer, senderId, viewerId)) return;

            String serverId = directItem.getItemId();
            String clientContext = directItem.getClientContext();
            if (isBlank(serverId) && isBlank(clientContext)) return;

            String threadId = directItem.getThreadId();
            if (isBlank(threadId) && !isBlank(threadIdHint)) threadId = threadIdHint;
            if (threadId == null) threadId = "";

            SpookdMessageDb db = SpookdMessageDb.getInstance(PikoUtils.getContext());
            String senderUsername = resolveSenderUsername(
                    db, senderId, threadId, session, sessionViewerId);
            String content = directItem.getText();
            String type = directItem.getItemType();
            if (type != null) type = type.trim().toLowerCase(java.util.Locale.ROOT);
            long timestamp = directItem.getTimestampMs();
            boolean hidden = restPath ? restHidden : directItem.isHideInThread();

            if ("action_log".equals(type) || "expired_placeholder".equals(type)
                    || "placeholder".equals(type)) {
                return;
            }

            if (type != null && !"text".equals(type)
                    && (isBlank(content) || !content.startsWith("http"))) {
                String url = directItem.getMediaUrl();
                if (url == null && type.startsWith("xma")) {
                    url = directItem.xmaReshareLink();
                }
                if (url != null && url.startsWith("http")) content = url;
            }

            if (hidden) {
                String messageId = db.resolveAndMergeMessageId(serverId, clientContext);
                if (messageId == null) return;
                markDeletedAndNotify(db, messageId);
                String storedContent = db.getStoredContent(messageId);
                RevokeCache.INSTANCE.put(serverId, clientContext, messageId, storedContent);
                if (!restPath) {
                    antiRevokeItem(directItem,
                            !isBlank(storedContent) ? storedContent : content);
                }
                return;
            }

            String messageId = db.upsertMessage(serverId, clientContext, threadId, senderId,
                    senderUsername, content, type, timestamp);
            if (messageId != null) {
                RevokeCache.INSTANCE.put(serverId, clientContext, messageId,
                        db.getStoredContent(messageId));
                if (Pref.dmMediaVault()) captureMedia(item, directItem, serverId, viewerId, type, timestamp);
                savePicture(db, senderId, session, viewerId);
                // Only full REST items are compared, since other payloads may leave the reactions out;
                // without the session's viewer id an own removal would look like someone else's.
                String reactionViewer = ReactionViewer.forComparison(restPath, sessionViewerId);
                if (reactionViewer != null) {
                    db.syncReactions(messageId, directItem.getReactions(), reactionViewer,
                            System.currentTimeMillis());
                }
            }
        } catch (Exception e) {
            spookd("SavedMessagesHook.processReceivedItem: " + e);
        }
    }

    /** Queues the media of a received photo, video or voice item for the vault, view-once too when that is on. */
    private static void captureMedia(Object item, DirectItem directItem, String serverId,
                                     String viewerId, String type, long timestamp) {
        if (isBlank(serverId)) return;
        java.util.List<MediaEligibility.WrapperField> wrappers = new DirectItemWrappers(item).read();
        if (!MediaEligibility.shouldCapture(type, wrappers, EphemeralMediaPatch.isMakeEphemeralMediaPermanent(),
                SharedPref.getBooleanPref(Settings.DM_MEDIA_VAULT_VIEW_ONCE))) return;
        String url = directItem.getMediaUrl();
        if (url == null) return;
        String kind = "voice_media".equals(type) ? "voice" : MediaUrls.isVideo(url) ? "video" : "photo";
        MediaVault.get().enqueue(serverId, viewerId, kind, url, timestamp, MediaEligibility.viewMode(wrappers));
    }

    private static void antiRevokeItem(DirectItem directItem, String restoredContent) {
        directItem.setHideInThread(false);
        if (!isBlank(restoredContent)) directItem.setText(restoredContent);
    }

    private static void markDeletedAndNotify(SpookdMessageDb db, String messageId) {
        if (!db.markDeleted(messageId)) return;
        // The notice shows who wrote what, so a hidden chat gets none.
        if (HiddenChats.shouldMuteDeletion(db.getThreadId(messageId))) return;

        String type = db.getMessageType(messageId);
        String content = db.getStoredContent(messageId);
        boolean isMedia = isBlank(content) || content.startsWith("http")
                || content.startsWith("[");
        notifyDeletion(db.getSenderDisplay(messageId),
                isMedia ? describeMediaType(type, content) : content, type);
    }

    private static void notifyDeletion(String sender, String content, String type) {
        try {
            Context context = PikoUtils.getContext();
            if (context == null) return;

            android.app.NotificationManager manager =
                    (android.app.NotificationManager) context.getSystemService(
                            Context.NOTIFICATION_SERVICE);
            if (manager == null) return;

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                android.app.NotificationChannel channel = new android.app.NotificationChannel(
                        NOTIFICATION_CHANNEL_ID, str("spookd_deleted_messages_channel"),
                        android.app.NotificationManager.IMPORTANCE_DEFAULT);
                channel.setDescription(str("spookd_deleted_messages_channel_desc"));
                manager.createNotificationChannel(channel);
            }

            String who = !isBlank(sender) ? sender : str("spookd_someone");
            String body = !isBlank(content)
                    ? content
                    : !isBlank(type) ? "[" + type + "]" : str("spookd_media_deleted_generic");

            Intent intent = ActivityHook.createDeletedMessagesIntent(context);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            int pendingIntentFlags = android.app.PendingIntent.FLAG_UPDATE_CURRENT
                    | (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M
                    ? android.app.PendingIntent.FLAG_IMMUTABLE : 0);
            android.app.PendingIntent pendingIntent = android.app.PendingIntent.getActivity(
                    context, 0, intent, pendingIntentFlags);

            int icon = context.getApplicationInfo().icon;
            android.app.Notification.Builder builder =
                    android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O
                            ? new android.app.Notification.Builder(context, NOTIFICATION_CHANNEL_ID)
                            : new android.app.Notification.Builder(context);
            android.app.Notification notification = builder
                    .setSmallIcon(icon != 0 ? icon : android.R.drawable.ic_dialog_info)
                    .setCategory(android.app.Notification.CATEGORY_MESSAGE)
                    .setContentTitle(String.format(str("spookd_deleted_a_message"), who))
                    .setContentText(body)
                    .setAutoCancel(true)
                    .setContentIntent(pendingIntent)
                    .build();

            manager.notify((int) (System.currentTimeMillis() & Integer.MAX_VALUE),
                    InboxLock.hideNotification(notification));
        } catch (Exception e) {
            spookd("SavedMessagesHook.notifyDeletion: " + e);
        }
    }

    private static String describeMediaType(String type, String url) {
        if (type == null) return str("spookd_media_deleted_generic");
        if (("media".equals(type) || "raven_media".equals(type) || "visual_media".equals(type))
                && MediaUrls.isVideo(url)) {
            type = "video";
        }
        String label;
        switch (type) {
            case "media":
            case "image":           label = str("spookd_media_photo"); break;
            case "raven_media":     label = str("spookd_media_disappearing_photo"); break;
            case "video":           label = str("spookd_media_video"); break;
            case "voice_media":
            case "audio":           label = str("spookd_media_voice"); break;
            case "animated_media":  label = str("spookd_media_gif"); break;
            case "reel_share":      label = str("spookd_media_reel"); break;
            case "story_share":     label = str("spookd_media_story"); break;
            case "media_share":     label = str("spookd_media_post"); break;
            case "like":            label = str("spookd_media_like"); break;
            case "link":            label = str("spookd_media_link"); break;
            case "action_log":      label = str("spookd_media_activity"); break;
            default:                 label = type; break;
        }
        return String.format(str("spookd_media_deleted"), label);
    }

    /** Deletes stored media, rows and their cached text on the worker and waits; never call from the main thread. */
    public static void deleteSavedMessages(final java.util.List<String> messageIds) throws Exception {
        java.util.concurrent.FutureTask<Void> task = new java.util.concurrent.FutureTask<Void>(() -> {
            // On the worker, so no capture of these messages can slip in between the media and the rows.
            MediaVault.get().deleteForMessages(messageIds);
            SpookdMessageDb.getInstance(PikoUtils.getContext()).deleteSaved(messageIds);
            RevokeCache.INSTANCE.removeMessages(messageIds);
            return null;
        });
        android.os.Handler worker = getWorker();
        if (worker.getLooper().isCurrentThread() || !worker.post(task)) task.run();
        try {
            task.get();
        } catch (java.util.concurrent.ExecutionException e) {
            if (e.getCause() instanceof Exception) throw (Exception) e.getCause();
            throw e;
        }
    }

    public static void onMessageHiddenFromDb(String serverId, String clientContext) {
        if (!Pref.saveDeletedMessages()) return;
        if (isBlank(serverId) && isBlank(clientContext)) return;
        getWorker().post(() -> processMessageHiddenFromDb(serverId, clientContext));
    }

    private static void processMessageHiddenFromDb(String serverId, String clientContext) {
        try {
            if (!Pref.saveDeletedMessages()) return;
            SpookdMessageDb db = SpookdMessageDb.getInstance(PikoUtils.getContext());
            String messageId = db.resolveAndMergeMessageId(serverId, clientContext);
            if (messageId == null) return;
            RevokeCache.INSTANCE.put(serverId, clientContext, messageId,
                    db.getStoredContent(messageId));
            if (!shouldCaptureStored(db.getSenderId(messageId), myUserId())) return;
            markDeletedAndNotify(db, messageId);
        } catch (Exception e) {
            spookd("SavedMessagesHook.processMessageHiddenFromDb: " + e);
        }
    }
}
