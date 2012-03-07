package com.smallstyle.shabetter;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;

import android.app.Activity;
import android.app.PendingIntent;
import android.app.ProgressDialog;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.IntentFilter.MalformedMimeTypeException;
import android.nfc.NdefMessage;
import android.nfc.NdefRecord;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.tech.Ndef;
import android.nfc.tech.NdefFormatable;
import android.os.AsyncTask;
import android.os.Bundle;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

public class TagWriterActivity extends Activity {

	private NfcAdapter mNfcAdapter;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.tag_writer);

		mNfcAdapter = NfcAdapter.getDefaultAdapter(this);

		final Button button = (Button) findViewById(R.id.button1);
		button.setOnClickListener(new OnClickListener() {

			@Override
			public void onClick(View v) {
				enableForegroundDispatch();

				TextView textView2 = (TextView) findViewById(R.id.textView2);
				textView2.setVisibility(View.VISIBLE);
				button.setEnabled(false);
			}
		});
	}

	@Override
	protected void onNewIntent(Intent intent) {
		super.onNewIntent(intent);

		String action = intent.getAction();
		if (NfcAdapter.ACTION_NDEF_DISCOVERED.equals(action)
				|| NfcAdapter.ACTION_TECH_DISCOVERED.equals(action)
				|| NfcAdapter.ACTION_TAG_DISCOVERED.equals(action)) {
			Tag tag = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG);

			EditText editText1 = (EditText) findViewById(R.id.editText1);
			writeNdefMessage(tag, editText1.getText().toString());
		}
	}

	@Override
	protected void onPause() {
		if (mNfcAdapter != null && this.isFinishing()) {
			mNfcAdapter.disableForegroundDispatch(this);
		}
		super.onPause();
	}

	private void enableForegroundDispatch() {
		PendingIntent intent =
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
		IntentFilter[] filters = new IntentFilter[] { ndef, tech, tag };

		String[][] techLists =
				new String[][] { new String[] { Ndef.class.getName() }, new String[] { NdefFormatable.class.getName() } };

		mNfcAdapter.enableForegroundDispatch(this, intent, filters, techLists);
	}

	private void writeNdefMessage(Tag tag, String screenName) {

		//https://github.com/commonsguy/cw-advandroid/tree/master/NFC/URLTagger
		
		final int prefix = 3;
		final byte[] url = ("twitter.com/" + screenName).getBytes();
		
		ByteBuffer buf = ByteBuffer.allocate(1 + url.length);
		final byte[] data = buf.put((byte)prefix).put(url).array();
		
		NdefRecord record = new NdefRecord(NdefRecord.TNF_WELL_KNOWN, NdefRecord.RTD_URI, new byte[]{}, data);
		NdefMessage message = new NdefMessage(new NdefRecord[] { record });

		new WriteTask(tag, message).execute();
	}
	
	private class WriteTask extends AsyncTask<Void, Void, Boolean> {

		private ProgressDialog progressDialog;
		private Tag tag;
		private NdefMessage msg;
		
		WriteTask(Tag tag, NdefMessage msg) {
			this.tag = tag;
			this.msg = msg;
		}

		@Override
		protected void onPreExecute() {
			super.onPreExecute();
			progressDialog = ProgressDialog.show(TagWriterActivity.this, "処理中", "タグにデータを書き込んでいます．書き込み完了まで動かさないでください．");
		}

		@Override
		protected Boolean doInBackground(Void... params) {
			boolean result = false;
			try {
				if (isNdefFormatable(tag)) {
					NdefFormatable ndef = NdefFormatable.get(tag);
					try {
						if (!ndef.isConnected()) {
							ndef.connect();
						}
						ndef.format(msg);
						result = true;
					} finally {
						ndef.close();
					}
				} else if (isNdef(tag)) {
					Ndef ndef = Ndef.get(tag);
					try {
						if (!ndef.isConnected()) {
							ndef.connect();
						}
						if (ndef.isWritable()) {
							ndef.writeNdefMessage(msg);
						}
						result= true;
					} finally {
						ndef.close();
					}
				}
			} catch (IOException e) {
				e.printStackTrace();
			} catch (Exception e) {
				e.printStackTrace();
			}
			return result;
		}

		@Override
		protected void onPostExecute(Boolean result) {
			super.onPostExecute(result);
			mNfcAdapter.disableForegroundDispatch(TagWriterActivity.this);
			progressDialog.dismiss();
			Toast.makeText(TagWriterActivity.this, result ? "書き込み完了しました" : "書き込みに失敗しました", Toast.LENGTH_LONG).show();
			final Button button = (Button) findViewById(R.id.button1);
			button.setEnabled(true);
			TextView textView2 = (TextView) findViewById(R.id.textView2);
			textView2.setVisibility(View.INVISIBLE);
		}
	}
	
	private boolean isNdefFormatable(Tag tag) {
		return Arrays.asList(tag.getTechList()).contains(NdefFormatable.class.getName());
	}
	
	private boolean isNdef(Tag tag) {
		return Arrays.asList(tag.getTechList()).contains(Ndef.class.getName());
	}
}
