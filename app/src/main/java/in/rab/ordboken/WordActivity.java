/*
 * Copyright (C) 2013 Rabin Vincent <rabin@rab.in>
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

package in.rab.ordboken;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.AsyncTask;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.Window;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SearchView;
import android.widget.ShareActionProvider;
import android.widget.TextView;
import android.widget.Toast;

import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;

import in.rab.ordboken.NeClient.NeWord;

public class WordActivity extends Activity {
    private static final String FLASHCARD_ACTION = "org.openintents.action.CREATE_FLASHCARD";
    private static final String CSS = "<style>"
            + "p.headword { display: none; }"
            + "object { display: none; }"
            + ".neopetit { font-size: 90%; }"
            + ".neokap { font-size: 90%; }"
            + "p { margin: 0; }"
            + "a.sound { "
            + "	text-decoration: none;"
            + "	width: 16px; height: 16px;"
            + "	display: inline-block;"
            + "	background-image: url('file:///android_asset/audio.png');"
            + "}"
            + "a.normal {"
            + " text-decoration: inherit;"
            + " color: inherit;"
            + " cursor: default;"
            + "}" + "</style>";
    private WebView mWebView;
    private Ordboken mOrdboken;
    private NeWord mWord;
    private String mTitle;
    private String mUrl;
    private ProgressBar mProgressBar;
    private TextView mStatusText;
    private LinearLayout mStatusLayout;
    private Button mRetryButton;
    private ShareActionProvider mShareActionProvider;
    private SearchView mSearchView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mOrdboken = Ordboken.getInstance(this);

        requestWindowFeature(Window.FEATURE_INDETERMINATE_PROGRESS);
        setContentView(R.layout.activity_word);
        setVolumeControlStream(AudioManager.STREAM_MUSIC);

        mWebView = (WebView) findViewById(R.id.webView);
        WebSettings settings = mWebView.getSettings();

        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        settings.setJavaScriptEnabled(true);

        mWebView.setInitialScale(mOrdboken.mPrefs.getInt("scale", 100));
        mWebView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                if (url.indexOf("/playAudio") != -1) {
                    setProgressBarIndeterminateVisibility(true);
                    new AudioTask().execute();
                } else if (url.indexOf("/search/") != -1) {
                    String word = url.substring(url.indexOf("ch/") + 3);

                    try {
                        mSearchView.setQuery(URLDecoder.decode(word, "UTF-8"), false);
                        mSearchView.setIconified(false);
                        mSearchView.requestFocusFromTouch();
                    } catch (UnsupportedEncodingException e) {
                        // Should not happen.
                    }

                    return true;
                } else {
                    Intent intent = new Intent(WordActivity.this, WordActivity.class);
                    intent.putExtra("url", url);
                    startActivity(intent);
                }

                return true;
            }
        });

        mProgressBar = (ProgressBar) findViewById(R.id.word_progress);
        mStatusText = (TextView) findViewById(R.id.word_status);
        mStatusLayout = (LinearLayout) findViewById(R.id.word_status_layout);
        mRetryButton = (Button) findViewById(R.id.word_retry);

        Intent intent = getIntent();

        mTitle = intent.getStringExtra("title");
        if (mTitle != null) {
            setTitle(mTitle);
        }

        try {
            mUrl = new URI(intent.getStringExtra("url")).toASCIIString();
        } catch (URISyntaxException e) {
            finish();
        }

        fetchWord(null);
    }

    public void fetchWord(View view) {
        mProgressBar.setVisibility(View.VISIBLE);
        mStatusText.setText(R.string.loading);
        mRetryButton.setVisibility(View.GONE);
        new WordTask().execute(mUrl);
    }

    private void loadWebView(NeWord word) {
        String text = word.mText;
        String javascript = "<script src='file:///android_asset/word.js'></script>";

        if (word.mHasAudio) {
            text = text.replace("</object>", "</object><a class='sound' href='/playAudio'></a>");
        }

        mWebView.loadDataWithBaseURL("http://api.ne.se/", CSS + text + javascript, "text/html",
                "UTF-8", null);
    }

    private class WordTask extends AsyncTask<String, Void, NeWord> {
        @Override
        protected NeWord doInBackground(String... params) {
            if (!mOrdboken.isOnline()) {
                return null;
            }

            try {
                return mOrdboken.getNeClient().fetchWord(params[0]);
            } catch (Exception e) {
                return null;
            }
        }

        @Override
        protected void onPostExecute(NeWord result) {
            mWord = result;
            mOrdboken.setCurrentWord(mWord);

            if (result == null) {
                mProgressBar.setVisibility(View.GONE);
                if (!mOrdboken.isOnline()) {
                    mStatusText.setText(R.string.error_offline);
                } else {
                    mStatusText.setText(R.string.error_word);
                }

                mRetryButton.setVisibility(View.VISIBLE);
                return;
            }

            loadWebView(result);
            setTitle(result.mTitle);
            mStatusLayout.setVisibility(View.GONE);
            mWebView.setVisibility(View.VISIBLE);
            updateShareIntent();
        }
    }

    private class AudioTask extends AsyncTask<Void, Void, String> {
        @Override
        protected String doInBackground(Void... params) {
            try {
                mOrdboken.initCookies();
                return mOrdboken.getNeClient().getAudioUrl(mWord);
            } catch (Exception e) {
                return null;
            }
        }

        private void showError() {
            setProgressBarIndeterminateVisibility(false);
            Toast.makeText(getApplicationContext(), R.string.error_audio, Toast.LENGTH_SHORT)
                    .show();
        }

        @Override
        protected void onPostExecute(String audioUrl) {
            if (audioUrl == null) {
                showError();
                return;
            }

            MediaPlayer mediaPlayer = new MediaPlayer();
            mediaPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);

            try {
                mediaPlayer.setDataSource(audioUrl);
            } catch (Exception e) {
                showError();
                return;
            }

            mediaPlayer.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                @Override
                public void onPrepared(MediaPlayer mp) {
                    setProgressBarIndeterminateVisibility(false);
                    mp.start();
                }
            });

            mediaPlayer.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                @Override
                public boolean onError(MediaPlayer mp, int what, int extra) {
                    showError();
                    return false;
                }
            });

            mediaPlayer.prepareAsync();
        }
    }

    private void updateShareIntent() {
        if (mShareActionProvider == null) {
            return;
        }

        if (mWord != null) {
            Intent shareIntent = new Intent(FLASHCARD_ACTION);
            shareIntent.putExtra("SOURCE_TEXT", mWord.mTitle);
            shareIntent.putExtra("TARGET_TEXT", CSS + mWord.mText);
            mShareActionProvider.setShareIntent(shareIntent);
        } else {
            mShareActionProvider.setShareIntent(null);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        mOrdboken.setCurrentWord(mWord);
        updateShareIntent();
    }

    @Override
    protected void onPause() {
        super.onPause();
        overridePendingTransition(0, 0);

        mOrdboken.setCurrentWord(null);
        if (mWord != null) {
            mOrdboken.setLastView(Ordboken.Where.WORD, mWord.mUrl);
        }

        SharedPreferences.Editor ed = mOrdboken.getPrefsEditor();

        // If the WebView was not made visible, getScale() does not
        // return the initalScale, but the default one.
        if (mWebView.getVisibility() == View.VISIBLE) {
            // getScale() is supposed to be deprecated, but its replacement
            // onScaleChanged() doesn't get called when zooming using pinch.
            @SuppressWarnings("deprecation")
            int scale = (int) (mWebView.getScale() * 100);

            ed.putInt("scale", scale);
        }

        ed.commit();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main, menu);

        menu.findItem(R.id.menu_resetzoom).setVisible(true);

        if (getPackageManager().queryIntentActivities(new Intent(FLASHCARD_ACTION), 0).size() > 0) {
            MenuItem shareItem = menu.findItem(R.id.menu_share);
            shareItem.setVisible(true);
            mShareActionProvider = (ShareActionProvider) shareItem.getActionProvider();
        }

        mSearchView = mOrdboken.initSearchView(this, menu, null, false);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (mOrdboken.onOptionsItemSelected(this, item)) {
            return true;
        }

        if (item.getItemId() == R.id.menu_resetzoom) {
            mWebView.setInitialScale(100);
            if (mWord != null) {
                loadWebView(mWord);
            }
        }

        return super.onOptionsItemSelected(item);
    }
}