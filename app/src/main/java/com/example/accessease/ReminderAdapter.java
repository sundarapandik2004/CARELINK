package com.example.accessease;

import android.content.Context;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.firebase.firestore.FirebaseFirestore;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class ReminderAdapter extends RecyclerView.Adapter<ReminderAdapter.ViewHolder> {

    private static final String TAG = "ReminderAdapter";

    private List<Reminder> reminderList;
    private Context context;
    private RemindersActivity activity;

    // ★ Firestore instance for updating status
    private FirebaseFirestore db;

    public ReminderAdapter(List<Reminder> reminderList, RemindersActivity activity) {
        this.reminderList = reminderList;
        this.activity     = activity;
        this.context      = activity;
        this.db           = FirebaseFirestore.getInstance();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_reminder, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Reminder reminder = reminderList.get(position);

        holder.tvTitle.setText(reminder.getTitle());
        holder.tvDescription.setText(reminder.getDescription());

        SimpleDateFormat sdf = new SimpleDateFormat("EEE, MMM dd • hh:mm a", Locale.getDefault());
        holder.tvTime.setText(sdf.format(new Date(reminder.getTime())));

        // Medication indicator — show/hide badge only, no background override
        if (reminder.isMedication()) {
            holder.medicationIndicator.setVisibility(View.VISIBLE);
        } else {
            holder.medicationIndicator.setVisibility(View.GONE);
        }

        // Set checkbox state WITHOUT triggering the listener
        holder.cbCompleted.setOnCheckedChangeListener(null);
        holder.cbCompleted.setChecked(reminder.isCompleted());

        // Apply strike-through based on current completed state
        applyStrikeThrough(holder, reminder.isCompleted());

        // ★ KEY FIX — when user ticks/unticks, update BOTH local + Firestore
        holder.cbCompleted.setOnCheckedChangeListener((buttonView, isChecked) -> {
            // 1. Update local model
            reminder.setCompleted(isChecked);

            // 2. Update local SharedPreferences via activity
            activity.saveRemindersFromAdapter();

            // 3. Apply visual strike-through
            applyStrikeThrough(holder, isChecked);

            // 4. ★ Update Firestore so caregiver sees the correct status
            updateFirestoreStatus(reminder, isChecked ? "Completed" : "Pending");
        });

        // Delete button
        holder.btnDelete.setOnClickListener(v -> activity.deleteReminder(position));
    }

    /**
     * ★ Updates the reminder's status in Firestore.
     *
     * Uses the firestoreId stored on the Reminder object.
     * If firestoreId is null (old reminder saved before this fix),
     * it searches Firestore by title + time to find the right document.
     */
    private void updateFirestoreStatus(Reminder reminder, String status) {
        String firestoreId = reminder.getFirestoreId();

        if (firestoreId != null && !firestoreId.isEmpty()) {
            // We have the document ID — update directly
            db.collection("reminders")
                    .document(firestoreId)
                    .update("status", status)
                    .addOnSuccessListener(v ->
                            Log.d(TAG, "Firestore status updated to " + status
                                    + " for: " + reminder.getTitle()))
                    .addOnFailureListener(e ->
                            Log.e(TAG, "Firestore update failed: " + e.getMessage()));

        } else {
            // No stored ID — search by userId + title + time to find the document
            // This handles reminders saved before the firestoreId fix
            String userId = activity.getCurrentUserId();
            if (userId == null) return;

            db.collection("reminders")
                    .whereEqualTo("userId", userId)
                    .whereEqualTo("title",  reminder.getTitle())
                    .whereEqualTo("time",   reminder.getTime())
                    .get()
                    .addOnSuccessListener(query -> {
                        if (!query.isEmpty()) {
                            String docId = query.getDocuments().get(0).getId();
                            // Save ID back to reminder so next update is instant
                            reminder.setFirestoreId(docId);
                            activity.saveRemindersFromAdapter();

                            db.collection("reminders").document(docId)
                                    .update("status", status)
                                    .addOnSuccessListener(v ->
                                            Log.d(TAG, "Firestore status updated (search) to "
                                                    + status + " for: " + reminder.getTitle()))
                                    .addOnFailureListener(e ->
                                            Log.e(TAG, "Firestore update failed: " + e.getMessage()));
                        } else {
                            Log.w(TAG, "Reminder not found in Firestore: " + reminder.getTitle());
                        }
                    })
                    .addOnFailureListener(e ->
                            Log.e(TAG, "Firestore search failed: " + e.getMessage()));
        }
    }

    private void applyStrikeThrough(ViewHolder holder, boolean strike) {
        int flags = holder.tvTitle.getPaintFlags();
        if (strike) {
            holder.tvTitle.setPaintFlags(
                    flags | android.graphics.Paint.STRIKE_THRU_TEXT_FLAG);
            holder.tvDescription.setPaintFlags(
                    holder.tvDescription.getPaintFlags()
                            | android.graphics.Paint.STRIKE_THRU_TEXT_FLAG);
        } else {
            holder.tvTitle.setPaintFlags(
                    flags & ~android.graphics.Paint.STRIKE_THRU_TEXT_FLAG);
            holder.tvDescription.setPaintFlags(
                    holder.tvDescription.getPaintFlags()
                            & ~android.graphics.Paint.STRIKE_THRU_TEXT_FLAG);
        }
    }

    @Override
    public int getItemCount() {
        return reminderList.size();
    }

    public void removeItem(int position) {
        reminderList.remove(position);
        notifyItemRemoved(position);
    }

    // ─────────────────────────────────────────────────────────────────────────
    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView    tvTitle, tvDescription, tvTime;
        CheckBox    cbCompleted;
        ImageButton btnDelete;
        View        medicationIndicator;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            tvTitle            = itemView.findViewById(R.id.tvTitle);
            tvDescription      = itemView.findViewById(R.id.tvDescription);
            tvTime             = itemView.findViewById(R.id.tvTime);
            cbCompleted        = itemView.findViewById(R.id.cbCompleted);
            btnDelete          = itemView.findViewById(R.id.btnDelete);
            medicationIndicator= itemView.findViewById(R.id.medicationIndicator);
        }
    }
}