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
package com.android.server.autofill.ui;

import static com.android.server.autofill.Helper.paramsToString;
import static com.android.server.autofill.Helper.sDebug;
import static com.android.server.autofill.Helper.sFullScreenMode;
import static com.android.server.autofill.Helper.sVerbose;
import static com.android.server.autofill.Helper.sVisibleDatasetsMaxCount;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.PendingIntent;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.view.ContextThemeWrapper;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.PackageManager;
import android.graphics.Point;
import android.graphics.Rect;
import android.service.autofill.Dataset;
import android.service.autofill.Dataset.DatasetFieldFilter;
import android.service.autofill.FillResponse;
import android.text.TextUtils;
import android.util.Slog;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.MeasureSpec;
import android.view.ViewGroup;
import android.view.ViewGroup.LayoutParams;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityManager;
import android.view.autofill.AutofillId;
import android.view.autofill.AutofillValue;
import android.view.autofill.IAutofillWindowPresenter;
import android.widget.BaseAdapter;
import android.widget.Filter;
import android.widget.Filterable;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.RemoteViews;
import android.widget.TextView;

import com.android.internal.R;
import com.android.server.UiThread;
import com.android.server.autofill.Helper;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

final class FillUi {
    private static final String TAG = "FillUi";

    private static final int THEME_ID = com.android.internal.R.style.Theme_DeviceDefault_Autofill;

    private static final TypedValue sTempTypedValue = new TypedValue();

    interface Callback {
        void onResponsePicked(@NonNull FillResponse response);
        void onDatasetPicked(@NonNull Dataset dataset);
        void onCanceled();
        void onDestroy();
        void requestShowFillUi(int width, int height,
                IAutofillWindowPresenter windowPresenter);
        void requestHideFillUi();
        void startIntentSender(IntentSender intentSender);
        void dispatchUnhandledKey(KeyEvent keyEvent);
    }

    private final @NonNull Point mTempPoint = new Point();

    private final @NonNull AutofillWindowPresenter mWindowPresenter =
            new AutofillWindowPresenter();

    private final @NonNull Context mContext;

    private final @NonNull AnchoredWindow mWindow;

    private final @NonNull Callback mCallback;

    private final @Nullable View mHeader;
    private final @NonNull ListView mListView;
    private final @Nullable View mFooter;

    private final @Nullable ItemsAdapter mAdapter;

    private @Nullable String mFilterText;

    private @Nullable AnnounceFilterResult mAnnounceFilterResult;

    private final boolean mFullScreen;
    private final int mVisibleDatasetsMaxCount;
    private int mContentWidth;
    private int mContentHeight;

    private boolean mDestroyed;

    public static boolean isFullScreen(Context context) {
        if (sFullScreenMode != null) {
            if (sVerbose) Slog.v(TAG, "forcing full-screen mode to " + sFullScreenMode);
            return sFullScreenMode;
        }
        return context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_LEANBACK);
    }

    FillUi(@NonNull Context context, @NonNull FillResponse response,
           @NonNull AutofillId focusedViewId, @NonNull @Nullable String filterText,
           @NonNull OverlayControl overlayControl, @NonNull CharSequence serviceLabel,
           @NonNull Drawable serviceIcon, @NonNull Callback callback) {
        mCallback = callback;
        mFullScreen = isFullScreen(context);
        mContext = new ContextThemeWrapper(context, THEME_ID);
        final LayoutInflater inflater = LayoutInflater.from(mContext);

        final RemoteViews headerPresentation = response.getHeader();
        final RemoteViews footerPresentation = response.getFooter();
        final ViewGroup decor;
        if (mFullScreen) {
            decor = (ViewGroup) inflater.inflate(R.layout.autofill_dataset_picker_fullscreen, null);
        } else if (headerPresentation != null || footerPresentation != null) {
            decor = (ViewGroup) inflater.inflate(R.layout.autofill_dataset_picker_header_footer,
                    null);
        } else {
            decor = (ViewGroup) inflater.inflate(R.layout.autofill_dataset_picker, null);
        }
        final TextView titleView = decor.findViewById(R.id.autofill_dataset_title);
        if (titleView != null) {
            titleView.setText(mContext.getString(R.string.autofill_window_title, serviceLabel));
        }
        final ImageView iconView = decor.findViewById(R.id.autofill_dataset_icon);
        if (iconView != null) {
            iconView.setImageDrawable(serviceIcon);
        }

        // In full screen we only initialize size once assuming screen size never changes
        if (mFullScreen) {
            final Point outPoint = mTempPoint;
            mContext.getDisplay().getSize(outPoint);
            // full with of screen and half height of screen
            mContentWidth = LayoutParams.MATCH_PARENT;
            mContentHeight = outPoint.y / 2;
            if (sVerbose) {
                Slog.v(TAG, "initialized fillscreen LayoutParams "
                        + mContentWidth + "," + mContentHeight);
            }
        }

        // Send unhandled keyevent to app window.
        decor.addOnUnhandledKeyEventListener((View view, KeyEvent event) -> {
            switch (event.getKeyCode() ) {
                case KeyEvent.KEYCODE_BACK:
                case KeyEvent.KEYCODE_ESCAPE:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_UP:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    return false;
                default:
                    mCallback.dispatchUnhandledKey(event);
                    return true;
            }
        });

        if (sVisibleDatasetsMaxCount > 0) {
            mVisibleDatasetsMaxCount = sVisibleDatasetsMaxCount;
            if (sVerbose) {
                Slog.v(TAG, "overriding maximum visible datasets to " + mVisibleDatasetsMaxCount);
            }
        } else {
            mVisibleDatasetsMaxCount = mContext.getResources()
                    .getInteger(com.android.internal.R.integer.autofill_max_visible_datasets);
        }

        final RemoteViews.OnClickHandler interceptionHandler = new RemoteViews.OnClickHandler() {
            @Override
            public boolean onClickHandler(View view, PendingIntent pendingIntent,
                    Intent fillInIntent) {
                if (pendingIntent != null) {
                    mCallback.startIntentSender(pendingIntent.getIntentSender());
                }
                return true;
            }
        };

        if (response.getAuthentication() != null) {
            mHeader = null;
            mListView = null;
            mFooter = null;
            mAdapter = null;

            // insert authentication item under autofill_dataset_picker
            ViewGroup container = decor.findViewById(R.id.autofill_dataset_picker);
            final View content;
            try {
                response.getPresentation().setApplyTheme(THEME_ID);
                content = response.getPresentation().apply(mContext, decor, interceptionHandler);
                container.addView(content);
            } catch (RuntimeException e) {
                callback.onCanceled();
                Slog.e(TAG, "Error inflating remote views", e);
                mWindow = null;
                return;
            }
            container.setFocusable(true);
            container.setOnClickListener(v -> mCallback.onResponsePicked(response));

            if (!mFullScreen) {
                final Point maxSize = mTempPoint;
                resolveMaxWindowSize(mContext, maxSize);
                // fullScreen mode occupy the full width defined by autofill_dataset_picker_max_width
                content.getLayoutParams().width = mFullScreen ? maxSize.x
                        : ViewGroup.LayoutParams.WRAP_CONTENT;
                content.getLayoutParams().height = ViewGroup.LayoutParams.WRAP_CONTENT;
                final int widthMeasureSpec = MeasureSpec.makeMeasureSpec(maxSize.x,
                        MeasureSpec.AT_MOST);
                final int heightMeasureSpec = MeasureSpec.makeMeasureSpec(maxSize.y,
                        MeasureSpec.AT_MOST);

                decor.measure(widthMeasureSpec, heightMeasureSpec);
                mContentWidth = content.getMeasuredWidth();
                mContentHeight = content.getMeasuredHeight();
            }

            mWindow = new AnchoredWindow(decor, overlayControl);
            requestShowFillUi();
        } else {
            final int datasetCount = response.getDatasets().size();
            if (sVerbose) {
                Slog.v(TAG, "Number datasets: " + datasetCount + " max visible: "
                        + mVisibleDatasetsMaxCount);
            }

            RemoteViews.OnClickHandler clickBlocker = null;
            if (headerPresentation != null) {
                clickBlocker = newClickBlocker();
                headerPresentation.setApplyTheme(THEME_ID);
                mHeader = headerPresentation.apply(mContext, null, clickBlocker);
                final LinearLayout headerContainer =
                        decor.findViewById(R.id.autofill_dataset_header);
                if (sVerbose) Slog.v(TAG, "adding header");
                headerContainer.addView(mHeader);
                headerContainer.setVisibility(View.VISIBLE);
            } else {
                mHeader = null;
            }

            if (footerPresentation != null) {
                final LinearLayout footerContainer =
                        decor.findViewById(R.id.autofill_dataset_footer);
                if (footerContainer != null) {
                    if (clickBlocker == null) { // already set for header
                        clickBlocker = newClickBlocker();
                    }
                    footerPresentation.setApplyTheme(THEME_ID);
                    mFooter = footerPresentation.apply(mContext, null, clickBlocker);
                    // Footer not supported on some platform e.g. TV
                    if (sVerbose) Slog.v(TAG, "adding footer");
                    footerContainer.addView(mFooter);
                    footerContainer.setVisibility(View.VISIBLE);
                } else {
                    mFooter = null;
                }
            } else {
                mFooter = null;
            }

            final ArrayList<ViewItem> items = new ArrayList<>(datasetCount);
            for (int i = 0; i < datasetCount; i++) {
                final Dataset dataset = response.getDatasets().get(i);
                final int index = dataset.getFieldIds().indexOf(focusedViewId);
                if (index >= 0) {
                    final RemoteViews presentation = dataset.getFieldPresentation(index);
                    if (presentation == null) {
                        Slog.w(TAG, "not displaying UI on field " + focusedViewId + " because "
                                + "service didn't provide a presentation for it on " + dataset);
                        continue;
                    }
                    final View view;
                    try {
                        if (sVerbose) Slog.v(TAG, "setting remote view for " + focusedViewId);
                        presentation.setApplyTheme(THEME_ID);
                        view = presentation.apply(mContext, null, interceptionHandler);
                    } catch (RuntimeException e) {
                        Slog.e(TAG, "Error inflating remote views", e);
                        continue;
                    }
                    final DatasetFieldFilter filter = dataset.getFilter(index);
                    Pattern filterPattern = null;
                    String valueText = null;
                    boolean filterable = true;
                    if (filter == null) {
                        final AutofillValue value = dataset.getFieldValues().get(index);
                        if (value != null && value.isText()) {
                            valueText = value.getTextValue().toString().toLowerCase();
                        }
                    } else {
                        filterPattern = filter.pattern;
                        if (filterPattern == null) {
                            if (sVerbose) {
                                Slog.v(TAG, "Explicitly disabling filter at id " + focusedViewId
                                        + " for dataset #" + index);
                            }
                            filterable = false;
                        }
                    }

                    items.add(new ViewItem(dataset, filterPattern, filterable, valueText, view));
                }
            }

            mAdapter = new ItemsAdapter(items);

            mListView = decor.findViewById(R.id.autofill_dataset_list);
            mListView.setAdapter(mAdapter);
            mListView.setVisibility(View.VISIBLE);
            mListView.setOnItemClickListener((adapter, view, position, id) -> {
                final ViewItem vi = mAdapter.getItem(position);
                mCallback.onDatasetPicked(vi.dataset);
            });

            if (filterText == null) {
                mFilterText = null;
            } else {
                mFilterText = filterText.toLowerCase();
            }

            applyNewFilterText();
            mWindow = new AnchoredWindow(decor, overlayControl);
        }
    }

    void requestShowFillUi() {
        mCallback.requestShowFillUi(mContentWidth, mContentHeight, mWindowPresenter);
    }

    /**
     * Creates a remoteview interceptor used to block clicks.
     */
    private RemoteViews.OnClickHandler newClickBlocker() {
        return new RemoteViews.OnClickHandler() {
            @Override
            public boolean onClickHandler(View view, PendingIntent pendingIntent,
                    Intent fillInIntent) {
                if (sVerbose) Slog.v(TAG, "Ignoring click on " + view);
                return true;
            }
        };
    }

    private void applyNewFilterText() {
        final int oldCount = mAdapter.getCount();
        mAdapter.getFilter().filter(mFilterText, (count) -> {
            if (mDestroyed) {
                return;
            }
            if (count <= 0) {
                if (sDebug) {
                    final int size = mFilterText == null ? 0 : mFilterText.length();
                    Slog.d(TAG, "No dataset matches filter with " + size + " chars");
                }
                mCallback.requestHideFillUi();
            } else {
                if (updateContentSize()) {
                    requestShowFillUi();
                }
                if (mAdapter.getCount() > mVisibleDatasetsMaxCount) {
                    mListView.setVerticalScrollBarEnabled(true);
                    mListView.onVisibilityAggregated(true);
                } else {
                    mListView.setVerticalScrollBarEnabled(false);
                }
                if (mAdapter.getCount() != oldCount) {
                    mListView.requestLayout();
                }
            }
        });
    }

    public void setFilterText(@Nullable String filterText) {
        throwIfDestroyed();
        if (mAdapter == null) {
            // ViewState doesn't not support filtering - typically when it's for an authenticated
            // FillResponse.
            if (TextUtils.isEmpty(filterText)) {
                requestShowFillUi();
            } else {
                mCallback.requestHideFillUi();
            }
            return;
        }

        if (filterText == null) {
            filterText = null;
        } else {
            filterText = filterText.toLowerCase();
        }

        if (Objects.equals(mFilterText, filterText)) {
            return;
        }
        mFilterText = filterText;

        applyNewFilterText();
    }

    public void destroy(boolean notifyClient) {
        throwIfDestroyed();
        if (mWindow != null) {
            mWindow.hide(false);
        }
        mCallback.onDestroy();
        if (notifyClient) {
            mCallback.requestHideFillUi();
        }
        mDestroyed = true;
    }

    private boolean updateContentSize() {
        if (mAdapter == null) {
            return false;
        }
        if (mFullScreen) {
            // always request show fill window with fixed size for fullscreen
            return true;
        }
        boolean changed = false;
        if (mAdapter.getCount() <= 0) {
            if (mContentWidth != 0) {
                mContentWidth = 0;
                changed = true;
            }
            if (mContentHeight != 0) {
                mContentHeight = 0;
                changed = true;
            }
            return changed;
        }

        Point maxSize = mTempPoint;
        resolveMaxWindowSize(mContext, maxSize);

        mContentWidth = 0;
        mContentHeight = 0;

        final int widthMeasureSpec = MeasureSpec.makeMeasureSpec(maxSize.x,
                MeasureSpec.AT_MOST);
        final int heightMeasureSpec = MeasureSpec.makeMeasureSpec(maxSize.y,
                MeasureSpec.AT_MOST);
        final int itemCount = mAdapter.getCount();

        if (mHeader != null) {
            mHeader.measure(widthMeasureSpec, heightMeasureSpec);
            changed |= updateWidth(mHeader, maxSize);
            changed |= updateHeight(mHeader, maxSize);
        }

        for (int i = 0; i < itemCount; i++) {
            final View view = mAdapter.getItem(i).view;
            view.measure(widthMeasureSpec, heightMeasureSpec);
            changed |= updateWidth(view, maxSize);
            if (i < mVisibleDatasetsMaxCount) {
                changed |= updateHeight(view, maxSize);
            }
        }

        if (mFooter != null) {
            mFooter.measure(widthMeasureSpec, heightMeasureSpec);
            changed |= updateWidth(mFooter, maxSize);
            changed |= updateHeight(mFooter, maxSize);
        }
        return changed;
    }

    private boolean updateWidth(View view, Point maxSize) {
        boolean changed = false;
        final int clampedMeasuredWidth = Math.min(view.getMeasuredWidth(), maxSize.x);
        final int newContentWidth = Math.max(mContentWidth, clampedMeasuredWidth);
        if (newContentWidth != mContentWidth) {
            mContentWidth = newContentWidth;
            changed = true;
        }
        return changed;
    }

    private boolean updateHeight(View view, Point maxSize) {
        boolean changed = false;
        final int clampedMeasuredHeight = Math.min(view.getMeasuredHeight(), maxSize.y);
        final int newContentHeight = mContentHeight + clampedMeasuredHeight;
        if (newContentHeight != mContentHeight) {
            mContentHeight = newContentHeight;
            changed = true;
        }
        return changed;
    }

    private void throwIfDestroyed() {
        if (mDestroyed) {
            throw new IllegalStateException("cannot interact with a destroyed instance");
        }
    }

    private static void resolveMaxWindowSize(Context context, Point outPoint) {
        context.getDisplay().getSize(outPoint);
        final TypedValue typedValue = sTempTypedValue;
        context.getTheme().resolveAttribute(R.attr.autofillDatasetPickerMaxWidth,
                typedValue, true);
        outPoint.x = (int) typedValue.getFraction(outPoint.x, outPoint.x);
        context.getTheme().resolveAttribute(R.attr.autofillDatasetPickerMaxHeight,
                typedValue, true);
        outPoint.y = (int) typedValue.getFraction(outPoint.y, outPoint.y);
    }

    /**
     * An item for the list view - either a (clickable) dataset or a (read-only) header / footer.
     */
    private static class ViewItem {
        public final @Nullable String value;
        public final @Nullable Dataset dataset;
        public final @NonNull View view;
        public final @Nullable Pattern filter;
        public final boolean filterable;

        /**
         * Default constructor.
         *
         * @param dataset dataset associated with the item or {@code null} if it's a header or
         * footer (TODO(b/69796626): make @NonNull if header/footer is refactored out of the list)
         * @param filter optional filter set by the service to determine how the item should be
         * filtered
         * @param filterable optional flag set by the service to indicate this item should not be
         * filtered (typically used when the dataset has value but it's sensitive, like a password)
         * @param value dataset value
         * @param view dataset presentation.
         */
        ViewItem(@Nullable Dataset dataset, @Nullable Pattern filter, boolean filterable,
                @Nullable String value, @NonNull View view) {
            this.dataset = dataset;
            this.value = value;
            this.view = view;
            this.filter = filter;
            this.filterable = filterable;
        }

        /**
         * Returns whether this item matches the value input by the user so it can be included
         * in the filtered datasets.
         */
        public boolean matches(CharSequence filterText) {
            if (TextUtils.isEmpty(filterText)) {
                // Always show item when the user input is empty
                return true;
            }
            if (!filterable) {
                // Service explicitly disabled filtering using a null Pattern.
                return false;
            }
            final String constraintLowerCase = filterText.toString().toLowerCase();
            if (filter != null) {
                // Uses pattern provided by service
                return filter.matcher(constraintLowerCase).matches();
            } else {
                // Compares it with dataset value with dataset
                return (value == null)
                        ? (dataset.getAuthentication() == null)
                        : value.toLowerCase().startsWith(constraintLowerCase);
            }
        }

        @Override
        public String toString() {
            final StringBuilder builder = new StringBuilder("ViewItem:[view=")
                    .append(view.getAutofillId());
            final String datasetId = dataset == null ? null : dataset.getId();
            if (datasetId != null) {
                builder.append(", dataset=").append(datasetId);
            }
            if (value != null) {
                // Cannot print value because it could contain PII
                builder.append(", value=").append(value.length()).append("_chars");
            }
            if (filterable) {
                builder.append(", filterable");
            }
            if (filter != null) {
                // Filter should not have PII, but it could be a huge regexp
                builder.append(", filter=").append(filter.pattern().length()).append("_chars");
            }
            return builder.append(']').toString();
        }
    }

    private final class AutofillWindowPresenter extends IAutofillWindowPresenter.Stub {
        @Override
        public void show(WindowManager.LayoutParams p, Rect transitionEpicenter,
                boolean fitsSystemWindows, int layoutDirection) {
            if (sVerbose) {
                Slog.v(TAG, "AutofillWindowPresenter.show(): fit=" + fitsSystemWindows
                        + ", params=" + paramsToString(p));
            }
            UiThread.getHandler().post(() -> mWindow.show(p));
        }

        @Override
        public void hide(Rect transitionEpicenter) {
            UiThread.getHandler().post(mWindow::hide);
        }
    }

    final class AnchoredWindow {
        private final @NonNull OverlayControl mOverlayControl;
        private final WindowManager mWm;
        private final View mContentView;
        private boolean mShowing;
        // Used on dump only
        private WindowManager.LayoutParams mShowParams;

        /**
         * Constructor.
         *
         * @param contentView content of the window
         */
        AnchoredWindow(View contentView, @NonNull OverlayControl overlayControl) {
            mWm = contentView.getContext().getSystemService(WindowManager.class);
            mContentView = contentView;
            mOverlayControl = overlayControl;
        }

        /**
         * Shows the window.
         */
        public void show(WindowManager.LayoutParams params) {
            mShowParams = params;
            if (sVerbose) {
                Slog.v(TAG, "show(): showing=" + mShowing + ", params=" + paramsToString(params));
            }
            try {
                params.packageName = "android";
                params.setTitle("Autofill UI"); // Title is set for debugging purposes
                if (!mShowing) {
                    params.accessibilityTitle = mContentView.getContext()
                            .getString(R.string.autofill_picker_accessibility_title);
                    mWm.addView(mContentView, params);
                    mOverlayControl.hideOverlays();
                    mShowing = true;
                } else {
                    mWm.updateViewLayout(mContentView, params);
                }
            } catch (WindowManager.BadTokenException e) {
                if (sDebug) Slog.d(TAG, "Filed with with token " + params.token + " gone.");
                mCallback.onDestroy();
            } catch (IllegalStateException e) {
                // WM throws an ISE if mContentView was added twice; this should never happen -
                // since show() and hide() are always called in the UIThread - but when it does,
                // it should not crash the system.
                Slog.wtf(TAG, "Exception showing window " + params, e);
                mCallback.onDestroy();
            }
        }

        /**
         * Hides the window.
         */
        void hide() {
            hide(true);
        }

        void hide(boolean destroyCallbackOnError) {
            try {
                if (mShowing) {
                    mWm.removeView(mContentView);
                    mShowing = false;
                }
            } catch (IllegalStateException e) {
                // WM might thrown an ISE when removing the mContentView; this should never
                // happen - since show() and hide() are always called in the UIThread - but if it
                // does, it should not crash the system.
                Slog.e(TAG, "Exception hiding window ", e);
                if (destroyCallbackOnError) {
                    mCallback.onDestroy();
                }
            } finally {
                mOverlayControl.showOverlays();
            }
        }
    }

    public void dump(PrintWriter pw, String prefix) {
        pw.print(prefix); pw.print("mCallback: "); pw.println(mCallback != null);
        pw.print(prefix); pw.print("mFullScreen: "); pw.println(mFullScreen);
        pw.print(prefix); pw.print("mVisibleDatasetsMaxCount: "); pw.println(
                mVisibleDatasetsMaxCount);
        if (mHeader != null) {
            pw.print(prefix); pw.print("mHeader: "); pw.println(mHeader);
        }
        if (mListView != null) {
            pw.print(prefix); pw.print("mListView: "); pw.println(mListView);
        }
        if (mFooter != null) {
            pw.print(prefix); pw.print("mFooter: "); pw.println(mFooter);
        }
        if (mAdapter != null) {
            pw.print(prefix); pw.print("mAdapter: "); pw.println(mAdapter);
        }
        if (mFilterText != null) {
            pw.print(prefix); pw.print("mFilterText: ");
            Helper.printlnRedactedText(pw, mFilterText);
        }
        pw.print(prefix); pw.print("mContentWidth: "); pw.println(mContentWidth);
        pw.print(prefix); pw.print("mContentHeight: "); pw.println(mContentHeight);
        pw.print(prefix); pw.print("mDestroyed: "); pw.println(mDestroyed);
        if (mWindow != null) {
            pw.print(prefix); pw.print("mWindow: ");
            final String prefix2 = prefix + "  ";
            pw.println();
            pw.print(prefix2); pw.print("showing: "); pw.println(mWindow.mShowing);
            pw.print(prefix2); pw.print("view: "); pw.println(mWindow.mContentView);
            if (mWindow.mShowParams != null) {
                pw.print(prefix2); pw.print("params: "); pw.println(mWindow.mShowParams);
            }
            pw.print(prefix2); pw.print("screen coordinates: ");
            if (mWindow.mContentView == null) {
                pw.println("N/A");
            } else {
                final int[] coordinates = mWindow.mContentView.getLocationOnScreen();
                pw.print(coordinates[0]); pw.print("x"); pw.println(coordinates[1]);
            }
        }
    }

    private void announceSearchResultIfNeeded() {
        if (AccessibilityManager.getInstance(mContext).isEnabled()) {
            if (mAnnounceFilterResult == null) {
                mAnnounceFilterResult = new AnnounceFilterResult();
            }
            mAnnounceFilterResult.post();
        }
    }

    private final class ItemsAdapter extends BaseAdapter implements Filterable {
        private @NonNull final List<ViewItem> mAllItems;

        private @NonNull final List<ViewItem> mFilteredItems = new ArrayList<>();

        ItemsAdapter(@NonNull List<ViewItem> items) {
            mAllItems = Collections.unmodifiableList(new ArrayList<>(items));
            mFilteredItems.addAll(items);
        }

        @Override
        public Filter getFilter() {
            return new Filter() {
                @Override
                protected FilterResults performFiltering(CharSequence filterText) {
                    // No locking needed as mAllItems is final an immutable
                    final List<ViewItem> filtered = mAllItems.stream()
                            .filter((item) -> item.matches(filterText))
                            .collect(Collectors.toList());
                    final FilterResults results = new FilterResults();
                    results.values = filtered;
                    results.count = filtered.size();
                    return results;
                }

                @Override
                protected void publishResults(CharSequence constraint, FilterResults results) {
                    final boolean resultCountChanged;
                    final int oldItemCount = mFilteredItems.size();
                    mFilteredItems.clear();
                    if (results.count > 0) {
                        @SuppressWarnings("unchecked")
                        final List<ViewItem> items = (List<ViewItem>) results.values;
                        mFilteredItems.addAll(items);
                    }
                    resultCountChanged = (oldItemCount != mFilteredItems.size());
                    if (resultCountChanged) {
                        announceSearchResultIfNeeded();
                    }
                    notifyDataSetChanged();
                }
            };
        }

        @Override
        public int getCount() {
            return mFilteredItems.size();
        }

        @Override
        public ViewItem getItem(int position) {
            return mFilteredItems.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            return getItem(position).view;
        }

        @Override
        public String toString() {
            return "ItemsAdapter: [all=" + mAllItems + ", filtered=" + mFilteredItems + "]";
        }
    }

    private final class AnnounceFilterResult implements Runnable {
        private static final int SEARCH_RESULT_ANNOUNCEMENT_DELAY = 1000; // 1 sec

        public void post() {
            remove();
            mListView.postDelayed(this, SEARCH_RESULT_ANNOUNCEMENT_DELAY);
        }

        public void remove() {
            mListView.removeCallbacks(this);
        }

        @Override
        public void run() {
            final int count = mListView.getAdapter().getCount();
            final String text;
            if (count <= 0) {
                text = mContext.getString(R.string.autofill_picker_no_suggestions);
            } else {
                text = mContext.getResources().getQuantityString(
                        R.plurals.autofill_picker_some_suggestions, count, count);
            }
            mListView.announceForAccessibility(text);
        }
    }
}
