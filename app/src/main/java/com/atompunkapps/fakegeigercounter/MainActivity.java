package com.atompunkapps.fakegeigercounter;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;

import android.annotation.SuppressLint;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.media.AudioAttributes;
import android.media.SoundPool;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.text.Html;
import android.util.DisplayMetrics;
import android.view.View;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;

import com.facebook.ads.AdSize;
import com.facebook.ads.AdView;
import com.facebook.ads.AudienceNetworkAds;

public class MainActivity extends AppCompatActivity implements SensorEventListener, Runnable {
    private static final int MAX_STREAMS = 10;

    private static final float SAMPLE_RATE = 1.5f;

//    private static final float[] MIN_LEVELS = { 0.16f, 1f, 130f, 1f, 1f, 1f };
//    private static final float[] MAX_LEVELS = { 0.39f, 1f, 2610f, 1f, 1f, 1f };
    private static final long[] MIN_LEVELS = { 300, 13, 10, 8, 3 };
    private static final long[] MAX_LEVELS = { 10000, 2500, 800, 350, 100 };

    private Sensor sensor;
    private SensorManager sensorManager;

    private SoundPool mSoundPool;
    private int id;

    private Thread thread;
    private boolean loaded = false;

    private long min = MIN_LEVELS[0];
    private long max = MAX_LEVELS[0];

    private int count;

    private float volume = 0.6f;

    private boolean stop;

    private CheckBox magCheck;

    private AdView adView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        AudienceNetworkAds.initialize(this);

        adView = new AdView(this, SafeStore.PLACEMENT_ID, AdSize.BANNER_HEIGHT_50);

        ((FrameLayout) findViewById(R.id.banner_container)).addView(adView);

        adView.loadAd();

        final SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);

        if(preferences.getBoolean("first_run", true)) {
            new AlertDialog.Builder(this)
                    .setTitle("Welcome")
                    .setMessage(
                            Html.fromHtml(
                                    "<br><b>When using the magnetometer, try holding your phone near a magnetic source such as a speaker.</b><br>" +
                                            "<br><br>" +
                                            "<br><b>LCD font derived from:</b><br><a href=\"https://www.fontspace.com/crystal-font-f746\">https://www.fontspace.com/crystal-font-f746</a><br><br>"
                            )
                    )
                    .setPositiveButton("Ok", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            preferences.edit().putBoolean("first_run", false).apply();
                        }
                    })
                    .create()
                    .show();
        }

        final SeekBar doseBar = findViewById(R.id.dose_bar);
        doseBar.setMax(MIN_LEVELS.length - 1);
        doseBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            ImageView doseIcon = findViewById(R.id.dose_icon);
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                min = MIN_LEVELS[progress];
                max = MAX_LEVELS[progress];
                if(thread != null) {
                    thread.interrupt();
                }

                preferences.edit().putInt("dose", progress).apply();

                if(progress == 0) {
                    doseIcon.setImageResource(R.drawable.baseline_house_white_36);
                }
                else {
                    doseIcon.setImageResource(R.drawable.radiation);
                }
            }
            @Override
            public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) { }
        });
        doseBar.setProgress(preferences.getInt("dose", 0));


        SeekBar volumeBar = findViewById(R.id.volume_bar);
        volumeBar.setMax(100);
        volumeBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            ImageView volumeIcon = findViewById(R.id.volume_icon);
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if(progress == 0) {
                    volumeIcon.setImageResource(R.drawable.baseline_volume_off_white_36);
                }
                else if(progress <= 25) {
                    volumeIcon.setImageResource(R.drawable.baseline_volume_mute_white_36);
                }
                else if(progress <= 75) {
                    volumeIcon.setImageResource(R.drawable.baseline_volume_down_white_36);
                }
                else {
                    volumeIcon.setImageResource(R.drawable.baseline_volume_up_white_36);
                }
                preferences.edit().putInt("volume", progress).apply();
                volume = progress / 100f;
            }
            @Override
            public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) { }
        });
        volumeBar.setProgress(preferences.getInt("volume", 60));

        magCheck = findViewById(R.id.mag_check);
        magCheck.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                doseBar.setEnabled(!isChecked);
                preferences.edit().putBoolean("use_mag", isChecked).apply();
                if(isChecked) {
                    if(sensorManager != null) {
                        sensorManager.registerListener(MainActivity.this, sensor, SensorManager.SENSOR_DELAY_FASTEST);
                        thread.interrupt();
                    }
                }
                else {
                    if(sensorManager != null) {
                        sensorManager.unregisterListener(MainActivity.this);
                    }

                    min = MIN_LEVELS[doseBar.getProgress()];
                    max = MAX_LEVELS[doseBar.getProgress()];
                }
            }
        });
        magCheck.setChecked(preferences.getBoolean("use_mag", false));

        final DrawerLayout drawerLayout = findViewById(R.id.drawer_layout);
        final ImageButton pullButton = findViewById(R.id.pull_button);
        pullButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                drawerLayout.openDrawer(GravityCompat.END, true);
            }
        });
        drawerLayout.addDrawerListener(new DrawerLayout.DrawerListener() {
            float offset = 5 * getResources().getDisplayMetrics().xdpi / DisplayMetrics.DENSITY_DEFAULT;
            @Override
            public void onDrawerSlide(@NonNull View drawerView, float slideOffset) {
                pullButton.setX(drawerView.getX() - pullButton.getWidth() + offset);
            }
            @Override public void onDrawerOpened(@NonNull View drawerView) {}
            @Override public void onDrawerClosed(@NonNull View drawerView) {}
            @Override public void onDrawerStateChanged(int newState) { }
        });


        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        if (sensorManager != null) {
            sensor = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED);
        }

        mSoundPool = new SoundPool.Builder()
                .setMaxStreams(MAX_STREAMS)
                .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).build())
                .build();

        mSoundPool.setOnLoadCompleteListener(new SoundPool.OnLoadCompleteListener() {
            @Override
            public void onLoadComplete(SoundPool soundPool, int sampleId, int status) {
                if(status == 0) {
                    loaded = true;
                }
            }
        });

        id = mSoundPool.load(MainActivity.this, R.raw.click, 1);


        final TextView dataView = findViewById(R.id.lcd);
        new Thread(new Runnable() {
            long t = System.currentTimeMillis();
            @Override
            public void run() {
                try {
                    while(!Thread.currentThread().isInterrupted()) {
                        runOnUiThread(new Runnable() {
                            @SuppressLint("SetTextI18n")
                            @Override
                            public void run() {
                                long dt = System.currentTimeMillis() - t;
                                dataView.setText("" + Math.round(count * 60000f / dt));
//                                if(dt > max) {
                                if(dt > 5000) {
                                    count = 0;
                                    t = System.currentTimeMillis();
                                }
                            }
                        });
                        Thread.sleep(1000);
                    }
                } catch (InterruptedException ignored) { }
            }
        }).start();
    }

    @Override
    public void onSensorChanged(final SensorEvent event) {
        float mag = (float) Math.sqrt((event.values[0] * event.values[0]) + (event.values[1] * event.values[1]) + (event.values[2] * event.values[2]));
        long oldMin = min;
        if(mag > 600) {
            min = MIN_LEVELS[4];
            max = MAX_LEVELS[4];
        }
        else if(mag > 270) {
            min = MIN_LEVELS[3];
            max = MAX_LEVELS[3];
        }
        else if(mag > 150) {
            min = MIN_LEVELS[2];
            max = MAX_LEVELS[2];
        }
        else if(mag > 95) {
            min = MIN_LEVELS[1];
            max = MAX_LEVELS[1];
        }
        else {
            min = MIN_LEVELS[0];
            max = MAX_LEVELS[0];
        }
        if(min != oldMin) {
            thread.interrupt();
        }
//        dataView.setText(String.format("%.2f", mag));
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) { }

    @Override
    protected void onPause() {
        stop = true;
        thread.interrupt();
        if(sensorManager != null) {
            sensorManager.unregisterListener(this);
        }
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if(sensorManager != null && magCheck.isChecked()) {
            sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_FASTEST);
        }
        stop = false;
        thread = new Thread(this);
        thread.start();
    }

    @Override
    protected void onDestroy() {
        if (adView != null) {
            adView.destroy();
        }
        super.onDestroy();
    }

    @Override
    public void run() {
        while(!stop) {
            if(!loaded) {
                continue;
            }
            try {
                mSoundPool.play(id, volume, volume, 1, 0, SAMPLE_RATE);    //(float) (Math.random() * 0.1f) + 1.45f
                count++;
                long delay = Math.round((Math.random() * (max - min)) + min);
                if(max > 1200 && delay > 500) {
                    if(Math.random() > 0.8f) {
                        Thread.sleep(Math.round((Math.random() * (250 - 150)) + 150));
                        mSoundPool.play(id, volume, volume, 1, 0, SAMPLE_RATE);
                        count++;
                        if(Math.random() > 0.3f) {
                            Thread.sleep(Math.round((Math.random() * (8 - 20)) + 20));
                            mSoundPool.play(id, volume, volume, 1, 0, SAMPLE_RATE);
                            count++;

                            if(Math.random() > 0.3f) {
                                Thread.sleep(Math.round((Math.random() * (80 - 20)) + 20));
                                mSoundPool.play(id, volume, volume, 1, 0, SAMPLE_RATE);
                                count++;
                                Thread.sleep(Math.round((Math.random() * (80 - 20)) + 20));
                                mSoundPool.play(id, volume, volume, 1, 0, SAMPLE_RATE);
                                count++;
                            }
                        }
                    }
                }
                Thread.sleep(delay);
            }
            catch (InterruptedException ignored) { }
        }
    }
}