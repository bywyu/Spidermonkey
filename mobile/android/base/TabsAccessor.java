/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.gecko;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

import org.json.JSONArray;
import org.json.JSONException;
import org.mozilla.gecko.db.BrowserContract;
import org.mozilla.gecko.util.ThreadUtils;
import org.mozilla.gecko.util.UIAsyncTask;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.util.Log;

public final class TabsAccessor {
    private static final String LOGTAG = "GeckoTabsAccessor";

    public static final String[] TABS_PROJECTION_COLUMNS = new String[] {
                                                                BrowserContract.Tabs.TITLE,
                                                                BrowserContract.Tabs.URL,
                                                                BrowserContract.Clients.GUID,
                                                                BrowserContract.Clients.NAME,
                                                                BrowserContract.Clients.LAST_MODIFIED,
                                                                BrowserContract.Clients.DEVICE_TYPE,
                                                            };

    private static final String LOCAL_TABS_SELECTION = BrowserContract.Tabs.CLIENT_GUID + " IS NULL";
    private static final String REMOTE_TABS_SELECTION = BrowserContract.Tabs.CLIENT_GUID + " IS NOT NULL";

    private static final String REMOTE_TABS_SORT_ORDER =
            // Most recently synced clients first.
            BrowserContract.Clients.LAST_MODIFIED + " DESC, " +
            // If two clients somehow had the same last modified time, this will
            // group them (arbitrarily).
            BrowserContract.Clients.GUID + " DESC, " +
            // Within a single client, most recently used tabs first.
            BrowserContract.Tabs.LAST_USED + " DESC";

    private static final String LOCAL_CLIENT_SELECTION = BrowserContract.Clients.GUID + " IS NULL";

    private static final Pattern FILTERED_URL_PATTERN = Pattern.compile("^(about|chrome|wyciwyg|file):");

    /**
     * A thin representation of a remote client.
     * <p>
     * We use the hash of the client's GUID as the ID in
     * {@link RemoteTabsExpandableListAdapter#getGroupId(int)}.
     */
    public static class RemoteClient {
        public final String guid;
        public final String name;
        public final long lastModified;
        public final String deviceType;
        public final ArrayList<RemoteTab> tabs;

        public RemoteClient(String guid, String name, long lastModified, String deviceType) {
            this.guid = guid;
            this.name = name;
            this.lastModified = lastModified;
            this.deviceType = deviceType;
            this.tabs = new ArrayList<RemoteTab>();
        }
    }

    /**
     * A thin representation of a remote tab.
     * <p>
     * We use the hash of the tab as the ID in
     * {@link RemoteTabsExpandableListAdapter#getClientId(int)}, and therefore we
     * must implement equality as well. These are generated functions.
     */
    public static class RemoteTab {
        public final String title;
        public final String url;

        public RemoteTab(String title, String url) {
            this.title = title;
            this.url = url;
        }

        @Override
        public int hashCode() {
            final int prime = 31;
            int result = 1;
            result = prime * result + ((title == null) ? 0 : title.hashCode());
            result = prime * result + ((url == null) ? 0 : url.hashCode());
            return result;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj == null) {
                return false;
            }
            if (getClass() != obj.getClass()) {
                return false;
            }
            RemoteTab other = (RemoteTab) obj;
            if (title == null) {
                if (other.title != null) {
                    return false;
                }
            } else if (!title.equals(other.title)) {
                return false;
            }
            if (url == null) {
                if (other.url != null) {
                    return false;
                }
            } else if (!url.equals(other.url)) {
                return false;
            }
            return true;
        }
    }

    /**
     * Extract client and tab records from a cursor.
     * <p>
     * The position of the cursor is moved to before the first record before
     * reading. The cursor is advanced until there are no more records to be
     * read. The position of the cursor is restored before returning.
     *
     * @param cursor
     *            to extract records from. The records should already be grouped
     *            by client GUID.
     * @return list of clients, each containing list of tabs.
     */
    public static List<RemoteClient> getClientsFromCursor(final Cursor cursor) {
        final ArrayList<RemoteClient> clients = new ArrayList<TabsAccessor.RemoteClient>();

        final int originalPosition = cursor.getPosition();
        try {
            if (!cursor.moveToFirst()) {
                return clients;
            }

            final int tabTitleIndex = cursor.getColumnIndex(BrowserContract.Tabs.TITLE);
            final int tabUrlIndex = cursor.getColumnIndex(BrowserContract.Tabs.URL);
            final int clientGuidIndex = cursor.getColumnIndex(BrowserContract.Clients.GUID);
            final int clientNameIndex = cursor.getColumnIndex(BrowserContract.Clients.NAME);
            final int clientLastModifiedIndex = cursor.getColumnIndex(BrowserContract.Clients.LAST_MODIFIED);
            final int clientDeviceTypeIndex = cursor.getColumnIndex(BrowserContract.Clients.DEVICE_TYPE);

            // A walking partition, chunking by client GUID. We assume the
            // cursor records are already grouped by client GUID; see the query
            // sort order.
            RemoteClient lastClient = null;
            while (!cursor.isAfterLast()) {
                final String clientGuid = cursor.getString(clientGuidIndex);
                if (lastClient == null || !TextUtils.equals(lastClient.guid, clientGuid)) {
                    final String clientName = cursor.getString(clientNameIndex);
                    final long lastModified = cursor.getLong(clientLastModifiedIndex);
                    final String deviceType = cursor.getString(clientDeviceTypeIndex);
                    lastClient = new RemoteClient(clientGuid, clientName, lastModified, deviceType);
                    clients.add(lastClient);
                }

                final String tabTitle = cursor.getString(tabTitleIndex);
                final String tabUrl = cursor.getString(tabUrlIndex);
                lastClient.tabs.add(new RemoteTab(tabTitle, tabUrl));

                cursor.moveToNext();
            }
        } finally {
            cursor.moveToPosition(originalPosition);
        }

        return clients;
    }

    public static Cursor getRemoteTabsCursor(Context context) {
        return getRemoteTabsCursor(context, -1);
    }

    public static Cursor getRemoteTabsCursor(Context context, int limit) {
        Uri uri = BrowserContract.Tabs.CONTENT_URI;

        if (limit > 0) {
            uri = uri.buildUpon()
                     .appendQueryParameter(BrowserContract.PARAM_LIMIT, String.valueOf(limit))
                     .build();
        }

        final Cursor cursor =  context.getContentResolver().query(uri,
                                                            TABS_PROJECTION_COLUMNS,
                                                            REMOTE_TABS_SELECTION,
                                                            null,
                                                            REMOTE_TABS_SORT_ORDER);
        return cursor;
    }

    public interface OnQueryTabsCompleteListener {
        public void onQueryTabsComplete(List<RemoteClient> clients);
    }

    // This method returns all tabs from all remote clients,
    // ordered by most recent client first, most recent tab first
    public static void getTabs(final Context context, final OnQueryTabsCompleteListener listener) {
        getTabs(context, 0, listener);
    }

    // This method returns limited number of tabs from all remote clients,
    // ordered by most recent client first, most recent tab first
    public static void getTabs(final Context context, final int limit, final OnQueryTabsCompleteListener listener) {
        // If there is no listener, no point in doing work.
        if (listener == null)
            return;

        (new UIAsyncTask.WithoutParams<List<RemoteClient>>(ThreadUtils.getBackgroundHandler()) {
            @Override
            protected List<RemoteClient> doInBackground() {
                final Cursor cursor = getRemoteTabsCursor(context, limit);
                if (cursor == null)
                    return null;

                try {
                    return Collections.unmodifiableList(getClientsFromCursor(cursor));
                } finally {
                    cursor.close();
                }
            }

            @Override
            protected void onPostExecute(List<RemoteClient> clients) {
                listener.onQueryTabsComplete(clients);
            }
        }).execute();
    }

    // Updates the modified time of the local client with the current time.
    private static void updateLocalClient(final ContentResolver cr) {
        ContentValues values = new ContentValues();
        values.put(BrowserContract.Clients.LAST_MODIFIED, System.currentTimeMillis());
        cr.update(BrowserContract.Clients.CONTENT_URI, values, LOCAL_CLIENT_SELECTION, null);
    }

    // Deletes all local tabs.
    private static void deleteLocalTabs(final ContentResolver cr) {
        cr.delete(BrowserContract.Tabs.CONTENT_URI, LOCAL_TABS_SELECTION, null);
    }

    /**
     * Tabs are positioned in the DB in the same order that they appear in the tabs param.
     *   - URL should never empty or null. Skip this tab if there's no URL.
     *   - TITLE should always a string, either a page title or empty.
     *   - LAST_USED should always be numeric.
     *   - FAVICON should be a URL or null.
     *   - HISTORY should be serialized JSON array of URLs.
     *   - POSITION should always be numeric.
     *   - CLIENT_GUID should always be null to represent the local client.
     */
    private static void insertLocalTabs(final ContentResolver cr, final Iterable<Tab> tabs) {
        // Reuse this for serializing individual history URLs as JSON.
        JSONArray history = new JSONArray();
        ArrayList<ContentValues> valuesToInsert = new ArrayList<ContentValues>();

        int position = 0;
        for (Tab tab : tabs) {
            // Skip this tab if it has a null URL or is in private browsing mode, or is a filtered URL.
            String url = tab.getURL();
            if (url == null || tab.isPrivate() || isFilteredURL(url))
                continue;

            ContentValues values = new ContentValues();
            values.put(BrowserContract.Tabs.URL, url);
            values.put(BrowserContract.Tabs.TITLE, tab.getTitle());
            values.put(BrowserContract.Tabs.LAST_USED, tab.getLastUsed());

            String favicon = tab.getFaviconURL();
            if (favicon != null)
                values.put(BrowserContract.Tabs.FAVICON, favicon);
            else
                values.putNull(BrowserContract.Tabs.FAVICON);

            // We don't have access to session history in Java, so for now, we'll
            // just use a JSONArray that holds most recent history item.
            try {
                history.put(0, tab.getURL());
                values.put(BrowserContract.Tabs.HISTORY, history.toString());
            } catch (JSONException e) {
                Log.w(LOGTAG, "JSONException adding URL to tab history array.", e);
            }

            values.put(BrowserContract.Tabs.POSITION, position++);

            // A null client guid corresponds to the local client.
            values.putNull(BrowserContract.Tabs.CLIENT_GUID);

            valuesToInsert.add(values);
        }

        ContentValues[] valuesToInsertArray = valuesToInsert.toArray(new ContentValues[valuesToInsert.size()]);
        cr.bulkInsert(BrowserContract.Tabs.CONTENT_URI, valuesToInsertArray);
    }

    // Deletes all local tabs and replaces them with a new list of tabs.
    public static synchronized void persistLocalTabs(final ContentResolver cr, final Iterable<Tab> tabs) {
        deleteLocalTabs(cr);
        insertLocalTabs(cr, tabs);
        updateLocalClient(cr);
    }

    /**
     * Matches the supplied URL string against the set of URLs to filter.
     *
     * @return true if the supplied URL should be skipped; false otherwise.
     */
    private static boolean isFilteredURL(String url) {
        return FILTERED_URL_PATTERN.matcher(url).lookingAt();
    }

    /**
     * Return a relative "Last synced" time span for the given tab record.
     *
     * @param now local time.
     * @param time to format string for.
     * @return string describing time span
     */
    public static String getLastSyncedString(Context context, long now, long time) {
        final CharSequence relativeTimeSpanString = DateUtils.getRelativeTimeSpanString(time, now, DateUtils.MINUTE_IN_MILLIS);
        return context.getResources().getString(R.string.remote_tabs_last_synced, relativeTimeSpanString);
    }
}
