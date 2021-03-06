package github.daneren2005.dsub.activity;

import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.TypedArray;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.preference.PreferenceManager;
import com.actionbarsherlock.app.ActionBar;
import com.actionbarsherlock.view.Menu;
import android.support.v4.view.ViewPager;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.widget.ImageButton;
import android.widget.TextView;
import github.daneren2005.dsub.R;
import github.daneren2005.dsub.domain.MusicDirectory;
import github.daneren2005.dsub.domain.PlayerState;
import github.daneren2005.dsub.fragments.MainFragment;
import github.daneren2005.dsub.fragments.SelectArtistFragment;
import github.daneren2005.dsub.fragments.SelectPlaylistFragment;
import github.daneren2005.dsub.service.DownloadFile;
import github.daneren2005.dsub.service.DownloadServiceImpl;
import github.daneren2005.dsub.updates.Updater;
import github.daneren2005.dsub.util.Constants;
import github.daneren2005.dsub.util.FileUtil;
import github.daneren2005.dsub.util.SilentBackgroundTask;
import github.daneren2005.dsub.util.Util;
import github.daneren2005.dsub.view.ChangeLog;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class MainActivity extends SubsonicActivity {
	private static final String TAG = MainActivity.class.getSimpleName();
	private static boolean infoDialogDisplayed;
	private ScheduledExecutorService executorService;
	private View coverArtView;
	private TextView trackView;
	private TextView artistView;
	private ImageButton startButton;
	private long lastBackPressTime = 0;

	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		if (getIntent().hasExtra(Constants.INTENT_EXTRA_NAME_EXIT)) {
            stopService(new Intent(this, DownloadServiceImpl.class));
			finish();
        } else if(getIntent().hasExtra(Constants.INTENT_EXTRA_NAME_DOWNLOAD)) {
			getIntent().removeExtra(Constants.INTENT_EXTRA_NAME_DOWNLOAD);
			Intent intent = new Intent();
			intent.setClass(this, DownloadActivity.class);
			startActivity(intent);
		}
		setContentView(R.layout.main);
		loadSettings();

		View bottomBar = findViewById(R.id.bottom_bar);
		bottomBar.setOnClickListener(new View.OnClickListener() {
			public void onClick(View v) {
				Intent intent = new Intent();
				intent.setClass(v.getContext(), DownloadActivity.class);
				startActivity(intent);
			}
		});
		coverArtView = bottomBar.findViewById(R.id.album_art);
		trackView = (TextView) bottomBar.findViewById(R.id.track_name);
		artistView = (TextView) bottomBar.findViewById(R.id.artist_name);
		
		ImageButton previousButton = (ImageButton) findViewById(R.id.download_previous);
		previousButton.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				new SilentBackgroundTask<Void>(MainActivity.this) {
					@Override
					protected Void doInBackground() throws Throwable {
						if(getDownloadService() == null) {
							return null;
						}
						
						getDownloadService().previous();
						return null;
					}

					@Override
					protected void done(Void result) {
						update();
					}
				}.execute();
			}
		});
		
		startButton = (ImageButton) findViewById(R.id.download_start);
		startButton.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				new SilentBackgroundTask<Void>(MainActivity.this) {
					@Override
					protected Void doInBackground() throws Throwable {
						PlayerState state = getDownloadService().getPlayerState();
						if(state == PlayerState.STARTED) {
							getDownloadService().pause();
						} else {
							getDownloadService().start();
						}
						
						return null;
					}

					@Override
					protected void done(Void result) {
						update();
					}
				}.execute();
			}
		});
		
		ImageButton nextButton = (ImageButton) findViewById(R.id.download_next);
		nextButton.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				new SilentBackgroundTask<Void>(MainActivity.this) {
					@Override
					protected Void doInBackground() throws Throwable {
						if(getDownloadService() == null) {
							return null;
						}
						
						if (getDownloadService().getCurrentPlayingIndex() < getDownloadService().size() - 1) {
							getDownloadService().next();
						}
						return null;
					}

					@Override
					protected void done(Void result) {
						update();
					}
				}.execute();
			}
		});

		viewPager = (ViewPager) findViewById(R.id.pager);
		pagerAdapter = new TabPagerAdapter(this, viewPager);
		viewPager.setAdapter(pagerAdapter);
		viewPager.setOnPageChangeListener(pagerAdapter);
		
		addTab("Home", MainFragment.class, null);
		addTab("Library", SelectArtistFragment.class, null);
		addTab("Playlists", SelectPlaylistFragment.class, null);
		
		getSupportActionBar().setDisplayHomeAsUpEnabled(false);
		getSupportActionBar().setHomeButtonEnabled(false);
		getSupportActionBar().setNavigationMode(ActionBar.NAVIGATION_MODE_TABS);
	}
	
	@Override
	protected void onPostCreate(Bundle bundle) {
		super.onPostCreate(bundle);
		
		showInfoDialog();
		checkUpdates();
		
		ChangeLog changeLog = new ChangeLog(this, Util.getPreferences(this));
		if(changeLog.isFirstRun()) {
			changeLog.getLogDialog().show();
		}
	}

	@Override
	public void onResume() {
		super.onResume();

		final Handler handler = new Handler();
		Runnable runnable = new Runnable() {
			@Override
			public void run() {
				handler.post(new Runnable() {
					@Override
					public void run() {
						update();
					}
				});
			}
		};

		executorService = Executors.newSingleThreadScheduledExecutor();
		executorService.scheduleWithFixedDelay(runnable, 0L, 1000L, TimeUnit.MILLISECONDS);
	}

	@Override
	public void onPause() {
		super.onPause();
		executorService.shutdown();
	}

	@Override
	public void onBackPressed() {
		if(onBackPressedSupport()) {
			if(lastBackPressTime < (System.currentTimeMillis() - 4000)) {
				lastBackPressTime = System.currentTimeMillis();
				Util.toast(this, R.string.main_back_confirm);
			} else {
				finish();
			}
		}
	}

	private void update() {
		if (getDownloadService() == null) {
			return;
		}

		DownloadFile current = getDownloadService().getCurrentPlaying();
		if(current == null) {
			trackView.setText("Title");
			artistView.setText("Artist");
			getImageLoader().loadImage(coverArtView, null, false, false);
			return;
		}

		MusicDirectory.Entry song = current.getSong();
		trackView.setText(song.getTitle());
		artistView.setText(song.getArtist());
		getImageLoader().loadImage(coverArtView, song, false, false);
		int[] attrs = new int[] {(getDownloadService().getPlayerState() == PlayerState.STARTED) ?  R.attr.media_button_pause : R.attr.media_button_start};
		TypedArray typedArray = this.obtainStyledAttributes(attrs);
		Drawable drawable = typedArray.getDrawable(0);
		startButton.setImageDrawable(drawable);
		typedArray.recycle();
	}
	
	public void checkUpdates() {
		try {
			String version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
			int ver = Integer.parseInt(version.replace(".", ""));
			Updater updater = new Updater(ver);
			updater.checkUpdates(this);
		}
		catch(Exception e) {

		}
	}
	
	private void loadSettings() {
		PreferenceManager.setDefaultValues(this, R.xml.settings, false);
		SharedPreferences prefs = Util.getPreferences(this);
		if (!prefs.contains(Constants.PREFERENCES_KEY_CACHE_LOCATION)) {
			SharedPreferences.Editor editor = prefs.edit();
			editor.putString(Constants.PREFERENCES_KEY_CACHE_LOCATION, FileUtil.getDefaultMusicDirectory().getPath());
			editor.commit();
		}

		if (!prefs.contains(Constants.PREFERENCES_KEY_OFFLINE)) {
			SharedPreferences.Editor editor = prefs.edit();
			editor.putBoolean(Constants.PREFERENCES_KEY_OFFLINE, false);
			editor.putInt(Constants.PREFERENCES_KEY_SERVER_INSTANCE, 1);
			editor.commit();
		} 
	}

	private void showInfoDialog() {
		if (!infoDialogDisplayed) {
			infoDialogDisplayed = true;
			Log.i(TAG, Util.getRestUrl(this, null));
			if (Util.getRestUrl(this, null).contains("demo.subsonic.org")) {
				Util.info(this, R.string.main_welcome_title, R.string.main_welcome_text);
			}
		}
	}
}
