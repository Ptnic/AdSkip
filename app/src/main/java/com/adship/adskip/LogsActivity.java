package com.adship.adskip;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Shows the in-memory recent DNS query log, with blocked entries highlighted. */
public final class LogsActivity extends Activity {

    private ListView list;
    private TextView summary;
    private TextView empty;
    private Switch showAllowed;
    private LogAdapter adapter;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refreshTick = new Runnable() {
        @Override
        public void run() {
            refresh();
            handler.postDelayed(this, 1000L);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_logs);

        list = findViewById(R.id.logList);
        summary = findViewById(R.id.logSummary);
        empty = findViewById(R.id.emptyLogs);
        showAllowed = findViewById(R.id.showAllowed);
        Button clear = findViewById(R.id.clearLogs);

        adapter = new LogAdapter();
        list.setAdapter(adapter);
        list.setEmptyView(empty);

        SharedPreferences prefs = getSharedPreferences(DnsConfig.PREFS, MODE_PRIVATE);
        showAllowed.setChecked(prefs.getBoolean(DnsConfig.KEY_SHOW_ALLOWED, false));
        showAllowed.setOnCheckedChangeListener((buttonView, checked) ->
                getSharedPreferences(DnsConfig.PREFS, MODE_PRIVATE)
                        .edit()
                        .putBoolean(DnsConfig.KEY_SHOW_ALLOWED, checked)
                        .apply());

        findViewById(R.id.backButton).setOnClickListener(v -> finish());
        clear.setOnClickListener(v -> {
            FilterStats.clearRecords();
            refresh();
            Toast.makeText(this, R.string.logs_cleared, Toast.LENGTH_SHORT).show();
        });
        refresh();
    }

    private void refresh() {
        List<FilterStats.QueryRecord> all = FilterStats.records();
        int blocked = 0;
        for (FilterStats.QueryRecord record : all) {
            if (record.blocked) {
                blocked++;
            }
        }
        summary.setText(getString(R.string.logs_summary, blocked, all.size() - blocked));

        boolean showAll = showAllowed.isChecked();
        List<FilterStats.QueryRecord> visible = new ArrayList<>();
        for (int i = all.size() - 1; i >= 0; i--) {
            FilterStats.QueryRecord record = all.get(i);
            if (showAll || record.blocked) {
                visible.add(record);
            }
        }
        adapter.setItems(visible);
        if (visible.isEmpty()) {
            empty.setText(showAll ? R.string.logs_empty : R.string.logs_empty_blocked);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
        handler.removeCallbacks(refreshTick);
        handler.post(refreshTick);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(refreshTick);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private final class LogAdapter extends BaseAdapter {
        private final LayoutInflater inflater = LayoutInflater.from(LogsActivity.this);
        private final SimpleDateFormat timeFormat =
                new SimpleDateFormat("yyyy年M月d日 HH:mm", Locale.CHINA);
        private final List<FilterStats.QueryRecord> items = new ArrayList<>();

        void setItems(List<FilterStats.QueryRecord> records) {
            items.clear();
            items.addAll(records);
            notifyDataSetChanged();
        }

        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public FilterStats.QueryRecord getItem(int position) {
            return items.get(position);
        }

        @Override
        public long getItemId(int position) {
            return items.get(position).timestamp;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            ViewHolder holder;
            if (convertView == null) {
                convertView = inflater.inflate(R.layout.item_log, parent, false);
                holder = new ViewHolder();
                holder.actor = convertView.findViewById(R.id.logActor);
                holder.tag = convertView.findViewById(R.id.logTag);
                holder.action = convertView.findViewById(R.id.logAction);
                holder.meta = convertView.findViewById(R.id.logMeta);
                convertView.setTag(holder);
            } else {
                holder = (ViewHolder) convertView.getTag();
            }

            FilterStats.QueryRecord record = getItem(position);
            holder.actor.setText(record.appName);
            holder.tag.setText(record.blocked ? R.string.log_blocked : R.string.log_allowed);
            holder.tag.setBackgroundResource(record.blocked
                    ? R.drawable.bg_badge : R.drawable.bg_badge_off);
            holder.tag.setTextColor(getColor(record.blocked ? R.color.accent : R.color.muted));
            holder.action.setText(getString(record.blocked
                    ? R.string.log_action_blocked : R.string.log_action_allowed, record.domain));
            holder.meta.setText(timeFormat.format(new Date(record.timestamp))
                    + " · " + typeLabel(record.type));
            return convertView;
        }

        private String typeLabel(int type) {
            if (type == DnsCodec.TYPE_A) {
                return "A";
            }
            if (type == DnsCodec.TYPE_AAAA) {
                return "AAAA";
            }
            return type == 0 ? "—" : "TYPE " + type;
        }
    }

    private static final class ViewHolder {
        TextView actor;
        TextView tag;
        TextView action;
        TextView meta;
    }
}
