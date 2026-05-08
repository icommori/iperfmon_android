package xzr.perfmon;

import static xzr.perfmon.WaveformView.MAX_TEMP;

import android.annotation.SuppressLint;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.res.AssetFileDescriptor;
import android.content.res.Configuration;
import android.graphics.PixelFormat;
import android.graphics.SurfaceTexture;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.ColorRes;
import androidx.core.content.ContextCompat;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class FloatingWindow extends Service {
    enum DisplayMode {
        TEXT,
        GRAPH
    }

    DisplayMode currentMode = DisplayMode.TEXT;

    static String TAG = "FloatingWindow";
    public static boolean doExit = true;
    WindowManager.LayoutParams params;
    WindowManager windowManager;
    LinearLayout rootLayout; // New root layout
    LinearLayout main;
    TextView[] line;
    int linen;
    Handler uiRefresher;

    ImageButton infoBtn;
    View infoDialogView;

    // Video components
    FlowLayout videoFlowLayout;
    List<VideoSession> videoSessions = new ArrayList<>();
    ImageButton playBtn;
    ArrayList<String> videoAssets = new ArrayList<>();
    java.util.Random random = new java.util.Random();

    // 實例化硬體監測器
    HardwareMonitor hardwareMonitor = new HardwareMonitor();
    LinearLayout textContainer;
    WaveformView waveView;
    TextView tvTempLegend;

    class VideoSession {
        View rootView;
        TextureView textureView;
        MediaPlayer mediaPlayer;
        int currentVideoIndex = 0;
        boolean isPlaying = false;
        float aspectRatio = 16f/9f;

        void playNext() {
            // Randomly select next video
            if (videoAssets.size() > 0) {
                int nextIndex = random.nextInt(videoAssets.size());
                // Avoid repeating same video if > 1 available
                if (videoAssets.size() > 1 && nextIndex == currentVideoIndex) {
                    nextIndex = (currentVideoIndex + 1) % videoAssets.size();
                }
                play(nextIndex);
            }
        }

        void play(int index) {
            if (videoAssets.isEmpty()) return;
            currentVideoIndex = index;
            try {
                mediaPlayer.reset();
                AssetFileDescriptor afd = getAssets().openFd(videoAssets.get(currentVideoIndex));
                mediaPlayer.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
                mediaPlayer.prepare();
                mediaPlayer.start();
                isPlaying = true;
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private void initVideoAssets() {
         try {
            String[] files = getAssets().list("video");
            if (files != null) {
                for (String f : files) {
                    if (f.endsWith(".mp4")) {
                        videoAssets.add("video/" + f);
                    }
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void initVideoView() {
        videoFlowLayout = new FlowLayout(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, 
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
        videoFlowLayout.setLayoutParams(lp);
        videoFlowLayout.setVisibility(View.GONE);
        rootLayout.addView(videoFlowLayout);
        initVideoAssets();
    }

    private void updateAllVideoSizes() {
        // 1. Calculate Available Width for the video area
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        int globalMargin = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 10, getResources().getDisplayMetrics());
        int roundingBuffer = 10;

        int availableSpace;
        if (isPortrait()) {
            // portrait: videos stack below panel, use full screen width
            availableSpace = screenWidth - globalMargin - roundingBuffer;
        } else {
            // landscape: videos sit to the right of the panel
            int mainWidth = 0;
            if (main.getVisibility() != View.GONE) {
                mainWidth = main.getWidth();
                if (mainWidth == 0) {
                    main.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                                 View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
                    mainWidth = main.getMeasuredWidth();
                }
            }
            availableSpace = screenWidth - mainWidth - globalMargin - roundingBuffer;
            if (availableSpace < 100) availableSpace = screenWidth;
        }

        // Update FlowLayout restriction
        videoFlowLayout.setMaxWidth(availableSpace);

        if (videoSessions.isEmpty()) return;

        int count = videoSessions.size();
        
        // Fix: Limit columns to 5 for sizing calculation. 
        // This ensures that if we have 6+ items, each item is sized to fit 5 per row, forcing the 6th to wrap.
        int columns = Math.min(count, 5);

        // Item margins: 8dp left + 8dp right
        // We set LayoutParams margins to 8 each, but FrameLayout/TextureView handling might vary.
        // Let's assume the margins in LayoutParams are used by FlowLayout.
        // In addNewVideo: lp.setMargins(8, 8, 8, 8); -> 16px total horizontal margin.
        int itemMarginHorizontal = 16; 
        int totalMarginSpace = itemMarginHorizontal * columns;
        
        int availableForContent = availableSpace - totalMarginSpace;
        
        // Default: 1/6 screen
        int preferredWidth = screenWidth / 6;
        
        // Calculate potential width if we fit perfectly in one row (up to 5 items)
        int shrinkWidth = availableForContent / columns;
        
        int targetWidth;
        if (shrinkWidth < preferredWidth) {
            // We need to shrink to fit
            targetWidth = shrinkWidth;
        } else {
            // We have enough space for preferred width
            targetWidth = preferredWidth;
        }
        
        // Min width constraint (e.g. 50dp)
        int minWidth = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 50, getResources().getDisplayMetrics());
        
        // If shrinking goes below min width, we capitulate and wrap (enforce min width)
        if (targetWidth < minWidth) {
            targetWidth = minWidth;
        }

        // Apply to all videos
        for (VideoSession session : videoSessions) {
            ViewGroup.LayoutParams vlp = session.rootView.getLayoutParams();
            if (vlp.width != targetWidth) {
                vlp.width = targetWidth;
                
                // Maintain aspect ratio
                float ratio = session.aspectRatio;
                if (ratio <= 0) ratio = 16f/9f;
                // Avoid div by zero
                vlp.height = (int) (targetWidth / ratio);
                
                session.rootView.setLayoutParams(vlp);
            }
        }
        
        videoFlowLayout.requestLayout();
        windowManager.updateViewLayout(rootLayout, params);
    }

    private void toggleVideo() {
        if (videoFlowLayout.getVisibility() == View.VISIBLE) {
            closeAllVideos();
        } else {
            videoFlowLayout.setVisibility(View.VISIBLE);
            playBtn.setImageResource(R.drawable.ic_btn_pause);
            updateAllVideoSizes();
            addNewVideo();
        }
        windowManager.updateViewLayout(rootLayout, params);
    }

    private void closeAllVideos() {
        for (VideoSession s : videoSessions) {
            if (s.mediaPlayer != null) s.mediaPlayer.release();
        }
        videoSessions.clear();
        videoFlowLayout.removeAllViews();
        videoFlowLayout.setVisibility(View.GONE);
        playBtn.setImageResource(R.drawable.ic_btn_play);
    }

    private void pauseAllVideos() {
        for (VideoSession s : videoSessions) {
            if (s.mediaPlayer != null && s.mediaPlayer.isPlaying()) {
                s.mediaPlayer.pause();
                s.isPlaying = false;
            }
        }
    }

    private void resumeAllVideos() {
        for (VideoSession s : videoSessions) {
            if (s.mediaPlayer != null && !s.isPlaying) {
                s.mediaPlayer.start();
                s.isPlaying = true;
            }
        }
    }
    private GradientDrawable createRoundedBg(@ColorRes int colorRes, float radiusDp) {
        GradientDrawable gd = new GradientDrawable();
        gd.setShape(GradientDrawable.RECTANGLE);
        gd.setColor(getResources().getColor(colorRes));

        float cornerRadius = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                radiusDp,
                getResources().getDisplayMetrics()
        );
        gd.setCornerRadius(cornerRadius);

        return gd;
    }

    private void addNewVideo() {
        VideoSession session = new VideoSession();
        
        FrameLayout container = new FrameLayout(this);
        
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        // Initial setup - default to preferred 1/6 width
        int width = screenWidth / 6; 
        int height = (int) (width * 9f / 16f); 
        
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(width, height);
        lp.setMargins(8, 8, 8, 8); 
        container.setLayoutParams(lp);

        TextureView textureView = new TextureView(this);
        float radius = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 15, getResources().getDisplayMetrics());
        textureView.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override
            public void getOutline(View view, android.graphics.Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        });
        textureView.setClipToOutline(true);
        container.addView(textureView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        
        session.textureView = textureView;
        session.rootView = container;
        session.mediaPlayer = new MediaPlayer();

        int vBtnSize = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 30, getResources().getDisplayMetrics());
        int vBtnPad  = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 3, getResources().getDisplayMetrics());

        ImageButton addBtn = new ImageButton(this);
        addBtn.setImageResource(R.drawable.ic_btn_add);
        addBtn.setBackground(null);
        addBtn.setPadding(vBtnPad, vBtnPad, vBtnPad, vBtnPad);
        addBtn.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        FrameLayout.LayoutParams addLp = new FrameLayout.LayoutParams(vBtnSize, vBtnSize);
        addLp.gravity = Gravity.TOP | Gravity.START;
        addBtn.setOnClickListener(v -> addNewVideo());
        container.addView(addBtn, addLp);

        ImageButton closeBtn = new ImageButton(this);
        closeBtn.setImageResource(R.drawable.ic_btn_close);
        closeBtn.setBackground(null);
        closeBtn.setPadding(vBtnPad, vBtnPad, vBtnPad, vBtnPad);
        closeBtn.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        FrameLayout.LayoutParams closeLp = new FrameLayout.LayoutParams(vBtnSize, vBtnSize);
        closeLp.gravity = Gravity.TOP | Gravity.END;
        closeBtn.setOnClickListener(v -> removeVideo(session));
        container.addView(closeBtn, closeLp);

        ImageButton resizeHandle = new ImageButton(this);
        resizeHandle.setImageResource(R.drawable.ic_btn_resize);
        resizeHandle.setBackground(null);
        resizeHandle.setPadding(vBtnPad, vBtnPad, vBtnPad, vBtnPad);
        resizeHandle.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        FrameLayout.LayoutParams handleLp = new FrameLayout.LayoutParams(vBtnSize, vBtnSize);
        handleLp.gravity = Gravity.BOTTOM | Gravity.END;
        container.addView(resizeHandle, handleLp);

        resizeHandle.setOnTouchListener(new View.OnTouchListener() {
            private int initialX;
            private int initialWidth;
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        initialX = (int) event.getRawX();
                        initialWidth = container.getLayoutParams().width;
                        v.getParent().requestDisallowInterceptTouchEvent(true);
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        int deltaX = (int) event.getRawX() - initialX;
                        int newWidth = initialWidth + deltaX;
                        
                        // Resizing manually overrides the auto-size momentarily
                        // But note: updateAllVideoSizes will reset this if adding/removing. 
                        // User prompt implies "sync shrink" on add/remove, but manual resize is allowed?
                        // Let's allow manual resize.
                        
                        int minWidth = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 50, getResources().getDisplayMetrics());
                        if (newWidth < minWidth) newWidth = minWidth;
                        
                        int newHeight = (int) (newWidth / session.aspectRatio);
                        
                        ViewGroup.LayoutParams lp = container.getLayoutParams();
                        lp.width = newWidth;
                        lp.height = newHeight;
                        container.setLayoutParams(lp);
                        
                        windowManager.updateViewLayout(rootLayout, params);
                        return true;
                }
                return false;
            }
        });

        textureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture surface, int w, int h) {
                session.mediaPlayer.setSurface(new android.view.Surface(surface));
                session.play(random.nextInt(videoAssets.size())); 
            }
            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {}
            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) { return false; }
            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture surface) {}
        });

        session.mediaPlayer.setOnCompletionListener(mp -> session.playNext());
        session.mediaPlayer.setOnVideoSizeChangedListener((mp, w, h) -> {
             if (w > 0 && h > 0) {
                 session.aspectRatio = (float) w / h;
                 // Sync height only
                 container.post(() -> {
                     ViewGroup.LayoutParams params = container.getLayoutParams();
                     params.height = (int) (params.width / session.aspectRatio);
                     container.setLayoutParams(params);
                     windowManager.updateViewLayout(rootLayout, FloatingWindow.this.params);
                 });
             }
        });

        videoSessions.add(session);
        videoFlowLayout.addView(container);
        
        // Ensure constraints are set and layout is refreshed
        updateAllVideoSizes();
        
        windowManager.updateViewLayout(rootLayout, params);
    }

    private void removeVideo(VideoSession session) {
        if (session.mediaPlayer != null) {
            session.mediaPlayer.release();
        }
        videoFlowLayout.removeView(session.rootView);
        videoSessions.remove(session);

        if (videoSessions.isEmpty()) {
            videoFlowLayout.setVisibility(View.GONE);
            playBtn.setImageResource(R.drawable.ic_btn_play);
        } else {
            updateAllVideoSizes();
        }
        windowManager.updateViewLayout(rootLayout, params);
    }


    @SuppressLint("ClickableViewAccessibility")
    void init() {
        // 獲取當前 CPU 核心數
        int cpuCount = Runtime.getRuntime().availableProcessors();
        linen = 0;
        linen += 4; // CPU Total, GPU, Temp, IPS
        linen += cpuCount;

        // 視窗參數設定
        params = new WindowManager.LayoutParams();
        windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        params.type = (Build.VERSION.SDK_INT >= 26) ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_SYSTEM_ALERT;

        params.format = PixelFormat.RGBA_8888;
        params.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        params.gravity = Gravity.LEFT | Gravity.CENTER_VERTICAL;

        int marginTopPx = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 30, getResources().getDisplayMetrics());
        int marginLedfyPx = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 10, getResources().getDisplayMetrics());
        params.x = marginLedfyPx; 
        
        params.width = WindowManager.LayoutParams.WRAP_CONTENT;
        params.height = WindowManager.LayoutParams.WRAP_CONTENT;

        // Root Layout — orientation follows device orientation
        rootLayout = new LinearLayout(this);
        rootLayout.setOrientation(isPortrait() ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        rootLayout.setVerticalGravity(Gravity.CENTER_VERTICAL); 

        // Main Stats Layout (Vertical)
        main = new LinearLayout(this);
        main.setOrientation(LinearLayout.VERTICAL);
        int minWidthPx = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 230, getResources().getDisplayMetrics());
        main.setMinimumWidth(minWidthPx);
        android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
        gd.setColor(getResources().getColor(R.color.floating_window_backgrouns));
        float cornerRadius = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 15, getResources().getDisplayMetrics());
        gd.setCornerRadius(cornerRadius);
        main.setBackground(gd);

        int paddingPx = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 12, getResources().getDisplayMetrics());
        main.setPadding(paddingPx, 0, paddingPx, paddingPx);
        
        // Header
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);

        int btnSizePx = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 36, getResources().getDisplayMetrics());
        int btnMarginPx = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 4, getResources().getDisplayMetrics());
        int btnPadPx = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 4, getResources().getDisplayMetrics());

        ImageButton closeBtn = new ImageButton(this);
        closeBtn.setImageResource(R.drawable.ic_btn_close);
        closeBtn.setBackground(null);
        closeBtn.setPadding(btnPadPx, btnPadPx, btnPadPx, btnPadPx);
        closeBtn.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams closeLp = new LinearLayout.LayoutParams(btnSizePx, btnSizePx);
        closeLp.rightMargin = btnMarginPx;
        closeBtn.setLayoutParams(closeLp);
        closeBtn.setOnClickListener(v -> stopSelf());

        ImageButton toggleBtn = new ImageButton(this);
        toggleBtn.setImageResource(R.drawable.ic_btn_graph);
        toggleBtn.setBackground(null);
        toggleBtn.setPadding(btnPadPx, btnPadPx, btnPadPx, btnPadPx);
        toggleBtn.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams toggleLp = new LinearLayout.LayoutParams(btnSizePx, btnSizePx);
        toggleLp.rightMargin = btnMarginPx;
        toggleBtn.setLayoutParams(toggleLp);

        // Play Button
        playBtn = new ImageButton(this);
        playBtn.setImageResource(R.drawable.ic_btn_play);
        playBtn.setBackground(null);
        playBtn.setPadding(btnPadPx, btnPadPx, btnPadPx, btnPadPx);
        playBtn.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams playLp = new LinearLayout.LayoutParams(btnSizePx, btnSizePx);
        playLp.rightMargin = btnMarginPx;
        playBtn.setLayoutParams(playLp);
        playBtn.setOnClickListener(v -> toggleVideo());

        infoBtn = new ImageButton(this);
        infoBtn.setImageResource(R.drawable.ic_btn_info);
        infoBtn.setBackground(null);
        infoBtn.setPadding(btnPadPx, btnPadPx, btnPadPx, btnPadPx);
        infoBtn.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams infoLp = new LinearLayout.LayoutParams(btnSizePx, btnSizePx);
        infoLp.rightMargin = btnMarginPx;
        infoBtn.setLayoutParams(infoLp);
        infoBtn.setOnClickListener(v -> showInfoDialog());

        header.addView(closeBtn);
        header.addView(toggleBtn);
        header.addView(playBtn);
        header.addView(infoBtn);

        // Legend
        LinearLayout legend = new LinearLayout(this);
        legend.setOrientation(LinearLayout.HORIZONTAL);
        legend.setPadding(8, 0, 0, 0);
        legend.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams legendLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        legendLp.rightMargin = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 8, getResources().getDisplayMetrics());

        TextView tvCpu = new TextView(this);
        tvCpu.setText("CPU");
        tvCpu.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        tvCpu.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        tvCpu.setTextColor(getResources().getColor(R.color.cpu_main));
        tvCpu.setLayoutParams(legendLp);

        TextView tvGpu = new TextView(this);
        tvGpu.setText("GPU");
        tvGpu.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        tvGpu.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        tvGpu.setTextColor(getResources().getColor(R.color.gpu_main));
        tvGpu.setLayoutParams(legendLp);

        tvTempLegend = new TextView(this);
        tvTempLegend.setText("TEMP");
        tvTempLegend.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        tvTempLegend.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        tvTempLegend.setTextColor(getResources().getColor(R.color.temp_main));
        tvTempLegend.setLayoutParams(legendLp);

        legend.addView(tvCpu);
        legend.addView(tvGpu);
        legend.addView(tvTempLegend);
        header.addView(legend);
        legend.setVisibility(View.GONE);

        main.addView(header);
        
        textContainer = new LinearLayout(this);
        textContainer.setOrientation(LinearLayout.VERTICAL);

        waveView = new WaveformView(this);
        int waveWidth = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 300, getResources().getDisplayMetrics());
        int waveHeight = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 150, getResources().getDisplayMetrics());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(waveWidth, waveHeight);
        waveView.setLayoutParams(lp);
        waveView.setVisibility(View.GONE);
        main.addView(waveView);
        main.addView(textContainer);

        final boolean[] waveMode = {false};
        toggleBtn.setOnClickListener(v -> {
            waveMode[0] = !waveMode[0];
            textContainer.setVisibility(waveMode[0] ? View.GONE : View.VISIBLE);
            waveView.setVisibility(waveMode[0] ? View.VISIBLE : View.GONE);
            legend.setVisibility(waveMode[0] ? View.VISIBLE : View.GONE);
            infoBtn.setVisibility(waveMode[0] ? View.GONE : View.VISIBLE);

            if (waveMode[0]) {
                header.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED), 
                               View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
                int headerWidth = header.getMeasuredWidth();
                int mainWidth = main.getWidth();
                int targetWidth = Math.max(headerWidth, mainWidth);
                int minWidth = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 250, getResources().getDisplayMetrics());
                if (targetWidth < minWidth) targetWidth = minWidth;
                ViewGroup.LayoutParams wlp = waveView.getLayoutParams();
                wlp.width = targetWidth;
                waveView.setLayoutParams(wlp);
            }
             windowManager.updateViewLayout(rootLayout, params);
        });

        rootLayout.setOnTouchListener(new View.OnTouchListener() {
            private int x, y;
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        x = (int) event.getRawX();
                        y = (int) event.getRawY();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        int nowX = (int) event.getRawX();
                        int nowY = (int) event.getRawY();
                        params.x += (nowX - x);
                        params.y += (nowY - y);
                        x = nowX;
                        y = nowY;
                        windowManager.updateViewLayout(rootLayout, params);
                        return true;
                }
                return false;
            }
        });

        rootLayout.addView(main, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        initVideoView();
        windowManager.addView(rootLayout, params);
    }

    void monitorInit() {

        LinearLayout.LayoutParams layoutParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        layoutParams.bottomMargin = 2; // 給每一行一點點間距，避免文字邊緣重疊
        line = new TextView[linen];
        for (int i = 0; i < linen; i++) {
            line[i] = new TextView(this);
            line[i].setTextColor(getResources().getColor(R.color.white));
            line[i].setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
            line[i].getPaint().setAntiAlias(true);
            line[i].getPaint().setSubpixelText(false); // 關掉 RGB 次像素
            line[i].getPaint().setFilterBitmap(true);
            line[i].setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
            line[i].setLayoutParams(layoutParams);
            textContainer.addView(line[i]);
        }

        uiRefresher = new Handler(msg -> {
            updateUI();
            return true;
        });

        // 啟動更新執行緒 (500ms：CPU/GPU load 即時；freq/temp 在 HardwareMonitor 內部 throttle)
        new Thread(() -> {
            while (!doExit) {
                try {
                    uiRefresher.sendEmptyMessage(0);
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }).start();
    }

    private void updateUI() {
        int currentIndex = 0;

        // 1. 讀取並顯示 CPU 負載（單次 /proc/stat 同時取 total + per-core）
        CpuStats cpuStats = hardwareMonitor.getCpuStats();
        int totalLoad = cpuStats.getTotal();
        if (currentIndex < linen) {
            line[currentIndex].setText("CPU Total: " + totalLoad + "%");
            line[currentIndex].setTextColor(getResources().getColor(R.color.cpu_main));
            currentIndex++;
        }
        for (Map.Entry<Integer, Integer> entry : cpuStats.getPerCore().entrySet()) {
            if (currentIndex < linen) {
                line[currentIndex].setText("CPU " + entry.getKey() + ": " + entry.getValue() + "%");
                line[currentIndex].setTextColor(getResources().getColor(R.color.cpu_sub));
                currentIndex++;
            }
        }

        // 2. 讀取並顯示 GPU 資訊
        int gFreq = hardwareMonitor.getGpuFreqMHz();
        int gLoad = hardwareMonitor.getGpuLoadEma();
        String gpuText = "GPU: ";
        gpuText += (gFreq > 0 ? gFreq + "MHz " : "N/A ");
        gpuText += (gLoad >= 0 ? gLoad + "%" : "N/A");

        if (currentIndex < linen) {
            line[currentIndex].setText(gpuText);
            line[currentIndex].setTextColor(getResources().getColor(R.color.gpu_main));
            currentIndex++;
        }


        // 3. 讀取並顯示 溫度
        double temp = hardwareMonitor.getCpuTemperature(this);
        if (currentIndex < linen) {
            line[currentIndex].setText("Temp: " + String.format("%.1f", temp) + " ℃");
            line[currentIndex].setTextColor(getResources().getColor(R.color.temp_main));
            currentIndex++;
        }

        // 4. 讀取並顯示 APU IPS
        int ips = hardwareMonitor.getApuIpsRaw();
        if (currentIndex < linen) {
            line[currentIndex].setText("APU IPS: " + ips);
            line[currentIndex].setTextColor(getResources().getColor(R.color.apu_ips));
            currentIndex++;
        }
        // ===== Legend TEMP 顏色同步 =====
        if (tvTempLegend != null) {
            if (temp >= MAX_TEMP) {
                tvTempLegend.setTextColor(ContextCompat.getColor(this,R.color.temp_hot));
            } else {
                tvTempLegend.setTextColor(ContextCompat.getColor(this,R.color.temp_main));
            }
        }

        waveView.addSample(totalLoad, gLoad, (float)temp);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        doExit = false;
        init();
        monitorInit();
    }

    @SuppressLint("ClickableViewAccessibility")
    private void showInfoDialog() {
        if (infoDialogView != null) return;

        // Full-screen transparent overlay
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xCC000000);

        // Centered card
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        int cardPad = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 24, getResources().getDisplayMetrics());
        card.setPadding(cardPad, cardPad, cardPad, cardPad);

        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(0xFFFFFFFF);
        cardBg.setCornerRadius(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 16, getResources().getDisplayMetrics()));
        card.setBackground(cardBg);

        int cardWidth = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 260, getResources().getDisplayMetrics());
        FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(cardWidth, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.gravity = Gravity.CENTER;
        card.setLayoutParams(cardLp);

        // Company name
        TextView tvName = new TextView(this);
        tvName.setText("InnoComm Mobile Technology Corp");
        tvName.setTextColor(0xFF333333);
        tvName.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tvName.setTypeface(Typeface.DEFAULT_BOLD);
        tvName.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nameLp.bottomMargin = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 12, getResources().getDisplayMetrics());
        tvName.setLayoutParams(nameLp);
        card.addView(tvName);

        // QR code
        int qrSizeDp = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 200, getResources().getDisplayMetrics());
        ImageView ivQr = new ImageView(this);
        ivQr.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams qrLp = new LinearLayout.LayoutParams(qrSizeDp, qrSizeDp);
        ivQr.setLayoutParams(qrLp);
        android.graphics.Bitmap qrBitmap = QrCodeGenerator.generate("https://www.innocomm.com/", 400);
        if (qrBitmap != null) ivQr.setImageBitmap(qrBitmap);
        card.addView(ivQr);

        // Logo
        ImageView ivLogo = new ImageView(this);
        ivLogo.setImageResource(R.drawable.logo);
        ivLogo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int logoH = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 60, getResources().getDisplayMetrics());
        LinearLayout.LayoutParams logoLp = new LinearLayout.LayoutParams(qrSizeDp, logoH);
        logoLp.topMargin = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 8, getResources().getDisplayMetrics());
        ivLogo.setLayoutParams(logoLp);
        card.addView(ivLogo);

        // URL hint
        TextView tvUrl = new TextView(this);
        tvUrl.setText("www.innocomm.com");
        tvUrl.setTextColor(0xFF888888);
        tvUrl.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        tvUrl.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams urlLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        urlLp.topMargin = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 4, getResources().getDisplayMetrics());
        tvUrl.setLayoutParams(urlLp);
        card.addView(tvUrl);

        root.addView(card);
        root.setOnTouchListener((v, e) -> { dismissInfoDialog(); return true; });

        WindowManager.LayoutParams dialogParams = new WindowManager.LayoutParams();
        dialogParams.type = (Build.VERSION.SDK_INT >= 26)
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_SYSTEM_ALERT;
        dialogParams.format = PixelFormat.RGBA_8888;
        dialogParams.width = WindowManager.LayoutParams.MATCH_PARENT;
        dialogParams.height = WindowManager.LayoutParams.MATCH_PARENT;
        dialogParams.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;

        infoDialogView = root;
        windowManager.addView(infoDialogView, dialogParams);
    }

    private void dismissInfoDialog() {
        if (infoDialogView == null) return;
        try { windowManager.removeView(infoDialogView); } catch (Exception ignored) {}
        infoDialogView = null;
    }

    private boolean isPortrait() {
        return getResources().getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT;
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (rootLayout == null) return;
        rootLayout.setOrientation(
            newConfig.orientation == Configuration.ORIENTATION_PORTRAIT
                ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        if (videoFlowLayout.getVisibility() == View.VISIBLE) {
            updateAllVideoSizes();
        }
        windowManager.updateViewLayout(rootLayout, params);
    }

    @Override
    public void onDestroy() {
        doExit = true;
        dismissInfoDialog();
        closeAllVideos();

        if (windowManager != null && rootLayout != null) {
            try {
                windowManager.removeView(rootLayout);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}