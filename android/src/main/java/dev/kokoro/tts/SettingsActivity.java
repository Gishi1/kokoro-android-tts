package dev.kokoro.tts;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Settings screen for the Kokoro TTS engine.
 *
 * <p>Reached from the app drawer, or from Android Settings &gt; System &gt;
 * Languages &amp; input &gt; Text-to-speech output &gt; the gear beside the engine
 * (via the {@code settingsActivity} attribute in {@code res/xml/tts_engine.xml}).
 *
 * <p>Laid out as two tabs. The voice list is why most people open this screen and
 * a server can offer 67 voices, so it gets a whole tab to itself, with a filter,
 * rather than competing for height with the configuration controls.
 *
 * <p>Changing anything here takes effect immediately. This activity runs in the
 * same process as {@link KokoroTtsService}, so they share one
 * {@link SharedPreferences} instance and the engine sees new values on the next
 * utterance. (Changing the file externally with adb does <em>not</em> have that
 * property — the process caches it, which is why {@code tools/set-voice.sh}
 * force-stops the app.)
 */
public class SettingsActivity extends Activity {

    private static final String PREFS = "kokoro";
    private static final String KEY_ENDPOINT = "endpoint";
    private static final String KEY_VOICE = "voice";
    private static final String KEY_VOICE_LIST = "voice_list";

    /** Master switch for the silence this engine inserts between chunks. */
    static final String KEY_ADD_GAPS = "add_gaps";
    /** Strip the silence Kokoro pads every chunk with. */
    static final String KEY_TRIM_SILENCE = "trim_silence";
    /** Off by default: the engine's own voice setting wins. */
    static final String KEY_APPS_OVERRIDE_VOICE = "apps_override_voice";
    static final String KEY_MID_SENTENCE_GAP_MS = "mid_sentence_gap_ms";
    static final String KEY_PARAGRAPH_GAP_MS = "paragraph_gap_ms";

    static final int DEFAULT_MID_GAP_MS = 90;
    static final int DEFAULT_PARA_GAP_MS = 280;

    /** Ranges offered by the sliders. */
    private static final int MAX_MID_GAP_MS = 600;
    private static final int MAX_PARA_GAP_MS = 1200;
    private static final int GAP_STEP_MS = 10;

    static final String KOFI_URL = "https://ko-fi.com/gishi";

    /** Shown only until the server answers; not a claim about what the server has. */
    private static final String[] FALLBACK_VOICES = {
            "af_heart", "af_bella", "af_nicole", "am_adam", "am_michael",
            "bf_emma", "bm_george",
    };

    private static final String SAMPLE =
            "This is how this voice sounds reading an ordinary sentence.";

    private static final int TAB_VOICES = 0;
    private static final int TAB_SETTINGS = 1;

    private SharedPreferences prefs;
    private EditText endpointField;
    private EditText filterField;
    private TextView status;
    private ListView list;
    private ArrayAdapter<String> adapter;

    /** Everything the server offered; {@link #voices} is the filtered view of it. */
    private final List<String> allVoices = new ArrayList<>();
    private final List<String> voices = new ArrayList<>();

    private SeekBar midSeek;
    private SeekBar paraSeek;
    private Button voicesTab;
    private Button settingsTab;
    private View voicesPane;
    private View settingsPane;

    private TextToSpeech tts;
    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        setContentView(buildUi());
        showTab(TAB_VOICES);
        loadVoices();
        tts = new TextToSpeech(this, null, getPackageName());
    }

    // ------------------------------------------------------------------- UI

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }

    private TextView heading(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(15);
        tv.setPadding(0, dp(14), 0, dp(4));
        return tv;
    }

    private View buildUi() {
        int pad = dp(16);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, dp(10), pad, pad);

        TextView title = new TextView(this);
        title.setText("Kokoro TTS");
        title.setTextSize(22);
        title.setPadding(0, 0, 0, dp(8));
        root.addView(title);

        // ---- tab bar
        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        voicesTab = new Button(this);
        voicesTab.setText("Voices");
        settingsTab = new Button(this);
        settingsTab.setText("Settings");
        LinearLayout.LayoutParams half =
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tabs.addView(voicesTab, half);
        tabs.addView(settingsTab, half);
        voicesTab.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showTab(TAB_VOICES);
            }
        });
        settingsTab.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showTab(TAB_SETTINGS);
            }
        });
        root.addView(tabs);

        // ---- content frame holds both panes; only one is visible at a time
        FrameLayout content = new FrameLayout(this);
        content.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        voicesPane = buildVoicesPane();
        settingsPane = buildSettingsPane();
        content.addView(voicesPane);
        content.addView(settingsPane);
        root.addView(content);
        return root;
    }

    private void showTab(int which) {
        voicesPane.setVisibility(which == TAB_VOICES ? View.VISIBLE : View.GONE);
        settingsPane.setVisibility(which == TAB_SETTINGS ? View.VISIBLE : View.GONE);
        styleTab(voicesTab, which == TAB_VOICES);
        styleTab(settingsTab, which == TAB_SETTINGS);
    }

    /** Selected tabs read as filled; the other recedes. */
    private void styleTab(Button tab, boolean selected) {
        tab.setBackgroundColor(selected ? 0xFF1F6F6B : 0xFFE8E8E8);
        tab.setTextColor(selected ? Color.WHITE : Color.DKGRAY);
    }

    // ------------------------------------------------------------ voices tab

    private View buildVoicesPane() {
        LinearLayout pane = new LinearLayout(this);
        pane.setOrientation(LinearLayout.VERTICAL);

        status = new TextView(this);
        status.setPadding(0, dp(8), 0, dp(6));
        pane.addView(status);

        Button test = new Button(this);
        test.setText("Speak a test sentence in the selected voice");
        test.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (tts == null) return;
                Bundle params = new Bundle();
                tts.speak(SAMPLE, TextToSpeech.QUEUE_FLUSH, params, "kokoro-preview");
            }
        });
        pane.addView(test);

        filterField = new EditText(this);
        filterField.setHint("Filter voices, e.g. af_ or british");
        filterField.setSingleLine(true);
        filterField.setInputType(InputType.TYPE_CLASS_TEXT);
        filterField.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
                applyFilter(s.toString());
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });
        pane.addView(filterField);

        list = new ListView(this);
        list.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_single_choice,
                voices);
        list.setAdapter(adapter);
        list.setChoiceMode(ListView.CHOICE_MODE_SINGLE);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                String chosen = voices.get(position);
                prefs.edit().putString(KEY_VOICE, chosen).apply();
                Toast.makeText(SettingsActivity.this, "Voice: " + chosen,
                        Toast.LENGTH_SHORT).show();
            }
        });
        pane.addView(list);
        return pane;
    }

    /** Narrow the list to voices containing {@code needle}, case-insensitively. */
    private void applyFilter(String needle) {
        String q = needle == null ? "" : needle.trim().toLowerCase();
        voices.clear();
        for (String v : allVoices) {
            if (q.isEmpty() || v.toLowerCase().contains(q)) voices.add(v);
        }
        adapter.notifyDataSetChanged();
        markCurrent();
        if (!q.isEmpty()) {
            status.setText(voices.size() + " of " + allVoices.size() + " voices match \""
                    + needle.trim() + "\"");
        } else {
            status.setText(allVoices.size() + " voices from the server. Tap one to select "
                    + "it, then use the test button.");
        }
    }

    private void markCurrent() {
        String current = prefs.getString(KEY_VOICE, KokoroTtsService.DEFAULT_VOICE);
        int index = voices.indexOf(current);
        if (index >= 0) {
            list.setItemChecked(index, true);
            // With a long list the selection is usually off-screen; bring it into view.
            list.setSelection(index);
        }
    }

    // ---------------------------------------------------------- settings tab

    private View buildSettingsPane() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout pane = new LinearLayout(this);
        pane.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(pane);

        // ---- endpoint
        pane.addView(heading("Server endpoint"));
        endpointField = new EditText(this);
        endpointField.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        endpointField.setText(prefs.getString(KEY_ENDPOINT, KokoroTtsService.DEFAULT_ENDPOINT));
        pane.addView(endpointField);

        Button reload = new Button(this);
        reload.setText("Save endpoint and reload voices");
        reload.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                prefs.edit()
                        .putString(KEY_ENDPOINT, endpointField.getText().toString().trim())
                        .apply();
                loadVoices();
                showTab(TAB_VOICES);
            }
        });
        pane.addView(reload);

        // ---- voice precedence
        pane.addView(heading("Voice precedence"));
        final CheckBox appsOverride = new CheckBox(this);
        appsOverride.setText("Let apps choose the voice (off: the voice above always wins)");
        appsOverride.setChecked(prefs.getBoolean(KEY_APPS_OVERRIDE_VOICE, false));
        appsOverride.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                prefs.edit().putBoolean(KEY_APPS_OVERRIDE_VOICE, isChecked).apply();
            }
        });

        pane.addView(appsOverride);

        // ---- pauses
        pane.addView(heading("Pauses between chunks"));

        TextView gapNote = new TextView(this);
        gapNote.setText("Kokoro synthesises each chunk separately and pads every one with "
                + "about 0.4 s of silence (50 ms before the speech, 360 ms after), so a "
                + "join already has a gap even when nothing is added. Trimming removes that "
                + "padding, which is what lets the pauses below be the whole pause.");
        gapNote.setTextSize(12);
        pane.addView(gapNote);

        final CheckBox trimSilence = new CheckBox(this);
        trimSilence.setText("Trim the silence Kokoro adds to each chunk");
        trimSilence.setChecked(prefs.getBoolean(KEY_TRIM_SILENCE, true));
        trimSilence.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                prefs.edit().putBoolean(KEY_TRIM_SILENCE, isChecked).apply();
            }
        });
        pane.addView(trimSilence);

        final CheckBox addGaps = new CheckBox(this);
        addGaps.setText("Insert pauses between chunks (switch off if the app you listen "
                + "with already adds its own)");
        addGaps.setChecked(prefs.getBoolean(KEY_ADD_GAPS, true));
        pane.addView(addGaps);

        final TextView midLabel = new TextView(this);
        midSeek = new SeekBar(this);
        midSeek.setMax(MAX_MID_GAP_MS / GAP_STEP_MS);
        midSeek.setProgress(prefs.getInt(KEY_MID_SENTENCE_GAP_MS, DEFAULT_MID_GAP_MS)
                / GAP_STEP_MS);
        hookSlider(midSeek, midLabel, KEY_MID_SENTENCE_GAP_MS, DEFAULT_MID_GAP_MS,
                "Seam cut mid-sentence");
        pane.addView(midLabel);
        pane.addView(midSeek);

        final TextView paraLabel = new TextView(this);
        paraSeek = new SeekBar(this);
        paraSeek.setMax(MAX_PARA_GAP_MS / GAP_STEP_MS);
        paraSeek.setProgress(prefs.getInt(KEY_PARAGRAPH_GAP_MS, DEFAULT_PARA_GAP_MS)
                / GAP_STEP_MS);
        hookSlider(paraSeek, paraLabel, KEY_PARAGRAPH_GAP_MS, DEFAULT_PARA_GAP_MS,
                "End of a paragraph");
        pane.addView(paraLabel);
        pane.addView(paraSeek);

        final Runnable applyEnabled = new Runnable() {
            @Override
            public void run() {
                boolean on = addGaps.isChecked();
                midSeek.setEnabled(on);
                paraSeek.setEnabled(on);
                float alpha = on ? 1f : 0.4f;
                midSeek.setAlpha(alpha);
                paraSeek.setAlpha(alpha);
            }
        };
        addGaps.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                prefs.edit().putBoolean(KEY_ADD_GAPS, isChecked).apply();
                applyEnabled.run();
            }
        });
        applyEnabled.run();

        // ---- support
        pane.addView(heading("Support"));
        Button kofi = new Button(this);
        kofi.setText("Support this project on Ko-fi");
        kofi.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(KOFI_URL)));
                } catch (Exception e) {
                    // No browser installed is not worth crashing over; show the URL.
                    Toast.makeText(SettingsActivity.this, KOFI_URL,
                            Toast.LENGTH_LONG).show();
                }
            }
        });
        pane.addView(kofi);

        TextView kofiUrl = new TextView(this);
        kofiUrl.setText(KOFI_URL);
        kofiUrl.setTextSize(12);
        kofiUrl.setGravity(Gravity.CENTER_HORIZONTAL);
        pane.addView(kofiUrl);
        return scroll;
    }

    /** Wire a slider to a millisecond preference, keeping its label in step. */
    private void hookSlider(final SeekBar bar, final TextView label, final String key,
                            final int fallbackMs, final String caption) {
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int ms = progress * GAP_STEP_MS;
                label.setText(caption + ": " + (ms == 0 ? "no pause" : ms + " ms"));
                if (fromUser) {
                    prefs.edit().putInt(key, ms).apply();
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        int current = prefs.getInt(key, fallbackMs);
        label.setText(caption + ": " + (current == 0 ? "no pause" : current + " ms"));
    }

    // -------------------------------------------------------------- voices

    private void loadVoices() {
        String endpoint = prefs.getString(KEY_ENDPOINT, KokoroTtsService.DEFAULT_ENDPOINT);
        status.setText("Fetching voices from " + endpoint + " ...");
        // Keep any "/v1" prefix: the voices endpoint lives beside the speech
        // endpoint, not at the server root.
        final String base = endpoint.replaceAll("/audio/speech/?$", "");

        new Thread(new Runnable() {
            @Override
            public void run() {
                final List<String> fetched = fetchVoices(base);
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        allVoices.clear();
                        if (fetched.isEmpty()) {
                            allVoices.addAll(Arrays.asList(FALLBACK_VOICES));
                            status.setText("Could not reach the server. Showing a fallback "
                                    + "list of " + allVoices.size() + " voices; check the "
                                    + "endpoint and that the server is running.");
                        } else {
                            allVoices.addAll(fetched);
                            status.setText(allVoices.size() + " voices from the server. "
                                    + "Tap one to select it, then use the test button.");
                            prefs.edit().putString(KEY_VOICE_LIST, join(allVoices)).apply();
                        }
                        applyFilter(filterField.getText().toString());
                    }
                });
            }
        }).start();
    }

    /** GET {base}/audio/voices -> {"voices":[...]} ; empty list on any failure. */
    private List<String> fetchVoices(String base) {
        List<String> out = new ArrayList<>();
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(base + "/audio/voices").openConnection();
            conn.setConnectTimeout(6000);
            conn.setReadTimeout(6000);
            if (conn.getResponseCode() != 200) return out;
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), "UTF-8"))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
            }
            JSONObject obj = new JSONObject(sb.toString());
            JSONArray arr = obj.optJSONArray("voices");
            if (arr == null) return out;
            for (int i = 0; i < arr.length(); i++) {
                Object item = arr.get(i);
                String name = item instanceof JSONObject
                        ? ((JSONObject) item).optString("id", null) : String.valueOf(item);
                if (name != null && !name.isEmpty()) out.add(name);
            }
            java.util.Collections.sort(out);
        } catch (Exception e) {
            android.util.Log.w("KokoroTts", "voice list fetch failed for " + base
                    + "/audio/voices: " + e);
            return new ArrayList<>();
        } finally {
            if (conn != null) conn.disconnect();
        }
        return out;
    }

    static String join(List<String> items) {
        StringBuilder sb = new StringBuilder();
        for (String s : items) {
            if (sb.length() > 0) sb.append(',');
            sb.append(s);
        }
        return sb.toString();
    }

    static List<String> split(String joined) {
        List<String> out = new ArrayList<>();
        if (joined == null || joined.isEmpty()) return out;
        for (String s : joined.split(",")) {
            if (!s.trim().isEmpty()) out.add(s.trim());
        }
        return out;
    }

    @Override
    protected void onDestroy() {
        if (tts != null) {
            tts.shutdown();
            tts = null;
        }
        super.onDestroy();
    }
}
