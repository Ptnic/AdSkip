package com.adship.adskip;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class MainActivity extends Activity {

    private static final int REQUEST_VPN = 1001;
    private static final int REQUEST_NOTIFICATIONS = 1002;

    private RadioGroup modeGroup;
    private RadioButton modeUdp;
    private RadioButton modeDoh;
    private RadioButton modeDot;
    private Spinner presetSpinner;
    private EditText dnsInput;
    private TextView dnsHint;
    private TextView configDirty;
    private Switch blockSwitch;
    private Switch bootSwitch;
    private View statusDot;
    private TextView statusTitle;
    private TextView statusBadge;
    private TextView statusDetail;
    private TextView statQueries;
    private TextView statBlocked;
    private TextView statRules;
    private TextView lastBlocked;
    private Button actionButton;
    private View openRules;
    private View openLogs;
    private Switch skipSwitch;
    private Switch continuousSwitch;
    private TextView accessibilityStatus;
    private View openAccessibility;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean loading = true;
    private boolean suppressPreset = true;

    private final Runnable refreshTick = new Runnable() {
        @Override
        public void run() {
            refreshState();
            handler.postDelayed(this, 1000L);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        modeGroup = findViewById(R.id.modeGroup);
        modeUdp = findViewById(R.id.modeUdp);
        modeDoh = findViewById(R.id.modeDoh);
        modeDot = findViewById(R.id.modeDot);
        presetSpinner = findViewById(R.id.presetSpinner);
        dnsInput = findViewById(R.id.dnsInput);
        dnsHint = findViewById(R.id.dnsHint);
        configDirty = findViewById(R.id.configDirty);
        blockSwitch = findViewById(R.id.blockSwitch);
        bootSwitch = findViewById(R.id.bootSwitch);
        statusDot = findViewById(R.id.statusDot);
        statusTitle = findViewById(R.id.statusTitle);
        statusBadge = findViewById(R.id.statusBadge);
        statusDetail = findViewById(R.id.statusDetail);
        statQueries = findViewById(R.id.statQueries);
        statBlocked = findViewById(R.id.statBlocked);
        statRules = findViewById(R.id.statRules);
        lastBlocked = findViewById(R.id.lastBlocked);
        actionButton = findViewById(R.id.actionButton);
        openRules = findViewById(R.id.openRules);
        openLogs = findViewById(R.id.openLogs);
        skipSwitch = findViewById(R.id.skipSwitch);
        continuousSwitch = findViewById(R.id.continuousSwitch);
        accessibilityStatus = findViewById(R.id.accessibilityStatus);
        openAccessibility = findViewById(R.id.openAccessibility);

        DnsConfig saved = DnsConfig.load(this);
        loading = true;
        selectMode(saved.mode);
        dnsInput.setText(saved.address);
        blockSwitch.setChecked(saved.blockAds);
        bootSwitch.setChecked(saved.startOnBoot);
        skipSwitch.setChecked(getSharedPreferences(DnsConfig.PREFS, MODE_PRIVATE)
                .getBoolean(DnsConfig.KEY_AUTO_SKIP, false));
        continuousSwitch.setChecked(getSharedPreferences(DnsConfig.PREFS, MODE_PRIVATE)
                .getBoolean(DnsConfig.KEY_CONTINUOUS_ADS, false));
        setupPresetSpinner(saved.mode, saved.address);
        loading = false;
        updateDnsHint();
        bindListeners();
        refreshState();
        refreshAccessibilityState();
    }

    private void bindListeners() {
        modeGroup.setOnCheckedChangeListener((group, checkedId) -> {
            if (loading) {
                return;
            }
            DnsConfig.Mode mode = currentMode();
            String address = DnsConfig.defaultAddress(mode);
            dnsInput.setText(address);
            setupPresetSpinner(mode, address);
            updateDnsHint();
            refreshConfigDirty();
        });

        dnsInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                if (!loading) {
                    dnsInput.setError(null);
                    refreshConfigDirty();
                }
            }
        });

        blockSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (!loading) {
                saveUiConfig();
                refreshConfigDirty();
            }
        });
        bootSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (!loading) {
                saveUiConfig();
            }
        });

        openRules.setOnClickListener(v ->
                startActivity(new Intent(MainActivity.this, RulesActivity.class)));
        openLogs.setOnClickListener(v ->
                startActivity(new Intent(MainActivity.this, LogsActivity.class)));
        actionButton.setOnClickListener(v -> onActionClicked());

        skipSwitch.setOnCheckedChangeListener((buttonView, checked) -> {
            getSharedPreferences(DnsConfig.PREFS, MODE_PRIVATE)
                    .edit()
                    .putBoolean(DnsConfig.KEY_AUTO_SKIP, checked)
                    .apply();
            if (checked && !AdSkipService.isEnabled(this)) {
                openAccessibilitySettings();
            }
            refreshAccessibilityState();
        });
        continuousSwitch.setOnCheckedChangeListener((buttonView, checked) ->
                getSharedPreferences(DnsConfig.PREFS, MODE_PRIVATE)
                        .edit()
                        .putBoolean(DnsConfig.KEY_CONTINUOUS_ADS, checked)
                        .apply());
        openAccessibility.setOnClickListener(v -> openAccessibilitySettings());
    }

    private void openAccessibilitySettings() {
        try {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        } catch (RuntimeException e) {
            Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void refreshAccessibilityState() {
        boolean wanted = getSharedPreferences(DnsConfig.PREFS, MODE_PRIVATE)
                .getBoolean(DnsConfig.KEY_AUTO_SKIP, false);
        boolean enabled = AdSkipService.isEnabled(this);
        if (!wanted) {
            accessibilityStatus.setText(R.string.accessibility_status_off);
            accessibilityStatus.setTextColor(getColor(R.color.text_secondary));
        } else if (enabled) {
            accessibilityStatus.setText(R.string.accessibility_status_on);
            accessibilityStatus.setTextColor(getColor(R.color.accent));
        } else {
            accessibilityStatus.setText(R.string.accessibility_status_pending);
            accessibilityStatus.setTextColor(getColor(R.color.warning));
        }
    }

    private void selectMode(DnsConfig.Mode mode) {
        switch (mode) {
            case DOH:
                modeDoh.setChecked(true);
                break;
            case DOT:
                modeDot.setChecked(true);
                break;
            case UDP:
            default:
                modeUdp.setChecked(true);
                break;
        }
    }

    private DnsConfig.Mode currentMode() {
        if (modeDoh.isChecked()) {
            return DnsConfig.Mode.DOH;
        }
        if (modeDot.isChecked()) {
            return DnsConfig.Mode.DOT;
        }
        return DnsConfig.Mode.UDP;
    }

    private int modeIndex(DnsConfig.Mode mode) {
        switch (mode) {
            case DOH:
                return 1;
            case DOT:
                return 2;
            case UDP:
            default:
                return 0;
        }
    }

    private void setupPresetSpinner(DnsConfig.Mode mode, String address) {
        suppressPreset = true;
        List<String> labels = new ArrayList<>();
        labels.add("阿里 DNS");
        labels.add("腾讯 DNS");
        labels.add("Cloudflare");
        labels.add("Google");
        labels.add(getString(R.string.preset_custom));

        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this, R.layout.item_spinner, labels);
        adapter.setDropDownViewResource(R.layout.item_spinner_dropdown);
        presetSpinner.setAdapter(adapter);

        int selected = labels.size() - 1;
        for (int i = 0; i < 4; i++) {
            if (DnsConfig.presetAddress(mode, i).equalsIgnoreCase(address)) {
                selected = i;
                break;
            }
        }
        presetSpinner.setSelection(selected, false);
        presetSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (loading || suppressPreset) {
                    return;
                }
                String value = DnsConfig.presetAddress(currentMode(), position);
                if (!value.isEmpty()) {
                    dnsInput.setText(value);
                    dnsInput.setSelection(dnsInput.getText().length());
                    refreshConfigDirty();
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        handler.postDelayed(() -> suppressPreset = false, 250L);
    }

    private void updateDnsHint() {
        DnsConfig.Mode mode = currentMode();
        switch (mode) {
            case DOH:
                dnsHint.setText(R.string.dns_hint_doh);
                dnsInput.setHint("https://dns.alidns.com/dns-query");
                break;
            case DOT:
                dnsHint.setText(R.string.dns_hint_dot);
                dnsInput.setHint("dns.alidns.com:853");
                break;
            case UDP:
            default:
                dnsHint.setText(R.string.dns_hint_udp);
                dnsInput.setHint("223.5.5.5:53");
                break;
        }
    }

    private DnsConfig collectConfig() {
        return new DnsConfig(
                currentMode(),
                dnsInput.getText().toString().trim(),
                blockSwitch.isChecked(),
                bootSwitch.isChecked());
    }

    private void saveUiConfig() {
        collectConfig().save(this);
    }

    private void onActionClicked() {
        if (DnsVpnService.isRunning() || DnsVpnService.isStarting()) {
            stopVpn();
            return;
        }
        saveUiConfig();
        DnsConfig config = collectConfig();
        if (!config.isValid()) {
            dnsInput.setError(getString(R.string.toast_invalid_dns));
            dnsInput.requestFocus();
            Toast.makeText(this, R.string.toast_invalid_dns, Toast.LENGTH_SHORT).show();
            return;
        }
        clearError();

        try {
            Intent prepare = VpnService.prepare(this);
            if (prepare != null) {
                startActivityForResult(prepare, REQUEST_VPN);
            } else {
                startVpn();
            }
        } catch (RuntimeException e) {
            Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void startVpn() {
        requestNotificationPermission();
        try {
            Intent intent = new Intent(this, DnsVpnService.class)
                    .setAction(DnsVpnService.ACTION_START);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent);
            } else {
                startService(intent);
            }
            statusTitle.setText(R.string.status_starting);
            statusDetail.setText(R.string.status_detail_starting);
            handler.postDelayed(this::refreshState, 400L);
        } catch (RuntimeException e) {
            Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void stopVpn() {
        stopService(new Intent(this, DnsVpnService.class));
        handler.postDelayed(this::refreshState, 250L);
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    REQUEST_NOTIFICATIONS);
        }
    }

    private void clearError() {
        getSharedPreferences(DnsConfig.PREFS, MODE_PRIVATE)
                .edit()
                .remove(DnsConfig.KEY_LAST_ERROR)
                .apply();
    }

    private String lastError() {
        SharedPreferences p = getSharedPreferences(DnsConfig.PREFS, MODE_PRIVATE);
        return p.getString(DnsConfig.KEY_LAST_ERROR, "");
    }

    private void refreshState() {
        boolean on = DnsVpnService.isRunning();
        boolean starting = DnsVpnService.isStarting();
        DnsConfig active = DnsVpnService.getActiveConfig();

        if (on) {
            statusDot.setBackgroundResource(R.drawable.dot_on);
            statusTitle.setText(R.string.status_on);
            statusBadge.setText(R.string.status_badge_on);
            statusBadge.setTextColor(getColor(R.color.accent));
            statusBadge.setBackgroundResource(R.drawable.bg_badge);
            if (active != null) {
                statusDetail.setText(getString(R.string.status_detail_on,
                        modeLabel(active.mode), active.address));
            }
            actionButton.setText(R.string.action_stop);
            actionButton.setBackgroundResource(R.drawable.bg_danger);
            actionButton.setTextColor(0xFFFFFFFF);
            statRules.setText(NumberFormat.getIntegerInstance(Locale.getDefault())
                    .format(DnsVpnService.getRuleCount()));
            refreshConfigDirty();
        } else if (starting) {
            statusDot.setBackgroundResource(R.drawable.dot_off);
            statusTitle.setText(R.string.status_starting);
            statusBadge.setText(R.string.status_badge_off);
            statusBadge.setTextColor(getColor(R.color.muted));
            statusBadge.setBackgroundResource(R.drawable.bg_badge_off);
            statusDetail.setText(R.string.status_detail_starting);
            actionButton.setText(R.string.action_stop);
            actionButton.setBackgroundResource(R.drawable.bg_danger);
            actionButton.setTextColor(0xFFFFFFFF);
            statRules.setText("—");
            configDirty.setVisibility(View.GONE);
        } else {
            statusDot.setBackgroundResource(R.drawable.dot_off);
            statusTitle.setText(R.string.status_off);
            statusBadge.setText(R.string.status_badge_off);
            statusBadge.setTextColor(getColor(R.color.muted));
            statusBadge.setBackgroundResource(R.drawable.bg_badge_off);
            String error = lastError();
            if (error != null && !error.isEmpty()) {
                statusDetail.setText(getString(R.string.status_detail_error, error));
            } else {
                statusDetail.setText(R.string.status_detail_off);
            }
            actionButton.setText(R.string.action_start);
            actionButton.setBackgroundResource(R.drawable.bg_primary);
            actionButton.setTextColor(0xFF06141C);
            statRules.setText("—");
            configDirty.setVisibility(View.GONE);
        }

        statQueries.setText(NumberFormat.getIntegerInstance(Locale.getDefault())
                .format(FilterStats.queries()));
        statBlocked.setText(NumberFormat.getIntegerInstance(Locale.getDefault())
                .format(FilterStats.blocked()));
        String recent = FilterStats.lastBlocked();
        if (recent == null || recent.isEmpty()) {
            lastBlocked.setText(R.string.last_blocked_none);
        } else {
            lastBlocked.setText(getString(R.string.last_blocked, recent));
        }
    }

    private void refreshConfigDirty() {
        if (!DnsVpnService.isRunning()) {
            configDirty.setVisibility(View.GONE);
            return;
        }
        DnsConfig active = DnsVpnService.getActiveConfig();
        if (active == null) {
            configDirty.setVisibility(View.GONE);
            return;
        }
        DnsConfig current = collectConfig();
        boolean changed = active.mode != current.mode
                || !active.address.equalsIgnoreCase(current.address)
                || active.blockAds != current.blockAds;
        configDirty.setVisibility(changed ? View.VISIBLE : View.GONE);
    }

    private String modeLabel(DnsConfig.Mode mode) {
        switch (mode) {
            case DOH:
                return getString(R.string.mode_doh);
            case DOT:
                return getString(R.string.mode_dot);
            case UDP:
            default:
                return getString(R.string.mode_udp);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_VPN) {
            if (resultCode == RESULT_OK) {
                startVpn();
            } else {
                Toast.makeText(this, R.string.toast_permission_denied, Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        // The tunnel can start without notification permission; Android simply hides the
        // notification. Nothing else to do here.
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshState();
        refreshAccessibilityState();
        handler.removeCallbacks(refreshTick);
        handler.post(refreshTick);
    }

    @Override
    protected void onPause() {
        saveUiConfig();
        handler.removeCallbacks(refreshTick);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
