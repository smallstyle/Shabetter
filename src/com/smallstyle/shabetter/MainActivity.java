package com.smallstyle.shabetter;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.HashMap;
import java.util.Locale;
import java.util.Random;

import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.ResponseHandler;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.protocol.HTTP;
import org.apache.http.util.EntityUtils;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.PendingIntent;
import android.app.ProgressDialog;
import android.content.DialogInterface;
import android.content.DialogInterface.OnClickListener;
import android.content.DialogInterface.OnDismissListener;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.IntentFilter.MalformedMimeTypeException;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.nfc.NdefMessage;
import android.nfc.NdefRecord;
import android.nfc.NfcAdapter;
import android.nfc.tech.NfcF;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Parcelable;
import android.speech.tts.TextToSpeech;
import android.speech.tts.TextToSpeech.OnUtteranceCompletedListener;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity implements TextToSpeech.OnInitListener {

	private final static String TAG = "MainActivity";
	private final static String SPEECH_TWEET_COMPLETE = "speech_tweet_complete";

	private final static String SCREEN_NAME = "screen_name";

	private final static int ACCOUNT_DIALOG = 1;
	private final static int ERROR_DIALOG = 2;

	private final static int TTS_CHECK_CODE = 1;

	private final static int MENU_CREATE_TAG = 1;
	
	private TextToSpeech mTextToSpeech;

	private NfcAdapter mNfcAdapter;
	private PendingIntent mPendingIntent;
	private IntentFilter[] mIntentFilters;
	private String[][] mTechLists;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		Log.v(TAG, "onCreate");
		super.onCreate(savedInstanceState);

		Intent checkIntent = new Intent();
		checkIntent.setAction(TextToSpeech.Engine.ACTION_CHECK_TTS_DATA);
		startActivityForResult(checkIntent, TTS_CHECK_CODE);

		mNfcAdapter = initNfcAdapter();
		setContentView(R.layout.main);
	}

	@Override
	protected void onResume() {
		Log.v(TAG, "onResume");
		if (mNfcAdapter != null) {
			Log.v(TAG, "Enable NFC foreground dispatch");
			mNfcAdapter.enableForegroundDispatch(this, mPendingIntent, mIntentFilters, mTechLists);
		}
		super.onResume();
	}

	@Override
	protected void onNewIntent(Intent intent) {
		Log.v(TAG, "onNewIntent");
		Log.i(TAG, "Discovered tag with intent: " + intent);
		if (NfcAdapter.ACTION_TAG_DISCOVERED.equals(intent.getAction())) {

			Parcelable[] rawMsgs = intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES);
			if (rawMsgs != null && rawMsgs.length == 1) {
				NdefRecord[] records = ((NdefMessage) rawMsgs[0]).getRecords();
				if (records != null && records.length > 0) {
					String payload = new String(records[0].getPayload());
					Log.v(TAG, "Payload: " + payload);
					String screenName = Uri.parse(payload).getLastPathSegment();

					Bundle bundle = new Bundle();
					bundle.putString(SCREEN_NAME, screenName);
					showDialog(ACCOUNT_DIALOG, bundle);

					new FetchTweetTask().execute(screenName);
				}
			}
		}
		super.onNewIntent(intent);
	}

	@Override
	protected void onPause() {
		Log.v(TAG, "onPause");
		if (mNfcAdapter != null && this.isFinishing()) {
			mNfcAdapter.disableForegroundDispatch(this);
		}

		super.onPause();
	}

	@Override
	protected void onDestroy() {
		Log.v(TAG, "onDestroy");
		super.onDestroy();
		if (mTextToSpeech != null) {
			mTextToSpeech.shutdown();
		}
	}

	@Override
	protected void onActivityResult(int requestCode, int resultCode, Intent data) {
		Log.v(TAG, "onActivityResult");
		if (requestCode == TTS_CHECK_CODE) {
			if (resultCode == TextToSpeech.Engine.CHECK_VOICE_DATA_PASS) {
				mTextToSpeech = new TextToSpeech(this, this);
			} else {
				Intent installIntent = new Intent();
				installIntent.setAction(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA);
				startActivity(installIntent);
			}
		}
	}

	@Override
	protected Dialog onCreateDialog(int id, Bundle args) {
		Dialog dialog = null;
		switch (id) {
		case ACCOUNT_DIALOG:
			dialog = accountDialog(args);
			break;
		case ERROR_DIALOG:
			dialog = errorDialog();
			break;
		default:
			dialog = null;
		}
		return dialog;
	}

	@Override
	protected void onPrepareDialog(final int id, Dialog dialog, Bundle args) {
		switch (id) {
		case ACCOUNT_DIALOG:
			dialog.setOnDismissListener(new OnDismissListener() {

				@Override
				public void onDismiss(DialogInterface dialog) {
					removeDialog(id);
				}
			});
			break;
		default:
			super.onPrepareDialog(id, dialog, args);
		}
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		menu.add(Menu.NONE, MENU_CREATE_TAG, Menu.NONE, "タグの作成");
		return true;
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		switch (item.getItemId()) {
		case MENU_CREATE_TAG:
			Intent i = new Intent(this, TagWriterActivity.class);
			startActivity(i);
			break;
		default:
		}
		return true;
	}

	private NfcAdapter initNfcAdapter() {
		mPendingIntent =
				PendingIntent.getActivity(
						this,
						0,
						new Intent(this, getClass()).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
						0);

		IntentFilter ndef = new IntentFilter(NfcAdapter.ACTION_NDEF_DISCOVERED);
		try {
			ndef.addDataType("*/*");
		} catch (MalformedMimeTypeException e) {
			throw new RuntimeException("fail", e);
		}
		IntentFilter tech = new IntentFilter(NfcAdapter.ACTION_TECH_DISCOVERED);
		IntentFilter tag = new IntentFilter(NfcAdapter.ACTION_TAG_DISCOVERED);
		mIntentFilters = new IntentFilter[] { ndef, tech, tag };

		mTechLists = new String[][] { new String[] { NfcF.class.getName() } };
		return NfcAdapter.getDefaultAdapter(this);
	}

	private Dialog accountDialog(Bundle bundle) {
		LayoutInflater inflater = LayoutInflater.from(this);
		View view = inflater.inflate(R.layout.account, null);

		String name = bundle.getString(SCREEN_NAME);

		ImageView profileImage = (ImageView) view.findViewById(R.id.image_profile_image);
		new FetchImageTask(profileImage).execute(name);

		TextView screenName = (TextView) view.findViewById(R.id.text_screen_name);
		screenName.setText(name);

		return new AlertDialog.Builder(this)
				.setIcon(android.R.drawable.ic_dialog_info)
				.setTitle(name + " is speaking")
				.setView(view)
				.setNegativeButton("Cancel", new OnClickListener() {

					@Override
					public void onClick(DialogInterface dialog, int which) {
						if (mTextToSpeech != null && mTextToSpeech.isSpeaking()) {
							mTextToSpeech.stop();
						}
					}
				})
				.setCancelable(false)
				.create();
	}

	private Dialog errorDialog() {
		return new AlertDialog.Builder(this)
				.setIcon(android.R.drawable.ic_dialog_alert)
				.setTitle("ERROR")
				.setMessage("テキスト読み上げの初期化に失敗しました．アプリケーションを終了します．")
				.setNegativeButton("Close", new OnClickListener() {

					@Override
					public void onClick(DialogInterface arg0, int arg1) {
						finish();
					}
				})
				.setCancelable(false)
				.create();
	}

	class FetchImageTask extends AsyncTask<String, Void, Bitmap> {

		private ImageView imageView;

		FetchImageTask(ImageView imageView) {
			this.imageView = imageView;
		}

		@Override
		protected Bitmap doInBackground(String... params) {
			Uri uri =
					Uri
							.parse("https://api.twitter.com/1/users/profile_image")
							.buildUpon()
							.appendQueryParameter("screen_name", params[0])
							.appendQueryParameter("size", "bigger")
							.build();
			try {
				URL url = new URL(uri.toString());
				BufferedInputStream bis = new BufferedInputStream(url.openStream());
				return BitmapFactory.decodeStream(bis);
			} catch (MalformedURLException e) {
				Log.w(TAG, "MalformedURLException");
			} catch (IOException e) {
				Log.w(TAG, "IOException");
			}

			return null;
		}

		@Override
		protected void onPostExecute(Bitmap result) {

			if (result == null) {
				imageView.setImageResource(android.R.drawable.ic_menu_report_image);
			} else {
				imageView.setImageBitmap(result);
			}
			imageView.invalidate();

			super.onPostExecute(result);
		}

	}

	class FetchTweetTask extends AsyncTask<String, Void, HashMap<String, String>> {

		ProgressDialog progressDialog;

		@Override
		protected void onPreExecute() {
			progressDialog = ProgressDialog.show(MainActivity.this, "通信中", "Tweetを取得しています");
			super.onPreExecute();
		}

		@Override
		protected HashMap<String, String> doInBackground(String... params) {
			DefaultHttpClient client = new DefaultHttpClient();
			Uri uri =
					Uri
							.parse("http://api.twitter.com/1/statuses/user_timeline.json")
							.buildUpon()
							.appendQueryParameter("screen_name", params[0])
							.appendQueryParameter("count", "1")
							.appendQueryParameter("include_rts", "f")
							.appendQueryParameter("exclude_replies", "t")
							.build();
			HttpGet request = new HttpGet(uri.toString());
			try {
				Log.v(TAG, "fetch tweet: " + uri.toString());
				return client.execute(request, new ResponseHandler<HashMap<String, String>>() {

					@Override
					public HashMap<String, String> handleResponse(HttpResponse response)
							throws ClientProtocolException, IOException {
						if (response == null) {
							throw new NullPointerException("response is null");
						}

						int statusCode = response.getStatusLine().getStatusCode();
						switch (statusCode) {
						case HttpStatus.SC_OK:
							try {
								JSONArray jsonArray =
										new JSONArray(EntityUtils.toString(response.getEntity(), HTTP.UTF_8));
								JSONObject tweet = jsonArray.getJSONObject(0);
								HashMap<String, String> map = new HashMap<String, String>();
								map.put("text", tweet.optString("text", ""));
								Log.v(TAG, "tweet: " + tweet.optString("text", ""));
								return map;
							} catch (JSONException e) {
								return null;
							}
						default:
							return null;
						}

					}
				});
			} catch (ClientProtocolException e) {
				Log.w(TAG, "ClientProtocolException");
			} catch (IOException e) {
				Log.w(TAG, "IOException");
			}
			return null;
		}

		@Override
		protected void onPostExecute(HashMap<String, String> result) {
			progressDialog.dismiss();

			if (result != null) {
				Log.v(TAG, "Start speech." + mTextToSpeech.isSpeaking());

				Random random = new Random();
				mTextToSpeech.setPitch(random.nextFloat() + 0.75F);
				mTextToSpeech.setSpeechRate(random.nextFloat() + 0.75F);
				String text = result.get("text").replaceAll("http://t.co/\\w+", "").replaceAll("#\\w+", "");
				HashMap<String, String> params = new HashMap<String, String>();
				params.put(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, SPEECH_TWEET_COMPLETE);
				mTextToSpeech.speak(text, TextToSpeech.QUEUE_FLUSH, params);
			} else {
				Toast.makeText(MainActivity.this, "Tweetを取得できませんでした", Toast.LENGTH_LONG).show();
				removeDialog(ACCOUNT_DIALOG);
			}
			super.onPostExecute(result);
		}

	}

	@Override
	public void onInit(int status) {
		if (TextToSpeech.SUCCESS == status) {
			if (mTextToSpeech.isLanguageAvailable(Locale.JAPANESE) == TextToSpeech.LANG_AVAILABLE) {
				mTextToSpeech.speak("準備できたよおお", TextToSpeech.QUEUE_FLUSH, null);
				mTextToSpeech.setOnUtteranceCompletedListener(new OnUtteranceCompletedListener() {

					@Override
					public void onUtteranceCompleted(String untteranceId) {
						removeDialog(ACCOUNT_DIALOG);
					}
				});
			} else {
				Toast.makeText(this, "日本語に対応したテキスト読み上げエンジンをインストールしてください．", Toast.LENGTH_LONG).show();
				mTextToSpeech.setLanguage(Locale.ENGLISH);
				mTextToSpeech.speak("Sorry, I can't speak Japanese.", TextToSpeech.QUEUE_FLUSH, null);
			}
		} else {
			if (mNfcAdapter != null) {
				mNfcAdapter.disableForegroundDispatch(MainActivity.this);
			}
			showDialog(ERROR_DIALOG);
		}
	}
}
