package com.example.accessease;

import android.app.Dialog;
import android.app.TimePickerDialog;
import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.widget.*;
import androidx.annotation.NonNull;
import java.util.Calendar;

public class AddReminderDialog extends Dialog {

    public interface OnReminderAddedListener {
        void onReminderAdded(Reminder reminder);
    }

    private EditText etTitle, etDescription;
    private TextView tvTime;
    private CheckBox cbMedication;
    private Button btnSetTime, btnSave, btnCancel;
    private Calendar selectedTime;
    private OnReminderAddedListener listener;

    public AddReminderDialog(@NonNull Context context) {
        super(context);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(R.layout.activity_add_reminder_dialog);

        etTitle = findViewById(R.id.etTitle);
        etDescription = findViewById(R.id.etDescription);
        tvTime = findViewById(R.id.tvTime);
        cbMedication = findViewById(R.id.cbMedication);
        btnSetTime = findViewById(R.id.btnSetTime);
        btnSave = findViewById(R.id.btnSave);
        btnCancel = findViewById(R.id.btnCancel);

        selectedTime = Calendar.getInstance();
        selectedTime.add(Calendar.HOUR_OF_DAY, 1);
        updateTime();

        btnSetTime.setOnClickListener(v -> showTimePicker());

        btnSave.setOnClickListener(v -> saveReminder());

        btnCancel.setOnClickListener(v -> dismiss());
    }

    private void showTimePicker() {
        new TimePickerDialog(getContext(),
                (view, hour, minute) -> {
                    selectedTime.set(Calendar.HOUR_OF_DAY, hour);
                    selectedTime.set(Calendar.MINUTE, minute);
                    updateTime();
                },
                selectedTime.get(Calendar.HOUR_OF_DAY),
                selectedTime.get(Calendar.MINUTE),
                false
        ).show();
    }

    private void updateTime() {
        tvTime.setText(android.text.format.DateFormat
                .format("hh:mm a", selectedTime));
    }

    private void saveReminder() {
        String title = etTitle.getText().toString().trim();
        String desc = etDescription.getText().toString().trim();

        if (title.isEmpty()) {
            etTitle.setError("Enter title");
            return;
        }

        // ✅ CORRECT CONSTRUCTOR USAGE
        Reminder reminder = new Reminder(
                title,
                desc,
                selectedTime.getTimeInMillis(),
                cbMedication.isChecked()
        );

        if (listener != null) {
            listener.onReminderAdded(reminder);
        }

        dismiss();
    }

    public void setOnReminderAddedListener(OnReminderAddedListener listener) {
        this.listener = listener;
    }
}
