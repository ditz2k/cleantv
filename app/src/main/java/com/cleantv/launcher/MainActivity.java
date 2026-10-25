package com.cleantv.launcher;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Clean TV Home: a small Apple-TV-style launcher built from plain framework views.
 * Rows: 0 = top pills, 1 = dock, 2.. = app grid. D-pad navigation is handled by hand
 * so Up/Down always move exactly one row and the ends never fall through.
 */
public class MainActivity extends Activity {

    private static final float FOCUS_SCALE = 1.07f;

    static class AppEntry {
        String pkg;
        String label;
        ComponentName cn;
        Drawable art;
        boolean banner;
        int color = 0xFF7A7A85;
    }

    static class Holder {
        int row;
        int col;
        AppEntry app;
        TextView label;
        boolean dock;
    }

    private int W, H;
    private float u;
    private int marginX, bandPad, gap, tileW, tileH, hp, radius;
    private int columns = 4;

    private FrameLayout root;
    private ScrollView scroll;
    private FrameLayout content;
    private LinearLayout column;
    private View highlight;
    private View glow;
    private TextView bigName;
    private TextView greetView;
    private TextView wifiPill;
    private LinearLayout dropdown;

    private final List<List<View>> rows = new ArrayList<>();
    private int dockCol = 0;
    private int pillCol = 0;
    private String lastPkg = null;
    private String lastSig = null;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            updateGreeting();
            refreshWifi();
            handler.postDelayed(this, 20000);
        }
    };

    private final BroadcastReceiver pkgReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            refreshIfChanged();
        }
    };

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        DisplayMetrics dm = getResources().getDisplayMetrics();
        W = Math.max(dm.widthPixels, dm.heightPixels);
        H = Math.min(dm.widthPixels, dm.heightPixels);
        u = H / 1080f;

        getWindow().getDecorView().setBackgroundColor(Color.BLACK);
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        noClip(root);
        setContentView(root);

        refreshIfChanged();

        if (Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            SharedPreferences sp = getSharedPreferences("launcher", 0);
            if (!sp.getBoolean("asked_location", false)) {
                sp.edit().putBoolean("asked_location", true).apply();
                requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 1);
            }
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_CHANGED);
        f.addDataScheme("package");
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(pkgReceiver, f, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(pkgReceiver, f);
        }
    }

    @Override
    protected void onStop() {
        try {
            unregisterReceiver(pkgReceiver);
        } catch (Throwable ignored) {
        }
        super.onStop();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshIfChanged();
        handler.removeCallbacks(tick);
        handler.post(tick);
        if (getCurrentFocus() == null) {
            focusDefault();
        }
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(tick);
        super.onPause();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Home pressed while we are already showing: go back to the dock.
        closeDropdown(false);
        lastPkg = null;
        focusDefault();
        scrollToY(0);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        refreshWifi();
    }

    @Override
    public void onBackPressed() {
        if (dropdown != null) {
            closeDropdown(true);
        } else if (scroll != null && scroll.getScrollY() > 0) {
            lastPkg = null;
            focusDefault();
        }
        // A launcher never quits on Back.
    }

    // ------------------------------------------------------------------ data

    private List<AppEntry> loadApps() {
        PackageManager pm = getPackageManager();
        List<String> hidden = Arrays.asList(getResources().getStringArray(R.array.hidden_apps));

        Intent q = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        List<ResolveInfo> list = pm.queryIntentActivities(q, 0);
        if (list.isEmpty()) {
            q = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
            list = pm.queryIntentActivities(q, 0);
        }

        Map<String, AppEntry> map = new LinkedHashMap<>();
        for (ResolveInfo ri : list) {
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(getPackageName()) || hidden.contains(pkg) || map.containsKey(pkg)) {
                continue;
            }
            AppEntry e = new AppEntry();
            e.pkg = pkg;
            CharSequence lab = ri.loadLabel(pm);
            e.label = lab == null ? pkg : lab.toString();
            e.cn = new ComponentName(pkg, ri.activityInfo.name);

            Drawable art = null;
            try {
                art = ri.activityInfo.loadBanner(pm);
            } catch (Throwable ignored) {
            }
            if (art == null) {
                try {
                    art = ri.activityInfo.applicationInfo.loadBanner(pm);
                } catch (Throwable ignored) {
                }
            }
            if (art != null) {
                e.banner = true;
            } else {
                try {
                    art = ri.loadIcon(pm);
                } catch (Throwable ignored) {
                }
            }
            e.art = art;
            e.color = sampleColor(art);
            map.put(pkg, e);
        }
        return new ArrayList<>(map.values());
    }

    private int sampleColor(Drawable d) {
        int fallback = 0xFF7A7A85;
        if (d == null) {
            return fallback;
        }
        try {
            Bitmap bmp = Bitmap.createBitmap(16, 9, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bmp);
            d.setBounds(0, 0, 16, 9);
            d.draw(c);
            long r = 0, g = 0, b = 0, n = 0;
            for (int x = 0; x < 16; x++) {
                for (int y = 0; y < 9; y++) {
                    int p = bmp.getPixel(x, y);
                    if (Color.alpha(p) < 40) {
                        continue;
                    }
                    r += Color.red(p);
                    g += Color.green(p);
                    b += Color.blue(p);
                    n++;
                }
            }
            bmp.recycle();
            if (n == 0) {
                return fallback;
            }
            float[] hsv = new float[3];
            Color.RGBToHSV((int) (r / n), (int) (g / n), (int) (b / n), hsv);
            hsv[2] = Math.max(hsv[2], 0.75f);
            return Color.HSVToColor(hsv);
        } catch (Throwable t) {
            return fallback;
        }
    }

    private static String signature(List<AppEntry> all) {
        StringBuilder sb = new StringBuilder();
        for (AppEntry e : all) {
            sb.append(e.pkg).append(';');
        }
        return sb.toString();
    }

    private void refreshIfChanged() {
        if (root == null) {
            return;
        }
        List<AppEntry> all = loadApps();
        String sig = signature(all);
        if (sig.equals(lastSig)) {
            return;
        }
        lastSig = sig;
        build(all);
    }

    // ------------------------------------------------------------------ build UI

    private void build(List<AppEntry> all) {
        closeDropdown(false);
        root.removeAllViews();
        rows.clear();

        // Split into dock and grid.
        String[] dockPk = getResources().getStringArray(R.array.dock_apps);
        int want = Math.min(dockPk.length, 7);
        List<AppEntry> rest = new ArrayList<>(all);
        Collections.sort(rest, new Comparator<AppEntry>() {
            @Override
            public int compare(AppEntry a, AppEntry b) {
                return a.label.compareToIgnoreCase(b.label);
            }
        });
        List<AppEntry> dock = new ArrayList<>();
        for (int i = 0; i < want; i++) {
            for (int j = 0; j < rest.size(); j++) {
                if (rest.get(j).pkg.equals(dockPk[i])) {
                    dock.add(rest.remove(j));
                    break;
                }
            }
        }
        int n = Math.min(want, all.size());
        while (dock.size() < n && !rest.isEmpty()) {
            dock.add(rest.remove(0));
        }
        columns = n > 0 ? n : 4;

        // Sizes (everything derives from screen size so any density works).
        marginX = (int) (W * 0.06f);
        bandPad = (int) (W * 0.012f);
        gap = (int) (W * 0.012f);
        hp = Math.max(4, (int) (W * 0.0045f));
        radius = (int) (18 * u);
        tileW = (W - 2 * marginX - 2 * bandPad - (columns - 1) * gap) / columns;
        int maxW = (int) (W * 0.22f);
        if (tileW > maxW) {
            tileW = maxW;
        }
        tileH = tileW * 9 / 16;

        scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setFocusable(false);
        scroll.setBackgroundColor(Color.BLACK);
        noClip(scroll);

        content = new FrameLayout(this);
        noClip(content);
        scroll.addView(content, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        int nameTop = (int) (H * 0.34f);
        int nameH = (int) (H * 0.12f);

        // Ambient glow, behind everything.
        int glowSize = (int) (W * 0.36f);
        glow = new View(this);
        glow.setAlpha(0f);
        FrameLayout.LayoutParams glp = new FrameLayout.LayoutParams(glowSize, glowSize);
        glp.leftMargin = marginX - (int) (W * 0.06f);
        glp.topMargin = nameTop + nameH / 2 - glowSize / 2;
        content.addView(glow, glp);

        column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        noClip(column);
        content.addView(column, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        buildHeader();

        // Big name above the dock.
        bigName = text("", 60 * u, Color.WHITE, "sans-serif-light");
        bigName.setGravity(Gravity.BOTTOM | Gravity.START);
        bigName.setSingleLine(true);
        bigName.setPadding(marginX + bandPad, 0, marginX, (int) (14 * u));
        column.addView(bigName, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, nameH));

        // Dock band.
        List<View> dockRow = new ArrayList<>();
        rows.add(dockRow);
        LinearLayout band = new LinearLayout(this);
        band.setOrientation(LinearLayout.HORIZONTAL);
        band.setPadding(bandPad, bandPad, bandPad, bandPad);
        GradientDrawable bandBg = new GradientDrawable();
        bandBg.setColor(0xFF151517);
        bandBg.setCornerRadius(radius * 2);
        band.setBackground(bandBg);
        noClip(band);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.leftMargin = marginX;
        column.addView(band, blp);
        for (int i = 0; i < dock.size(); i++) {
            View tile = makeTile(dock.get(i), 1, i, true, null);
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(tileW, tileH);
            if (i < dock.size() - 1) {
                tlp.rightMargin = gap;
            }
            band.addView(tile, tlp);
            dockRow.add(tile);
        }

        // Grid.
        int rowIndex = 2;
        for (int i = 0; i < rest.size(); i += columns) {
            List<View> rowViews = new ArrayList<>();
            rows.add(rowViews);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            noClip(row);
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rlp.leftMargin = marginX + bandPad;
            rlp.topMargin = (rowIndex == 2) ? (int) (H * 0.035f) : (int) (H * 0.012f);
            column.addView(row, rlp);

            int end = Math.min(i + columns, rest.size());
            for (int j = i; j < end; j++) {
                LinearLayout cell = new LinearLayout(this);
                cell.setOrientation(LinearLayout.VERTICAL);
                noClip(cell);
                TextView label = text(rest.get(j).label, 24 * u, Color.WHITE, "sans-serif");
                label.setSingleLine(true);
                label.setEllipsize(android.text.TextUtils.TruncateAt.END);
                label.setGravity(Gravity.CENTER);
                label.setVisibility(View.INVISIBLE);
                View tile = makeTile(rest.get(j), rowIndex, j - i, false, label);
                cell.addView(tile, new LinearLayout.LayoutParams(tileW, tileH));
                LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                        tileW, ViewGroup.LayoutParams.WRAP_CONTENT);
                llp.topMargin = (int) (16 * u);
                cell.addView(label, llp);
                LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                        tileW, ViewGroup.LayoutParams.WRAP_CONTENT);
                if (j < end - 1) {
                    clp.rightMargin = gap;
                }
                row.addView(cell, clp);
                rowViews.add(tile);
            }
            rowIndex++;
        }

        // Room at the bottom so the last row can scroll up.
        View spacer = new View(this);
        column.addView(spacer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (int) (H * 0.45f)));

        // Glass highlight that springs between tiles.
        highlight = new View(this);
        GradientDrawable hg = new GradientDrawable();
        hg.setColor(0x30FFFFFF);
        hg.setCornerRadius(radius + hp);
        hg.setStroke(Math.max(2, (int) (3 * u)), 0x99FFFFFF);
        highlight.setBackground(hg);
        highlight.setAlpha(0f);
        highlight.setScaleX(FOCUS_SCALE);
        highlight.setScaleY(FOCUS_SCALE);
        content.addView(highlight, new FrameLayout.LayoutParams(tileW + 2 * hp, tileH + 2 * hp));

        root.addView(scroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        updateGreeting();
        root.post(new Runnable() {
            @Override
            public void run() {
                focusDefault();
            }
        });
    }

    private void buildHeader() {
        FrameLayout header = new FrameLayout(this);
        noClip(header);
        column.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (int) (H * 0.34f)));

        // Greeting, clock, date (top left).
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        greetView = text("", 30 * u, 0xB3FFFFFF, "sans-serif-light");
        left.addView(greetView);

        TextClock clock = new TextClock(this);
        clock.setFormat12Hour("h:mm");
        clock.setFormat24Hour("HH:mm");
        clock.setTextColor(Color.WHITE);
        clock.setTextSize(TypedValue.COMPLEX_UNIT_PX, 120 * u);
        clock.setTypeface(Typeface.create("sans-serif-thin", Typeface.NORMAL));
        clock.setIncludeFontPadding(false);
        left.addView(clock);

        TextClock date = new TextClock(this);
        date.setFormat12Hour("EEEE, d MMMM");
        date.setFormat24Hour("EEEE, d MMMM");
        date.setTextColor(0x99FFFFFF);
        date.setTextSize(TypedValue.COMPLEX_UNIT_PX, 30 * u);
        date.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        date.setIncludeFontPadding(false);
        left.addView(date);

        FrameLayout.LayoutParams llp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        llp.leftMargin = marginX;
        llp.topMargin = (int) (H * 0.06f);
        header.addView(left, llp);

        // Pills (top right).
        List<View> pillRow = new ArrayList<>();
        rows.add(pillRow);
        LinearLayout pills = new LinearLayout(this);
        pills.setOrientation(LinearLayout.HORIZONTAL);
        noClip(pills);

        final TextView inputs = makePill("Inputs", 0, null);
        inputs.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openInputs(v);
            }
        });
        wifiPill = makePill(wifiLabel(), 1, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                safeStart(new Intent(Settings.ACTION_WIFI_SETTINGS));
            }
        });
        TextView settingsPill = makePill("Settings", 2, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                safeStart(new Intent(Settings.ACTION_SETTINGS));
            }
        });
        View[] ps = new View[]{inputs, wifiPill, settingsPill};
        for (int i = 0; i < ps.length; i++) {
            LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i > 0) {
                plp.leftMargin = (int) (16 * u);
            }
            pills.addView(ps[i], plp);
            pillRow.add(ps[i]);
        }
        FrameLayout.LayoutParams pplp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.END);
        pplp.topMargin = (int) (H * 0.05f);
        pplp.rightMargin = marginX;
        header.addView(pills, pplp);
    }

    private TextView text(String s, float sizePx, int color, String family) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, sizePx);
        t.setTypeface(Typeface.create(family, Typeface.NORMAL));
        t.setIncludeFontPadding(false);
        return t;
    }

    private static void noClip(ViewGroup g) {
        g.setClipChildren(false);
        g.setClipToPadding(false);
    }

    private GradientDrawable pillBg(boolean focused) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(200 * u);
        g.setColor(focused ? 0xF2FFFFFF : 0x33FFFFFF);
        return g;
    }

    private TextView makePill(String label, int col, View.OnClickListener click) {
        TextView t = text(label, 26 * u, Color.WHITE, "sans-serif");
        t.setGravity(Gravity.CENTER);
        t.setPadding((int) (30 * u), (int) (13 * u), (int) (30 * u), (int) (13 * u));
        t.setBackground(pillBg(false));
        t.setFocusable(true);
        t.setClickable(true);
        t.setDefaultFocusHighlightEnabled(false);
        if (click != null) {
            t.setOnClickListener(click);
        }
        Holder h = new Holder();
        h.row = 0;
        h.col = col;
        t.setTag(h);
        t.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean has) {
                onFocusChanged(v, has);
            }
        });
        return t;
    }

    private View makeTile(final AppEntry e, int row, int col, boolean dock, TextView label) {
        FrameLayout tile = new FrameLayout(this);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF2A2A2D);
        bg.setCornerRadius(radius);
        tile.setBackground(bg);
        tile.setClipToOutline(true);
        tile.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius);
            }
        });

        ImageView iv = new ImageView(this);
        if (e.art != null) {
            iv.setImageDrawable(e.art);
        }
        if (e.banner) {
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        } else {
            iv.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            int p = (int) (tileH * 0.18f);
            iv.setPadding(p, p, p, p);
        }
        tile.addView(iv, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        tile.setFocusable(true);
        tile.setClickable(true);
        tile.setDefaultFocusHighlightEnabled(false);
        tile.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                launchApp(e);
            }
        });
        Holder h = new Holder();
        h.row = row;
        h.col = col;
        h.app = e;
        h.label = label;
        h.dock = dock;
        tile.setTag(h);
        tile.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean has) {
                onFocusChanged(v, has);
            }
        });
        return tile;
    }

    // ------------------------------------------------------------------ focus + motion

    private void onFocusChanged(View v, boolean has) {
        Holder h = (Holder) v.getTag();
        if (h == null) {
            return;
        }
        if (h.row == 0) {
            TextView t = (TextView) v;
            t.setBackground(pillBg(has));
            t.setTextColor(has ? Color.BLACK : Color.WHITE);
            if (has) {
                pillCol = h.col;
                hideHighlight();
                setBigName(null);
                scrollToY(0);
            }
            return;
        }
        if (has) {
            v.animate().scaleX(FOCUS_SCALE).scaleY(FOCUS_SCALE).setDuration(170).start();
            if (h.label != null) {
                h.label.setVisibility(View.VISIBLE);
            }
            if (h.dock) {
                dockCol = h.col;
                setBigName(h.app);
            } else {
                setBigName(null);
            }
            lastPkg = h.app.pkg;
            moveHighlight(v);
            if (h.dock) {
                scrollToY(0);
            } else {
                scrollToTile(v);
            }
        } else {
            v.animate().scaleX(1f).scaleY(1f).setDuration(150).start();
            if (h.label != null) {
                h.label.setVisibility(View.INVISIBLE);
            }
        }
    }

    private void setBigName(AppEntry e) {
        if (bigName == null || glow == null) {
            return;
        }
        if (e == null) {
            bigName.setText("");
            glow.animate().alpha(0f).setDuration(200).start();
            return;
        }
        bigName.setText(e.label);
        GradientDrawable g = new GradientDrawable();
        g.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        g.setGradientRadius(W * 0.18f);
        g.setGradientCenter(0.5f, 0.5f);
        g.setColors(new int[]{(e.color & 0x00FFFFFF) | 0x80000000, e.color & 0x00FFFFFF});
        glow.setBackground(g);
        glow.animate().cancel();
        glow.setAlpha(0f);
        glow.animate().alpha(1f).setDuration(350).start();
    }

    private void moveHighlight(final View tile) {
        content.post(new Runnable() {
            @Override
            public void run() {
                int[] o = new int[2];
                offsetIn(tile, content, o);
                float x = o[0] - hp;
                float y = o[1] - hp;
                highlight.animate().cancel();
                if (highlight.getAlpha() < 0.5f) {
                    highlight.setX(x);
                    highlight.setY(y);
                    highlight.animate().alpha(1f).setDuration(150).start();
                } else {
                    highlight.animate().alpha(1f).x(x).y(y).setDuration(280)
                            .setInterpolator(new OvershootInterpolator(1.0f)).start();
                }
            }
        });
    }

    private void hideHighlight() {
        if (highlight != null) {
            highlight.animate().cancel();
            highlight.animate().alpha(0f).setDuration(120).start();
        }
    }

    private static void offsetIn(View v, View ancestor, int[] out) {
        int x = 0;
        int y = 0;
        View c = v;
        while (c != null && c != ancestor) {
            x += c.getLeft();
            y += c.getTop();
            Object p = c.getParent();
            c = (p instanceof View) ? (View) p : null;
        }
        out[0] = x;
        out[1] = y;
    }

    private void scrollToTile(View tile) {
        int[] o = new int[2];
        offsetIn(tile, content, o);
        scrollToY(Math.max(0, o[1] - (int) (H * 0.30f)));
    }

    private void scrollToY(int y) {
        if (scroll != null) {
            scroll.smoothScrollTo(0, y);
        }
    }

    private void focusDefault() {
        if (rows.isEmpty()) {
            return;
        }
        View target = null;
        if (lastPkg != null) {
            for (int r = 1; r < rows.size() && target == null; r++) {
                for (View v : rows.get(r)) {
                    Holder h = (Holder) v.getTag();
                    if (h != null && h.app != null && lastPkg.equals(h.app.pkg)) {
                        target = v;
                        break;
                    }
                }
            }
        }
        if (target == null && rows.size() > 1 && !rows.get(1).isEmpty()) {
            List<View> d = rows.get(1);
            target = d.get(Math.min(dockCol, d.size() - 1));
        }
        if (target == null && !rows.get(0).isEmpty()) {
            target = rows.get(0).get(0);
        }
        if (target != null) {
            target.requestFocus();
        }
    }

    // ------------------------------------------------------------------ D-pad

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getAction() == KeyEvent.ACTION_DOWN && dropdown == null && !rows.isEmpty()) {
            int kc = e.getKeyCode();
            if (kc == KeyEvent.KEYCODE_DPAD_UP || kc == KeyEvent.KEYCODE_DPAD_DOWN
                    || kc == KeyEvent.KEYCODE_DPAD_LEFT || kc == KeyEvent.KEYCODE_DPAD_RIGHT) {
                View f = getCurrentFocus();
                if (f != null && f.getTag() instanceof Holder) {
                    moveFocus((Holder) f.getTag(), kc);
                    return true;
                }
                if (f == null) {
                    focusDefault();
                    return true;
                }
            }
        }
        return super.dispatchKeyEvent(e);
    }

    private void moveFocus(Holder h, int kc) {
        int r = h.row;
        int c = h.col;
        switch (kc) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                c = Math.max(0, c - 1);
                break;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                c = Math.min(rows.get(r).size() - 1, c + 1);
                break;
            case KeyEvent.KEYCODE_DPAD_UP:
                do {
                    r--;
                } while (r >= 0 && rows.get(r).isEmpty());
                if (r < 0) {
                    return;
                }
                if (r == 0) {
                    c = pillCol;
                }
                break;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                do {
                    r++;
                } while (r < rows.size() && rows.get(r).isEmpty());
                if (r >= rows.size()) {
                    return;
                }
                if (h.row == 0) {
                    c = dockCol;
                }
                break;
            default:
                return;
        }
        List<View> target = rows.get(r);
        c = Math.max(0, Math.min(c, target.size() - 1));
        target.get(c).requestFocus();
    }

    // ------------------------------------------------------------------ actions

    private void launchApp(AppEntry e) {
        Intent i = new Intent(Intent.ACTION_MAIN);
        i.setComponent(e.cn);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        safeStart(i);
    }

    private void safeStart(Intent i) {
        try {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Throwable t) {
            Toast.makeText(this, "Can't open that", Toast.LENGTH_SHORT).show();
        }
    }

    private void updateGreeting() {
        if (greetView == null) {
            return;
        }
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        String g;
        if (hour >= 5 && hour < 12) {
            g = "Good morning";
        } else if (hour >= 12 && hour < 17) {
            g = "Good afternoon";
        } else if (hour >= 17 && hour < 22) {
            g = "Good evening";
        } else {
            g = "Good night";
        }
        greetView.setText(g);
    }

    private String wifiLabel() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            Network n = cm.getActiveNetwork();
            NetworkCapabilities nc = n == null ? null : cm.getNetworkCapabilities(n);
            if (nc == null) {
                return "Offline";
            }
            if (nc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                return "Ethernet";
            }
            if (nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                WifiInfo wi = wm == null ? null : wm.getConnectionInfo();
                String s = wi == null ? null : wi.getSSID();
                if (s != null && !s.contains("unknown") && s.length() > 0) {
                    return s.replace("\"", "");
                }
                return "Wi-Fi";
            }
            return "Online";
        } catch (Throwable t) {
            return "Wi-Fi";
        }
    }

    private void refreshWifi() {
        if (wifiPill != null) {
            wifiPill.setText(wifiLabel());
        }
    }

    // ------------------------------------------------------------------ inputs dropdown

    private void openInputs(View pill) {
        if (dropdown != null) {
            closeDropdown(true);
            return;
        }
        final List<String> labels = new ArrayList<>();
        final List<Intent> intents = new ArrayList<>();
        Intent live = null;
        try {
            TvInputManager tim = (TvInputManager) getSystemService(Context.TV_INPUT_SERVICE);
            if (tim != null) {
                for (TvInputInfo info : tim.getTvInputList()) {
                    if (info.isHidden(this)) {
                        continue;
                    }
                    CharSequence l = info.loadCustomLabel(this);
                    if (l == null || l.length() == 0) {
                        l = info.loadLabel(this);
                    }
                    if (info.isPassthroughInput()) {
                        labels.add(String.valueOf(l));
                        intents.add(new Intent(Intent.ACTION_VIEW,
                                TvContract.buildChannelUriForPassthroughInput(info.getId())));
                    } else if (info.getType() == TvInputInfo.TYPE_TUNER && live == null) {
                        live = new Intent(Intent.ACTION_VIEW, TvContract.Channels.CONTENT_URI);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        if (live != null) {
            labels.add(0, "Live TV");
            intents.add(0, live);
        }
        if (labels.isEmpty()) {
            Toast.makeText(this, "No inputs found", Toast.LENGTH_SHORT).show();
            return;
        }

        int count = labels.size();
        final LinearLayout dd = new LinearLayout(this);
        dd.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xF01C1C1E);
        bg.setCornerRadius(28 * u);
        bg.setStroke(Math.max(1, (int) (2 * u)), 0x33FFFFFF);
        dd.setBackground(bg);
        int pad = (int) (10 * u);
        dd.setPadding(pad, pad, pad, pad);

        final int[] ids = new int[count];
        final List<TextView> items = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            final TextView it = text(labels.get(i), 28 * u, Color.WHITE, "sans-serif");
            it.setPadding((int) (24 * u), (int) (14 * u), (int) (24 * u), (int) (14 * u));
            it.setFocusable(true);
            it.setClickable(true);
            it.setDefaultFocusHighlightEnabled(false);
            it.setId(View.generateViewId());
            ids[i] = it.getId();
            final int idx = i;
            it.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    closeDropdown(false);
                    safeStart(intents.get(idx));
                }
            });
            it.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                @Override
                public void onFocusChange(View v, boolean has) {
                    GradientDrawable g = new GradientDrawable();
                    g.setCornerRadius(18 * u);
                    g.setColor(has ? 0xF2FFFFFF : 0x00000000);
                    v.setBackground(g);
                    ((TextView) v).setTextColor(has ? Color.BLACK : Color.WHITE);
                }
            });
            items.add(it);
            dd.addView(it, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        for (int i = 0; i < count; i++) {
            TextView it = items.get(i);
            it.setNextFocusUpId(i == 0 ? ids[i] : ids[i - 1]);
            it.setNextFocusDownId(i == count - 1 ? ids[i] : ids[i + 1]);
            it.setNextFocusLeftId(ids[i]);
            it.setNextFocusRightId(ids[i]);
        }

        int width = (int) (360 * u);
        int[] a = new int[2];
        int[] r = new int[2];
        pill.getLocationOnScreen(a);
        root.getLocationOnScreen(r);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                width, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = a[1] - r[1] + pill.getHeight() + (int) (10 * u);
        lp.leftMargin = Math.max(0, Math.min(a[0] - r[0], W - width - marginX));
        dropdown = dd;
        root.addView(dd, lp);
        items.get(0).requestFocus();
    }

    private void closeDropdown(boolean refocus) {
        if (dropdown != null) {
            root.removeView(dropdown);
            dropdown = null;
        }
        if (refocus && !rows.isEmpty() && !rows.get(0).isEmpty()) {
            rows.get(0).get(0).requestFocus();
        }
    }
}
