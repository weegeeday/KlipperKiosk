package com.klipperkiosk.app;

import android.annotation.SuppressLint;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.ConsumerIrManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class MainActivity extends AppCompatActivity {

    private static final String PREFS_NAME = "klipperkiosk_prefs";
    private static final String KEY_TABS_JSON = "tabs_json";
    private static final String KEY_ACTIVE_ID = "active_tab_id";
    private static final String KEY_KIOSK_LOCKED = "kiosk_locked";
    private static final String KEY_SETTINGS_PIN = "settings_pin";
    private static final String KEY_SLEEP_TIMEOUT_MIN = "sleep_timeout_min";
    private static final String DEFAULT_SETTINGS_PIN = "1234";
    private static final int DEFAULT_SLEEP_TIMEOUT_MIN = 5;
    private static final int REMOTE_API_PORT = 8765;
    private static final String MDNS_HOSTNAME = "klipper-kiosk.local";

    private static final int COLOR_BG_CHROME = Color.parseColor("#111318");
    private static final int COLOR_BG_TAB = Color.parseColor("#1A1D24");
    private static final int COLOR_BG_TAB_ACTIVE = Color.parseColor("#272C38");
    private static final int COLOR_BORDER = Color.parseColor("#2B303C");
    private static final int COLOR_TEXT = Color.parseColor("#F3F4F6");
    private static final int COLOR_MUTED = Color.parseColor("#9CA3AF");
    private static final int COLOR_ACCENT = Color.parseColor("#38BDF8");

    // Polyfill missing ES2022/ES2023 Array methods on Android 6 WebView 81
    private static final String WEBVIEW_POLYFILL_JS =
        "(function(){" +
        "if(!Array.prototype.findLastIndex){Array.prototype.findLastIndex=function(p,t){for(var i=this.length-1;i>=0;i--){if(p.call(t,this[i],i,this))return i;}return -1;};}" +
        "if(!Array.prototype.findLast){Array.prototype.findLast=function(p,t){var i=this.findLastIndex(p,t);return i>=0?this[i]:undefined;};}" +
        "if(!Array.prototype.at){Array.prototype.at=function(n){n=Math.trunc(n)||0;if(n<0)n+=this.length;if(n<0||n>=this.length)return undefined;return this[n];};}" +
        "})();";

    // Strip expensive transitions, ripples, and box-shadows for low-RAM PowerVR GPU smoothness
    private static final String PERF_CSS_JS =
        "(function(){" +
        "var id='klipperkiosk-perf-css';if(document.getElementById(id))return;" +
        "var s=document.createElement('style');s.id=id;" +
        "s.textContent='*,*::before,*::after{transition:none!important;animation-duration:0.001ms!important;animation-iteration-count:1!important;box-shadow:none!important;text-shadow:none!important;}.v-ripple__container{display:none!important;}';" +
        "(document.head||document.documentElement).appendChild(s);" +
        "})();";

    private static final class TabItem {
        String id;
        String title;
        String url;

        TabItem(String id, String title, String url) {
            this.id = id;
            this.title = title;
            this.url = url;
        }
    }

    private final List<TabItem> tabs = new ArrayList<>();
    private final Map<String, String> resolvedUrls = new HashMap<>();
    private final ExecutorService resolverPool = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean pendingRetry = false;
    private ConnectivityManager.NetworkCallback networkCallback;

    private final Runnable retryRunnable = () -> {
        pendingRetry = false;
        reloadActiveTab();
    };

    private String activeTabId = "";
    private String settingsPin = DEFAULT_SETTINGS_PIN;
    private int sleepTimeoutMinutes = DEFAULT_SLEEP_TIMEOUT_MIN;
    private boolean kioskLocked = false;
    private boolean isSleeping = false;
    private boolean lockTaskBypassed = false;
    private int activeTapCount = 0;
    private long lastTapTime = 0L;

    private final Runnable sleepRunnable = this::enterSleepMode;

    private LinearLayout tabsContainer;
    private LinearLayout actionsContainer;
    private FrameLayout webContainer;
    private FrameLayout sleepOverlay;
    private WebView sharedWebView;
    private RemoteManagementServer remoteServer;
    private MdnsResponder mdnsResponder;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        );
        applyKioskImmersiveMode();
        getWindow().getDecorView().setOnSystemUiVisibilityChangeListener(visibility -> {
            if ((visibility & View.SYSTEM_UI_FLAG_FULLSCREEN) == 0) {
                applyKioskImmersiveMode();
            }
        });
        configureDeviceOwnerKiosk();

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (sharedWebView != null && sharedWebView.canGoBack()) {
                    sharedWebView.goBack();
                }
            }
        });

        loadState();
        buildNativeLayout();
        sharedWebView = createConfiguredWebView();
        webContainer.addView(sharedWebView, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ));

        renderTabBar();
        registerNetworkCallback();
        startRemoteManagementServer();
        mdnsResponder = new MdnsResponder(this, MDNS_HOSTNAME);
        mdnsResponder.start();
        if (!activeTabId.isEmpty()) {
            activateTab(activeTabId, true);
        }
        resetSleepTimer();
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (isSleeping) {
            if (ev.getAction() == MotionEvent.ACTION_UP) {
                wakeFromSleep();
            }
            return true;
        }
        resetSleepTimer();
        return super.dispatchTouchEvent(ev);
    }

    @Override
    protected void onResume() {
        super.onResume();
        applyKioskImmersiveMode();
        if (!lockTaskBypassed) {
            enterLockTaskIfDeviceOwner();
        }
        if (isSleeping) {
            wakeFromSleep();
        } else {
            if (sharedWebView != null) {
                sharedWebView.onResume();
            }
            resetSleepTimer();
        }
    }

    @Override
    protected void onPause() {
        mainHandler.removeCallbacks(sleepRunnable);
        if (sharedWebView != null) {
            sharedWebView.onPause();
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        mainHandler.removeCallbacks(retryRunnable);
        mainHandler.removeCallbacks(sleepRunnable);
        unregisterNetworkCallback();
        if (remoteServer != null) {
            remoteServer.stop();
            remoteServer = null;
        }
        if (mdnsResponder != null) {
            mdnsResponder.stop();
            mdnsResponder = null;
        }
        if (sharedWebView != null) {
            sharedWebView.destroy();
            sharedWebView = null;
        }
        resolverPool.shutdownNow();
        super.onDestroy();
    }

    private void registerNetworkCallback() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return;
            NetworkRequest req = new NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build();
            networkCallback = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    if (mdnsResponder != null) {
                        mdnsResponder.announceAsync();
                    }
                    runOnUiThread(() -> {
                        if (pendingRetry) {
                            mainHandler.removeCallbacks(retryRunnable);
                            pendingRetry = false;
                            reloadActiveTab();
                        }
                    });
                }
            };
            cm.registerNetworkCallback(req, networkCallback);
        } catch (Exception ignored) {
        }
    }

    private void unregisterNetworkCallback() {
        try {
            if (networkCallback != null) {
                ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
                if (cm != null) {
                    cm.unregisterNetworkCallback(networkCallback);
                }
                networkCallback = null;
            }
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            applyKioskImmersiveMode();
        }
    }

    private void configureDeviceOwnerKiosk() {
        try {
            DevicePolicyManager dpm = (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
            if (dpm == null || !dpm.isDeviceOwnerApp(getPackageName())) {
                return;
            }
            ComponentName admin = new ComponentName(this, KioskDeviceAdminReceiver.class);
            dpm.setLockTaskPackages(admin, new String[]{getPackageName()});
            dpm.setStatusBarDisabled(admin, true);
            dpm.setKeyguardDisabled(admin, true);

            // Persist this activity as the default Home launcher across reboots
            IntentFilter filter = new IntentFilter(Intent.ACTION_MAIN);
            filter.addCategory(Intent.CATEGORY_HOME);
            filter.addCategory(Intent.CATEGORY_DEFAULT);
            dpm.addPersistentPreferredActivity(admin, filter, new ComponentName(this, MainActivity.class));
        } catch (Exception ignored) {
        }
    }

    private void enterLockTaskIfDeviceOwner() {
        try {
            DevicePolicyManager dpm = (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
            if (dpm == null || !dpm.isDeviceOwnerApp(getPackageName())) {
                return;
            }
            ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null && am.getLockTaskModeState() == ActivityManager.LOCK_TASK_MODE_NONE) {
                startLockTask();
            }
        } catch (Exception ignored) {
        }
    }

    private void exitKioskLockTask() {
        lockTaskBypassed = true;
        try {
            DevicePolicyManager dpm = (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
            if (dpm != null && dpm.isDeviceOwnerApp(getPackageName())) {
                ComponentName admin = new ComponentName(this, KioskDeviceAdminReceiver.class);
                dpm.setStatusBarDisabled(admin, false);
                dpm.setKeyguardDisabled(admin, false);
                dpm.clearPackagePersistentPreferredActivities(admin, getPackageName());
            }
        } catch (Exception ignored) {
        }
        try {
            stopLockTask();
        } catch (Exception ignored) {
        }
    }

    @SuppressWarnings("deprecation")
    private void applyKioskImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        View decor = getWindow().getDecorView();
        decor.setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_FULLSCREEN
        );
        WindowInsetsControllerCompat controller =
            WindowCompat.getInsetsController(getWindow(), decor);
        if (controller != null) {
            controller.hide(WindowInsetsCompat.Type.systemBars());
            controller.setSystemBarsBehavior(
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            );
        }
    }

    private int dp(int value) {
        return Math.round(TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics()
        ));
    }

    private void buildNativeLayout() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(COLOR_BG_CHROME);
        root.setLayoutParams(new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ));

        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setBackgroundColor(COLOR_BG_CHROME);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(dp(4), 0, dp(4), 0);
        topBar.setLayoutParams(new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(42)
        ));

        HorizontalScrollView scrollView = new HorizontalScrollView(this);
        scrollView.setHorizontalScrollBarEnabled(false);
        scrollView.setFillViewport(false);
        scrollView.setLayoutParams(new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.MATCH_PARENT, 1f
        ));

        tabsContainer = new LinearLayout(this);
        tabsContainer.setOrientation(LinearLayout.HORIZONTAL);
        tabsContainer.setGravity(Gravity.BOTTOM);
        tabsContainer.setPadding(0, dp(4), 0, 0);
        tabsContainer.setLayoutParams(new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT
        ));
        scrollView.addView(tabsContainer);
        topBar.addView(scrollView);

        actionsContainer = new LinearLayout(this);
        actionsContainer.setOrientation(LinearLayout.HORIZONTAL);
        actionsContainer.setGravity(Gravity.CENTER_VERTICAL);
        actionsContainer.setLayoutParams(new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT
        ));

        actionsContainer.addView(createIconButton("\u263E", v -> enterSleepMode()));
        actionsContainer.addView(createIconButton("\u21BB", v -> reloadActiveTab()));
        actionsContainer.addView(createIconButton("\u2699", v -> promptForSettingsPin()));
        topBar.addView(actionsContainer);

        View divider = new View(this);
        divider.setBackgroundColor(COLOR_BORDER);
        divider.setLayoutParams(new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1))
        ));

        webContainer = new FrameLayout(this);
        webContainer.setBackgroundColor(Color.parseColor("#0B0D11"));
        webContainer.setLayoutParams(new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ));

        root.addView(topBar);
        root.addView(divider);
        root.addView(webContainer);

        FrameLayout outerRoot = new FrameLayout(this);
        outerRoot.setLayoutParams(new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ));
        outerRoot.addView(root);

        sleepOverlay = new FrameLayout(this);
        sleepOverlay.setBackgroundColor(Color.BLACK);
        sleepOverlay.setVisibility(View.GONE);
        sleepOverlay.setClickable(true);
        sleepOverlay.setFocusable(true);
        TextView sleepHint = new TextView(this);
        sleepHint.setText("Sleeping - Tap to wake");
        sleepHint.setTextColor(Color.parseColor("#333842"));
        sleepHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        FrameLayout.LayoutParams hintLp = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER
        );
        sleepOverlay.addView(sleepHint, hintLp);
        outerRoot.addView(sleepOverlay, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ));

        setContentView(outerRoot);
    }

    private void resetSleepTimer() {
        mainHandler.removeCallbacks(sleepRunnable);
        if (sleepTimeoutMinutes > 0 && !isSleeping) {
            mainHandler.postDelayed(sleepRunnable, sleepTimeoutMinutes * 60_000L);
        }
    }

    private void enterSleepMode() {
        if (isSleeping) return;
        isSleeping = true;
        mainHandler.removeCallbacks(sleepRunnable);

        if (sleepOverlay != null) {
            sleepOverlay.setVisibility(View.VISIBLE);
        }
        if (sharedWebView != null) {
            sharedWebView.onPause();
            sharedWebView.pauseTimers();
        }

        // Dim backlight to minimum and release FLAG_KEEP_SCREEN_ON so LCD can turn fully off
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = 0.01f;
        getWindow().setAttributes(lp);
    }

    @SuppressWarnings("deprecation")
    private void wakeFromSleep() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null && !pm.isInteractive()) {
                PowerManager.WakeLock wl = pm.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP,
                    "KlipperKiosk:Wake"
                );
                wl.acquire(3000);
                wl.release();
            }
        } catch (Exception ignored) {
        }

        isSleeping = false;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
        getWindow().setAttributes(lp);

        if (sleepOverlay != null) {
            sleepOverlay.setVisibility(View.GONE);
        }
        if (sharedWebView != null) {
            sharedWebView.resumeTimers();
            sharedWebView.onResume();
        }
        applyKioskImmersiveMode();
        resetSleepTimer();
    }

    private Button createIconButton(String symbol, View.OnClickListener onClick) {
        Button btn = new Button(this);
        btn.setText(symbol);
        btn.setTextColor(COLOR_MUTED);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        btn.setBackgroundColor(Color.TRANSPARENT);
        btn.setPadding(0, 0, 0, 0);
        btn.setMinimumWidth(0);
        btn.setMinWidth(0);
        btn.setMinimumHeight(0);
        btn.setMinHeight(0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(36), dp(36));
        lp.leftMargin = dp(4);
        btn.setLayoutParams(lp);
        btn.setOnClickListener(onClick);
        return btn;
    }

    private void renderTabBar() {
        tabsContainer.removeAllViews();
        actionsContainer.setVisibility(kioskLocked ? View.GONE : View.VISIBLE);

        for (TabItem tab : tabs) {
            boolean isActive = tab.id.equals(activeTabId);

            LinearLayout tabBtn = new LinearLayout(this);
            tabBtn.setOrientation(LinearLayout.HORIZONTAL);
            tabBtn.setGravity(Gravity.CENTER_VERTICAL);
            tabBtn.setPadding(dp(16), 0, dp(16), 0);

            GradientDrawable bg = new GradientDrawable();
            bg.setColor(isActive ? COLOR_BG_TAB_ACTIVE : COLOR_BG_TAB);
            bg.setCornerRadii(new float[]{dp(8), dp(8), dp(8), dp(8), 0, 0, 0, 0});
            if (isActive) {
                bg.setStroke(dp(1), COLOR_BORDER);
            }
            tabBtn.setBackground(bg);

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT
            );
            lp.rightMargin = dp(4);
            tabBtn.setLayoutParams(lp);

            View dot = new View(this);
            GradientDrawable dotBg = new GradientDrawable();
            dotBg.setShape(GradientDrawable.OVAL);
            dotBg.setColor(isActive ? COLOR_ACCENT : COLOR_MUTED);
            dot.setBackground(dotBg);
            dot.setAlpha(isActive ? 1f : 0.45f);
            LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dp(7), dp(7));
            dotLp.rightMargin = dp(8);
            dot.setLayoutParams(dotLp);

            TextView label = new TextView(this);
            label.setText(tab.title);
            label.setTextColor(isActive ? COLOR_TEXT : COLOR_MUTED);
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            label.setTypeface(Typeface.DEFAULT, isActive ? Typeface.BOLD : Typeface.NORMAL);
            label.setSingleLine(true);
            label.setEllipsize(TextUtils.TruncateAt.END);
            label.setMaxWidth(dp(180));

            tabBtn.addView(dot);
            tabBtn.addView(label);
            tabBtn.setOnClickListener(v -> handleTabTap(tab.id));

            tabsContainer.addView(tabBtn);
        }
    }

    private void handleTabTap(String tabId) {
        if (tabId.equals(activeTabId) && kioskLocked) {
            long now = System.currentTimeMillis();
            if (now - lastTapTime > 1500) {
                activeTapCount = 0;
            }
            lastTapTime = now;
            activeTapCount++;
            if (activeTapCount >= 5) {
                activeTapCount = 0;
                promptForSettingsPin();
            }
            return;
        }
        activeTapCount = 0;
        if (!tabId.equals(activeTabId)) {
            activateTab(tabId, false);
        }
    }

    private void activateTab(String tabId, boolean forceLoad) {
        TabItem target = findTab(tabId);
        if (target == null || sharedWebView == null) return;

        if (!forceLoad && tabId.equals(activeTabId)) {
            return;
        }

        activeTabId = tabId;
        saveState();
        renderTabBar();
        loadTabUrl(target);
    }

    private void reloadActiveTab() {
        TabItem target = findTab(activeTabId);
        if (target != null && sharedWebView != null) {
            resolvedUrls.remove(target.id);
            loadTabUrl(target);
        }
    }

    private void loadTabUrl(TabItem tab) {
        if (sharedWebView == null) return;
        final String requestedTabId = tab.id;
        String cached = HostResolver.getCachedUrl(this, tab.url);
        if (cached != null) {
            resolvedUrls.put(tab.id, cached);
            sharedWebView.stopLoading();
            sharedWebView.loadUrl(cached);
        }

        resolverPool.execute(() -> {
            String resolved = HostResolver.resolveUrlSync(MainActivity.this, tab.url);
            runOnUiThread(() -> {
                if (sharedWebView == null || !requestedTabId.equals(activeTabId)) return;
                String prev = resolvedUrls.get(tab.id);
                resolvedUrls.put(tab.id, resolved);
                if (!resolved.equals(prev)) {
                    sharedWebView.stopLoading();
                    sharedWebView.loadUrl(resolved);
                }
            });
        });
    }

    @SuppressLint("SetJavaScriptEnabled")
    private WebView createConfiguredWebView() {
        WebView webView = new WebView(this);
        // Keep LAYER_TYPE_NONE so Chromium's GPU compositor draws directly without an extra FBO
        webView.setLayerType(View.LAYER_TYPE_NONE, null);
        webView.setInitialScale(125);
        webView.setScrollBarStyle(View.SCROLLBARS_INSIDE_OVERLAY);
        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        webView.setBackgroundColor(Color.parseColor("#0B0D11"));

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setUseWideViewPort(false);
        settings.setLoadWithOverviewMode(false);
        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);

        // Report as Android tablet (omit "Mobile" token)
        String defaultUa = settings.getUserAgentString();
        if (defaultUa != null && defaultUa.contains("Mobile")) {
            settings.setUserAgentString(
                defaultUa.replace(" Mobile ", " ").replace("Mobile Safari", "Safari")
            );
        }

        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                view.evaluateJavascript(WEBVIEW_POLYFILL_JS, null);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                if (url != null && !url.startsWith("data:") && !url.equals("about:blank")) {
                    pendingRetry = false;
                    mainHandler.removeCallbacks(retryRunnable);
                }
                view.evaluateJavascript(PERF_CSS_JS, null);
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                super.onReceivedError(view, request, error);
                if (request != null && request.isForMainFrame()) {
                    scheduleReconnect(view);
                }
            }
        });

        return webView;
    }

    private void scheduleReconnect(WebView view) {
        pendingRetry = true;
        mainHandler.removeCallbacks(retryRunnable);
        String html = "<html><body style='margin:0;height:100vh;display:flex;align-items:center;"
            + "justify-content:center;background:#0B0D11;color:#9CA3AF;font-family:sans-serif;"
            + "font-size:16px;'>Connecting to printer...</body></html>";
        view.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null);
        mainHandler.postDelayed(retryRunnable, 3000);
    }

    private TabItem findTab(String id) {
        for (TabItem t : tabs) {
            if (t.id.equals(id)) return t;
        }
        return null;
    }

    private void loadState() {
        tabs.clear();
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        kioskLocked = prefs.getBoolean(KEY_KIOSK_LOCKED, false);
        settingsPin = prefs.getString(KEY_SETTINGS_PIN, DEFAULT_SETTINGS_PIN);
        sleepTimeoutMinutes = prefs.getInt(KEY_SLEEP_TIMEOUT_MIN, DEFAULT_SLEEP_TIMEOUT_MIN);

        String savedJson = prefs.getString(KEY_TABS_JSON, null);
        if (savedJson != null) {
            parseTabsJson(savedJson);
        }

        if (tabs.isEmpty()) {
            String assetJson = readAssetFile("public/sites.json");
            if (assetJson != null) {
                parseTabsJson(assetJson);
            }
        }

        if (tabs.isEmpty()) {
            tabs.add(new TabItem("site-1", "Farz", "http://pi-klipper.local"));
            tabs.add(new TabItem("site-2", "Klip", "http://pi-klipper2.local"));
        }

        String savedActive = prefs.getString(KEY_ACTIVE_ID, "");
        activeTabId = findTab(savedActive) != null ? savedActive : tabs.get(0).id;
    }

    private void parseTabsJson(String json) {
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                String id = obj.optString("id", "tab-" + i);
                String title = obj.optString("title", "Tab " + (i + 1));
                String url = obj.optString("url", "");
                if (!url.isEmpty()) {
                    tabs.add(new TabItem(id, title, url));
                }
            }
        } catch (Exception ignored) {
        }
    }

    private String readAssetFile(String assetPath) {
        try (InputStream is = getAssets().open(assetPath);
             BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append('\n');
            }
            return sb.toString();
        } catch (Exception ignored) {
            return null;
        }
    }

    private void saveState() {
        try {
            JSONArray arr = new JSONArray();
            for (TabItem t : tabs) {
                JSONObject obj = new JSONObject();
                obj.put("id", t.id);
                obj.put("title", t.title);
                obj.put("url", t.url);
                arr.put(obj);
            }
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putString(KEY_TABS_JSON, arr.toString())
                .putString(KEY_ACTIVE_ID, activeTabId)
                .putBoolean(KEY_KIOSK_LOCKED, kioskLocked)
                .putString(KEY_SETTINGS_PIN, settingsPin)
                .putInt(KEY_SLEEP_TIMEOUT_MIN, sleepTimeoutMinutes)
                .apply();
        } catch (Exception ignored) {
        }
    }

    private void promptForSettingsPin() {
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(dp(20), dp(12), dp(20), dp(4));

        EditText pinInput = new EditText(this);
        pinInput.setHint("Enter PIN");
        pinInput.setInputType(
            InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD
        );
        container.addView(pinInput);

        TextView errorView = new TextView(this);
        errorView.setTextColor(Color.parseColor("#F87171"));
        errorView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        errorView.setVisibility(View.GONE);
        container.addView(errorView);

        AlertDialog dialog = new AlertDialog.Builder(this)
            .setTitle("Settings PIN")
            .setView(container)
            .setPositiveButton("Unlock", null)
            .setNegativeButton("Cancel", (d, w) -> applyKioskImmersiveMode())
            .create();

        dialog.setOnShowListener(d -> {
            Button unlockBtn = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            unlockBtn.setOnClickListener(v -> {
                String entered = pinInput.getText().toString().trim();
                if (entered.equals(settingsPin)) {
                    dialog.dismiss();
                    openTabManagerDialog();
                } else {
                    errorView.setText("Incorrect PIN");
                    errorView.setVisibility(View.VISIBLE);
                    pinInput.setText("");
                }
            });
        });

        dialog.show();
    }

    private void openTabManagerDialog() {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(12), dp(16), dp(8));

        TextView apiInfo = new TextView(this);
        apiInfo.setText(
            "Remote API: http://" + MDNS_HOSTNAME + ":" + REMOTE_API_PORT
                + " (" + RemoteManagementServer.getDeviceIpv4() + ")"
        );
        apiInfo.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        apiInfo.setPadding(0, 0, 0, dp(8));
        content.addView(apiInfo);

        EditText titleInput = new EditText(this);
        titleInput.setHint("Tab name (e.g. Mainsail)");
        EditText urlInput = new EditText(this);
        urlInput.setHint("URL (e.g. http://pi-klipper.local)");
        urlInput.setInputType(InputType.TYPE_TEXT_VARIATION_URI);

        Button addBtn = new Button(this);
        addBtn.setText("Add Tab");

        content.addView(titleInput);
        content.addView(urlInput);
        content.addView(addBtn);

        LinearLayout listLayout = new LinearLayout(this);
        listLayout.setOrientation(LinearLayout.VERTICAL);
        ScrollView listScroll = new ScrollView(this);
        listScroll.addView(listLayout);
        LinearLayout.LayoutParams listLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(180)
        );
        listLp.topMargin = dp(8);
        listScroll.setLayoutParams(listLp);
        content.addView(listScroll);

        CheckBox lockCheck = new CheckBox(this);
        lockCheck.setText("Hide settings & reload icons (tap active tab 5x to reopen)");
        lockCheck.setChecked(kioskLocked);
        content.addView(lockCheck);

        LinearLayout pinRow = new LinearLayout(this);
        pinRow.setOrientation(LinearLayout.HORIZONTAL);
        pinRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams pinRowLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        pinRowLp.topMargin = dp(8);
        pinRow.setLayoutParams(pinRowLp);

        EditText newPinInput = new EditText(this);
        newPinInput.setHint("New Settings PIN (current: " + settingsPin + ")");
        newPinInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        newPinInput.setLayoutParams(new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
        ));
        Button savePinBtn = new Button(this);
        savePinBtn.setText("Save PIN");
        savePinBtn.setOnClickListener(v -> {
            String nextPin = newPinInput.getText().toString().trim();
            if (!nextPin.isEmpty()) {
                settingsPin = nextPin;
                saveState();
                newPinInput.setText("");
                newPinInput.setHint("PIN updated (" + settingsPin + ")");
            }
        });
        pinRow.addView(newPinInput);
        pinRow.addView(savePinBtn);
        content.addView(pinRow);

        LinearLayout sleepRow = new LinearLayout(this);
        sleepRow.setOrientation(LinearLayout.HORIZONTAL);
        sleepRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams sleepRowLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        sleepRowLp.topMargin = dp(8);
        sleepRow.setLayoutParams(sleepRowLp);

        EditText sleepInput = new EditText(this);
        sleepInput.setHint("Auto-sleep minutes, 0=off (current: " + sleepTimeoutMinutes + ")");
        sleepInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        sleepInput.setLayoutParams(new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
        ));
        Button saveSleepBtn = new Button(this);
        saveSleepBtn.setText("Save Sleep");
        saveSleepBtn.setOnClickListener(v -> {
            String raw = sleepInput.getText().toString().trim();
            if (!raw.isEmpty()) {
                try {
                    int mins = Math.max(0, Math.min(1440, Integer.parseInt(raw)));
                    sleepTimeoutMinutes = mins;
                    saveState();
                    resetSleepTimer();
                    sleepInput.setText("");
                    sleepInput.setHint("Auto-sleep updated (" + sleepTimeoutMinutes + " min)");
                } catch (NumberFormatException ignored) {
                }
            }
        });
        sleepRow.addView(sleepInput);
        sleepRow.addView(saveSleepBtn);
        content.addView(sleepRow);

        Runnable rebuildList = new Runnable() {
            @Override
            public void run() {
                listLayout.removeAllViews();
                for (int i = 0; i < tabs.size(); i++) {
                    TabItem tab = tabs.get(i);
                    LinearLayout row = new LinearLayout(MainActivity.this);
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    row.setGravity(Gravity.CENTER_VERTICAL);
                    row.setPadding(0, dp(4), 0, dp(4));

                    TextView info = new TextView(MainActivity.this);
                    info.setText(tab.title + "\n" + tab.url);
                    info.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
                    info.setLayoutParams(new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                    ));
                    row.addView(info);

                    Button delBtn = new Button(MainActivity.this);
                    delBtn.setText("Delete");
                    delBtn.setOnClickListener(v -> {
                        tabs.remove(tab);
                        if (activeTabId.equals(tab.id)) {
                            activeTabId = tabs.isEmpty() ? "" : tabs.get(0).id;
                        }
                        saveState();
                        renderTabBar();
                        if (!activeTabId.isEmpty()) activateTab(activeTabId, true);
                        this.run();
                    });
                    row.addView(delBtn);
                    listLayout.addView(row);
                }
            }
        };
        rebuildList.run();

        addBtn.setOnClickListener(v -> {
            String rawUrl = urlInput.getText().toString().trim();
            if (rawUrl.isEmpty()) return;
            if (!rawUrl.startsWith("http://") && !rawUrl.startsWith("https://")) {
                rawUrl = "http://" + rawUrl;
            }
            String rawTitle = titleInput.getText().toString().trim();
            if (rawTitle.isEmpty()) rawTitle = rawUrl.replaceFirst("^https?://", "");
            TabItem added = new TabItem("tab-" + System.currentTimeMillis(), rawTitle, rawUrl);
            tabs.add(added);
            titleInput.setText("");
            urlInput.setText("");
            saveState();
            renderTabBar();
            activateTab(added.id, true);
            rebuildList.run();
        });

        lockCheck.setOnCheckedChangeListener((btn, checked) -> {
            kioskLocked = checked;
            saveState();
            renderTabBar();
        });

        new AlertDialog.Builder(this)
            .setTitle("Kiosk Settings")
            .setView(content)
            .setPositiveButton("Done", (d, w) -> {
                lockTaskBypassed = false;
                applyKioskImmersiveMode();
                enterLockTaskIfDeviceOwner();
            })
            .setNegativeButton("Exit Kiosk", (d, w) -> exitKioskLockTask())
            .setNeutralButton("Reset to sites.ts", (d, w) -> {
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().remove(KEY_TABS_JSON).apply();
                loadState();
                saveState();
                renderTabBar();
                if (!activeTabId.isEmpty()) activateTab(activeTabId, true);
            })
            .show();
    }

    private <T> T runOnUiSync(java.util.concurrent.Callable<T> task, T fallback) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            try {
                return task.call();
            } catch (Exception e) {
                return fallback;
            }
        }
        AtomicReference<T> ref = new AtomicReference<>(fallback);
        CountDownLatch latch = new CountDownLatch(1);
        runOnUiThread(() -> {
            try {
                ref.set(task.call());
            } catch (Exception ignored) {
            } finally {
                latch.countDown();
            }
        });
        try {
            latch.await(3, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
        }
        return ref.get();
    }

    private JSONObject tabToJson(TabItem t) {
        JSONObject obj = new JSONObject();
        try {
            obj.put("id", t.id);
            obj.put("title", t.title);
            obj.put("url", t.url);
            String resolved = resolvedUrls.get(t.id);
            if (resolved != null) {
                obj.put("resolvedUrl", resolved);
            }
        } catch (Exception ignored) {
        }
        return obj;
    }

    private JSONObject buildTabsPayload() {
        JSONObject payload = new JSONObject();
        JSONArray arr = new JSONArray();
        for (TabItem t : tabs) {
            arr.put(tabToJson(t));
        }
        try {
            payload.put("activeTabId", activeTabId);
            payload.put("tabs", arr);
        } catch (Exception ignored) {
        }
        return payload;
    }

    private String normalizeUrl(String raw) {
        String trimmed = raw == null ? "" : raw.trim();
        if (trimmed.isEmpty()) return "";
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            return "http://" + trimmed;
        }
        return trimmed;
    }

    private void startRemoteManagementServer() {
        remoteServer = new RemoteManagementServer(REMOTE_API_PORT, new RemoteManagementServer.Controller() {
            @Override
            public String getPin() {
                return settingsPin;
            }

            @Override
            public JSONObject getStatusJson() {
                return runOnUiSync(() -> {
                    JSONObject obj = buildTabsPayload();
                    obj.put("kioskLocked", kioskLocked);
                    obj.put("sleeping", isSleeping);
                    obj.put("sleepTimeoutMinutes", sleepTimeoutMinutes);
                    obj.put("hostname", MDNS_HOSTNAME);
                    obj.put("deviceIp", RemoteManagementServer.getDeviceIpv4());
                    obj.put("port", REMOTE_API_PORT);
                    ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
                    boolean inLockTask = am != null
                        && am.getLockTaskModeState() != ActivityManager.LOCK_TASK_MODE_NONE;
                    obj.put("lockTaskMode", inLockTask);
                    return obj;
                }, new JSONObject());
            }

            @Override
            public JSONObject getTabsJson() {
                return runOnUiSync(MainActivity.this::buildTabsPayload, new JSONObject());
            }

            @Override
            public JSONObject addTab(String title, String url, boolean activate) {
                return runOnUiSync(() -> {
                    String normalized = normalizeUrl(url);
                    String cleanTitle = (title == null || title.trim().isEmpty())
                        ? normalized.replaceFirst("^https?://", "")
                        : title.trim();
                    TabItem item = new TabItem("tab-" + System.currentTimeMillis(), cleanTitle, normalized);
                    tabs.add(item);
                    saveState();
                    renderTabBar();
                    if (activate || activeTabId.isEmpty()) {
                        MainActivity.this.activateTab(item.id, true);
                    }
                    return tabToJson(item);
                }, new JSONObject());
            }

            @Override
            public JSONObject updateTab(String id, String title, String url) {
                return runOnUiSync(() -> {
                    TabItem item = findTab(id);
                    if (item == null) return null;
                    if (title != null && !title.trim().isEmpty()) {
                        item.title = title.trim();
                    }
                    if (url != null && !url.trim().isEmpty()) {
                        item.url = normalizeUrl(url);
                        resolvedUrls.remove(item.id);
                    }
                    saveState();
                    renderTabBar();
                    if (item.id.equals(activeTabId)) {
                        loadTabUrl(item);
                    }
                    return tabToJson(item);
                }, null);
            }

            @Override
            public boolean deleteTab(String id) {
                return runOnUiSync(() -> {
                    TabItem item = findTab(id);
                    if (item == null) return false;
                    tabs.remove(item);
                    resolvedUrls.remove(id);
                    if (activeTabId.equals(id)) {
                        activeTabId = tabs.isEmpty() ? "" : tabs.get(0).id;
                    }
                    saveState();
                    renderTabBar();
                    if (!activeTabId.isEmpty()) {
                        MainActivity.this.activateTab(activeTabId, true);
                    }
                    return true;
                }, false);
            }

            @Override
            public boolean activateTab(String id) {
                return runOnUiSync(() -> {
                    if (findTab(id) == null) return false;
                    MainActivity.this.activateTab(id, true);
                    return true;
                }, false);
            }

            @Override
            public boolean reorderTabs(List<String> ids) {
                return runOnUiSync(() -> {
                    List<TabItem> reordered = new ArrayList<>();
                    for (String id : ids) {
                        TabItem found = findTab(id);
                        if (found != null && !reordered.contains(found)) {
                            reordered.add(found);
                        }
                    }
                    for (TabItem t : tabs) {
                        if (!reordered.contains(t)) {
                            reordered.add(t);
                        }
                    }
                    tabs.clear();
                    tabs.addAll(reordered);
                    saveState();
                    renderTabBar();
                    return true;
                }, false);
            }

            @Override
            public JSONObject resetTabs() {
                return runOnUiSync(() -> {
                    getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().remove(KEY_TABS_JSON).apply();
                    resolvedUrls.clear();
                    loadState();
                    saveState();
                    renderTabBar();
                    if (!activeTabId.isEmpty()) {
                        MainActivity.this.activateTab(activeTabId, true);
                    }
                    return buildTabsPayload();
                }, new JSONObject());
            }

            @Override
            public void reloadActiveTab() {
                runOnUiThread(MainActivity.this::reloadActiveTab);
            }

            @Override
            public void navigateActive(String url) {
                String normalized = normalizeUrl(url);
                if (normalized.isEmpty()) return;
                runOnUiThread(() -> {
                    TabItem temp = new TabItem(activeTabId, "", normalized);
                    resolvedUrls.remove(activeTabId);
                    loadTabUrl(temp);
                });
            }

            @Override
            public void setKioskLocked(boolean locked) {
                runOnUiThread(() -> {
                    kioskLocked = locked;
                    saveState();
                    renderTabBar();
                });
            }

            @Override
            public void exitKioskMode() {
                runOnUiThread(MainActivity.this::exitKioskLockTask);
            }

            @Override
            public void enterKioskMode() {
                runOnUiThread(() -> {
                    lockTaskBypassed = false;
                    configureDeviceOwnerKiosk();
                    applyKioskImmersiveMode();
                    enterLockTaskIfDeviceOwner();
                });
            }

            @Override
            public boolean updatePin(String newPin) {
                return runOnUiSync(() -> {
                    if (newPin == null || newPin.trim().isEmpty()) return false;
                    settingsPin = newPin.trim();
                    saveState();
                    return true;
                }, false);
            }

            @Override
            public JSONObject getIrInfo() {
                JSONObject obj = new JSONObject();
                try {
                    ConsumerIrManager ir = (ConsumerIrManager) getSystemService(Context.CONSUMER_IR_SERVICE);
                    boolean hasIr = ir != null && ir.hasIrEmitter();
                    obj.put("hasIrEmitter", hasIr);
                    JSONArray ranges = new JSONArray();
                    if (hasIr) {
                        ConsumerIrManager.CarrierFrequencyRange[] freqs = ir.getCarrierFrequencies();
                        if (freqs != null) {
                            for (ConsumerIrManager.CarrierFrequencyRange r : freqs) {
                                JSONObject range = new JSONObject();
                                range.put("min", r.getMinFrequency());
                                range.put("max", r.getMaxFrequency());
                                ranges.put(range);
                            }
                        }
                    }
                    obj.put("carrierFrequencies", ranges);
                } catch (Exception e) {
                    try {
                        obj.put("hasIrEmitter", false);
                        obj.put("error", e.getMessage());
                    } catch (Exception ignored) {
                    }
                }
                return obj;
            }

            @Override
            public JSONObject transmitIr(int frequencyHz, int[] patternUs, int repeat, int repeatDelayMs) {
                JSONObject obj = new JSONObject();
                try {
                    ConsumerIrManager ir = (ConsumerIrManager) getSystemService(Context.CONSUMER_IR_SERVICE);
                    if (ir == null || !ir.hasIrEmitter()) {
                        obj.put("ok", false);
                        obj.put("error", "Device does not have an IR emitter");
                        return obj;
                    }
                    for (int i = 0; i < repeat; i++) {
                        ir.transmit(frequencyHz, patternUs);
                        if (i + 1 < repeat && repeatDelayMs > 0) {
                            Thread.sleep(repeatDelayMs);
                        }
                    }
                    obj.put("ok", true);
                    obj.put("frequency", frequencyHz);
                    obj.put("pulses", patternUs.length);
                    obj.put("repeat", repeat);
                } catch (Exception e) {
                    try {
                        obj.put("ok", false);
                        obj.put("error", "IR transmit failed: " + e.getMessage());
                    } catch (Exception ignored) {
                    }
                }
                return obj;
            }

            @Override
            public void sleepNow() {
                runOnUiThread(MainActivity.this::enterSleepMode);
            }

            @Override
            public void wakeNow() {
                runOnUiThread(MainActivity.this::wakeFromSleep);
            }

            @Override
            public boolean setSleepTimeoutMinutes(int minutes) {
                return runOnUiSync(() -> {
                    sleepTimeoutMinutes = Math.max(0, Math.min(1440, minutes));
                    saveState();
                    resetSleepTimer();
                    return true;
                }, false);
            }
        });
        remoteServer.start();
    }
}
