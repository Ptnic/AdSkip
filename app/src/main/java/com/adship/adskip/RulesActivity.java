package com.adship.adskip;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

public final class RulesActivity extends Activity {

    private static final int REQUEST_IMPORT_BLOCK = 2001;
    private static final int REQUEST_IMPORT_ALLOW = 2002;
    private static final int MAX_IMPORT_BYTES = 4 * 1024 * 1024;

    private EditText blockRules;
    private EditText allowRules;
    private TextView rulesCount;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_rules);

        blockRules = findViewById(R.id.blockRules);
        allowRules = findViewById(R.id.allowRules);
        rulesCount = findViewById(R.id.rulesCount);
        Button save = findViewById(R.id.saveRules);

        SharedPreferences p = getSharedPreferences(DnsConfig.PREFS, MODE_PRIVATE);
        blockRules.setText(p.getString(DnsConfig.KEY_CUSTOM_BLOCK, ""));
        allowRules.setText(p.getString(DnsConfig.KEY_CUSTOM_ALLOW, ""));

        findViewById(R.id.backButton).setOnClickListener(v -> finish());
        save.setOnClickListener(v -> saveRules());
        findViewById(R.id.loadRecommended).setOnClickListener(v ->
                loadPack(RulePacks.RECOMMENDED, blockRules));
        findViewById(R.id.loadAggressive).setOnClickListener(v ->
                loadPack(RulePacks.AGGRESSIVE, blockRules));
        findViewById(R.id.loadAllowlist).setOnClickListener(v ->
                loadPack(RulePacks.ALLOWLIST, allowRules));
        findViewById(R.id.importBlock).setOnClickListener(v ->
                pickFile(REQUEST_IMPORT_BLOCK));
        findViewById(R.id.importAllow).setOnClickListener(v ->
                pickFile(REQUEST_IMPORT_ALLOW));

        TextWatcher watcher = new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                updateCounts();
            }
        };
        blockRules.addTextChangedListener(watcher);
        allowRules.addTextChangedListener(watcher);
        updateCounts();
    }

    private void updateCounts() {
        int block = countRules(blockRules.getText().toString());
        int allow = countRules(allowRules.getText().toString());
        rulesCount.setText(getString(R.string.rules_count, block, allow));
    }

    private int countRules(String text) {
        if (text == null || text.trim().isEmpty()) {
            return 0;
        }
        int count = 0;
        for (String line : text.split("\\r?\\n")) {
            if (Blocklist.parseRule(line) != null) {
                count++;
            }
        }
        return count;
    }

    private void loadPack(String asset, EditText target) {
        int added = RulePacks.append(this, asset, target);
        if (added < 0) {
            Toast.makeText(this, R.string.rules_pack_failed, Toast.LENGTH_SHORT).show();
            return;
        }
        if (added == 0) {
            Toast.makeText(this, R.string.rules_pack_none, Toast.LENGTH_SHORT).show();
            return;
        }
        updateCounts();
        Toast.makeText(this, getString(R.string.rules_pack_loaded, added),
                Toast.LENGTH_LONG).show();
    }

    private void pickFile(int requestCode) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES,
                new String[]{"text/*", "application/octet-stream"});
        try {
            startActivityForResult(intent, requestCode);
        } catch (RuntimeException e) {
            Toast.makeText(this, R.string.rules_import_failed, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        if (requestCode != REQUEST_IMPORT_BLOCK && requestCode != REQUEST_IMPORT_ALLOW) {
            return;
        }
        EditText target = requestCode == REQUEST_IMPORT_BLOCK ? blockRules : allowRules;
        try {
            String text = readText(data.getData());
            int added = RulePacks.appendText(target, text);
            if (added == 0) {
                Toast.makeText(this, R.string.rules_import_empty, Toast.LENGTH_SHORT).show();
            } else {
                updateCounts();
                Toast.makeText(this, getString(R.string.rules_import_ok, added),
                        Toast.LENGTH_LONG).show();
            }
        } catch (IOException | SecurityException e) {
            Toast.makeText(this, R.string.rules_import_failed, Toast.LENGTH_SHORT).show();
        }
    }

    private String readText(Uri uri) throws IOException {
        try (InputStream input = getContentResolver().openInputStream(uri)) {
            if (input == null) {
                throw new IOException("No input stream");
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream(8192);
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (output.size() + read > MAX_IMPORT_BYTES) {
                    throw new IOException(getString(R.string.rules_import_too_large));
                }
                output.write(buffer, 0, read);
            }
            byte[] bytes = output.toByteArray();
            if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xFE) {
                return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16LE);
            }
            if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFE && (bytes[1] & 0xFF) == 0xFF) {
                return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16BE);
            }
            int offset = 0;
            if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF
                    && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF) {
                offset = 3;
            }
            String text = new String(bytes, offset, bytes.length - offset,
                    StandardCharsets.UTF_8);
            if (text.indexOf('\uFFFD') >= 0) {
                try {
                    text = new String(bytes, offset, bytes.length - offset,
                            Charset.forName("GBK"));
                } catch (RuntimeException ignored) {
                    // Keep the UTF-8 replacement-character version.
                }
            }
            return text;
        }
    }

    private void saveRules() {
        getSharedPreferences(DnsConfig.PREFS, MODE_PRIVATE)
                .edit()
                .putString(DnsConfig.KEY_CUSTOM_BLOCK, blockRules.getText().toString().trim())
                .putString(DnsConfig.KEY_CUSTOM_ALLOW, allowRules.getText().toString().trim())
                .apply();
        boolean running = DnsVpnService.isRunning();
        Toast.makeText(this,
                running ? R.string.toast_saved : R.string.toast_rule_saved,
                Toast.LENGTH_SHORT).show();
        finish();
    }
}
